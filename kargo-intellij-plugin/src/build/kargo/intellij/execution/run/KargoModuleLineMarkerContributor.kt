package build.kargo.intellij.execution.run

import build.kargo.intellij.KargoIcons
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

class KargoModuleLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val keyValue = element.parent as? YAMLKeyValue ?: return null
        if (keyValue.key != element || keyValue.keyText != "product") return null

        val file = element.containingFile as? YAMLFile ?: return null
        if (file.name != "module.yaml") return null

        val productType = getProductType(keyValue) ?: return null
        val isApp = productType.endsWith("/app") || productType == "app"
        val icon = if (isApp) AllIcons.RunConfigurations.TestState.Run else AllIcons.Actions.Compile

        return Info(icon, ExecutorAction.getActions(1).filterNotNull().toTypedArray()) {
            if (isApp) "Run Kargo module" else "Build Kargo module"
        }
    }

    private fun getProductType(productKv: YAMLKeyValue): String? {
        val value = productKv.value
        if (value is YAMLMapping) {
            return value.keyValues.find { it.keyText == "type" }?.valueText
        }
        return productKv.valueText
    }
}
