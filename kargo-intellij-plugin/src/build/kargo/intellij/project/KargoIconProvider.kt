package build.kargo.intellij.project

import build.kargo.intellij.KargoIcons
import com.intellij.ide.IconProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import javax.swing.Icon

/**
 * Provides the Kargo icon for Kargo configuration files (project.yaml, module.yaml).
 * Directory icons are handled by KargoProjectViewNodeDecorator.
 */
class KargoIconProvider : IconProvider() {

    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val virtualFile = element.containingFile?.virtualFile ?: try {
            element.javaClass.getMethod("getVirtualFile").invoke(element) as? VirtualFile
        } catch (_: Exception) {
            null
        } ?: return null

        return if (isKargoFile(virtualFile)) KargoIcons.Kargo else null
    }
}
