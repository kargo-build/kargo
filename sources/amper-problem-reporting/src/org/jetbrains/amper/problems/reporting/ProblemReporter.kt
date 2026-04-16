/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.problems.reporting

import org.jetbrains.amper.stdlib.collections.forEachEndAware
import org.jetbrains.annotations.Nls

interface ProblemReporter {

    fun reportMessage(message: BuildProblem)
}

/**
 * A [ProblemReporter] that does nothing.
 */
object NoopProblemReporter : ProblemReporter {
    override fun reportMessage(message: BuildProblem) = Unit
}

/**
 * A [ProblemReporter] that collects problems so they can be queried later.
 *
 * Note: This class is not thread-safe. Problems collecting might misbehave when used from multiple threads
 * (e.g. in Gradle).
 */
class CollectingProblemReporter : ProblemReporter {
    private val myProblems = mutableListOf<BuildProblem>()
    val problems: List<BuildProblem> by ::myProblems

    override fun reportMessage(message: BuildProblem) {
        myProblems.add(message)
    }
}

/**
 * Report all collected problems from the current reporter to the given [other] reporter.
 */
fun CollectingProblemReporter.replayProblemsTo(other: ProblemReporter) =
    problems.forEach { other.reportMessage(it) }

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
        val start = source.range.start
        append(':').append(start.line).append(':').append(start.column)
    }
}