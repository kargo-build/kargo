/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins.beta

import org.jetbrains.amper.plugins.*

@TaskAction
fun runCodestyle(moduleName: String) {
    println("Beta codestyle run in $moduleName")
}
