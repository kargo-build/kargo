package build.kargo.tasks.jvm

import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.PackageTask
import org.jetbrains.amper.engine.PackageTask.Format
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.AbstractJarTask
import org.jetbrains.amper.tasks.CommonTaskType
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.jvm.ExecutableJarTask
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

internal class ExecutableJarTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    val incrementalCache: IncrementalCache,
    val userCacheRoot: AmperUserCacheRoot,
    private val taskOutputRoot: TaskOutputRoot,
    private val outputJarName: String = "${module.userReadableName}-jvm-executable.jar"
) : PackageTask {

    val delegate = ExecutableJarTask(
        taskName = taskName,
        module = module,
        incrementalCache = incrementalCache,
        userCacheRoot = userCacheRoot,
        taskOutputRoot = taskOutputRoot,
        outputJarName = outputJarName
    )

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext
    ): TaskResult {
        val result = delegate.run(dependenciesResult, executionContext) as AbstractJarTask.Result
        val artifactPath = result.jarPath

        // In Kargo, we support an 'output' configuration for JVM products
        if (module.type.isApplication()) {
            val outputSetting = module.fragments.firstNotNullOfOrNull { it.settings.jvm.output } ?: "dist/"
            val moduleRoot = module.source.moduleDir

            val destination = if (outputSetting.endsWith("/")) {
                moduleRoot.resolve(outputSetting).resolve(artifactPath.fileName)
            } else {
                val fileName = outputSetting.substringAfterLast("/")
                if (fileName.contains(".")) {
                    moduleRoot.resolve(outputSetting)
                } else {
                    val ext = artifactPath.fileName.toString().substringAfterLast(".", "jar")
                    moduleRoot.resolve("$outputSetting.$ext")
                }
            }

            destination.parent.createDirectories()
            artifactPath.copyTo(destination, overwrite = true)

            logger.debug("Copied JVM executable JAR to {}", destination)

            // Return a result that points to the new destination
            return ExecutableJarTask.Result(jarPath = destination)
        }

        return result
    }

    override val platform: Platform get() = delegate.platform
    override val buildType: BuildType get() = delegate.buildType
    override val format: Format get() = delegate.format

    private val logger = LoggerFactory.getLogger(javaClass)
}