package build.kargo.tasks.jvm

import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.jvm.JvmClassesJarTask
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

internal class JvmClassesJarTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    override val buildType: BuildType? = null,
    override val platform: Platform = Platform.JVM,
    private val taskOutputRoot: TaskOutputRoot,
    incrementalCache: IncrementalCache,
) : org.jetbrains.amper.engine.BuildTask {

    val delegate = JvmClassesJarTask(
        taskName = taskName,
        module = module,
        buildType = buildType,
        platform = platform,
        taskOutputRoot = taskOutputRoot,
        incrementalCache = incrementalCache
    )

    override val isTest: Boolean
        get() = delegate.isTest

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext
    ): TaskResult {
        val result = delegate.run(dependenciesResult, executionContext) as JvmClassesJarTask.Result
        val artifactPath = result.jarPath

        // In Kargo, we support an 'output' configuration for JVM products
        // Only libraries (and not tests) should have their jar output copied
        val outputSetting = module.fragments.firstNotNullOfOrNull { it.settings.jvm.output }
        if (module.type.isLibrary() && !isTest && outputSetting != null) {
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

            logger.debug("Copied JVM library JAR to {}", destination)

            // Return a result that points to the new destination
            return JvmClassesJarTask.Result(jarPath = destination, module = module)
        }

        return result
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}
