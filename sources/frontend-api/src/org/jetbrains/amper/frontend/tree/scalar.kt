/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.TraceablePath
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.types.SchemaEnumDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import java.nio.file.Path

/**
 * A [ScalarNode] with boolean [value].
 */
class BooleanNode(
    val value: Boolean,
    override val trace: Trace,
    override val contexts: Contexts,
) : ScalarNode

/**
 * A [ScalarNode] with string [value].
 */
class StringNode(
    val value: String,
    val semantics: SchemaType.StringType.Semantics?,
    override val trace: Trace,
    override val contexts: Contexts,
) : ScalarNode

fun StringNode.copyWithValue(value: String) = StringNode(value, semantics, trace, contexts)

/**
 * A [ScalarNode] with integer [value].
 */
class IntNode(
    val value: Int,
    override val trace: Trace,
    override val contexts: Contexts,
) : ScalarNode

/**
 * A [ScalarNode] with path [value].
 */
class PathNode(
    val value: Path,
    override val trace: Trace,
    override val contexts: Contexts,
) : ScalarNode

val PathNode.traceableValue get() = TraceablePath(value, trace)

/**
 * A [ScalarNode] with enum value, defined by the [entryName].
 *
 * [enumConstantIfAvailable] and [schemaValue] extensions are also available.
 */
class EnumNode(
    /**
     * Entry's name, as in [EnumEntry.name][org.jetbrains.amper.frontend.types.SchemaEnumDeclaration.EnumEntry.name].
     *
     * NOTE: Not a [schema value][org.jetbrains.amper.frontend.types.SchemaEnumDeclaration.EnumEntry.schemaValue].
     * For that, use the [schemaValue] extension property.
     */
    val entryName: String,
    val declaration: SchemaEnumDeclaration,
    override val trace: Trace,
    override val contexts: Contexts,
) : ScalarNode

/**
 * Returns [Enum] constant instance if the enum is backed by the builtin schema. `null` otherwise.
 *
 * @see org.jetbrains.amper.frontend.types.SchemaEnumDeclaration.toEnumConstant
 */
val EnumNode.enumConstantIfAvailable: Enum<*>?
    get() = declaration.toEnumConstant(entryName)

/**
 * Returns the [schema value][org.jetbrains.amper.frontend.types.SchemaEnumDeclaration.EnumEntry.schemaValue]
 * of the underlying enum entry.
 */
val EnumNode.schemaValue: String
    get() = checkNotNull(declaration.getEntryByName(entryName)) {
        "Unknown enum entry name: $entryName in ${declaration.displayName}"
    }.schemaValue

/**
 * Checks if this scalar node's underlying value equals to another scalar node's underlying value.
 * NOTE: Doesn't do any type checks.
 */
infix fun ScalarNode?.valueEqualsTo(another: ScalarNode?): Boolean = when (this) {
    is BooleanNode -> this.value == (another as? BooleanNode)?.value
    is StringNode -> this.value == (another as? StringNode)?.value
    is IntNode -> this.value == (another as? IntNode)?.value
    is PathNode -> this.value == (another as? PathNode)?.value
    is EnumNode -> this.entryName == (another as? EnumNode)?.entryName
    null -> another == null
}

fun ScalarNode.copyWithContexts(contexts: Contexts): ScalarNode = when (this) {
    is BooleanNode -> BooleanNode(value, trace, contexts)
    is EnumNode -> EnumNode(entryName, declaration, trace, contexts)
    is IntNode -> IntNode(value, trace, contexts)
    is PathNode -> PathNode(value, trace, contexts)
    is StringNode -> StringNode(value, semantics, trace, contexts)
}