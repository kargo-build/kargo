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
import org.jetbrains.amper.frontend.tree.resolution.resolveReferences
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
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
        withDefaults: Boolean = true,
    ): RefinedMappingNode = RefineRequest(
        selectedContexts = selectedContexts,
        withDefaults = withDefaults,
        contextComparator = contextComparator,
    ).refineMapping(tree)
}

@TestOnly
context(_: ProblemReporter)
internal fun MappingNode.refineTree(
    selectedContexts: Contexts,
    contextComparator: ContextsInheritance<Context>,
    withDefaults: Boolean = true,
): RefinedMappingNode = RefineRequest(
    selectedContexts = selectedContexts,
    withDefaults = withDefaults,
    contextComparator = contextComparator,
).refineMapping(this)

private class RefineRequest(
    private val selectedContexts: Contexts,
    private val withDefaults: Boolean,
    contextComparator: ContextsInheritance<Context>,
) : ContextsInheritance<Context> by contextComparator {

    /**
     * Creates a copy out of the subtree of the initial tree, selected by [selectedContexts]
     * with merged nodes.
     */
    context(_: ProblemReporter)
    fun refineMapping(node: MappingNode): RefinedMappingNode {
        val declaration = checkNotNull(node.declaration) { "Refining should start from the object node" }
        // Question: Incorporate reference resolution routine tightly into refining to enable merging resolved values?
        return refine(node, ExpectedType { declaration.toType() }).resolveReferences() as RefinedMappingNode
    }

    context(_: ProblemReporter)
    private fun refine(node: TreeNode, expectedType: ExpectedType): RefinedTreeNode {
        return when (node) {
            is RefinedTreeNode -> node
            is ListNode -> RefinedListNode(
                children = node.children.filterByContexts().map {
                    refine(it, ExpectedType { (expectedType.type as SchemaType.ListType).elementType })
                },
                trace = node.trace,
                contexts = node.contexts,
            )
            is MappingNode -> refinedMappingNodeWithDefaults(
                node.children.refineProperties(
                    ownerType = recoverOwnerTypeOf(node = node, expectedType = expectedType)
                ),
                declaration = node.declaration,
                trace = node.trace,
                contexts = node.contexts,
            )
        }
    }

    /**
     * Merge named properties, comparing them by their contexts and by their name.
     * @param ownerType the tracked/recovered type of the node these properties belong to
     */
    context(_: ProblemReporter)
    private fun List<KeyValue>.refineProperties(ownerType: OwnerType): Map<String, RefinedKeyValue> =
        filterByContexts().run {
            // Do actual overriding for key-value pairs.
            val refinedProperties = refineOrReduceByKeys(ownerType) { props ->
                val groupedKeyValues = groupMostSpecificKeyValues(props)
                check(groupedKeyValues.isNotEmpty()) { "Key values grouped by value shouldn't be empty" }

                // TODO: This is incorrect as we should build graph of props and traverse them in
                //  the topological order.
                val partiallySortedProps = props.sortedWith { first, second ->
                    first.contexts.compareContexts(second.contexts).asCompareResult ?: 0
                }

                // TODO: This is completely incorrect as we should build graph of traces instead of a chain.
                val newTrace = reduceTrace(partiallySortedProps)

                if (groupedKeyValues.size > 1) {
                    // We have conflicts, let's report an error node
                    val anyConflictingValueList = groupedKeyValues.values.first()
                    val anyKeyValue = anyConflictingValueList.first()
                    return@refineOrReduceByKeys anyKeyValue.copyWithValue(
                        // Here we finally use the `expectedType` we've been tracking.
                        // In other "good" paths it doesn't play any role.
                        ErrorNode(
                            expectedType = recoverExpectedTypeOf(anyKeyValue, ownerType).type,
                            trace = newTrace,
                            contexts = anyKeyValue.contexts,
                        )
                    )
                }

                val actualKeyValues = groupedKeyValues.values.single()
                val valueToUse = actualKeyValues.first()
                // We consider the most specific key value as the source of truth for the type of the node and contexts.
                val refinedValue = when (val value = valueToUse.value) {
                    is LeafTreeNode -> value.copyWithTrace(newTrace)
                    is ListNode -> {
                        val expectedType = recoverExpectedTypeOf(valueToUse, ownerType)
                        val children = partiallySortedProps.flatMap { (it.value as? ListNode)?.children.orEmpty() }
                        RefinedListNode(
                            children = children.filterByContexts().map { refine(it, expectedType = expectedType) },
                            trace = newTrace,
                            contexts = value.contexts,
                        )
                    }
                    is MappingNode -> {
                        val children = partiallySortedProps.flatMap { (it.value as? MappingNode)?.children.orEmpty() }
                        refinedMappingNodeWithDefaults(
                            refinedChildren = children.refineProperties(
                                ownerType = recoverOwnerTypeOf(
                                    node = value,
                                    expectedType = recoverExpectedTypeOf(valueToUse, ownerType),
                                )
                            ),
                            declaration = value.declaration,
                            trace = newTrace,
                            contexts = value.contexts,
                        )
                    }
                }
                valueToUse.copyWithValue(refinedValue)
            }

            // Restore order of key values to the one in the original mapping
            val unordered = refinedProperties.associateBy { it.key }
            return mapTo(mutableSetOf()) { it.key }.associateWith { unordered[it]!! }
        }

    /**
     * Groups most specific [keyValues] by unique values.
     *
     * The returned map contains node values as keys (where containers don't have separation between contents and
     * are identical by type) and lists of incomparable key values as values of the map.
     *
     * NB: the presence of multiple incomparable key values in the list doesn't mean that there is a conflict,
     * because they are all providing the same value. However, the presence of multiple keys in the map definitely
     * means that we don't know what the final value should be.
     */
    context(problemReporter: ProblemReporter)
    private fun groupMostSpecificKeyValues(keyValues: List<KeyValue>): Map<Any?, List<KeyValue>> {
        val indeterminateValues = buildList<KeyValue> {
            for (keyValue in keyValues) {
                // For each of the key values in the list of indeterminate ones, we want to be sure in the final list
                // there are no values that are more specific than this one. Thus, on each step we delete less specific
                // values from the list and then add an item if no values are more specific as the current.
                // This way we end up with the list of values that are either indeterminate or same with any other
                // value in the list.
                removeAll { it.contexts.compareContexts(keyValue.value.contexts) == ContextsInheritance.Result.IS_LESS_SPECIFIC }
                if (none { it.contexts.compareContexts(keyValue.value.contexts) == ContextsInheritance.Result.IS_MORE_SPECIFIC }) add(keyValue)
            }
        }.distinct() // A key value might have come from the same tree read multiple times; we deduplicate them here.

        val keyValuesGroupedByNodeValue = indeterminateValues.groupBy { keyValue ->
            when (val value = keyValue.value) {
                is MappingNode, is ListNode, is ErrorNode -> CanMergeWithoutConflicts
                is ReferenceNode -> value.referencedPath
                is StringInterpolationNode -> value.parts
                is NullLiteralNode -> null
                is BooleanNode -> value.value
                is EnumNode -> value.entryName
                is IntNode -> value.value
                is PathNode -> value.value
                is StringNode -> value.value
            }
        }

        if (keyValuesGroupedByNodeValue.size > 1) {
            problemReporter.reportMessage(
                ConflictingProperties(selectedContexts, indeterminateValues)
            )
        }

        return keyValuesGroupedByNodeValue
    }

    /**
     * Helper object to represent the equality for containers when detecting conflicts as we can meaningfully merge
     * mappings, lists, and errors.
     */
    private object CanMergeWithoutConflicts

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

    context(_: ProblemReporter)
    private fun refinedMappingNodeWithDefaults(
        refinedChildren: Map<String, RefinedKeyValue>,
        declaration: SchemaObjectDeclaration?,
        trace: Trace,
        contexts: Contexts,
    ): RefinedMappingNode {
        val refinedChildrenWithDefaults = if (withDefaults && declaration != null) {
            val defaults = buildMap {
                for (property in declaration.properties) {
                    val existingValue = refinedChildren[property.name]
                    if (existingValue == null || existingValue.value is ErrorNode) {
                        declaration.getDefaultFor(property)?.let { default ->
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
            declaration = declaration,
            trace = trace,
            contexts = contexts,
        )
    }

    /**
     * Refines the element if it is single or applies [reduce] to a collection of properties grouped by keys.
     */
    context(_: ProblemReporter)
    private fun List<KeyValue>.refineOrReduceByKeys(
        ownerType: OwnerType,
        reduce: (List<KeyValue>) -> RefinedKeyValue,
    ) = groupBy { it.key }.values.map { props ->
        props.singleOrNull()?.let {
            it.copyWithValue(refine(it.value, recoverExpectedTypeOf(it, ownerType)))
        } ?: props.filterByContexts().let(reduce)
    }

    private fun <T : WithContexts> List<T>.filterByContexts() =
        filter { selectedContexts.compareContexts(it.contexts).sameOrMoreSpecific }

    /*
     WARNING: Because of maven's "RAW" trees, where type contracts MAY BE violated,
     we make this whole `ExpectedType`/`OwnerType` system lazy to avoid crashes.
     In maven trees these types are never going to be queried, because they are "valid" by definition.
     */

    /**
     * Schema-level type of node that is expected in some place (hole)
     * @see recoverExpectedTypeOf
     */
    @JvmInline
    private value class ExpectedType(private val _type: () -> SchemaType) {
        val type get() = _type()
    }

    /**
     * Factual type of [MappingNode].
     * Is different from the [ExpectedType] because, e.g., expected type can be abstract (nullable, variant, etc.).
     * @see recoverOwnerTypeOf
     */
    @JvmInline
    private value class OwnerType(private val _type: () -> SchemaType.MapLikeType) {
        val type get() = _type()
    }

    private fun recoverOwnerTypeOf(node: MappingNode, expectedType: ExpectedType) = OwnerType {
        node.declaration?.toType() // Either the concrete `ObjectType`...
            ?: expectedType.type as SchemaType.MapType  // ...or a MapType (from tracked expectedType).
    }

    private fun recoverExpectedTypeOf(keyValue: KeyValue, ownerType: OwnerType) = ExpectedType {
        keyValue.propertyDeclaration?.type  // Derive the type from `propertyDeclaration` if owner type is ObjectType...
            ?: (ownerType.type as SchemaType.MapType).valueType // ... or from `valueType` if the owner type is MapType
    }
}