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
        KargoRunConfigurationType().configurationFactories.first()

    override fun setupConfigurationFromContext(
        configuration: KargoRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val element = sourceElement.get() ?: return false
        val keyValue = element.parent as? YAMLKeyValue ?: return false
        if (keyValue.keyText != "product") return false

        val file = keyValue.containingFile as? YAMLFile ?: return false
        if (file.name != "module.yaml") return false

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
        val keyValue = element.parent as? YAMLKeyValue ?: return false
        if (keyValue.keyText != "product") return false

        val file = keyValue.containingFile as? YAMLFile ?: return false
        if (file.name != "module.yaml") return false

        val basePath = file.virtualFile?.parent?.path ?: return false

        return configuration.workingDirectory == basePath &&
            (configuration.command == "run" || configuration.command == "build")
    }

    private fun getProductType(productKv: YAMLKeyValue): String? {
        val value = productKv.value
        if (value is YAMLMapping) {
            return value.keyValues.find { it.keyText == "type" }?.valueText
        }
        return productKv.valueText
    }
}
