package build.kargo.intellij.project.wizard

import com.intellij.icons.AllIcons
import com.intellij.ide.projectWizard.NewProjectWizardCollector.Maven.logGroupIdChanged
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep.Companion.GROUP_ID_PROPERTY_NAME
import com.intellij.openapi.ui.validation.CHECK_GROUP_ID
import com.intellij.openapi.ui.validation.CHECK_NON_EMPTY
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.util.trim
import com.intellij.ui.dsl.builder.trimmedTextValidation
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.io.path.createDirectories

data class KargoTemplate(val id: String, val name: String, val description: String)

class KargoProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    final val groupIdProperty: GraphProperty<String> = propertyGraph.lazyProperty { getPersistentValue(GROUP_ID_PROPERTY_NAME, "org.example") }

    private val templates = listOf(
        KargoTemplate("jvm-app", "JVM Application", "A simple JVM application"),
        KargoTemplate("jvm-lib", "JVM Library", "A simple JVM library"),
        KargoTemplate("native-app", "Native Application", "A simple Kotlin/Native application")
    )
    
    private val selectedTemplateProperty: GraphProperty<KargoTemplate> = propertyGraph.property(templates.first())

    override fun setupUI(builder: Panel) {
        builder.apply {
            setupGroupIdUI(this)
            //TODO trazer os projetos de /home/leodouglas/Projects/kargo-build/kargo/sources/amper-project-templates
            row("Project Template:") {
                comboBox(templates, com.intellij.ui.SimpleListCellRenderer.create { label, value, _ ->
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
                setPersistentValue(GROUP_ID_PROPERTY_NAME, getPersistentValue(GROUP_ID_PROPERTY_NAME, "org.example"))
            }
        }
    }

    override fun setupProject(project: Project) {
      //TODO implementar a criação do projeto
    }

    private fun getPersistentValue(property: String, defaultValue: String) = PropertiesComponent.getInstance().getValue(property, defaultValue)

    private fun setPersistentValue(property: String, value: String?) = PropertiesComponent.getInstance().setValue(property, value)
}
