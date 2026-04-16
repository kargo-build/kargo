/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.frontend.aomBuilder.plugins.PluginDiagnosticId
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.plugins.PluginYamlRoot
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.get
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Issues a warning if a plugin doesn't register any tasks.
 */
internal object NoTasksDiagnosticFactory : IsolatedPluginYamlDiagnosticsFactory {
    context(reporter: ProblemReporter)
    override fun analyze(pluginTree: RefinedMappingNode) {
        val tasks = pluginTree[PluginYamlRoot::tasks] as? MappingNode
        if (tasks == null || tasks.children.isEmpty()) {
            reporter.reportBundleError(
                source = tasks?.asBuildProblemSource() as? PsiBuildProblemSource
                // If tasks are `{}` by *default*, then we need to use the whole tree trace.
                    ?: pluginTree.asBuildProblemSource(),
                diagnosticId = PluginDiagnosticId.PluginDoesntRegisterAnyTasks,
                messageKey = "plugin.missing.tasks",
                level = Level.Warning,
            )
        }
    }
}