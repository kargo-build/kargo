package build.kargo.intellij.execution

import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.openapi.components.StoredProperty

class KargoRunConfigurationOptions : LocatableRunConfigurationOptions() {
    private val commandProperty: StoredProperty<String?> = string("build").provideDelegate(this, "command")
    private val workingDirectoryProperty: StoredProperty<String?> = string("").provideDelegate(this, "workingDirectory")

    var command: String?
        get() = commandProperty.getValue(this)
        set(value) {
            commandProperty.setValue(this, value)
        }

    var workingDirectory: String?
        get() = workingDirectoryProperty.getValue(this)
        set(value) {
            workingDirectoryProperty.setValue(this, value)
        }
}
