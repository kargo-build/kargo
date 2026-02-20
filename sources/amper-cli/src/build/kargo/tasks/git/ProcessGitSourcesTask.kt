package build.kargo.tasks.git

import build.kargo.frontend.resolver.GitSourcesExtension
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.tasks.AdditionalClasspathProvider
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Task that processes Git sources declared in module.yaml and provides
 * the built artifacts as additional classpath entries.
 *
 * This task integrates with the existing build system through the
 * AdditionalClasspathProvider interface, requiring minimal changes
 * to the core build pipeline.
 */
internal class ProcessGitSourcesTask(
    override val module: AmperModule,
    override val taskName: TaskName,
    private val targetPlatforms: List<Platform>,
    override val buildType: BuildType? = null
) : BuildTask {

    override val platform: Platform = targetPlatforms.firstOrNull() ?: Platform.JVM
    override val isTest: Boolean = false

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext
    ): TaskResult {
        logger.info("Processing Git sources for module: ${module.userReadableName}")

        // Process Git sources and get artifacts
        val artifacts = GitSourcesExtension.processModuleGitSources(
            module = module,
            targetPlatforms = targetPlatforms
        )

        val artifactPaths = artifacts.map { it.artifactPath }

        if (artifactPaths.isNotEmpty()) {
            logger.info("Git sources provided ${artifactPaths.size} artifacts for ${module.userReadableName}")
        } else {
            logger.debug("No Git sources found for module: ${module.userReadableName}")
        }

        return Result(compileClasspath = artifactPaths)
    }

    class Result(
        override val compileClasspath: List<Path>
    ) : TaskResult, AdditionalClasspathProvider

    companion object {
        private val logger = LoggerFactory.getLogger(ProcessGitSourcesTask::class.java)
    }
}