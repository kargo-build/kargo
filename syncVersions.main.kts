/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalPathApi::class)

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolute
import kotlin.io.path.pathString

/*
This script is meant to be used to update several user-facing versions in:
- the Amper project itself
- our examples
- our docs

The source of truth is the list of versions at the top of this file.
 */

val bootstrapAmperVersion = "0.11.0-dev-3836" // AUTO-UPDATED BY THE CI - DO NOT RENAME

private val amperRootDir: Path = __FILE__.toPath().absolute().parent

@Suppress("PROCESS_BUILDER_START_LEAK")
fun runAmperCli(vararg args: String): Int {
    val isWindows = System.getProperty("os.name").startsWith("Win", ignoreCase = true)
    val amperScript = amperRootDir.resolve(if (isWindows) "amper.bat" else "amper")
    return ProcessBuilder(amperScript.pathString, *args).inheritIO().start().waitFor()
}

val exitCode = runAmperCli("do", "syncVersions")

check(exitCode == 0) {
    "syncVersions failed: $exitCode. Check the output above"
}