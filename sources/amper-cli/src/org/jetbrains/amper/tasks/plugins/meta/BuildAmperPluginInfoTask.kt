/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.plugins.meta

import org.jetbrains.amper.cli.CliProblemReporter
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.aomBuilder.plugins.AmperPluginImpl
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.replayProblemsTo
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.jvm.logger
import org.jetbrains.amper.util.BuildType

class BuildAmperPluginInfoTask(
    private val projectContext: AmperProjectContext,
    override val module: AmperModule,
    /**
     * Indicates whether the plugin is applied to the project
     */
    private val isApplied: Boolean,
    override val taskName: TaskName,
) : BuildTask {
    override val isTest: Boolean = false
    override val platform: Platform = Platform.JVM
    override val buildType: BuildType? = null

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext,
    ): TaskResult {
        if (isApplied) {
            // Do nothing for now, as we don't know the "plugin binary format" yet.
            // TODO: fetch plugin data from the model
            return EmptyTaskResult
        }

        val globalResult = dependenciesResult.requireSingleDependency<PreProcessAmperPluginsTask.Result>()
        val modulePath = PluginData.Source.Local(module.source.moduleDir)
        val (pluginData, problems) = globalResult.result.first { it.pluginData.source == modulePath }

        problems.forEach(CliProblemReporter::reportMessage)
        if (problems.any { it.level == Level.Error }) {
            userReadableError("Plugin Kotlin schema processing failed, see the errors above.")
        }

        val reporter = CollectingProblemReporter()
        // TODO: It's not super beautiful to create `AmperPluginImpl` directly here; we'll see what can be done later
        AmperPluginImpl(
            projectContext = projectContext,
            pluginModule = module,
            pluginData = pluginData,
            types = SchemaTypingContext(
                pluginData = listOf(pluginData),
            ),
            problemReporter = reporter,
        )
        reporter.replayProblemsTo(CliProblemReporter)
        if (reporter.problems.any { it.level == Level.Error }) {
            userReadableError("`plugin.yaml` processing failed, see the errors above.")
        }

        logger.info("Amper plugin passed validation - OK")

        // TODO: Actually save something

        return EmptyTaskResult
    }
}