package build.kargo.intellij.codeInsight.yaml

import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.icons.AllIcons
import build.kargo.intellij.KargoIcons
import javax.swing.Icon

class KargoYamlColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Kargo (project.yaml)"
    
    override fun getIcon(): Icon = KargoIcons.Kargo

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter {
        // Will delegate to org.jetbrains.yaml.YAMLSyntaxHighlighter if YAML plugin is available
        return PlainSyntaxHighlighter() 
    }

    override fun getDemoText(): String = """
        # Kargo project.yaml
        product: lib
        dependencies:
          - org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = emptyMap()

    override fun getAttributeDescriptors(): Array<com.intellij.openapi.options.colors.AttributesDescriptor> = emptyArray()
}
