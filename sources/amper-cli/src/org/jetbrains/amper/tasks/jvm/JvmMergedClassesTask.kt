/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.BuildPrimitives
import org.jetbrains.amper.core.extract.cleanDirectory
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString

/**
 * Merges multiple output directories from [JvmClassesTask] into a single directory.
 * This is needed for Amper Plugin API which expects a single path for classes.
 */
class JvmMergedClassesTask(
    override val taskName: TaskName,
    val module: AmperModule,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
) : Task {
    override suspend fun run(dependenciesResult: List<TaskResult>, executionContext: TaskGraphExecutionContext): TaskResult {
        val classesResults = dependenciesResult.filterIsInstance<JvmClassesTask.Result>()
        val inputDirs = classesResults.flatMap { it.paths }.filter { it.exists() }
        val outputDir = taskOutputRoot.path

        incrementalCache.execute(
            key = taskName.name,
            inputValues = mapOf("outputDir" to outputDir.pathString),
            inputFiles = inputDirs,
        ) {
            // TODO: Do it incrementally?
            cleanDirectory(outputDir)
            inputDirs.forEach { inputDir ->
                BuildPrimitives.copy(from = inputDir, to = outputDir)
            }
            IncrementalCache.ExecutionResult(outputFiles = listOf(outputDir))
        }

        return Result(outputDir.toAbsolutePath(), module)
    }

    class Result(
        val path: Path,
        val module: AmperModule,
    ) : TaskResult
}
