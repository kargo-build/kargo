/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds
import platform.posix.exit

@Composable
fun App() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("Hello, World!")
        }
    }
    // This is definitely not a good practice for normal apps, it is only here so tests don't run indefinitely
    LaunchedEffect(Unit) {
        delay(1.seconds)
        exit(0)
    }
}

fun ViewController() = ComposeUIViewController { App() }