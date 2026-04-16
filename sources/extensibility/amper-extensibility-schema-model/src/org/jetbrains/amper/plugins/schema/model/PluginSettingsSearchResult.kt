/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins.schema.model

import kotlinx.serialization.Serializable

/**
 * A result of searching for a `pluginSettings` class in the Kotlin code.
 */
@Serializable
sealed interface PluginSettingsSearchResult {
    /**
     * `@Configurable` settings interface was found.
     */
    @Serializable
    data class Success(
        val name: PluginData.SchemaName,
    ) : PluginSettingsSearchResult

    /**
     * No such Kotlin type was found at all.
     */
    @Serializable
    data object NotFound : PluginSettingsSearchResult

    /**
     * Some matching Kotlin type was found, but it is either not an interface or not annotated with `@Configurable`.
     */
    @Serializable
    data object Invalid : PluginSettingsSearchResult
}