package build.kargo.intellij.project.wizard

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.newProjectWizardBaseStepWithoutGap
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GeneratorNewProjectWizardBuilderAdapter
import com.intellij.ide.wizard.RootNewProjectWizardStep
import build.kargo.intellij.KargoIcons
import javax.swing.Icon

class KargoNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "kargo"
    override val name: String = "Kargo"
    override val icon: Icon = KargoIcons.Kargo

    override fun createStep(context: WizardContext): NewProjectWizardStep {
        return RootNewProjectWizardStep(context)
            .nextStep(::newProjectWizardBaseStepWithoutGap)
            .nextStep(::KargoProjectWizardStep)
    }

    class Builder : GeneratorNewProjectWizardBuilderAdapter(KargoNewProjectWizard()) {
        override fun getWeight(): Int = JVM_WEIGHT + 101
    }
}
