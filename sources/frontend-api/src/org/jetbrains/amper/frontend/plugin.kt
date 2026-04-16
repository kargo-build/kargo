/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend

import org.jetbrains.amper.plugins.schema.model.PluginData

/**
 * A model of a **local** amper plugin, backed by a [pluginModule].
 */
interface AmperPlugin {
    /**
     * It's guaranteed to have [org.jetbrains.amper.frontend.schema.ProductType.JVM_AMPER_PLUGIN]
     */
    val pluginModule: AmperModule

    /**
     * Plugin ID.
     *
     * Guaranteed to be unique within the project.
     */
    val id: PluginData.Id
}
