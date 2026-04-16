/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins.cascading

import org.jetbrains.amper.plugins.*
import java.nio.file.Path

@Configurable
interface NestedObj {
    val path: Path
    val name: String
}

@TaskAction
fun taskAction1(
    @Output nestedObj: NestedObj,
) {
    println("taskAction1: path=${nestedObj.path}, name=${nestedObj.name}")
}

@TaskAction
fun taskAction2(
    @Input nestedObj: NestedObj,
) {
    println("taskAction2: path=${nestedObj.path}, name=${nestedObj.name}")
}
