/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.isDefault
import org.jetbrains.amper.frontend.api.withPrecedingValue
import org.jetbrains.amper.frontend.contexts.EmptyContexts
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration

/**
 * Merges all the trees from the argument list.
 */
fun mergeTrees(vararg trees: MappingNode) = mergeTrees(trees.toList())

/**
 * Merges (joins) all given [MappingNode]s into a single value.
 * The [trace][MappingNode.trace] is merged by adding each tree's trace as a preceding value.
 *
 * NOTE: The resulting tree node will have no contexts.
 *
 * @param trees input trees to merge. Must not be empty.
 */
fun mergeTrees(trees: List<MappingNode>): MappingNode {
    require(trees.isNotEmpty()) { "Cannot merge empty list of trees" }
    if (trees.size == 1) return trees.single()

    val allChildren = trees.flatMap { it.children }
    val trace = trees.fold(DefaultTrace as Trace) { acc, tree ->
        if (acc.isDefault) tree.trace else acc.withPrecedingValue(tree)
    }
    return MappingNode(
        children = allChildren,
        // TODO Maybe check that we are merging (or within same hierarchy) types?
        //  Currently it's not possible, because some declarations have common supertypes,
        //  but our schema doesn't retain this info. E.g., `Template` and `Module`.
        //  We need to teach our schema inheritance to make such type-checks possible.
        declaration = trees.first().declaration,
        trace = trace,
        // Note: all the children already have the necessary contexts
        contexts = EmptyContexts,
    )
}

/**
 * Truncates the type of the given [tree] to a "sub"-type ([truncateAs]).
 * All the [MappingNode.children] that are not present in the new type declaration are filtered out.
 */
fun truncateTree(
    tree: MappingNode,
    truncateAs: SchemaObjectDeclaration,
): MappingNode = tree.copy(
    declaration = truncateAs,
    children = tree.children.filter {
        // Include those properties that have matching `propertyDeclaration`s in the "new" type.
        truncateAs.getProperty(it.key) == it.propertyDeclaration
    },
)
