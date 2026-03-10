package build.kargo.intellij.execution.run

import build.kargo.intellij.execution.KargoRunConfiguration
import build.kargo.intellij.execution.KargoRunConfigurationType
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

class KargoModuleFileConfigurationProducer : LazyRunConfigurationProducer<KargoRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        KargoRunConfigurationType.getInstance().factory

    override fun setupConfigurationFromContext(
        configuration: KargoRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val element = sourceElement.get() ?: return false
        val file = element.containingFile as? YAMLFile ?: return false
        if (file.name != "module.yaml") return false

        val keyValue = getProductKeyValue(element) ?: return false
        val productType = getProductType(keyValue) ?: return false
        val isApp = productType.endsWith("/app") || productType == "app"

        configuration.name = if (isApp) "Run ${file.virtualFile.parent.name}" else "Build ${file.virtualFile.parent.name}"
        configuration.command = if (isApp) "run" else "build"
        configuration.workingDirectory = file.virtualFile?.parent?.path ?: return false

        return true
    }

    override fun isConfigurationFromContext(
        configuration: KargoRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val element = context.psiLocation ?: return false
        val file = element.containingFile as? YAMLFile ?: return false
        if (file.name != "module.yaml") return false

        val basePath = file.virtualFile?.parent?.path ?: return false
        if (configuration.workingDirectory != basePath) return false

        val keyValue = getProductKeyValue(element) ?: return false
        val productType = getProductType(keyValue) ?: return false
        val isApp = productType.endsWith("/app") || productType == "app"
        val expectedCommand = if (isApp) "run" else "build"

        return configuration.command == expectedCommand
    }

    private fun getProductKeyValue(element: PsiElement): YAMLKeyValue? {
        var current: PsiElement? = element
        while (current != null && current !is YAMLFile) {
            if (current is YAMLKeyValue && current.keyText == "product") {
                return current
            }
            current = current.parent
        }
        // Fallback to searching the whole file if not under a specific key
        val file = element.containingFile as? YAMLFile ?: return null
        val mapping = file.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return null
        return mapping.keyValues.find { it.keyText == "product" }
    }

    private fun getProductType(productKv: YAMLKeyValue): String? {
        val value = productKv.value
        if (value is YAMLMapping) {
            return value.keyValues.find { it.keyText == "type" }?.valueText
        }
        return productKv.valueText
    }
}
