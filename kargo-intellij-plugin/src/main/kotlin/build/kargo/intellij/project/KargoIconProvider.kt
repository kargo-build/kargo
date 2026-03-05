package build.kargo.intellij.project

import build.kargo.intellij.KargoIcons
import com.intellij.ide.IconProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import javax.swing.Icon

/**
 * Provides the Kargo icon for Kargo configuration files and project directories.
 */
class KargoIconProvider : IconProvider() {

    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val virtualFile = element.containingFile?.virtualFile ?: try {
            val getVirtualFileMethod = element.javaClass.getMethod("getVirtualFile")
            getVirtualFileMethod.invoke(element) as? VirtualFile
        } catch (_: Exception) {
            null
        }

        if (virtualFile != null) {
            if (virtualFile.isDirectory) {
                if (virtualFile.findChild("project.yaml") != null || virtualFile.findChild("module.yaml") != null) {
                    return KargoIcons.Kargo
                }
            } else {
                val name = virtualFile.name
                if (name == "project.yaml" || name == "module.yaml") {
                    return KargoIcons.Kargo
                }
            }
        }
        return null
    }
}
