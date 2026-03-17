/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.isDefault
import org.jetbrains.amper.frontend.api.withPrecedingValue
import org.jetbrains.amper.frontend.contexts.Context
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.contexts.ContextsInheritance
import org.jetbrains.amper.frontend.contexts.WithContexts
import org.jetbrains.amper.frontend.contexts.asCompareResult
import org.jetbrains.amper.frontend.contexts.defaultContextsInheritance
import org.jetbrains.amper.frontend.contexts.sameOrMoreSpecific
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.TestOnly

/**
 * This is a class responsible for refining [TreeNode] values for a specified [Contexts].
 * Consider the following example:
 * ```yaml
 * # tree 1
 * foo:
 *   bar: myValue
 *   baz: myValue
 *
 * # tree 2
 * foo@jvm:
 *   bar@jvm: myValueJvm
 *
 * # refined tree for contexts `[jvm]`:
 * foo@jvm:
 *   bar@jvm: myValueJvm
 *   baz: myValue
 * ```
 */
class TreeRefiner(
    private val contextComparator: ContextsInheritance<Context> = defaultContextsInheritance,
) {
    context(_: ProblemReporter)
    fun refineTree(
        tree: MappingNode,
        selectedContexts: Contexts,
        resolveReferences: Boolean = true,
        withDefaults: Boolean = true,
    ): RefinedMappingNode = RefineRequest(
        selectedContexts = selectedContexts,
        withDefaults = withDefaults,
        resolveReferences = resolveReferences,
        contextComparator = contextComparator,
    ).refineMapping(tree)
}

@TestOnly
context(_: ProblemReporter)
internal fun MappingNode.refineTree(
    selectedContexts: Contexts,
    contextComparator: ContextsInheritance<Context>,
    withDefaults: Boolean = true,
    resolveReferences: Boolean = true,
): RefinedMappingNode = RefineRequest(
    selectedContexts = selectedContexts,
    withDefaults = withDefaults,
    resolveReferences = resolveReferences,
    contextComparator = contextComparator,
).refineMapping(this)

private class RefineRequest(
    private val selectedContexts: Contexts,
    private val withDefaults: Boolean,
    private val resolveReferences: Boolean,
    contextComparator: ContextsInheritance<Context>,
) : ContextsInheritance<Context> by contextComparator {

    /**
     * Creates a copy out of the subtree of the initial tree, selected by [selectedContexts]
     * with merged nodes.
     */
    context(_: ProblemReporter)
    fun refineMapping(node: MappingNode): RefinedMappingNode {
        var refined = refine(node) as RefinedMappingNode
        if (resolveReferences) {
            // TODO: Incorporate reference resolution routine tightly into refining to enable merging resolved values.
            refined = refined.resolveReferences()
        }
        return refined
    }

    private fun refine(node: TreeNode): RefinedTreeNode {
        return when (node) {
            is RefinedTreeNode -> node
            is ListNode -> RefinedListNode(
                children = node.children.filterByContexts().map { refine(it) },
                type = node.type,
                trace = node.trace,
                contexts = node.contexts,
            )
            is MappingNode -> refinedMappingNodeWithDefaults(
                node.children.refineProperties(),
                type = node.type,
                trace = node.trace,
                contexts = node.contexts,
            )
        }
    }

    /**
     * Merge named properties, comparing them by their contexts and by their name.
     */
    private fun List<KeyValue>.refineProperties(): Map<String, RefinedKeyValue> =
        filterByContexts().run {
            // Do actual overriding for key-value pairs.
            val refinedProperties = refineOrReduceByKeys { props ->
                val sorted = props.sortedWith(::compareAndReport)

                val mostSpecificKeyValue = sorted.last()
                val newTrace = reduceTrace(sorted)
                // We consider the most specific key value as the source of truth for the type of the node and contexts.
                val refinedValue = when (val value = mostSpecificKeyValue.value) {
                    is ErrorNode -> {
                        // We try recovering as much information for the invalid but "best-effort" tree by finding
                        // last non-error node.
                        val lastNonErrorNode = sorted.lastOrNull { it.value !is ErrorNode }
                        lastNonErrorNode?.let { refine(it.value) } ?: ErrorNode(newTrace)
                    }
                    is LeafTreeNode -> value.copyWithTrace(newTrace)
                    is ListNode -> {
                        val children = sorted.flatMap { (it.value as? ListNode)?.children.orEmpty() }
                        RefinedListNode(
                            children = children.filterByContexts().map { refine(it) },
                            type = value.type,
                            trace = newTrace,
                            contexts = value.contexts,
                        )
                    }
                    is MappingNode -> {
                        val children = sorted.flatMap { (it.value as? MappingNode)?.children.orEmpty() }
                        refinedMappingNodeWithDefaults(
                            refinedChildren = children.refineProperties(),
                            type = value.type,
                            trace = newTrace,
                            contexts = value.contexts,
                        )
                    }
                }
                mostSpecificKeyValue.copyWithValue(refinedValue)
            }

            // Restore order. Also, ignore NoValues if anything is overwriting them.
            val unordered = refinedProperties.associateBy { it.key }
            return mapTo(mutableSetOf()) { it.key }.associateWith { unordered[it]!! }
        }

    /**
     * Reduces the traces of sorted list of key-value pairs by merging them into a single trace
     * with the chain of preceding values.
     */
    private fun reduceTrace(sorted: List<KeyValue>): Trace {
        var trace = sorted[0].value.trace
        for (i in 1 until sorted.size) {
            val newTrace = sorted[i].value.trace
            // Defaults with higher priority just replace each other without saving preceding value
            trace = if (newTrace.isDefault) newTrace else newTrace.withPrecedingValue(sorted[i - 1].value)
        }
        return trace
    }

    private fun refinedMappingNodeWithDefaults(
        refinedChildren: Map<String, RefinedKeyValue>,
        type: SchemaType.MapLikeType,
        trace: Trace,
        contexts: Contexts,
    ): RefinedMappingNode {
        val refinedChildrenWithDefaults = if (withDefaults && type is SchemaType.ObjectType) {
            val defaults = buildMap {
                for (property in type.declaration.properties) {
                    val existingValue = refinedChildren[property.name]
                    if (existingValue == null || existingValue.value is ErrorNode) {
                        type.declaration.getDefaultFor(property)?.let { default ->
                            put(property.name, default)
                        }
                    }
                }
            }
            if (defaults.isEmpty()) refinedChildren else refinedChildren + defaults
        } else refinedChildren

        // TODO: We could report missing properties here and pull this logic from the `schemaInstantiator.kt`.
        return RefinedMappingNode(
            refinedChildren = refinedChildrenWithDefaults,
            type = type,
            trace = trace,
            contexts = contexts,
        )
    }

    /**
     * Compares two nodes by their contexts.
     * If they are not comparable ([compareContexts] had returned null), then the problem is reported.
     * Node is treated as "greater than" another node if its contexts can be inherited from other node contexts.
     */
    fun compareAndReport(first: KeyValue, second: KeyValue): Int =
        (first.value.contexts.compareContexts(second.value.contexts)).asCompareResult ?: run {
            // TODO AMPER-4516 Report unable to sort. Maybe even same contexts? See [asCompareResult].
            0
        }

    /**
     * Refines the element if it is single or applies [reduce] to a collection of properties grouped by keys.
     */
    private fun List<KeyValue>.refineOrReduceByKeys(reduce: (List<KeyValue>) -> RefinedKeyValue) =
        groupBy { it.key }.values.map { props ->
            props.singleOrNull()?.let { it.copyWithValue(refine(it.value)) }
                ?: props.filterByContexts().let(reduce)
        }

    private fun <T : WithContexts> List<T>.filterByContexts() =
        filter { selectedContexts.compareContexts(it.contexts).sameOrMoreSpecific }
}