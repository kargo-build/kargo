/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.logs

import org.slf4j.MDC
import org.tinylog.core.LogEntry
import org.tinylog.core.LogEntryValue
import org.tinylog.writers.AbstractFormatPatternWriter
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

/**
 * A tinylog writer that writes log entries to a file defined for each test by the [LogFileExtension].
 */
class TestDebugLogFileWriter(properties: MutableMap<String, String>) : AbstractFormatPatternWriter(properties) {

    override fun getRequiredLogEntryValues(): Collection<LogEntryValue> = LogEntryValue.entries.toList()

    override fun write(logEntry: LogEntry) {
        val file = MDC.get(LogFileExtension.MDC_TEST_LOG_FILE_KEY)?.let(::Path)
            ?: error(
                "No test log file defined for this test. The LogFileExtension is automatically registered, but it " +
                        "operates via the MDC. Make sure you properly propagate the MDC if you start root coroutines " +
                        "by adding MDCContext() to your coroutine context."
            )
        file.createParentDirectories()
        file.writeText(render(logEntry), options = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
    }

    // we open and close the file each time, no nothing to flush (it might be a different file each time)
    override fun flush() {}

    // we open and close the file each time, no nothing to close (it might be a different file each time)
    override fun close() {}
}
