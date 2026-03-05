package build.kargo.intellij.sync

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * Service to hold project synchronization status and contexts for Kargo.
 */
@Service(Service.Level.PROJECT)
class KargoProjectContextService(
    private val project: Project,
    private val scope: CoroutineScope
) {
    companion object {
        fun getInstance(project: Project): KargoProjectContextService =
            project.getService(KargoProjectContextService::class.java)
    }
}
