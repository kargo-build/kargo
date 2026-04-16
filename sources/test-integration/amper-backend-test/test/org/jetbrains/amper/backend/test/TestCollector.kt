/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.backend.test

import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.jetbrains.amper.test.runTestWithMdc
import org.jetbrains.amper.test.spans.SpansTestCollector
import org.slf4j.MDC
import java.util.*
import java.util.function.Consumer
import kotlin.time.Duration

class TestCollector(val backgroundScope: CoroutineScope) : SpansTestCollector {
    private val collectedSpans = mutableListOf<SpanData>()
    private fun addSpan(spanData: SpanData) = synchronized(collectedSpans) { collectedSpans.add(spanData) }
    override val spans: List<SpanData>
        get() = synchronized(collectedSpans) { collectedSpans.toList() }
    override fun clearSpans() = synchronized(collectedSpans) { collectedSpans.clear() }

    companion object {
        private const val MDC_KEY = "test-collector"
        fun runTestWithCollector(timeout: Duration = Duration.INFINITE, block: suspend TestCollector.() -> Unit) {
            val id = UUID.randomUUID().toString()

            MDC.putCloseable(MDC_KEY, id).use {
                // We need the MDCContext here, not only for the MDC_KEY right above, but also in general for JUnit
                // extensions relying on MDC propagation.
                runTestWithMdc(timeout = timeout) {
                    val testCollector = TestCollector(backgroundScope = backgroundScope)

                    withContext(Context.current().with(KEY, CurrentCollector(id)).asContextElement()) {
                        val listener = Consumer<SpanData> {
                            if (Context.current().get(KEY)?.id == id) {
                                testCollector.addSpan(it)
                            }
                        }

                        OpenTelemetryCollector.addListener(listener)
                        try {
                            block(testCollector)
                        } finally {
                            OpenTelemetryCollector.removeListener(listener)
                        }
                    }
                }
            }
        }

        private data class CurrentCollector(val id: String)
        private val KEY = ContextKey.named<CurrentCollector>("opentelemetry-test-collector")
    }
}
