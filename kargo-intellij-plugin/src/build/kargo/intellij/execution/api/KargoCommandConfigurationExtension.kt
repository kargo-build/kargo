package build.kargo.intellij.execution.api

import build.kargo.intellij.execution.KargoCommandConfiguration
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.extensions.ExtensionPointName

interface KargoCommandConfigurationExtension {
    fun isApplicableFor(configuration: KargoCommandConfiguration): Boolean
    fun updateRunnerSettings(configuration: KargoCommandConfiguration, runnerSettings: RunnerSettings)
    // More profile implementations later
    
    companion object {
        val EP_NAME = ExtensionPointName.create<KargoCommandConfigurationExtension>("build.kargo.intellij.commandRunExtension")
    }
}
