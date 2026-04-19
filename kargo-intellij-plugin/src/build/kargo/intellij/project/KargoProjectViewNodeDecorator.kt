package build.kargo.intellij.project

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.PlatformIcons

/**
 * Cleans up the Project View for Kargo projects:
 * - Project root: bold name
 * - Source roots (src/, src@linux/, etc.): blue source folder icon
 * - Vendor module content roots (git source repos): library icon + clean repo name
 * - Other module dirs: strips bracketed suffixes
 */
class KargoProjectViewNodeDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        if (!file.isDirectory) return
        val project = node.project ?: return

        if (!isKargoProject(project)) return

        // --- Project root: bold ---
        if (file.path == project.basePath) {
            data.clearText()
            data.addText(file.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            data.setIcon(AllIcons.Nodes.Module)
            data.locationString = null
            return
        }

        val moduleManager = ModuleManager.getInstance(project)

        // --- Vendor content roots: "External Sources" style ---
        // The content root of a vendor module is the repo root (parent of src/)
        val vendorModule = moduleManager.modules.firstOrNull { module ->
            module.name.startsWith("vendor.") && ModuleRootManager.getInstance(module).contentRoots.any { it.path == file.path }
        }
        if (vendorModule != null) {
            // Clean name: strip "vendor." prefix and fragment suffix (.common, .linuxX64, etc.)
            // "vendor.kargo-build-kargo-native-git-lib.common" → "kargo-native-git-lib"
            val repoSlug = vendorModule.name
                .removePrefix("vendor.")
                .substringBeforeLast(".")   // remove fragment name
            // Further clean: if slug is "org-repo-name", keep as-is; looks good enough
            data.clearText()
            data.addText(repoSlug, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            data.locationString = null
            data.setIcon(AllIcons.Nodes.PpLibFolder)
            return
        }

        // --- Source roots: blue for main, green for test ---
        data class SourceRootKind(val isTest: Boolean)
        val sourceRootKind = moduleManager.modules.firstNotNullOfOrNull { module ->
            val rootManager = ModuleRootManager.getInstance(module)
            val mainRoots = rootManager.getSourceRoots(false).map { it.path }.toSet()
            val allRoots  = rootManager.getSourceRoots(true).map { it.path }.toSet()
            when (file.path) {
                in mainRoots -> SourceRootKind(isTest = false)
                in allRoots  -> SourceRootKind(isTest = true)
                else -> null
            }
        }
        if (sourceRootKind != null) {
            data.clearText()
            data.addText(file.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            data.locationString = null
            if (sourceRootKind.isTest)
                data.setIcon(PlatformIcons.TEST_SOURCE_FOLDER)
            else
                data.setIcon(PlatformIcons.MODULES_SOURCE_FOLDERS_ICON)
            return
        }

        // --- Other module-owned dirs: strip bracketed suffixes ---
        ModuleUtilCore.findModuleForFile(file, project) ?: return
        val hasBrackets = data.coloredText.any { it.text.contains("[") && it.text.contains("]") }
        if (hasBrackets || data.locationString != null) {
            data.clearText()
            data.addText(file.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            data.locationString = null
        }
    }
}
