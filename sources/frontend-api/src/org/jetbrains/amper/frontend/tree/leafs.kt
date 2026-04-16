/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.types.SchemaType

/**
 * Represents an invalid/missing value that the parser was unable to parse.
 * Also, may be used instead of the [ResolvableNode] that was failed to be resolved.
 */
class ErrorNode(
    /**
     * A type that was expected for this node.
     */
    val expectedType: SchemaType,
    /**
     * If there is no PSI element at all for the (absent) value, this trace points to the key-value element that
     * contains no value (E.g. `foo: ` in YAML).
     */
    override val trace: Trace,
    override val contexts: Contexts,
) : LeafTreeNode

/**
 * Represents the null literal.
 */
class NullLiteralNode(
    override val trace: Trace,
    override val contexts: Contexts,
) : LeafTreeNode, CompleteTreeNode

/**
 * This is a scalar value tree node.
 * See the sealed inheritors for type-safe access to the actual value.
 */
sealed interface ScalarNode : LeafTreeNode, CompleteTreeNode

/**
 * A node that contains references and thus can be "resolved" - replaced with another node or an [ErrorNode]
 * if resolution fails.
 */
sealed interface ResolvableNode : LeafTreeNode {
    /**
     * A type that the result of this node's resolution is expected to be assignable to.
     */
    val expectedType: SchemaType
}

@RequiresOptIn("This mechanism is only intended for procedural defaults. Do not use it for anything else")
annotation class DefaultsReferenceTransform

/**
 * This is a reference value tree node, pointing to some subtree.
 */
class ReferenceNode(
    val referencedPath: List<String>,
    override val expectedType: SchemaType,
    val transform: Transform? = null,
    override val trace: Trace,
    override val contexts: Contexts,
) : ResolvableNode {
    /**
     * Implementations must be **named classes**.
     *
     * @see [Transform.function]
     */
    interface TransformFunction<out T> {
        fun transform(node: RefinedTreeNode): T
    }

    class Transform @DefaultsReferenceTransform constructor(
        /**
         * Description for the transformed trace.
         */
        val description: String,
        /**
         * Transforms the refined node that was resolved from the [org.jetbrains.amper.frontend.tree.ReferenceNode].
         * The result is a value with the [org.jetbrains.amper.frontend.api.Default.Static.value] semantics.
         */
        val function: TransformFunction<Any?>,
    )

    init {
        require(referencedPath.isNotEmpty()) { "`referencePath` can't be empty" }
    }
}

/**
 * String interpolation node, containing one or more [references][Part.Reference] inside a string.
 */
class StringInterpolationNode(
    val parts: List<Part>,
    override val expectedType: SchemaType.StringInterpolatableType,
    override val trace: Trace,
    override val contexts: Contexts,
) : ResolvableNode {
    init {
        require(parts.any { it is Part.Reference }) {
            "Makes no sense to construct StringInterpolationValue without references"
        }
    }

    sealed interface Part {
        data class Reference(val referencePath: List<String>) : Part {
            init {
                require(referencePath.isNotEmpty()) { "`referencePath` can't be empty" }
            }
        }
        data class Text(val text: String): Part
    }
}

fun StringInterpolationNode.copy(
    parts: List<StringInterpolationNode.Part> = this.parts,
    expectedType: SchemaType.StringInterpolatableType = this.expectedType,
    trace: Trace = this.trace,
    contexts: Contexts = this.contexts,
) = StringInterpolationNode(parts, expectedType, trace, contexts)