package build.kargo.intellij.execution

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.icons.AllIcons

import com.intellij.execution.configurations.ConfigurationTypeUtil

class KargoRunConfigurationType : SimpleConfigurationType(
    "Kargo.Run",
    "Kargo",
    "Run or build Kargo project",
    NotNullLazyValue.createValue { build.kargo.intellij.KargoIcons.Kargo }
), DumbAware {

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return KargoCommandConfiguration(project, this)
    }

    override fun getOptionsClass(): Class<out BaseState> = KargoCommandOptions::class.java

    override fun isEditableInDumbMode(): Boolean = true

    companion object {
        fun getInstance(): KargoRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(KargoRunConfigurationType::class.java)
    }
}
