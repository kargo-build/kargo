package build.kargo.intellij.sync

import build.kargo.intellij.project.isKargoProject
import build.kargo.intellij.project.KargoProjectAware
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
        val isKargoProject = withContext(Dispatchers.IO) {
            isKargoProject(project)
        }

        if (isKargoProject && !project.isDisposed) {
            KargoSyncManager.getInstance(project).scheduleSync("Project Open")
        }
    }
}
