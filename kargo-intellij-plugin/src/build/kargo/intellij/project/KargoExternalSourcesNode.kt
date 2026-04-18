package build.kargo.intellij.project

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.PlatformIcons

/**
 * Injects an "External Sources" group node into the Project View that contains
 * all vendor (git source) module directories — similar to "External Libraries".
 *
 * Vendor modules are identified by their name starting with "vendor.".
 * Their content roots (the cloned repo directories) are moved out of the top-level
 * tree and placed under this synthetic group node.
 */
class KargoExternalSourcesTreeStructureProvider : TreeStructureProvider {

    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?
    ): Collection<AbstractTreeNode<*>> {
        val project = parent.project ?: return children
        if (!isKargoProject(project)) return children

        // Only act when the parent is the project root node (ProjectViewProjectNode)
        // identified by its class name — avoids recursion into our own synthetic node
        val parentClassName = parent.javaClass.simpleName
        if (parentClassName != "ProjectViewProjectNode" && parentClassName != "ProjectNode") return children

        val vendorRoots = collectVendorRoots(project)
        if (vendorRoots.isEmpty()) return children

        // Separate vendor nodes from regular nodes
        val vendorNodes = children.filter { node ->
            val path = (node as? ProjectViewNode<*>)?.virtualFile?.path ?: return@filter false
            vendorRoots.any { it.path == path }
        }

        if (vendorNodes.isEmpty()) return children

        val regularNodes = children.filter { it !in vendorNodes }

        val externalSourcesNode = KargoExternalSourcesNode(project, vendorNodes, settings)
        return regularNodes + externalSourcesNode
    }

    private fun collectVendorRoots(project: Project): List<com.intellij.openapi.vfs.VirtualFile> {
        return ModuleManager.getInstance(project).modules
            .filter { it.name.startsWith("vendor.") }
            .flatMap { ModuleRootManager.getInstance(it).contentRoots.toList() }
            .distinctBy { it.path }
    }
}

/**
 * The synthetic "External Sources" group node.
 */
class KargoExternalSourcesNode(
    project: Project,
    private val vendorNodes: Collection<AbstractTreeNode<*>>,
    private val settings: ViewSettings?
) : ProjectViewNode<String>(project, "External Sources", settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> = vendorNodes

    override fun update(presentation: PresentationData) {
        presentation.presentableText = "External Sources"
        presentation.setIcon(PlatformIcons.LIBRARY_ICON)
        presentation.addText("External Sources", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override fun contains(file: com.intellij.openapi.vfs.VirtualFile): Boolean =
        vendorNodes.filterIsInstance<ProjectViewNode<*>>().any { node ->
            node.virtualFile?.let { file.path.startsWith(it.path) } == true
        }

    override fun getSortOrder(settings: com.intellij.ide.projectView.NodeSortSettings) =
        com.intellij.ide.projectView.NodeSortOrder.LIBRARY_ROOT
}
