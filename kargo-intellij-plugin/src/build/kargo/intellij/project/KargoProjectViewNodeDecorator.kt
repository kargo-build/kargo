package build.kargo.intellij.project

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.ui.SimpleTextAttributes

/**
 * Removes the bracketed module name suffixes (e.g., "[kargo-demo-app.common]") 
 * from the Project View, ensuring a clean folder structure identical to Gradle/Amper.
 */
class KargoProjectViewNodeDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        if (!file.isDirectory) return
        val project = node.project ?: return

        // We only want to touch the UI if this is actually a Kargo-managed project.
        // Kargo fragments always contain '.' in their module names (e.g., project.common).
        val isKargoProject = com.intellij.openapi.module.ModuleManager.getInstance(project).modules.any { it.name.contains(".") }
        if (!isKargoProject) return
        
        // The file must belong to SOME module in the workspace to be decorated by us.
        ModuleUtilCore.findModuleForFile(file, project) ?: return

        // IntelliJ often groups modules at the root or shared directories as "directory-name [module1, module2]"
        val hasBrackets = data.coloredText.any { it.text.contains("[") && it.text.contains("]") }
        val hasLocationText = data.locationString != null

        if (hasBrackets || hasLocationText) {
            // Unconditionally strip the suffix and location string, leaving only the clean directory name.
            data.clearText()
            
            // To emulate Amper/Gradle native behavior, if the folder is the project root, make it bold
            val isRoot = file.path == project.basePath
            val attrs = if (isRoot) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
            
            data.addText(file.name, attrs)
            data.locationString = null
        }
    }
}
