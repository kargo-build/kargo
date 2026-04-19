package build.kargo.intellij.project

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider

/**
 * Marks all files inside vendor (git source) module content roots as read-only in the IDE.
 * This prevents accidental edits to cloned git dependencies.
 */
class KargoVendorReadonlyPolicy(private val project: Project) : WritingAccessProvider() {

    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> {
        if (!isKargoProject(project)) return emptyList()
        val vendorRoots = collectVendorRoots(project)
        if (vendorRoots.isEmpty()) return emptyList()

        // Return files that are DENIED write access (inside vendor roots)
        return files.filter { file ->
            vendorRoots.any { root -> file.path.startsWith(root) }
        }
    }

    override fun isPotentiallyWritable(file: VirtualFile): Boolean {
        if (!isKargoProject(project)) return true
        val vendorRoots = collectVendorRoots(project)
        return vendorRoots.none { root -> file.path.startsWith(root) }
    }

    private fun collectVendorRoots(project: Project): List<String> {
        return ModuleManager.getInstance(project).modules
            .filter { it.name.startsWith("vendor.") }
            .flatMap { ModuleRootManager.getInstance(it).contentRoots.toList() }
            .map { it.path }
            .distinct()
    }
}
