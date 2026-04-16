/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.contexts

import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.TraceablePath
import org.jetbrains.amper.frontend.contexts.ContextsInheritance.Result.INDETERMINATE
import org.jetbrains.amper.frontend.contexts.ContextsInheritance.Result.IS_LESS_SPECIFIC
import org.jetbrains.amper.frontend.contexts.ContextsInheritance.Result.IS_MORE_SPECIFIC
import org.jetbrains.amper.frontend.contexts.ContextsInheritance.Result.SAME
import java.nio.file.Path
import java.util.*
import kotlin.io.path.pathString

class PathCtx(val path: Path, override val trace: Trace? = null) : Context {
    override fun withoutTrace() = PathCtx(path)
    override fun toString() = path.pathString
}

/**
 * Compares path contexts based on where they belong in the template application graph.
 *
 * - The root file is always more specific than any template.
 * - Templates that apply other templates are more specific than the templates they apply (including transitively).
 *   I.e., there is a path through the template application graph.
 * - Non-connected templates are [INDETERMINATE].
 *
 * @param templateGraph the template application graph: maps each path to the set of template paths it applies directly.
 */
class PathInheritance(
    templateGraph: Map<Path, Set<TraceablePath>>,
    private val rootPath: Path,
) : ContextsInheritance<PathCtx> {
    private val templateReachabilityGraph: Map<Path, Set<Path>>
    private val templatePaths = templateGraph.keys + templateGraph.values.flatten().map(TraceablePath::value).toSet()

    init {
        val reachabilityMap = mutableMapOf<Path, Set<Path>>()
        // Naive BFS for every node.
        // We could do DFS with memoization if we were guaranteed that the template graph is acyclic but alas.
        for (path in templatePaths) {
            reachabilityMap[path] = buildSet {
                val queue = ArrayDeque(templateGraph[path].orEmpty())
                while (queue.isNotEmpty()) {
                    val next = queue.removeFirst()
                    if (next.value !in this) {
                        add(next.value)
                        queue.addAll(templateGraph[next.value].orEmpty())
                    }
                }
            }
        }

        templateReachabilityGraph = reachabilityMap
    }

    private fun isReachable(from: Path, to: Path): Boolean = templateReachabilityGraph[from]?.contains(to) == true

    override fun Collection<PathCtx>.compareContexts(other: Collection<PathCtx>): ContextsInheritance.Result {
        // Values in trees shouldn't have more than a single path context.
        val thisPath = singleOrNull()?.path
        val otherPath = other.singleOrNull()?.path

        return when {
            thisPath == otherPath -> SAME
            // We treat absence of path ctx as the most generic ctx.
            thisPath == null -> IS_LESS_SPECIFIC
            otherPath == null -> IS_MORE_SPECIFIC
            // Root is more specific than any template.
            rootPath == thisPath && otherPath in templatePaths -> IS_MORE_SPECIFIC
            rootPath == otherPath && thisPath in templatePaths -> IS_LESS_SPECIFIC
            // Use the template application graph to determine ordering.
            else -> {
                val thisReachesOther = isReachable(from = thisPath, to = otherPath)
                val otherReachesThis = isReachable(from = otherPath, to = thisPath)
                when {
                    thisReachesOther && !otherReachesThis -> IS_MORE_SPECIFIC
                    otherReachesThis && !thisReachesOther -> IS_LESS_SPECIFIC
                    // If paths are different but reachable from each other (thisReachesOther == otherReachesThis),
                    // they indicate a loop—no precedence can be decided on them.
                    // If paths are not reachable, they are obviously unordered.
                    else -> INDETERMINATE
                }
            }
        }
    }
}