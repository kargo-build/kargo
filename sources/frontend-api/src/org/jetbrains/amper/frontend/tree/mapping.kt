/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import kotlin.reflect.KProperty1

/**
 * This is a mapping node in a value tree that is holding either a map or
 * an [object][org.jetbrains.amper.frontend.types.SchemaTypeDeclaration], which is determined by [declaration] field.
 *
 * When [declaration] is `null`, this node represents a map. When non-null, it represents an object.
 *
 * This node can have more than one [KeyValue] with different contexts but with the same [key][KeyValue.key].
 *
 * @see RefinedMappingNode
 */
// NOTE: `sealed` is needed here to appease the compiler's exhaustiveness checks.
// TODO: Maybe introduce map/object sub-interfaces for better expressiveness?
sealed interface MappingNode : TreeNode {
    val children: List<KeyValue>
    val declaration: SchemaObjectDeclaration?
}

/**
 * Same as [MappingNode], but guarantess key string uniqueness in [children].
 * Also provides access to the [key-values][RefinedKeyValue] as a map - [refinedChildren].
 */
interface RefinedMappingNode : MappingNode, RefinedTreeNode {
    override val children : List<RefinedKeyValue>
    val refinedChildren : Map<String, RefinedKeyValue>
}

/**
 * Same as [RefinedMappingNode], but guarantees *completeness* of its [refinedChildren].
 * Has two distinct variants:
 * - [CompleteMapNode] (map)
 * - [CompleteObjectNode] (object)
 */
sealed interface CompleteMappingNode : RefinedMappingNode, CompleteTreeNode {
    override val children : List<CompleteKeyValue>
    override val refinedChildren : Map<String, CompleteKeyValue>
}

/**
 * A complete tree node of a [SchemaType.MapType] type.
 * All child nodes are complete.
 */
interface CompleteMapNode : CompleteMappingNode {
    override val children : List<CompleteMapKeyValue>
    override val refinedChildren : Map<String, CompleteMapKeyValue>
}

/**
 * A complete tree node of a [SchemaType.ObjectType] type.
 * Every [keyValue][CompletePropertyKeyValue] ([children]/[refinedChildren]) is guaranteed to have a
 * [property declaration][CompletePropertyKeyValue.propertyDeclaration].
 * All child nodes are complete.
 */
interface CompleteObjectNode : CompleteMappingNode {
    override val declaration: SchemaObjectDeclaration
    override val children : List<CompletePropertyKeyValue>
    override val refinedChildren : Map<String, CompletePropertyKeyValue>

    /**
     * A cached [SchemaNode] instance, created on demand.
     */
    val instance: SchemaNode
}

/**
 * Helper function to access a [CompleteObjectNode.instance] in a typed manner.
 */
inline fun <reified T : SchemaNode> CompleteObjectNode.instance(): T = instance as T

fun MappingNode(
    children: List<KeyValue>,
    declaration: SchemaObjectDeclaration?,
    trace: Trace,
    contexts: Contexts,
): MappingNode = MappingNodeImpl(children, declaration, trace, contexts)

fun RefinedMappingNode(
    refinedChildren: Map<String, RefinedKeyValue>,
    declaration: SchemaObjectDeclaration?,
    trace: Trace,
    contexts: Contexts,
) : RefinedMappingNode = RefinedMappingNodeImpl(refinedChildren, declaration, trace, contexts)

fun CompleteMapNode(
    refinedChildren: Map<String, CompleteMapKeyValue>,
    trace: Trace,
    contexts: Contexts,
) : CompleteMapNode = CompleteMapNodeImpl(refinedChildren, trace, contexts)

fun CompleteObjectNode(
    refinedChildren: Map<String, CompletePropertyKeyValue>,
    declaration: SchemaObjectDeclaration,
    trace: Trace,
    contexts: Contexts,
) : CompleteObjectNode = CompleteObjectNodeImpl(refinedChildren, declaration, trace, contexts)

/**
 * NOTE: Doesn't check given [children] for key uniqueness criteria.
 * Thus, the resulting copy will never be a [RefinedMappingNode], even if the original node was one.
 */
fun MappingNode.copy(
    children: List<KeyValue> = this.children,
    declaration: SchemaObjectDeclaration? = this.declaration,
    trace: Trace = this.trace,
    contexts: Contexts = this.contexts,
): MappingNode = MappingNode(children, declaration, trace, contexts)

fun RefinedMappingNode.copy(
    refinedChildren: Map<String, RefinedKeyValue> = this.refinedChildren,
    declaration: SchemaObjectDeclaration? = this.declaration,
    trace: Trace = this.trace,
    contexts: Contexts = this.contexts,
): RefinedMappingNode = RefinedMappingNode(refinedChildren, declaration, trace, contexts)

/**
 * Returns the value from the mapping with the key equal to the name of the given [property],
 * if this is a [RefinedMappingNode] and it has such a value;
 * `null` otherwise.
 */
operator fun RefinedTreeNode?.get(property: KProperty1<out SchemaNode, *>): RefinedTreeNode? = get(property.name)

/**
 * Returns the value from the mapping with the key [property],
 * if this is a [RefinedMappingNode] and it has such a value;
 * `null` otherwise.
 */
operator fun RefinedTreeNode?.get(property: String): RefinedTreeNode? =
    (this as? RefinedMappingNode)?.refinedChildren[property]?.value

operator fun CompleteObjectNode?.get(property: String): CompleteTreeNode? =
    this?.refinedChildren[property]?.value

private class MappingNodeImpl(
    override val children: List<KeyValue>,
    override val declaration: SchemaObjectDeclaration?,
    override val trace: Trace,
    override val contexts: Contexts,
) : MappingNode

private class RefinedMappingNodeImpl(
    override val refinedChildren: Map<String, RefinedKeyValue>,
    override val declaration: SchemaObjectDeclaration?,
    override val trace: Trace,
    override val contexts: Contexts,
) : RefinedMappingNode {
    override val children = refinedChildren.values.toList()
}

private class CompleteMapNodeImpl(
    override val refinedChildren: Map<String, CompleteMapKeyValue>,
    override val trace: Trace,
    override val contexts: Contexts,
) : CompleteMapNode {
    override val children = refinedChildren.values.toList()
    override val declaration: SchemaObjectDeclaration? = null
}

private class CompleteObjectNodeImpl(
    override val refinedChildren: Map<String, CompletePropertyKeyValue>,
    override val declaration: SchemaObjectDeclaration,
    override val trace: Trace,
    override val contexts: Contexts,
) : CompleteObjectNode {
    override val children = refinedChildren.values.toList()

    override val instance: SchemaNode by lazy {
        declaration.createInstance().apply { initialize(this@CompleteObjectNodeImpl) }
    }
}
