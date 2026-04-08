/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.aomBuilder.plugins.PluginDiagnosticId
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.plugins.CustomCommand
import org.jetbrains.amper.frontend.plugins.PluginYamlRoot
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.RefinedListNode
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.get
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.stdlib.collections.distinctBy

/**
 * Checks that all names across the plugin's commands are unique
 */
internal object ConflictingCommandNamesDiagnosticFactory : IsolatedPluginYamlDiagnosticsFactory {
    context(reporter: ProblemReporter)
    override fun analyze(pluginTree: RefinedMappingNode) {
        val commandList = pluginTree[PluginYamlRoot::commands] as? RefinedListNode ?: return
        val commandNames = commandList.children.filterIsInstance<RefinedMappingNode>()
            .mapNotNull { it[CustomCommand::name] as? StringNode }
        if (commandNames.isEmpty()) return

        commandNames.distinctBy(
            selector = { it.value },
            onDuplicates = block@ { name, duplicates ->
                reporter.reportBundleError(
                    source = MultipleLocationsBuildProblemSource(
                        sources = duplicates.mapNotNull { it.asBuildProblemSource() as? FileBuildProblemSource },
                        groupingMessage = SchemaBundle.message("plugin.commands.conflicting.name.grouping"),
                    ),
                    diagnosticId = PluginDiagnosticId.ConflictingCommandNames,
                    messageKey = "plugin.commands.conflicting.name",
                    name,
                )
            }
        )
    }
}
