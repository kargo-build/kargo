/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins.checker

import org.jetbrains.amper.plugins.*

@TaskAction
fun runCheckA(moduleName: String) {
    println("Check A run in $moduleName")
}

@TaskAction
fun runCheckB(moduleName: String) {
    println("Check B run in $moduleName")
}
