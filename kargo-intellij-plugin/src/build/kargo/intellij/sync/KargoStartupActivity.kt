package build.kargo.intellij.sync

import build.kargo.intellij.project.KargoProjectAware
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs after every project open to trigger a Kargo sync.
 * This is the primary mechanism for loading source roots and dependencies.
 */
class KargoStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        val root = java.nio.file.Path.of(basePath)

        // Ensure IO operations run on the IO dispatcher to prevent UI thread blocking
        val hasKargoFiles = withContext(Dispatchers.IO) {
            try {
                java.nio.file.Files.walk(root, 3).use { stream ->
                    stream.anyMatch { path ->
                        val name = path.fileName?.toString()
                        name == "project.yaml" || name == "module.yaml"
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        if (hasKargoFiles && !project.isDisposed) {
            KargoSyncManager.getInstance(project).scheduleSync()
            
            // Register Kargo to use IntelliJ's native floating sync action
            val projectAware = KargoProjectAware(project)
            val projectTracker = ExternalSystemProjectTracker.getInstance(project)
            projectTracker.register(projectAware, project)
            projectTracker.activate(projectAware.projectId)
        }
    }
}
