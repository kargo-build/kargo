/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.MissingPropertiesHandler
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.completeTree
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Pre-checks `plugin.yaml` tree completeness, e.g., reports missing properties.
 */
internal object MissingPropertiesDiagnosticFactory : IsolatedPluginYamlDiagnosticsFactory {
    context(reporter: ProblemReporter)
    override fun analyze(pluginTree: RefinedMappingNode) {
        // Purely for reporting missing properties
        // TODO: Maybe extract just properties reporting routine so we don't so unnecessary transform?
        pluginTree.completeTree(TaskParameterAwareMissingPropertiesHandler(reporter))
        // We don't need to save the result
    }

    private class TaskParameterAwareMissingPropertiesHandler(
        problemReporter: ProblemReporter,
    ) : MissingPropertiesHandler.Default(problemReporter) {
        override fun onMissingRequiredPropertyValue(
            trace: Trace,
            valuePath: List<String>,
            relativeValuePath: List<String>,
        ) {
            if (valuePath.size == 4) {
                // ["tasks", *, "action", *]
                val (maybeTasks, _, maybeAction, maybeParameterName) = valuePath
                if (maybeTasks == "tasks" && maybeAction == "action") {
                    problemReporter.reportBundleError(
                        source = trace.asBuildProblemSource(),
                        diagnosticId = TreeDiagnosticId.NoValueForRequiredProperty,
                        messageKey = "validation.missing.task.parameter.value",
                        maybeParameterName,
                    )
                }
            } else {
                super.onMissingRequiredPropertyValue(trace, valuePath, relativeValuePath)
            }
        }
    }
}