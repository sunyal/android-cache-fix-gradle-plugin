package org.gradle.android

import com.android.build.gradle.internal.pipeline.StreamBasedTask
import com.android.build.gradle.internal.tasks.CheckManifest
import com.android.build.gradle.internal.tasks.IncrementalTask
import com.android.build.gradle.tasks.ExtractAnnotations
import com.android.build.gradle.tasks.ProcessAndroidResources
import com.android.build.gradle.tasks.factory.AndroidJavaCompile
import com.android.builder.model.Version
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.internal.Factory
import org.gradle.util.DeprecationLogger
import org.gradle.util.GradleVersion
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static org.gradle.android.CompilerArgsProcessor.AnnotationProcessorOverride
import static org.gradle.android.CompilerArgsProcessor.Skip
import static org.gradle.android.CompilerArgsProcessor.SkipNext
import static org.gradle.android.Versions.android

@CompileStatic
class AndroidCacheFixPlugin implements Plugin<Project> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AndroidCacheFixPlugin)

    private static final String IGNORE_VERSION_CHECK_PROPERTY = "org.gradle.android.cache-fix.ignoreVersionCheck"

    @Override
    void apply(Project project) {
        def currentGradleVersion = GradleVersion.current()
        def currentAndroidVersion = android(Version.ANDROID_GRADLE_PLUGIN_VERSION)

        if (!Boolean.getBoolean(IGNORE_VERSION_CHECK_PROPERTY)) {
            if (!Versions.SUPPORTED_ANDROID_VERSIONS.contains(currentAndroidVersion)) {
                throw new RuntimeException("Android plugin $currentAndroidVersion is not supported by Android cache fix plugin. Supported Android plugin versions: ${Versions.SUPPORTED_ANDROID_VERSIONS.join(", ")}. Override with -D${IGNORE_VERSION_CHECK_PROPERTY}=true.")
            }
            if (!Versions.SUPPORTED_GRADLE_VERSIONS*.baseVersion.contains(currentGradleVersion.baseVersion)) {
                throw new RuntimeException("$currentGradleVersion is not supported by Android cache fix plugin. Supported Gradle versions: ${Versions.SUPPORTED_GRADLE_VERSIONS*.version.join(", ")}. Override with -D${IGNORE_VERSION_CHECK_PROPERTY}=true.")
            }
        }

        def compilerArgsProcessor = new CompilerArgsProcessor(project)
        def workarounds = [
            new AndroidJavaCompile_BootClasspath_Workaround(),
            new AndroidJavaCompile_AnnotationProcessorSource_Workaround(compilerArgsProcessor),
            new AndroidJavaCompile_ProcessorListFile_Workaround(),
            new DataBindingDependencyArtifacts_Workaround(compilerArgsProcessor),
            new ExtractAnnotations_Source_Workaround(),
            new CombinedInput_Workaround(),
            new ProcessAndroidResources_MergeBlameLogFolder_Workaround(),
            new CheckManifest_Manifest_Workaround(),
        ]

        for (def workaround : workarounds) {
            def androidIssue = workaround.class.getAnnotation(AndroidIssue)
            def introducedIn = android(androidIssue.introducedIn())
            if (currentAndroidVersion < introducedIn) {
                continue
            }
            if (androidIssue.fixedIn().any { String supportedAndroidVersion -> currentAndroidVersion == android(supportedAndroidVersion) }) {
                continue
            }
            LOGGER.debug("Applying Android workaround {} to {}", workaround.getClass().simpleName, project)
            workaround.apply(project)
        }
    }

    /**
     * Fix {@link org.gradle.api.tasks.compile.CompileOptions#getBootClasspath()} introducing relocatability problems for {@link AndroidJavaCompile}.
     */
    @AndroidIssue(introducedIn = "3.0.0", link = "https://issuetracker.google.com/issues/68392933")
    static class AndroidJavaCompile_BootClasspath_Workaround implements Workaround {
        @Override
        @CompileStatic(TypeCheckingMode.SKIP)
        void apply(Project project) {
            project.tasks.withType(AndroidJavaCompile) { AndroidJavaCompile task ->
                task.inputs.property "options.bootClasspath", ""
                // Override workaround introduced in 3.1.0-alpha02
                task.inputs.property "options.bootClasspath.filtered", ""
                task.inputs.files({
                        DeprecationLogger.whileDisabled({
                            //noinspection GrDeprecatedAPIUsage
                            task.options.bootClasspath?.split(File.pathSeparator)
                        } as Factory)
                    })
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                    .withPropertyName("options.bootClasspath.workaround")
                    .optional(true)
            }
        }
    }

    /**
     * Filter the Java annotation processor output folder from compiler arguments to avoid absolute path.
     */
    @AndroidIssue(introducedIn = "3.0.0", link = "https://issuetracker.google.com/issues/68391973")
    static class AndroidJavaCompile_AnnotationProcessorSource_Workaround implements Workaround {
        private final CompilerArgsProcessor compilerArgsProcessor

        AndroidJavaCompile_AnnotationProcessorSource_Workaround(CompilerArgsProcessor compilerArgsProcessor) {
            this.compilerArgsProcessor = compilerArgsProcessor
        }

        @CompileStatic(TypeCheckingMode.SKIP)
        @Override
        void apply(Project project) {
            compilerArgsProcessor.addRule(SkipNext.matching("-s"))
        }
    }

    /**
     * Override path sensitivity for {@link AndroidJavaCompile#getProcessorListFile()} to {@link PathSensitivity#NONE}.
     */
    @AndroidIssue(introducedIn = "3.0.0", link = "https://issuetracker.google.com/issues/68759178")
    static class AndroidJavaCompile_ProcessorListFile_Workaround implements Workaround {
        @CompileStatic(TypeCheckingMode.SKIP)
        @Override
        void apply(Project project) {
            project.tasks.withType(AndroidJavaCompile) { AndroidJavaCompile task ->
                def originalValue

                project.gradle.taskGraph.beforeTask {
                    if (task == it) {
                        originalValue = task.processorListFile
                        task.processorListFile = project.files()
                        task.inputs.files(originalValue)
                            .withPathSensitivity(PathSensitivity.NONE)
                            .withPropertyName("processorListFile.workaround")
                    }
                }

                task.doFirst {
                    task.processorListFile = originalValue
                }
            }
        }
    }

    /**
     * Override path sensitivity for {@link AndroidJavaCompile#getDataBindingDependencyArtifacts()} to {@link PathSensitivity#RELATIVE}.
     */
    @AndroidIssue(introducedIn = "3.0.0", link = "https://issuetracker.google.com/issues/68759178")
    static class DataBindingDependencyArtifacts_Workaround implements Workaround {
        private final CompilerArgsProcessor compilerArgsProcessor

        DataBindingDependencyArtifacts_Workaround(CompilerArgsProcessor compilerArgsProcessor) {
            this.compilerArgsProcessor = compilerArgsProcessor
        }

        @Override
        void apply(Project project) {
            compilerArgsProcessor.addRule(Skip.matching("-Aandroid.databinding.sdkDir=.*"))
            compilerArgsProcessor.addRule(Skip.matching("-Aandroid.databinding.bindingBuildFolder=.*"))
            compilerArgsProcessor.addRule(Skip.matching("-Aandroid.databinding.xmlOutDir=.*"))

            def outputRules = [
                AnnotationProcessorOverride.of("android.databinding.generationalFileOutDir") { Task task, String path ->
                    task.outputs.dir(path)
                        .withPropertyName("android.databinding.generationalFileOutDir.workaround")
                },
                AnnotationProcessorOverride.of("android.databinding.exportClassListTo") { Task task, String path ->
                    task.outputs.file(path)
                        .withPropertyName("android.databinding.exportClassListTo")
                }
            ]
            outputRules.each {
                compilerArgsProcessor.addRule it
            }

            project.tasks.withType(AndroidJavaCompile) { AndroidJavaCompile task ->
                reconfigurePathSensitivityForDataBindingDependencyArtifacts(project, task)
                filterDataBindingInfoFromSource(project, task)
                configureAdditionalOutputs(project, task, outputRules)
            }
        }

        @CompileStatic(TypeCheckingMode.SKIP)
        private
        static void reconfigurePathSensitivityForDataBindingDependencyArtifacts(Project project, AndroidJavaCompile task) {
            def originalValue

            project.gradle.taskGraph.beforeTask {
                if (task == it) {
                    originalValue = task.dataBindingDependencyArtifacts
                    if (originalValue != null) {
                        task.dataBindingDependencyArtifacts = project.files()
                        task.inputs.files(originalValue)
                            .withPathSensitivity(PathSensitivity.RELATIVE)
                            .withPropertyName("dataBindingDependencyArtifacts.workaround")
                    }
                }
            }

            task.doFirst {
                task.dataBindingDependencyArtifacts = originalValue
            }
        }

        @CompileStatic(TypeCheckingMode.SKIP)
        private static void filterDataBindingInfoFromSource(Project project, AndroidJavaCompile task) {
            def originalValue

            project.gradle.taskGraph.beforeTask {
                if (task == it) {
                    originalValue = task.source
                    if (originalValue != null) {
                        task.source = project.files()
                        def filteredSources = originalValue.matching { PatternFilterable filter ->
                            filter.exclude("android/databinding/layouts/DataBindingInfo.java")
                        }
                        task.inputs.files(filteredSources)
                            .withPathSensitivity(PathSensitivity.RELATIVE)
                            .withPropertyName("source.workaround")
                            .skipWhenEmpty()
                    }
                }
            }

            task.doFirst {
                task.source = originalValue
            }
        }


        @CompileStatic(TypeCheckingMode.SKIP)
        private static void configureAdditionalOutputs(Project project, AndroidJavaCompile task, List<AnnotationProcessorOverride> overrides) {
            def configTask = project.tasks.create("configure" + task.name.capitalize()) { configTask ->
                configTask.doFirst {
                    overrides.each {
                        it.configureAndroidJavaCompile(task)
                    }
                }
            }
            task.dependsOn configTask
        }
    }

    /**
     * Override path sensitivity for {@link ExtractAnnotations#getSource()} to {@link PathSensitivity#RELATIVE}.
     */
    @AndroidIssue(introducedIn = "3.0.0", link = "https://issuetracker.google.com/issues/68759476")
    static class ExtractAnnotations_Source_Workaround implements Workaround {
        @CompileStatic(TypeCheckingMode.SKIP)
        @Override
        void apply(Project project) {
            project.tasks.withType(ExtractAnnotations) { ExtractAnnotations task ->
                def originalValue

                project.gradle.taskGraph.beforeTask {
                    if (task == it) {
                        originalValue = task.source
                        task.source = []
                        task.inputs.files(originalValue)
                            .withPathSensitivity(PathSensitivity.RELATIVE)
                            .withPropertyName("source.workaround")
                            .skipWhenEmpty(true)
                    }
                }

                task.doFirst {
                    task.source = originalValue
                }
            }
        }
    }

    /**
     * Fix {@link IncrementalTask#getCombinedInput()} and {@link StreamBasedTask#getCombinedInput()} relocatability.
     */
    @AndroidIssue(introducedIn = "3.0.0", fixedIn = ["3.1.0-alpha04"], link = "https://issuetracker.google.com/issues/68771542")
    static class CombinedInput_Workaround implements Workaround {
        @CompileStatic(TypeCheckingMode.SKIP)
        @Override
        void apply(Project project) {
            project.tasks.withType(IncrementalTask) { IncrementalTask task ->
                task.inputs.property "combinedInput", ""
                task.inputs.property "combinedInput.workaround", {
                    fixCombinedInputs(task.combinedInput)
                }
            }
            project.tasks.withType(StreamBasedTask) { StreamBasedTask task ->
                task.inputs.property "combinedInput", ""
                task.inputs.property "combinedInput.workaround", {
                    fixCombinedInputs(task.combinedInput)
                }
            }
        }

        @CompileStatic(TypeCheckingMode.SKIP)
        private static Map<String, Boolean> fixCombinedInputs(String combinedInputs) {
            combinedInputs.split("\n").collectEntries {
                def (propertyName, value) = it.split("=", 2)
                [(propertyName): (value != "null")]
            }
        }
    }

    /**
     * {@link ProcessAndroidResources#getMergeBlameLogFolder()} shouldn't be an {@literal @}{@link org.gradle.api.tasks.Input}.
     */
    @AndroidIssue(introducedIn = "3.0.0", fixedIn = "3.0.1", link = "https://issuetracker.google.com/issues/68385486")
    static class ProcessAndroidResources_MergeBlameLogFolder_Workaround implements Workaround {
        @CompileStatic(TypeCheckingMode.SKIP)
        @Override
        void apply(Project project) {
            project.tasks.withType(ProcessAndroidResources) { ProcessAndroidResources task ->
                task.inputs.property "mergeBlameLogFolder", ""
            }
        }
    }

    /**
     * {@link com.android.build.gradle.internal.tasks.CheckManifest#getManifest()} should not be an {@literal @}{@link org.gradle.api.tasks.Input}.
     */
    @AndroidIssue(introducedIn = "3.0.0", link = "https://issuetracker.google.com/issues/68772035")
    static class CheckManifest_Manifest_Workaround implements Workaround {
        @CompileStatic(TypeCheckingMode.SKIP)
        @Override
        void apply(Project project) {
            project.tasks.withType(CheckManifest) { CheckManifest task ->
                task.inputs.property "manifest", ""
            }
        }
    }
}
