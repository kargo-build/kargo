/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.types.SchemaType


/**
 * Checks if [rhs] type can be assigned to this [SchemaType].
 *
 * This check considers:
 * - Nullability: A non-nullable type cannot be assigned a nullable type.
 * - Identity: The same types are always assignable.
 * - Hierarchy: Object types are assignable to [SchemaType.VariantType] if they are members of that variant.
 * - Implicit conversions: If [allowStringConversion] is true, [SchemaType.PathType], [SchemaType.EnumType],
 *   and [SchemaType.IntType] are considered assignable to [SchemaType.StringType] (provided [SchemaType.StringType.semantics] is null).
 * - Collections: [SchemaType.ListType] and [SchemaType.MapType] elements are checked invariantly (no implicit conversions
 *   for elements).
 *
 * @param rhs the type to check for assignability.
 * @param allowStringConversion whether to allow implicit conversions to [SchemaType.StringType].
 * @return true if [rhs] is assignable to this type.
 * @see cast
 */
@UsedInIdePlugin
fun SchemaType.isAssignableFrom(
    rhs: SchemaType,
    allowStringConversion: Boolean = true,
): Boolean {
    if (!isMarkedNullable && rhs.isMarkedNullable) {
        return false
    }

    return when (this) {
        is SchemaType.ListType -> rhs is SchemaType.ListType
                && elementType.isAssignableFrom(rhs.elementType, allowStringConversion = false)
        is SchemaType.MapType -> rhs is SchemaType.MapType
                && keyType.isAssignableFrom(rhs.keyType, allowStringConversion = false)
                && valueType.isAssignableFrom(rhs.valueType, allowStringConversion = false)
        is SchemaType.ObjectType -> rhs is SchemaType.ObjectType && declaration == rhs.declaration
        is SchemaType.BooleanType -> rhs is SchemaType.BooleanType
        is SchemaType.EnumType -> rhs is SchemaType.EnumType && declaration == rhs.declaration
        is SchemaType.IntType -> rhs is SchemaType.IntType
        is SchemaType.PathType -> rhs is SchemaType.PathType
        is SchemaType.StringType -> when (rhs) {
            is SchemaType.StringType -> semantics == null || semantics == rhs.semantics
            is SchemaType.PathType, is SchemaType.EnumType, is SchemaType.IntType ->
                allowStringConversion && semantics == null
            else -> false
        }
        is SchemaType.VariantType -> when (rhs) {
            is SchemaType.VariantType -> declaration == rhs.declaration
            is SchemaType.ObjectType -> rhs.declaration in declaration.variants
            else -> false
        }
    }
}

/**
 * Casts a [RefinedTreeNode] to this [SchemaType], possibly performing implicit conversions.
 *
 * This function handles:
 * - Simple types: returns the [node] if its type matches this [SchemaType].
 * - Objects and Variants: returns the [node] if its [RefinedMappingNode.declaration] matches this type or its member.
 * - Collections: returns the [node] if its elements are invariantly assignable to this type's elements.
 * - Implicit conversions: if [allowStringConversion] is true and this type is [SchemaType.StringType] (with null [SchemaType.StringType.semantics]),
 *   it can convert [PathNode], [EnumNode], and [IntNode] into a new [StringNode].
 * - [ResolvableNode] and [ErrorNode]: checks if their expected types are assignable to this type.
 * - Nullability: returns the [NullLiteralNode] if this type is marked nullable.
 *
 * Used primarily during reference resolution and tree refinement to ensure nodes match the expected schema types.
 *
 * @param node the node to be cast.
 * @param allowStringConversion whether to perform implicit conversions to [SchemaType.StringType].
 * @return the original or converted [RefinedTreeNode], or `null` if the cast is not possible.
 * @see isAssignableFrom
 */
fun SchemaType.cast(
    node: RefinedTreeNode,
    allowStringConversion: Boolean = true,
): RefinedTreeNode? {
    if (node is ErrorNode) {
        // We allow passing an error node here as long as its expectedType is right
        return node.takeIf { isAssignableFrom(node.expectedType, allowStringConversion) }
    }

    if (node is ResolvableNode) {
        // For references/interpolation we just perform a type-check
        return node.takeIf { isAssignableFrom(node.expectedType, allowStringConversion) }
    }

    if (node is NullLiteralNode) {
        return node.takeIf { isMarkedNullable }
    }

    return when (this) {
        // No conversion - just type check
        is SchemaType.ObjectType if node is RefinedMappingNode && declaration == node.declaration -> node
        is SchemaType.BooleanType if node is BooleanNode -> node
        is SchemaType.EnumType if node is EnumNode && declaration == node.declaration -> node
        is SchemaType.IntType if node is IntNode -> node
        is SchemaType.VariantType if node is RefinedMappingNode && node.declaration in declaration.variants -> node
        is SchemaType.PathType if node is PathNode -> node
        is SchemaType.StringType if node is StringNode && (semantics == null || semantics == node.semantics) -> node
        is SchemaType.ListType if node is RefinedListNode -> node.takeIf {
            node.children.all { elementType.isAssignableFrom(it, allowStringConversion = false) }
        }
        is SchemaType.MapType if node is RefinedMappingNode && node.declaration == null -> node.takeIf {
            node.refinedChildren.values.all { kv ->
                valueType.isAssignableFrom(kv.value, allowStringConversion = false)
            }
        }
        // Conversion to string...
        is SchemaType.StringType if allowStringConversion -> when (node) {
            // ...from path
            is PathNode if semantics == null -> StringNode(node.value.toString(), null, node.trace, node.contexts)
            // ...from enum
            is EnumNode if semantics == null -> StringNode(node.schemaValue, null, node.trace, node.contexts)
            // ...from int
            is IntNode if semantics == null -> StringNode(node.value.toString(), null, node.trace, node.contexts)
            else -> null
        }
        else -> null
    }
}

/**
 * `true`, if [cast] can be successful, `false` otherwise.
 * @see cast
 */
@UsedInIdePlugin
fun SchemaType.isAssignableFrom(
    node: RefinedTreeNode,
    allowStringConversion: Boolean = true,
): Boolean {
    return cast(node, allowStringConversion) != null
}
