package build.kargo.intellij.execution.run

import build.kargo.intellij.execution.KargoCommandConfiguration
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import build.kargo.intellij.execution.KargoRunConfigurationType
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

class KargoModuleFileConfigurationProducer : LazyRunConfigurationProducer<KargoCommandConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory {
        return KargoRunConfigurationType.getInstance()
    }

    override fun setupConfigurationFromContext(
        configuration: KargoCommandConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val vFile = context.location?.virtualFile ?: return false
        if (vFile.isDirectory && vFile.findChild("project.yaml") == null) return false
        if (!vFile.isDirectory && vFile.name != "project.yaml") return false

        configuration.name = "Kargo Run"
        configuration.command = "run"
        return true
    }

    override fun isConfigurationFromContext(
        configuration: KargoCommandConfiguration,
        context: ConfigurationContext
    ): Boolean {
        return configuration.command == "run"
    }
}
