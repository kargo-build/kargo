/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package com.example

import org.jetbrains.amper.plugins.*

@Configurable interface Nested {
    val x: String?
    val sub: Nested?
}

@Configurable interface Settings {
    val nullableNested: Nested?
}

@TaskAction
fun loopAction(
    p1: String?,
    p2: String?,
    p3: String?,
    p4: Nested?,
    p5: Nested?,
    p6: Nested?,
) {}
