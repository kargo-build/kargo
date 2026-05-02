/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.render
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.ProblemReporter
import java.nio.file.InvalidPathException
import kotlin.io.path.Path

context(_: Contexts, _: ParsingConfig, _: ProblemReporter)
internal fun parseScalar(scalar: YamlValue.Scalar, type: SchemaType.ScalarType): TreeNode = when (type) {
    is SchemaType.BooleanType -> when(val boolean = scalar.textValue.toBooleanStrictOrNull()) {
        null -> {
            reportParsing(scalar, TreeDiagnosticId.UnexpectedValue, "validation.expected", type.render(), type = BuildProblemType.TypeMismatch)
            errorNode(scalar, type)
        }
        else -> booleanNode(scalar, boolean)
    }
    is SchemaType.IntType -> when(val int = scalar.textValue.toIntOrNull()) {
        null -> {
            reportParsing(scalar, TreeDiagnosticId.UnexpectedValue, "validation.expected", type.render(), type = BuildProblemType.TypeMismatch)
            errorNode(scalar, type)
        }
        else -> intNode(scalar, int)
    }
    is SchemaType.StringType -> {
        val semanticsCheckPassed =
            when (type.semantics) {
                SchemaType.StringType.Semantics.JvmMainClass,
                SchemaType.StringType.Semantics.PluginSettingsClass,
                SchemaType.StringType.Semantics.MavenPlexusConfigXml,
                SchemaType.StringType.Semantics.TaskName,
                null -> true
            }
        if (semanticsCheckPassed) {
            stringNode(scalar, type.semantics, scalar.textValue)
        } else {
            errorNode(scalar, type)
        }
    }
    is SchemaType.EnumType -> parseEnum(scalar, type)
    is SchemaType.PathType -> parsePath(scalar)
}


context(_: Contexts, config: ParsingConfig, _: ProblemReporter)
private fun parsePath(scalar: YamlValue.Scalar): TreeNode {
    var path = try {
        Path(scalar.textValue)
    } catch (e: InvalidPathException) {
        reportParsing(scalar, TreeDiagnosticId.InvalidPath, "validation.types.invalid.path", e.message)
        return errorNode(scalar, SchemaType.PathType)
    }
    path = if (path.isAbsolute) path else config.basePath.resolve(path)
    path = path.normalize()
    return pathNode(scalar, path)
}

context(_: Contexts, _: ProblemReporter)
internal fun parseEnum(
    scalar: YamlValue.Scalar,
    type: SchemaType.EnumType,
    additionalSuggestedValues: List<String> = emptyList(),
): TreeNode {
    val textValue = scalar.textValue
    val entry = type.declaration.getEntryBySchemaValue(textValue)
    if (entry == null) {
        val suggestedValues = additionalSuggestedValues + type.declaration.entries
            .filter { it.isIncludedIntoJsonSchema && !it.isOutdated }
            .map { it.schemaValue }
        reportParsing(
            scalar, TreeDiagnosticId.UnknownEnumValue,
            "validation.types.unknown.enum.value",
            textValue, suggestedValues.joinToString(),
            type = BuildProblemType.TypeMismatch,
        )
        return errorNode(scalar, type)
    }
    return enumNode(scalar, type.declaration, entry.name)
}
