package build.kargo.intellij.sync

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator

@Service(Service.Level.PROJECT)
class KargoSyncManager(
    val project: Project,
    val scope: CoroutineScope
) {
    private val logger = Logger.getInstance(KargoSyncManager::class.java)
    private val isSyncRunning = AtomicBoolean(false)

    companion object {
        fun getInstance(project: Project): KargoSyncManager =
            project.getService(KargoSyncManager::class.java)
    }
    
    fun scheduleSync() {
        if (!isSyncRunning.compareAndSet(false, true)) {
            logger.info("Kargo: Sync already in progress for project ${project.name}, dropping redundant request")
            return
        }

        logger.info("Kargo: scheduleSync triggered for project ${project.name}")
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Kargo Sync", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = true
                    indicator.text = "Reading Kargo project model..."
                    
                    if (project.isDisposed) return
                    
                    val projectPath = project.basePath?.let { java.nio.file.Path.of(it) } ?: return
                    
                    val model = try {
                        KargoModelReader.readModel(projectPath, project)
                    } catch (t: Throwable) {
                        logger.error("Kargo: Model read failed", t)
                        null
                    }
                    
                    if (model != null && !project.isDisposed) {
                        indicator.text = "Updating IntelliJ workspace..."
                        val updater = KargoWorkspaceModelUpdater(project)
                        updater.updateWorkspaceModel(model)
                    }
                } finally {
                    isSyncRunning.set(false)
                }
            }
        })
    }
}
