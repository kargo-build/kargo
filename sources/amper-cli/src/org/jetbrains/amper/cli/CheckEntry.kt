/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.frontend.plugins.CheckFromPlugin

sealed interface CheckEntry : QualifiedEntity {
    /** Builtin 'tests' check */
    data object Tests : CheckEntry {
        override val name = QualifiedName("tests", null)
    }

    /** Custom check from a plugin */
    data class Custom(val custom: CheckFromPlugin) : CheckEntry {
        override val name = QualifiedName(custom.name, custom.pluginId.value)
    }
}