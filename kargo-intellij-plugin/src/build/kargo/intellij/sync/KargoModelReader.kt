package build.kargo.intellij.sync

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.amper.frontend.project.StandaloneAmperProjectContext
import org.jetbrains.amper.frontend.aomBuilder.readProjectModel
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.frontend.Model
import java.nio.file.Path

class KargoModelReader {

    companion object {
        private val logger = Logger.getInstance(KargoModelReader::class.java)

        fun readModel(
            projectPath: Path,
            project: Project,
            errorCollector: KargoSyncErrorCollector
        ): Model? {

            logger.info("Kargo: Attempting to read model from $projectPath (Project: ${project.name})")

            val reporter = object : ProblemReporter {
                override fun reportMessage(message: BuildProblem) {
                    logger.warn("Kargo Sync Problem: ${message.message}")
                    errorCollector.reportProblem(message)
                }
            }

            return try {
                ApplicationManager.getApplication().runReadAction(Computable {
                    with(reporter) {
                        val context = runCatching {
                            StandaloneAmperProjectContext.create(projectPath, null, project)
                        }.onFailure {
                            logger.error("Kargo: Error during StandaloneAmperProjectContext.create", it)
                            errorCollector.reportException(it)
                        }.getOrNull() ?: return@Computable null

                        runCatching {
                            context.readProjectModel(
                                pluginData = emptyList(),
                                mavenPluginXmls = emptyList()
                            )
                        }.onFailure {
                            logger.error("Kargo: Error during context.readProjectModel", it)
                            errorCollector.reportException(it)
                        }.getOrNull()
                    }
                })
            } catch (t: Throwable) {
                logger.error("Kargo: Unexpected FATAL error in readModel", t)
                errorCollector.reportException(t)
                null
            }
        }
    }
}
