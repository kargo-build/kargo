/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Factory to provide diagnostics for the `plugin.yaml` file in isolation,
 * i.e., without the context of any module it is applied to.
 *
 * **IMPORTANT:**
 * Any [errors][org.jetbrains.amper.problems.reporting.Level.Error] issued lead to the plugin being considered invalid:
 * it can no longer be applied to any module.
 */
internal interface IsolatedPluginYamlDiagnosticsFactory {
    /**
     * Analyzes given [pluginTree] and issues diagnostics into [reporter].
     *
     * @param pluginTree `plugin.yaml` tree after refined (includes reference resolution).
     */
    context(reporter: ProblemReporter)
    fun analyze(pluginTree: RefinedMappingNode)
}
