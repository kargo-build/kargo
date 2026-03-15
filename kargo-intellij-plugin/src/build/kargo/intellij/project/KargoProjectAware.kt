package build.kargo.intellij.project

import build.kargo.intellij.sync.KargoSyncManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectAware
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectListener
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectReloadContext
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemSettingsFilesReloadContext
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.project.stateStore
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds

/**
 * Integrates Kargo with IntelliJ's Native External System Auto-Import feature.
 * This provides the modern "floating sync button" over the editor instead of a clunky banner.
 */
class KargoProjectAware(private val project: Project) : ExternalSystemProjectAware {

    override val projectId = ExternalSystemProjectId(ProjectSystemId("Kargo"), project.stateStore.projectBasePath.pathString)

    override val settingsFiles: Set<String>
        get() {
            val root = project.basePath?.let { Path(it) } ?: return emptySet()
            if (!root.exists()) return emptySet()
            
            return try {
                Files.walk(root, 4).use { stream ->
                    stream.filter { it.name == "project.yaml" || it.name == "module.yaml" }
                          .map { it.toString() }
                          .toList()
                          .toSet()
                }
            } catch (e: Exception) {
                emptySet()
            }
        }

    private val listeners = mutableListOf<ExternalSystemProjectListener>()

    override fun subscribe(listener: ExternalSystemProjectListener, parentDisposable: Disposable) {
        listeners.add(listener)
        Disposer.register(parentDisposable) {
            listeners.remove(listener)
        }
    }

    fun notifySyncStarted() {
        listeners.forEach { it.onProjectReloadStart() }
    }

    fun notifySyncFinished(success: Boolean) {
        val status = if (success) ExternalSystemRefreshStatus.SUCCESS else ExternalSystemRefreshStatus.FAILURE
        listeners.forEach { it.onProjectReloadFinish(status) }
    }

    private fun settingsFilesChanged(modifications: ExternalSystemSettingsFilesReloadContext): Boolean {
        return !(modifications.created.isEmpty() && modifications.deleted.isEmpty() && modifications.updated.isEmpty())
    }

    override fun reloadProject(context: ExternalSystemProjectReloadContext) {
        notifySyncStarted()
        try {   
            if (context.isExplicitReload || settingsFilesChanged(context.settingsFilesContext)) {
                KargoSyncManager.getInstance(project).scheduleSync()
            }
        } finally {
            notifySyncFinished(true)
        }
    }

}
