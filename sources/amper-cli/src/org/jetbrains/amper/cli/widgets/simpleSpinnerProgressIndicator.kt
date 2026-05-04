/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets

import com.github.ajalt.mordant.animation.coroutines.animateInCoroutine
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.progress.progressBarLayout
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Displays a simple infinitely spinning widget with a static message in the terminal.
 *
 * To stop the spinner, cancel the [Job] returned by this function.
 */
context(scope: CoroutineScope)
fun simpleSpinnerProgressIndicator(
    terminal: Terminal,
    message: String,
): Job {
    val platformProgress = PlatformProgressReporter(terminal)

    val animator = progressBarLayout(align = TextAlign.LEFT) {
        spinner(Spinner.Dots(terminal.theme.success))
        text(terminal.theme.muted(message))
    }.animateInCoroutine(terminal)

    return scope.launch(Dispatchers.IO) {
        try {
            platformProgress.update(PlatformProgressReporter.Progress.Indeterminate)
            animator.execute()
        } finally {
            platformProgress.update(PlatformProgressReporter.Progress.Hidden)
            animator.clear()
        }
    }
}
