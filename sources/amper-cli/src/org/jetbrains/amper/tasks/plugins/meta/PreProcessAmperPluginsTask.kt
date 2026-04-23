/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.plugins.meta

import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.AmperProjectRoot
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.plugins.PluginManifest
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForSerializable
import org.jetbrains.amper.plugins.PluginDataWithDiagnostics
import org.jetbrains.amper.plugins.runAmperSchemaProcessor
import org.jetbrains.amper.tasks.TaskResult

/**
 * Pre-processes unapplied Amper plugins by running schema processing and collecting diagnostics.
 * This task is never marked as failed and doesn't report any diagnostics itself.
 *
 * All the diagnostics are reported later by individual per-plugin tasks.
 *
 * @see BuildAmperPluginInfoTask
 * @see org.jetbrains.amper.plugins.preparePlugins
 */
class PreProcessAmperPluginsTask(
    override val taskName: TaskName,
    private val projectRoot: AmperProjectRoot,
    private val incrementalCache: IncrementalCache,
    private val processRunner: ProcessRunner,
    private val unappliedPluginModules: List<AmperModule>,
) : Task {
    init {
        require(unappliedPluginModules.all { it.type == ProductType.JVM_AMPER_PLUGIN })
    }

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext,
    ): TaskResult {
        val plugins = unappliedPluginModules.associate { module ->
            val pluginInfo = checkNotNull(module.commonModuleNode.pluginInfo) {
                "Plugin info must be present for plugin module: ${module.userReadableName}"
            }
            @Suppress("DEPRECATION") // we fall back to the deprecated description for a transition period
            module.source.moduleDir to PluginManifest(
                id = pluginInfo.id,
                description = module.description ?: pluginInfo.description,
                settingsClass = pluginInfo.settingsClass,
            )
        }

        val response = incrementalCache.executeForSerializable(
            key = taskName.name,
            inputValues = mapOf(
                "plugins" to plugins.values.joinToString(),
            ),
            inputFiles = plugins.keys.toList(),
        ) {
            runAmperSchemaProcessor(
                projectRoot = projectRoot,
                plugins = plugins,
                processRunner = processRunner,
            )
        }

        return Result(response)
    }

    class Result(val result: List<PluginDataWithDiagnostics>) : TaskResult
}