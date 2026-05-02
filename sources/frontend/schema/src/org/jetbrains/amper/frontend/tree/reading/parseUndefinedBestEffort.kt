/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * We try to parse an arbitrary value in as much a narrow type as is reasonable, trying to predict the best
 * "expected type" to be later suggested by tooling.
 */
context(contexts: Contexts, config: ParsingConfig, _: ProblemReporter)
internal fun parseUndefinedBestEffort(value: YamlValue): TreeNode {
    return when (value) {
        is YamlValue.Mapping ->
            // We never try to infer an object
            parseMap(value, SchemaType.MapType(keyType = SchemaType.StringType, valueType = SchemaType.UndefinedType))
        is YamlValue.Scalar -> when {
            // NOTE: references and `null` are parsed before this in their usual branch in `parseNode`
            // Every quoted value is treated as a string
            !value.isLiteral -> parseScalar(value, SchemaType.StringType)
            // Int
            SimpleIntRegex.matches(value.textValue) -> parseScalar(value, SchemaType.IntType)
            // Boolean
            BooleanRegex.matches(value.textValue) -> parseScalar(value, SchemaType.BooleanType)
            // TODO: Maybe try to deduce a path? If string contains a path separator or smth?
            // The fallback is always a plain string
            else -> parseScalar(value, SchemaType.StringType)
        }
        // Sequence means the list automatically
        is YamlValue.Sequence -> parseList(value, SchemaType.ListType(SchemaType.UndefinedType))
        // The rest is invalid even for UndefinedType, report it as usual
        is YamlValue.UnknownCompound, is YamlValue.Alias, is YamlValue.Missing -> {
            reportUnexpectedValue(value, SchemaType.UndefinedType)
            errorNode(value, SchemaType.UndefinedType)
        }
    }
}

// A very conservative base-10 integer regex
private val SimpleIntRegex = """-?(0|[1-9]\d{0,9})""".toRegex()

private val BooleanRegex = """true|false""".toRegex()
