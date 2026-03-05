package build.kargo.intellij.execution.run

import build.kargo.intellij.execution.KargoCommandConfiguration
import build.kargo.intellij.execution.api.KargoCommandConfigurationExtension
import com.intellij.execution.configurations.RunnerSettings

class KargoRunExtension : KargoCommandConfigurationExtension {
    override fun isApplicableFor(configuration: KargoCommandConfiguration): Boolean {
        return configuration.command == "run"
    }

    override fun updateRunnerSettings(
        configuration: KargoCommandConfiguration,
        runnerSettings: RunnerSettings
    ) {
        // Stub — will be populated when run configuration is fully implemented
    }
}
