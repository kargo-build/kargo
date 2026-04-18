/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.problems.reporting

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
