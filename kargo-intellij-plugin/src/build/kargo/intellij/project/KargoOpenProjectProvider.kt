package build.kargo.intellij.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Helper to check if a file or directory is a Kargo project.
 * Does NOT open files or interact with the IDE project system directly —
 * that is delegated to KargoProjectOpenProcessor.
 */
class KargoOpenProjectProvider {

    fun isProjectFile(file: VirtualFile): Boolean =
        file.name == "project.yaml" || file.name == "module.yaml"

    fun canOpenProject(file: VirtualFile): Boolean {
        if (file.isDirectory) {
            return file.children.any { isProjectFile(it) }
        }
        return isProjectFile(file)
    }
}
