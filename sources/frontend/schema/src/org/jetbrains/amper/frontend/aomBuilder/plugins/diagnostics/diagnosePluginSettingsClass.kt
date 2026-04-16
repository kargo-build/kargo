/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics

import org.jetbrains.amper.frontend.aomBuilder.plugins.PluginDiagnosticId
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.plugins.PluginDeclarationSchema
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.types.generated.settingsClassDelegate
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.plugins.schema.model.PluginSettingsSearchResult
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.ProblemReporter

context(problemReporter: ProblemReporter)
internal fun diagnosePluginSettingsClass(
    pluginData: PluginData,
    pluginInfo: PluginDeclarationSchema,
) {
    when (pluginData.pluginSettingsSearchResult) {
        is PluginSettingsSearchResult.Invalid -> {
            problemReporter.reportBundleError(
                source = pluginInfo.settingsClassDelegate.asBuildProblemSource(),
                diagnosticId = PluginDiagnosticId.PluginInvalidSchemaClass,
                messageKey = "plugin.settings.class.invalid", pluginInfo.settingsClass,
            )
        }
        is PluginSettingsSearchResult.NotFound -> {
            problemReporter.reportBundleError(
                source = pluginInfo.settingsClassDelegate.asBuildProblemSource(),
                diagnosticId = PluginDiagnosticId.PluginMissingSchemaClass,
                messageKey = "plugin.settings.class.missing", pluginInfo.settingsClass,
                problemType = BuildProblemType.UnresolvedReference,
            )
        }
        is PluginSettingsSearchResult.Success, null -> {}
    }
}