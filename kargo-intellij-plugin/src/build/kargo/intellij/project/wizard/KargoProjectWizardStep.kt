package build.kargo.intellij.project.wizard

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep.Companion.GROUP_ID_PROPERTY_NAME
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.util.trim
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.validation.CHECK_GROUP_ID
import com.intellij.openapi.ui.validation.CHECK_NON_EMPTY
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.trimmedTextValidation
import com.intellij.ui.SimpleListCellRenderer
import org.jetbrains.amper.templates.AmperProjectTemplate
import org.jetbrains.amper.templates.AmperProjectTemplates
import java.nio.file.Path

class KargoProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    val groupIdProperty: GraphProperty<String> = propertyGraph.lazyProperty { getPersistentValue(GROUP_ID_PROPERTY_NAME, "org.example") }

    private val templates = AmperProjectTemplates.availableTemplates.filter { it.name.startsWith("Kargo") }
    
    private val selectedTemplateProperty: GraphProperty<AmperProjectTemplate> = propertyGraph.property(templates.first())

    override fun setupUI(builder: Panel) {
        builder.apply {
            setupGroupIdUI(this)
            row("Project Template:") {
                comboBox(templates, SimpleListCellRenderer.create { label, value, _ ->
                    label.text = value.name
                    label.toolTipText = value.description
                })
                .bindItem(selectedTemplateProperty)
            }
        }
    }

    protected fun setupGroupIdUI(builder: Panel) {
        builder.row {
            layout(RowLayout.LABEL_ALIGNED)
            label("Group:")
                .applyToComponent { horizontalTextPosition = JBLabel.LEFT }
                .applyToComponent { icon = AllIcons.General.ContextHelp }
                .applyToComponent { toolTipText = "Unique identifies your project, usually in reverse domain name notation." }
            textField()
            .bindText(groupIdProperty.trim())
            .columns(COLUMNS_MEDIUM)
            .trimmedTextValidation(CHECK_NON_EMPTY, CHECK_GROUP_ID)
            .validationInfo { null }
            .onApply {
                setPersistentValue(GROUP_ID_PROPERTY_NAME, groupIdProperty.get())
            }
        }
    }

    override fun setupProject(project: Project) {
        val projectPath = Path.of(context.projectFileDirectory)
        val template = selectedTemplateProperty.get()
        
        template.listFiles().forEach { file ->
            file.extractTo(projectPath)
        }

        // Extrair os wrappers kargo e kargo.bat
        extractWrapper("kargo", projectPath, true)
        extractWrapper("kargo.bat", projectPath, false)
    }

    private fun extractWrapper(name: String, projectPath: Path, executable: Boolean) {
        val resourcePath = "/wrappers/$name"
        val inputStream = javaClass.getResourceAsStream(resourcePath) 
            ?: return // Silently fail if not found for now, or log it
        
        val targetPath = projectPath.resolve(name)
        inputStream.use { input ->
            java.nio.file.Files.copy(input, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        
        if (executable) {
            targetPath.toFile().setExecutable(true)
        }
    }

    private fun getPersistentValue(property: String, defaultValue: String) = PropertiesComponent.getInstance().getValue(property, defaultValue)

    private fun setPersistentValue(property: String, value: String?) = PropertiesComponent.getInstance().setValue(property, value)
}
