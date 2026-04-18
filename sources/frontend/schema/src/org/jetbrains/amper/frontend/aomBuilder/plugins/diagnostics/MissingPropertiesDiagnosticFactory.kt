/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.frontend.tree.RefinedMappingNode
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
        pluginTree.completeTree()
        // We don't need to save the result
    }
}
