/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.plugins

import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.plugins.schema.model.PluginData

/**
 * A custom command contributed by a plugin.
 * It defines a sub-command that can be invoked via the `amper do` command.
 */
class CustomCommandFromPlugin(
    /**
     * The simple name of this command as it appears in the CLI.
     */
    val name: String,

    /**
     * The name of the task that performs this command.
     */
    val performedBy: TaskName,

    /**
     * Plugin ID that this command belongs to.
     */
    val pluginId: PluginData.Id,
)
