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

        fun readModel(projectPath: Path, project: Project): Model? {
            logger.info("Kargo: Attempting to read model from $projectPath (Project: ${project.name})")
            
            val reporter = object : ProblemReporter {
                override fun reportMessage(message: BuildProblem) {
                    logger.warn("Kargo Sync Problem: ${message.message}")
                }
            }
            
            return try {
                ApplicationManager.getApplication().runReadAction(Computable {
                    with(reporter) {
                        val context = try {
                            StandaloneAmperProjectContext.create(projectPath, null, project)
                        } catch (t: Throwable) {
                            logger.error("Kargo: Error during StandaloneAmperProjectContext.create", t)
                            return@with null
                        }
                        
                        if (context == null) return@with null
                        
                        val model = try {
                            context.readProjectModel(pluginData = emptyList(), mavenPluginsWithXmls = emptyList())
                        } catch (t: Throwable) {
                            logger.error("Kargo: Error during context.readProjectModel", t)
                            return@with null
                        }
                        
                        if (model != null) {
                            logger.info("Kargo: Successfully read model with ${model.modules.size} modules")
                        }
                        model
                    }
                })
            } catch (t: Throwable) {
                logger.error("Kargo: Unexpected FATAL error in readModel", t)
                null
            }
        }
    }
}
