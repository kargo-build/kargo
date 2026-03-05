package build.kargo.intellij.sync

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Service(Service.Level.PROJECT)
class KargoSyncManager(
    val project: Project,
    val scope: CoroutineScope
) {
    private val logger = Logger.getInstance(KargoSyncManager::class.java)
    private val syncMutex = Mutex()

    companion object {
        fun getInstance(project: Project): KargoSyncManager =
            project.getService(KargoSyncManager::class.java)
    }
    
    fun scheduleSync() {
        logger.info("Kargo: scheduleSync triggered for project ${project.name}")
        scope.launch {
            if (project.isDisposed) return@launch
            
            syncMutex.withLock {
                val projectPath = project.basePath?.let { java.nio.file.Path.of(it) } ?: return@withLock
                
                val model = try {
                    KargoModelReader.readModel(projectPath, project)
                } catch (t: Throwable) {
                    logger.error("Kargo: Model read failed", t)
                    null
                }
                
                if (model != null && !project.isDisposed) {
                    val updater = KargoWorkspaceModelUpdater(project)
                    updater.updateWorkspaceModel(model)
                }
            }
        }
    }
}
