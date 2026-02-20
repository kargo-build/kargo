package build.kargo.tasks.git

import org.jetbrains.amper.tasks.PlatformTaskType
import org.jetbrains.amper.tasks.ProjectTasksBuilder

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
                ProcessGitSourcesTask(
                    module = module,
                    taskName = gitSourcesTaskName,
                    targetPlatforms = listOf(platform)
                )
            )
        }
}
