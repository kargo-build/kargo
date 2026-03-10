package build.kargo.intellij.execution.test

import build.kargo.intellij.execution.KargoCommandConfiguration
import build.kargo.intellij.execution.api.KargoCommandConfigurationExtension
import com.intellij.execution.configurations.RunnerSettings

class KargoTestExtension : KargoCommandConfigurationExtension {
    override fun isApplicableFor(configuration: KargoCommandConfiguration): Boolean {
        return configuration.command == "test"
    }

    override fun updateRunnerSettings(
        configuration: KargoCommandConfiguration,
        runnerSettings: RunnerSettings
    ) {
        // Stub — will be populated when test configuration is fully implemented
    }
}
