/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.intellij

import com.intellij.diagnostic.ThreadDumper

object ThreadDumper {
    init {
        // Tells the IntelliJ Platform code not to use the APIs added to the IntelliJ coroutine fork on top of Kotlin
        // vanilla coroutines. We exclude the IntelliJ fork from dependencies in Amper, so only vanilla coroutines APIs are available.
        System.setProperty("ide.can.use.coroutines.fork", "false")
    }

    fun dumpThreadsToString(): String = ThreadDumper.dumpThreadsToString()
}
