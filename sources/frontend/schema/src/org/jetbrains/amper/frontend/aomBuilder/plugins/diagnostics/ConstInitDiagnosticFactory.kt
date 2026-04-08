/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.frontend.aomBuilder.plugins.PluginDiagnosticId
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.RecurringRefinedTreeVisitorUnit
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.resolution.subtreeContainsResolvableNodes
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Enforces [org.jetbrains.amper.frontend.api.ConstInit] contract.
 */
internal object ConstInitDiagnosticFactory : IsolatedPluginYamlDiagnosticsFactory {
    context(reporter: ProblemReporter)
    override fun analyze(pluginTree: RefinedMappingNode) {
        object : RecurringRefinedTreeVisitorUnit() {
            override fun visitMap(node: RefinedMappingNode) {
                node.refinedChildren.values.forEach { keyValue ->
                    val isConstInit = keyValue.propertyDeclaration?.isConstInit == true
                    if (isConstInit && keyValue.value.subtreeContainsResolvableNodes()) {
                        reporter.reportBundleError(
                            source = keyValue.asBuildProblemSource(),
                            diagnosticId = PluginDiagnosticId.ConstInitPropertyIsNotFullyResolved,
                            messageKey = "plugin.constinit.property.contains.holes",
                            keyValue.key,
                        )
                    }
                }
                super.visitMap(node)
            }
        }.visit(pluginTree)
    }
}