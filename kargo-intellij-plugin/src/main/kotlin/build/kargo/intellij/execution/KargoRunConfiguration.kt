package build.kargo.intellij.execution

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.io.OutputStream

class KargoRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : LocatableConfigurationBase<KargoRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): KargoRunConfigurationOptions {
        return super.getOptions() as KargoRunConfigurationOptions
    }

    var command: String?
        get() = options.command
        set(value) {
            options.command = value
        }

    var workingDirectory: String?
        get() = options.workingDirectory
        set(value) {
            options.workingDirectory = value
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return KargoRunConfigurationEditor(project)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        return object : RunProfileState {
            override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
                val command = command ?: "build"
                val workingDir = workingDirectory ?: project.basePath ?: ""

                val projectBase = project.basePath ?: "."
                val kargoExe = resolveKargoWrapper(projectBase)
                
                val args = buildList {
                    add(kargoExe)
                    add(command)
                }

                val process = ProcessBuilder(args)
                    .directory(File(workingDir))
                    .redirectErrorStream(true)
                    .start()

                val processHandler = object : ProcessHandler() {
                    override fun destroyProcessImpl() { process.destroyForcibly() }
                    override fun detachProcessImpl() { process.destroyForcibly() }
                    override fun detachIsDefault(): Boolean = false
                    override fun getProcessInput(): OutputStream? = process.outputStream

                    init {
                        Thread({
                            process.inputStream.bufferedReader().forEachLine { line ->
                                notifyTextAvailable(line + "\n", Key.create<String>("stdout"))
                            }
                            val exitCode = process.waitFor()
                            notifyProcessTerminated(exitCode)
                        }, "kargo-output-reader").also { it.isDaemon = true }.start()
                    }
                }

                val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
                console.attachToProcess(processHandler)

                return DefaultExecutionResult(console, processHandler)
            }
        }
    }

    private fun resolveKargoWrapper(basePath: String): String {
        for (name in listOf("kargo", "kargo.sh", "kargow")) {
            val file = File(basePath, name)
            if (file.exists() && file.canExecute()) return file.absolutePath
        }
        return "kargo"
    }
}
