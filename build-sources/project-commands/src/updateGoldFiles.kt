/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ReplacePrintlnWithLogging")

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*

@TaskAction(ExecutionAvoidance.Disabled) // we can't track all outputs
fun updateGoldFiles(@Input amperRootDir: Path, versions: Versions) {
    syncVersions(amperRootDir, versions)
    AmperGoldUpdater(amperRootDir).updateGoldFiles()
}

private class AmperGoldUpdater(
    val amperRootDir: Path,
    val maxAttempts: Int = 10,
) {
    private val schemaModuleDir = amperRootDir / "sources/frontend/schema"
    private val testResourcesDir = schemaModuleDir / "testResources"
    private val testResourcePathRegex = Regex("(${Regex.escape(testResourcesDir.absolutePathString())})[^),\"'\n\r]*")

    fun updateGoldFiles() {
        updateDrGoldFiles(amperRootDir)
        updateSchemaGoldFiles(amperRootDir)
        updateCliGoldFiles(amperRootDir)
    }

    private fun updateDrGoldFiles(amperRootDir: Path) {
        updateGoldFilesUntilSuccess(
            sectionName = "dr module",
            goldFilesRoot = amperRootDir / "sources/frontend/dr",
        ) {
            runAmperCli(amperRootDir, "test", "-m", "dr")
        }
    }

    private fun updateSchemaGoldFiles(amperRootDir: Path) {
        updateGoldFilesUntilSuccess(
            sectionName = "schema module",
            goldFilesRoot = schemaModuleDir,
        ) {
            runAmperCli(amperRootDir, "test", "-m", "schema")
        }
    }

    private fun updateCliGoldFiles(amperRootDir: Path) {
        updateGoldFilesUntilSuccess(
            sectionName = "amper-cli-test module",
            goldFilesRoot = amperRootDir / "sources/test-integration/amper-cli-test",
        ) {
            runAmperCli(amperRootDir, "test", "-m", "amper-cli-test", "--include-classes=*.ShowSettingsCommandTest", "--include-classes=*.ShowDependenciesCommandTest")
        }
    }

    private fun updateGoldFilesUntilSuccess(sectionName: String, goldFilesRoot: Path, runTests: () -> Int) {
        println("=== Updating $sectionName gold files ===")
        repeat(maxAttempts) { attemptIndex ->
            val attemptNumber = attemptIndex + 1
            println("Attempt $attemptNumber/$maxAttempts: running tests...")
            val exitCode = runTests()
            if (exitCode == 0) {
                println("Tests passed for $sectionName.")
                println()
                return
            }

            val updatedFilesCount = updateTmpFilesUnder(goldFilesRoot)
            if (updatedFilesCount == 0) {
                println("Tests failed for $sectionName, but no .tmp files were found.")
            } else {
                println("Updated $updatedFilesCount gold file(s) for $sectionName.")
            }
            println()
        }

        error("Failed to update $sectionName gold files after $maxAttempts attempts.")
    }

    private fun updateTmpFilesUnder(root: Path): Int {
        var updatedFilesCount = 0
        root.walk()
            .filter { it.name.endsWith(".tmp") }
            .forEach { tmpResultFile ->
                updateGoldFileFor(tmpResultFile)
                updatedFilesCount++
            }
        return updatedFilesCount
    }

    @Suppress("PROCESS_BUILDER_START_LEAK")
    private fun runAmperCli(amperRootDir: Path, vararg args: String): Int {
        val isWindows = System.getProperty("os.name").startsWith("Win", ignoreCase = true)
        val amperScript = amperRootDir.resolve(if (isWindows) "amper.bat" else "amper")
        return ProcessBuilder(amperScript.pathString, *args).inheritIO().start().waitFor()
    }

    private fun updateGoldFileFor(tmpResultFile: Path) {
        val realGoldFile = goldFileFor(tmpResultFile)
        println("Replacing ${realGoldFile.name} with the contents of ${tmpResultFile.name}")
        val newGoldContent = tmpResultFile.contentsWithVariables()
        realGoldFile.writeText(newGoldContent)
        tmpResultFile.deleteExisting()
    }

    private fun goldFileFor(tmpResultFile: Path): Path = tmpResultFile.resolveSibling(tmpResultFile.name.removeSuffix(".tmp"))

    /**
     * Gets the contents of this temp file with the paths replaced with variables, as they are usually in gold files to
     * make them machine-/os-independent.
     */
    private fun Path.contentsWithVariables(): String = readText().replace(testResourcePathRegex) { match ->
        // See variable substitution in schema/helper/util.kt
        match.value
            // {{ testResources }} is used for the "base" path, which is the dir containing the gold file
            .replace(parent.absolutePathString(), "{{ testResources }}")
            // {{ testProcessDir }} is the dir in which tests are run, which is the schema module
            .replace(schemaModuleDir.absolutePathString(), "{{ testProcessDir }}")
            .replace(File.separator, "{{ fileSeparator }}")
    }
}
