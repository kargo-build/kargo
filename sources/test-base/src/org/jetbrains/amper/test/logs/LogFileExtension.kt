/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.test.logs

import org.junit.jupiter.api.MediaType
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import org.slf4j.MDC
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendText
import kotlin.io.path.copyTo
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists

/**
 * An extension that makes SLF4J loggers write logs to a per-test file, and attaches this file to the test as a JUnit
 * artifact. It is automatically registered for all tests.
 *
 * Important: when using coroutines, make sure your root coroutine builder forwards the MDC by passing a `MDCContext()`
 * element to your coroutine context (from `kotlinx-coroutines-slf4j`). For example:
 *
 * ```kotlin
 * @Test
 * fun test() = runBlocking(MDCContext()) {
 *     // ...
 * }
 *
 * @Test
 * fun test() = runTest(context = MDCContext()) {
 *     // ...
 * }
 * ```
 */
class LogFileExtension : BeforeEachCallback, TestWatcher, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        val className = context.requiredTestClass.let { it.canonicalName ?: it.simpleName }
        val testName = context.requiredTestMethod.name

        val logFile = createTempFile(prefix = "$className-$testName".sanitizeForFilename(), suffix = ".log")
        context.store.put(LOG_FILE_KEY, logFile)

        // We set it in the MDC so the loggers can find the file for the current test
        MDC.put(MDC_TEST_LOG_FILE_KEY, logFile.absolutePathString())
    }

    private fun String.sanitizeForFilename(): String = replace(Regex("[^a-zA-Z0-9._-]"), "_")

    override fun testAborted(context: ExtensionContext, cause: Throwable) {
        val logFile = context.currentTestLogFile()
        logFile.appendText("\n-------------\nTEST ABORTED:\n${cause.stackTraceToString()}")
        context.publishAndDeleteTestLogFile()
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable) {
        val logFile = context.currentTestLogFile()
        logFile.appendText("\n-------------\nTEST FAILED:\n${cause.stackTraceToString()}")
        context.publishAndDeleteTestLogFile()
    }

    override fun testSuccessful(context: ExtensionContext) {
        context.publishAndDeleteTestLogFile()
    }

    private fun ExtensionContext.publishAndDeleteTestLogFile() {
        val logFile = currentTestLogFile()
        if (logFile.exists()) {
            publishFile("test-debug.log", MediaType.TEXT_PLAIN_UTF_8) { path ->
                logFile.copyTo(path.createParentDirectories())
            }
            logFile.deleteExisting()
        }
    }

    override fun afterEach(context: ExtensionContext) {
        MDC.remove(MDC_TEST_LOG_FILE_KEY)
        // we can't delete the file here because TestWatcher callbacks are called after afterEach
    }

    private fun ExtensionContext.currentTestLogFile(): Path = store.get<Path>(LOG_FILE_KEY)

    companion object {

        const val MDC_TEST_LOG_FILE_KEY = "testLogFile"

        private const val LOG_FILE_KEY = "logFile"

        private val namespace: ExtensionContext.Namespace =
            ExtensionContext.Namespace.create(LogFileExtension::class.java)

        private val ExtensionContext.store: ExtensionContext.Store
            get() = getStore(namespace)

        private inline fun <reified T> ExtensionContext.Store.get(key: String): T = get(key, T::class.java)
    }
}
