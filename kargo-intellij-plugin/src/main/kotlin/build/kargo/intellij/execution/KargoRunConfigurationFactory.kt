package build.kargo.intellij.execution

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

class KargoRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "KargoRunConfiguration"

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return KargoRunConfiguration(project, this, "Kargo Run Configuration")
    }

    override fun getOptionsClass(): Class<out com.intellij.execution.configurations.RunConfigurationOptions> =
        KargoRunConfigurationOptions::class.java
}
