package build.kargo.intellij.execution

import build.kargo.intellij.KargoIcons
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.openapi.util.NotNullLazyValue

class KargoRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Kargo",
    "Kargo run configuration",
    NotNullLazyValue.createValue { KargoIcons.Kargo }
) {
    init {
        addFactory(KargoRunConfigurationFactory(this))
    }

    companion object {
        const val ID = "KargoRunConfiguration"
    }
}
