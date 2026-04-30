/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.jar.JarConfig
import org.jetbrains.amper.jar.ZipInput
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * Creates a jar file containing all the main sources of the given [module] that are relevant to the given [platform].
 */
class JavadocJarTask(
    override val taskName: TaskName,
    private val module: AmperModule,
    private val platform: Platform,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
) : AbstractJarTask(taskName, incrementalCache) {

    override suspend fun assembleInputDirs(dependenciesResult: List<TaskResult>): List<ZipInput> {
        // TODO give the option to provide real Javadocs by calling Dokka
        val contentsDir = taskOutputRoot.path / "contents"
        incrementalCache.executeForFiles(
            key = "${taskName.name}-generateEmptyJavadocJar",
            inputValues = emptyMap(),
            inputFiles = emptyList(),
        ) {
            contentsDir.deleteRecursively()
            contentsDir.createDirectories().resolve("README.md").writeText("""
                # Empty Javadoc JAR
                
                Javadocs are not available for this module at the moment.
            """.trimIndent())
            listOf(contentsDir)
        }
        return listOf(ZipInput(path = contentsDir, destPathInArchive = Path(".")))
    }

    // Matches the current format of KMP jar publications from the KMP Gradle plugin (except for the version)
    // TODO add version here?
    override fun outputJarPath(): Path = taskOutputRoot.path / "${module.userReadableName}-${platform.schemaValue.lowercase()}-javadoc.jar"

    override fun jarConfig(): JarConfig = JarConfig()

    override fun createResult(jarPath: Path): AbstractJarTask.Result = Result(jarPath, platform)

    class Result(jarPath: Path, val platform: Platform) : AbstractJarTask.Result(jarPath)
}
