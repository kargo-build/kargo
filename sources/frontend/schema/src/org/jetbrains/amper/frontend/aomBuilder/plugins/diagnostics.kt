/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins

import org.jetbrains.amper.core.UsedInIdePlugin

@Deprecated(
    message = "Was relocated into the .diagnostics package",
    replaceWith = ReplaceWith(
        expression = "org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics.PluginYamlMissing",
        imports = ["org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics.PluginYamlMissing"],
    )
)
@UsedInIdePlugin
typealias PluginYamlMissing = org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics.PluginYamlMissing