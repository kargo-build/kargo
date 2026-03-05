package build.kargo.intellij.project

import build.kargo.intellij.sync.KargoSyncManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectAware
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectListener
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectReloadContext
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.toList

/**
 * Integrates Kargo with IntelliJ's Native External System Auto-Import feature.
 * This provides the modern "floating sync button" over the editor instead of a clunky banner.
 */
class KargoProjectAware(private val project: Project) : ExternalSystemProjectAware {

    override val projectId: ExternalSystemProjectId = 
        ExternalSystemProjectId(ProjectSystemId("Kargo"), project.name)

    override val settingsFiles: Set<String>
        get() {
            val root = project.basePath?.let { Path.of(it) } ?: return emptySet()
            if (!Files.exists(root)) return emptySet()
            
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

    override fun subscribe(listener: ExternalSystemProjectListener, parentDisposable: Disposable) {
        // We can publish sync events here if we want the floating button to show a spinning progress icon.
        // For now, the basic integration just schedules a standard sync.
    }

    override fun reloadProject(context: ExternalSystemProjectReloadContext) {
        // Trigger the actual Kargo model read and workspace model update
        KargoSyncManager.getInstance(project).scheduleSync()
    }
}
