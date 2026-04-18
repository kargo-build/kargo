/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.CliBundle
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.catalogs.ComposeMaterial3UnknownVersionMappingProblem
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.appendFileSource
import org.jetbrains.amper.frontend.messages.appendMultipleSources
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.frontend.messages.renderMessage
import org.jetbrains.amper.frontend.tree.BooleanNode
import org.jetbrains.amper.frontend.tree.ConflictingProperties
import org.jetbrains.amper.frontend.tree.EnumNode
import org.jetbrains.amper.frontend.tree.ErrorNode
import org.jetbrains.amper.frontend.tree.IntNode
import org.jetbrains.amper.frontend.tree.ListNode
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.NullLiteralNode
import org.jetbrains.amper.frontend.tree.PathNode
import org.jetbrains.amper.frontend.tree.ReferenceNode
import org.jetbrains.amper.frontend.tree.StringInterpolationNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.tree.enumConstantIfAvailable
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.stdlib.collections.forEachEndAware
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

internal object CliProblemReporter : ProblemReporter {
    private val logger = LoggerFactory.getLogger("build")
    private val problemsWereReported = AtomicBoolean(false)

    fun wereProblemsReported() = problemsWereReported.get()

    override fun reportMessage(message: BuildProblem) {
        val renderedMessage = when (message) {
            is ConflictingProperties -> reportConflictingProperties(message)
            is ComposeMaterial3UnknownVersionMappingProblem -> reportComposeMaterial3UnknownVersionMapping(message)
            else -> renderMessage(message)
        }

        when (message.level) {
            Level.Warning -> logger.warn(renderedMessage)
            Level.Error -> {
                logger.error(renderedMessage)
                problemsWereReported.set(true)
            }
            Level.WeakWarning -> logger.info(renderedMessage)
        }
    }

    private fun reportComposeMaterial3UnknownVersionMapping(problem: ComposeMaterial3UnknownVersionMappingProblem) =
        buildString {
            appendLine(problem.message)
            append(CliBundle.message("compose.material3.unknown.mapping.compose.version", problem.composeVersion))
            appendFileSource(PsiBuildProblemSource(problem.composeVersionTrace.extractPsiElement()))
        }

    private fun reportConflictingProperties(message: ConflictingProperties): String = buildString {
        appendLine(CliBundle.message(
            "conflicting.properties.header",
            message.keyValues.first().key,
            // TODO: Represent contexts in more user-friendly way than just toString?
            message.contexts
        ))

        message.keyValues.groupBy { it.value.renderValue() }
            .entries
            .forEachEndAware { isLast, entry ->
                val (renderedValue, keyValues) = entry
                appendLine(CliBundle.message("conflicting.properties.line", renderedValue))
                appendMultipleSources(keyValues.mapNotNull { it.value.trace.asBuildProblemSource() as? FileBuildProblemSource }, indent = 4)
                if (!isLast) appendLine()
            }
    }

    private fun TreeNode.renderValue(): String = when (this) {
        is ErrorNode -> "error"
        is NullLiteralNode -> "null"
        is ReferenceNode -> referencedPath.renderReference()
        is BooleanNode -> value.toString()
        is EnumNode -> enumConstantIfAvailable?.toString() ?: entryName
        is IntNode -> value.toString()
        is PathNode -> value.toString()
        is StringNode -> value
        is StringInterpolationNode -> parts.joinToString("") {
            when (it) {
                is StringInterpolationNode.Part.Text -> it.text
                is StringInterpolationNode.Part.Reference -> it.referencePath.renderReference()
            }
        }
        is MappingNode -> CliBundle.message("conflicting.properties.mapping.render")
        is ListNode -> CliBundle.message("conflicting.properties.list.render")
    }

    private fun List<String>.renderReference(): String = joinToString(separator = ".", prefix = $$"${", postfix = "}")
}
