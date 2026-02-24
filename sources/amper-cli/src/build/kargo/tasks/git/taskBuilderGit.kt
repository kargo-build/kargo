package build.kargo.tasks.git

import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.ModuleDependencies
import org.jetbrains.amper.tasks.PlatformTaskType
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.native.NativeTaskType
import org.jetbrains.amper.util.BuildType

// Git Sources task type
internal enum class GitSourcesTaskType(override val prefix: String) : PlatformTaskType {
    ProcessGitSources("processGitSources"),
}

fun ProjectTasksBuilder.setupGitTasks() {
    allModules()
        .alsoPlatforms()
        .alsoTests()
        .withEach {
            // Register Git Sources task to process and provide Git-backed dependencies
            val gitSourcesTaskName = GitSourcesTaskType.ProcessGitSources.getTaskName(module, platform, isTest)
            tasks.registerTask(
                ResolveGitSourcesDependenciesTask(
                    module = module,
                    taskName = gitSourcesTaskName,
                    targetPlatforms = listOf(platform)
                )
            )

            // Wire git sources as dependency of compile tasks (generic - JVM + Native)
            if (platform.isDescendantOf(Platform.NATIVE)) {
                for (buildType in BuildType.entries) {
                    tasks.registerDependency(
                        NativeTaskType.CompileKLib.getTaskName(module, platform, isTest, buildType),
                        gitSourcesTaskName
                    )
                    tasks.registerDependency(
                        NativeTaskType.Link.getTaskName(module, platform, isTest, buildType),
                        gitSourcesTaskName
                    )
                }
            } else {
                tasks.registerDependency(
                    CommonTaskType.Compile.getTaskName(module, platform, isTest),
                    gitSourcesTaskName
                )
            }
        }
}
