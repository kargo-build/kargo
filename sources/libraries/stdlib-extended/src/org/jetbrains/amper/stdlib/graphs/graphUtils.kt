/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.graphs

/**
 * Generates a sequence of all the elements in graph `(V; E)` using depth-first search.
 *
 * `V` are all the nodes reachable from [roots], while `E` is expressed by [adjacent].
 * The implementation ignores loops, as it visits each node only once.
 *
 * @return a sequence of all visited nodes in the DFS order.
 * @see depthFirstDetectLoops
 */
fun <T> depthFirstNodeSequence(
    roots: Iterable<T>,
    adjacent: (T) -> Iterable<T>,
): Sequence<T> = sequence {
    val seen = mutableSetOf<T>()
    val queue = ArrayDeque<T>()
    queue.addAll(roots)

    while (queue.isNotEmpty()) {
        val element = queue.removeLast()
        if (!seen.add(element)) {
            continue
        }
        yield(element)
        queue.addAll(adjacent(element))
    }
}

/**
 * Detects loops in a directed graph `(V; E)`,
 * where `V` are all the nodes reachable from [roots], and `E` is expressed by [adjacent].
 *
 * @param roots graph nodes from which all the graph is reachable.
 * @param adjacent the function that defines the nodes that are adjacent to the given node (linked by edges)
 * @return a list of node lists, each representing a loop in the graph.
 *   NOTE: The first and the last nodes of the loop list are not the same.
 */
fun <T : Any> depthFirstDetectLoops(
    roots: Iterable<T>,
    adjacent: (T) -> Iterable<T>,
): List<List<T>> {
    val loops = mutableListOf<List<T>>()

    val markedGray = hashSetOf<T>()
    val markedBlack = hashSetOf<T>()
    val stack = arrayListOf<MutableList<T>>()

    stack += roots.toMutableList()

    while (stack.isNotEmpty()) {
        // Substack is introduced to preserve node hierarchy
        val subStack = stack.last()
        if (subStack.isEmpty()) {
            stack.removeAt(stack.lastIndex)
            continue
        }

        when (val node = subStack.last()) {
            in markedBlack -> {
                subStack.removeAt(subStack.lastIndex)
            }

            in markedGray -> {
                subStack.removeLast()
                markedBlack += node
                markedGray -= node
            }

            else -> {
                markedGray += node
                stack += adjacent(node).mapNotNullTo(arrayListOf()) { child ->
                    if (child in markedGray) {
                        // Loop: report and skip
                        loops += stack.map { it.last() }.dropWhile { it != child }
                        null
                    } else child
                }
            }
        }
    }
    return loops
}
