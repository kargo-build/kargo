/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.types

import java.nio.file.Path

/**
 * [org.jetbrains.amper.frontend.api.Trace] analog for the schema type-system.
 */
sealed interface SchemaOrigin {
    /**
     * Entity comes from the local plugin sources.
     */
    data class LocalPlugin(
        /**
         * The folder the plugin originates from.
         */
        val pluginFolder: Path,
        /**
         * Possible location inside the plugin sources that points to the exact place where the schema part is defined.
         */
        val sourceCodeLocation: SourceCodeLocation?,
    ) : SchemaOrigin {
        data class SourceCodeLocation(
            val path: Path,
            val textRange: IntRange?,
        )
    }

    /**
     * Entity comes from the maven plugin.
     */
    data object MavenPlugin : SchemaOrigin
    
    /**
     * Entity comes from the builtin schema.
     */
    data object Builtin : SchemaOrigin
}