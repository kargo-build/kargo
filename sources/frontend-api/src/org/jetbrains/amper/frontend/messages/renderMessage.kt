/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.messages

import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.stdlib.collections.forEachEndAware
import org.jetbrains.annotations.Nls

@OptIn(NonIdealDiagnostic::class)
fun renderMessage(problem: BuildProblem): @Nls String = buildString {
    fun appendSource(source: BuildProblemSource) {
        when (source) {
            is FileBuildProblemSource -> {
                appendFileSource(source)
                append(": ").append(problem.message)
            }
            is MultipleLocationsBuildProblemSource -> {
                appendLine(problem.message)
                appendLine("╰─ ${source.groupingMessage}")
                appendMultipleSources(source.sources, indent = 3)
            }
            GlobalBuildProblemSource -> append(problem.message)
        }
    }

    appendSource(problem.source)
}

fun StringBuilder.appendMultipleSources(sources: List<FileBuildProblemSource>, indent: Int = 0) {
    sources.forEachEndAware { isLast, source ->
        repeat(indent) { append(' ') }
        if (isLast) {
            append("╰─ ")
        } else {
            append("├─ ")
        }
        appendFileSource(source)
        if (!isLast) appendLine()
    }
}

fun StringBuilder.appendFileSource(source: FileBuildProblemSource) {
    append(source.file.normalize())
    if (source is FileWithRangesBuildProblemSource) {
        val start = source.computeRange().start
        append(':').append(start.line).append(':').append(start.column)
    }
}
