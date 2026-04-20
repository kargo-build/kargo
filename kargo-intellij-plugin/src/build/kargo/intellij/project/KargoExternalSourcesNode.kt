package build.kargo.intellij.project

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.PlatformIcons

/**
 * Replaces the auto-generated "vendor" ModuleGroup in the Project View with a
 * single "External Sources" node — similar to "External Libraries".
 *
 * The vendor ModuleGroup is created by IntelliJ because modules are named
 * "vendor.repo.fragment". This provider intercepts the project root children,
 * removes the vendor group, and injects a clean "External Sources" node instead.
 */
class KargoExternalSourcesTreeStructureProvider : TreeStructureProvider {

    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?
    ): Collection<AbstractTreeNode<*>> {
        if (parent is KargoExternalSourcesNode) return children

        val project = parent.project ?: return children
        if (!isKargoProject(project)) return children

        // Only act at the project root level (value is the Project itself)
        if (parent.value !is Project) return children

        val vendorRoots = collectVendorContentRoots(project)
        if (vendorRoots.isEmpty()) return children

        // Find the vendor ModuleGroup node among the children — it's the one whose
        // children contain our vendor content roots
        val vendorGroupNode = children.firstOrNull { node ->
            isVendorModuleGroup(node)
        }

        // Also find any vendor directory nodes that appear directly at root level
        val directVendorNodes = children.filter { node ->
            val path = (node as? ProjectViewNode<*>)?.virtualFile?.path ?: return@filter false
            vendorRoots.any { it.path == path }
        }

        val nodesToRemove = setOfNotNull(vendorGroupNode) + directVendorNodes
        if (nodesToRemove.isEmpty()) return children

        val regularNodes = children.filter { it !in nodesToRemove }

        // Build deduplicated vendor directory nodes from content roots
        val externalSourcesNode = KargoExternalSourcesNode(project, vendorRoots, settings)
        return regularNodes + externalSourcesNode
    }

    private fun isVendorModuleGroup(node: AbstractTreeNode<*>): Boolean {
        val value = node.value ?: return false
        if (value.javaClass.name != "com.intellij.ide.projectView.impl.ModuleGroup") return false
        return runCatching {
            val path = value.javaClass.getMethod("getGroupPath").invoke(value) as? Array<*>
            path?.firstOrNull()?.toString() == "vendor"
        }.getOrDefault(false)
    }

    private fun collectVendorContentRoots(project: Project): List<VirtualFile> {
        return ModuleManager.getInstance(project).modules
            .filter { it.name.startsWith("vendor.") }
            .flatMap { ModuleRootManager.getInstance(it).contentRoots.toList() }
            .distinctBy { it.path }
    }
}

/**
 * The synthetic "External Sources" group node.
 * Its children are PsiDirectoryNodes for each unique vendor repo root.
 */
class KargoExternalSourcesNode(
    project: Project,
    private val vendorRoots: List<VirtualFile>,
    private val settings: ViewSettings?
) : ProjectViewNode<String>(project, "External Sources", settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val psiManager = com.intellij.psi.PsiManager.getInstance(myProject)
        return vendorRoots.mapNotNull { vFile ->
            val psiDir = psiManager.findDirectory(vFile) ?: return@mapNotNull null
            com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode(myProject, psiDir, settings)
        }
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = "External Sources"
        presentation.setIcon(PlatformIcons.LIBRARY_ICON)
        presentation.addText("External Sources", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override fun contains(file: VirtualFile): Boolean =
        vendorRoots.any { file.path.startsWith(it.path) }

    override fun getSortOrder(settings: com.intellij.ide.projectView.NodeSortSettings) =
        com.intellij.ide.projectView.NodeSortOrder.LIBRARY_ROOT
}
