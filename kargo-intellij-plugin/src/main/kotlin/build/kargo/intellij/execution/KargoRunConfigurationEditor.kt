package build.kargo.intellij.execution

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class KargoRunConfigurationEditor(private val project: Project) : SettingsEditor<KargoRunConfiguration>() {
    private val commandField = JBTextField()
    private val workingDirectoryField = TextFieldWithBrowseButton()

    override fun createEditor(): JComponent {
        workingDirectoryField.addBrowseFolderListener(
            "Select Working Directory",
            null,
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Command:"), commandField, 1, false)
            .addLabeledComponent(JBLabel("Working directory:"), workingDirectoryField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun resetEditorFrom(s: KargoRunConfiguration) {
        commandField.text = s.command ?: "build"
        workingDirectoryField.text = s.workingDirectory ?: ""
    }

    override fun applyEditorTo(s: KargoRunConfiguration) {
        s.command = commandField.text
        s.workingDirectory = workingDirectoryField.text
    }
}
