/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

/**
 * Exception that can be directly reported to the user without a stacktrace
 */
class UserReadableError(
    override val message: String,
    val exitCode: Int,
    cause: Throwable? = null,
): RuntimeException(message, cause)

/**
 * Prints the given error [message] to the user and immediately exits with the specified [exitCode].
 *
 * Markdown is not supported in the message (it messes up the red formatting and might not be possible if the state of
 * the program is critically broken).
 *
 * The [cause] parameter can be used if the user error was caused by an exception, and the stacktrace is useful
 * complementary information (either for the user or for the Amper team).
 * The cause's stacktrace will be logged to a file without polluting the console output.
 *
 * **Important:** not all user errors caused by an exception should pass it as [cause]. If the user message
 * completely describes the error, then the cause should be omitted to avoid unnecessary noise in the logs.
 * The typical case where the cause should be added is when there is some sort of catch-all block for a high-level
 * operation that can fail in multiple ways; the cause then provides additional context to investigate what failed more
 * precisely.
 */
fun userReadableError(
    message: String,
    cause: Throwable? = null,
    exitCode: Int = 1,
): Nothing {
    throw UserReadableError(message, exitCode, cause)
}
