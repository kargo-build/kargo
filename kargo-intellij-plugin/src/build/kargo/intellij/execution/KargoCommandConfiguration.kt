package build.kargo.intellij.execution

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.xmlb.annotations.Attribute
import java.io.File
import java.io.OutputStream
import javax.swing.JComponent
import javax.swing.JPanel

/** Options persisted for a single Kargo run configuration. */
class KargoCommandOptions : RunConfigurationOptions() {
    @get:Attribute
    var command: String = ""

    @get:Attribute
    var moduleName: String = ""
}

/** A minimal settings editor (placeholder). */
class KargoCommandSettingsEditor : SettingsEditor<KargoCommandConfiguration>() {
    private val panel = JPanel()
    override fun createEditor(): JComponent = panel
    override fun resetEditorFrom(s: KargoCommandConfiguration) {}
    override fun applyEditorTo(s: KargoCommandConfiguration) {}
}

/** Represents a Kargo run configuration entry in the IDE run dialog. */
class KargoCommandConfiguration(
    project: Project,
    factory: ConfigurationFactory
) : LocatableConfigurationBase<KargoCommandOptions>(project, factory, "Kargo") {

    private val kargoOptions: KargoCommandOptions
        get() = state!!

    /** Kargo sub-command to run (e.g. "run", "test", "build"). */
    var command: String
        get() = kargoOptions.command
        set(value) { kargoOptions.command = value }

    /** Optional module name to scope the command (e.g. ":my-app"). */
    var moduleName: String
        get() = kargoOptions.moduleName
        set(value) { kargoOptions.moduleName = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = KargoCommandSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return KargoRunProfileState(
            project = project,
            command = command,
            moduleName = moduleName
        )
    }
}

/**
 * A [RunProfileState] that spawns the `kargo <command> [moduleName]` process
 * and wires its output into the IDE's run console.
 */
class KargoRunProfileState(
    private val project: Project,
    private val command: String,
    private val moduleName: String
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
        val projectBase = project.basePath ?: "."
        val kargoExe = resolveKargoWrapper(projectBase)
        val args = buildList {
            add(kargoExe)
            add(command)
            if (moduleName.isNotBlank()) add(moduleName)
        }

        val process = ProcessBuilder(args)
            .directory(File(projectBase))
            .redirectErrorStream(true)
            .start()

        // Wraps the Java process into an IntelliJ ProcessHandler
        val processHandler = object : ProcessHandler() {
            override fun destroyProcessImpl() { process.destroyForcibly() }
            override fun detachProcessImpl() { process.destroyForcibly() }
            override fun detachIsDefault(): Boolean = false
            override fun getProcessInput(): OutputStream? = process.outputStream

            init {
                Thread(Runnable {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        notifyTextAvailable(line + "\n", Key.create<String>("stdout"))
                    }
                    val exitCode = process.waitFor()
                    notifyProcessTerminated(exitCode)
                }, "kargo-output-reader").also { it.isDaemon = true }.start()
            }
        }

        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project).console
        console.attachToProcess(processHandler)

        return DefaultExecutionResult(console, processHandler)
    }

    private fun resolveKargoWrapper(basePath: String): String {
        for (name in listOf("kargo", "kargo.sh", "kargow")) {
            val file = File(basePath, name)
            if (file.exists() && file.canExecute()) return file.absolutePath
        }
        return "kargo"
    }
}
