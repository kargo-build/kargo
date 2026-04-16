/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins.alpha

import org.jetbrains.amper.plugins.*

@TaskAction
fun runCodestyle(moduleName: String) {
    println("Alpha codestyle run in $moduleName")
}
