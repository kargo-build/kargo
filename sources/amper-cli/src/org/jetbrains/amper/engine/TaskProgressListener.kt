/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.engine

interface TaskProgressListener {
    fun taskStarted(task: Task): TaskProgressCookie
    interface TaskProgressCookie: AutoCloseable

    object Noop: TaskProgressListener {
        override fun taskStarted(task: Task): TaskProgressCookie = object : TaskProgressCookie {
            override fun close() = Unit
        }
    }
}
