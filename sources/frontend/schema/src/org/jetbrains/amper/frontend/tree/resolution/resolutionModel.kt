/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.resolution

import org.jetbrains.amper.frontend.tree.RefinedTreeNode
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType

/**
 * A graph-node in a reference resolution linked-list
 * The links between nodes are defined by [ResolutionEdge].
 *
 * May hold a [value][Value] or a ["hole"][Hole]
 *
 * `${foo.bar.baz}` becomes
 * ```
 * "foo", "bar", "baz"
 * <edge> <edge> <edge>
 *       ^      ^      ^
 *     <step> <step> <step>
 * ```
 */
internal sealed interface ResolutionStep {
    /**
     * When there is no physical value, but the reference still type-checks using the type information.
     */
    data class Hole(
        /**
         * The expected type of potential value in this hole.
         */
        val type: SchemaType,
    ) : ResolutionStep

    /**
     * Physically-present [value].
     */
    data class Value(
        val value: RefinedTreeNode
    ) : ResolutionStep
}

/**
 * Result of a resolution of a string element from `referencePath`.
 * `["foo", "bar", "baz"]` will have 3 [ResolutionEdge]s exactly for each path element
 * in case of successfull resolution. If something was not resolved, the resulting path could have fewer edge objects.
 *
 * @see ResolutionStep
 * @see resolveReferences
 * @see org.jetbrains.amper.frontend.tree.ReferenceNode.referencedPath
 * @see org.jetbrains.amper.frontend.tree.StringInterpolationNode.Part.Reference.referencePath
 */
internal sealed interface ResolutionEdge {
    /**
     * String corresponding to the element of the `referencePath` of a reference.
     */
    val text: String

    /**
     * Resolved value/hole that this edge *leads to*.
     */
    val step: ResolutionStep

    /**
     * A [key][text] in the map
     */
    data class MapKey(
        override val step: ResolutionStep,
        override val text: String,
    ) : ResolutionEdge

    /**
     * A [property] from the object
     */
    data class Property(
        override val step: ResolutionStep,
        val property: SchemaObjectDeclaration.Property,
    ) : ResolutionEdge {
        override val text get() = property.name
    }
}
