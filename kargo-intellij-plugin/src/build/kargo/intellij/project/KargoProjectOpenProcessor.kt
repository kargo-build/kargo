package build.kargo.intellij.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectOpenProcessor

/**
 * Handles "Open As Kargo Project". Detects project.yaml / module.yaml
 * and lets the standard platform processor open the project frame.
 * Sync is handled by KargoStartupActivity after the project loads.
 */
class KargoProjectOpenProcessor : ProjectOpenProcessor() {
    private val projectProvider = KargoOpenProjectProvider()

    override val name: String get() = "Kargo"
    override val icon: javax.swing.Icon get() = build.kargo.intellij.KargoIcons.Kargo32

    override fun canOpenProject(file: VirtualFile): Boolean =
        projectProvider.canOpenProject(file)

    /**
     * Returns null to let the platform open the project normally.
     * KargoStartupActivity will trigger sync once the project is open.
     */
    override suspend fun openProjectAsync(
        virtualFile: VirtualFile,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean
    ): Project? = null
}
