/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs a root coroutine for tests like [runTest][kotlinx.coroutines.test.runTest] but also captures the current MDC
 * context. This is necessary for extensions that rely on MDC propagation.
 */
fun runTestWithMdc(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = 1.minutes,
    testBody: suspend TestScope.() -> Unit
) =
    @Suppress("SSBasedInspection")
    runTest(context = MDCContext() + context, timeout = timeout) {
        testBody()
    }

/**
 * Run given [testBody] respecting delays.
 * Overrides default behavior of [kotlinx.coroutines.test.runTest] that skips delays by default.
 */
fun runTestRespectingDelays(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = 1.minutes,
    testBody: suspend TestScope.() -> Unit
) = runTestWithMdc(context = context, timeout = timeout) {
    // wrap testBody into Default dispatcher, delays are respected this way
    withContext(Dispatchers.Default) {
        testBody()
    }
}
