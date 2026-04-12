/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.tree.copyWithTrace
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.SchemaVariantDeclaration
import org.jetbrains.amper.frontend.types.TaskActionVariantDeclaration
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.ProblemReporter

context(_: Contexts, _: ParsingConfig, reporter: ProblemReporter)
internal fun parseVariant(
    value: YamlValue,
    type: SchemaType.VariantType,
): TreeNode? = when (type.declaration) {
    DeclarationOfVariantDependency -> {
        val singleKeyValue = (value as? YamlValue.Mapping)?.keyValues?.singleOrNull()
        if (singleKeyValue != null && singleKeyValue.key.psi.text == "bom") {
            parseNode(singleKeyValue.value, type.checkSubType(DeclarationOfVariantBomDependency))
                .copyWithTrace(trace = singleKeyValue.asTrace())
        } else {
            parseVariant(value, type.checkSubType(DeclarationOfVariantScopedDependency))
        }
    }
    DeclarationOfVariantBomDependency -> when (peekValueAsKey(value)?.firstOrNull()) {
        '.' -> {
            reportParsing(value, TreeDiagnosticId.LocalBomAreNotSupported, "unexpected.bom.local")
            null
        }
        '$' -> parseObject(value, type.checkSubType(DeclarationOfCatalogBomDependency))
        else -> parseObject(value, type.checkSubType(DeclarationOfExternalMavenBomDependency))
    }
    DeclarationOfVariantScopedDependency -> when (peekValueAsKey(value)?.firstOrNull()) {
        '.' -> parseObject(value, type.checkSubType(DeclarationOfInternalDependency))
        '$' -> parseObject(value, type.checkSubType(DeclarationOfCatalogDependency))
        else -> parseObject(value, type.checkSubType(DeclarationOfExternalMavenDependency))
    }
    DeclarationOfVariantUnscopedDependency -> {
        val singleKeyValue = (value as? YamlValue.Mapping)?.keyValues?.singleOrNull()
        if (singleKeyValue != null && singleKeyValue.key.psi.text == "bom") {
            singleKeyValue.value.let { bomDependency ->
                parseVariant(bomDependency, type.checkSubType(DeclarationOfVariantUnscopedBomDependency))
                    ?.copyWithTrace(trace = singleKeyValue.asTrace())
            }
        } else {
            when (peekValueAsKey(value)?.firstOrNull()) {
                '.' -> parseObject(value, type.checkSubType(DeclarationOfUnscopedModuleDependency))
                else -> parseVariant(value, type.checkSubType(DeclarationOfVariantUnscopedExternalDependency))
            }
        }
    }
    DeclarationOfVariantUnscopedExternalDependency -> when (peekValueAsKey(value)?.firstOrNull()) {
        '.' -> {
            reportParsing(value, TreeDiagnosticId.LocalBomAreNotSupported, "unexpected.local.module")
            null
        }
        '$' -> parseObject(value, type.checkSubType(DeclarationOfUnscopedCatalogDependency))
        else -> parseObject(value, type.checkSubType(DeclarationOfUnscopedExternalMavenDependency))
    }
    DeclarationOfVariantUnscopedBomDependency -> when (peekValueAsKey(value)?.firstOrNull()) {
        '.' -> {
            reportParsing(value, TreeDiagnosticId.LocalBomAreNotSupported, "unexpected.bom.local")
            null
        }
        '$' -> parseObject(value, type.checkSubType(DeclarationOfUnscopedCatalogBomDependency))
        else -> parseObject(value, type.checkSubType(DeclarationOfUnscopedExternalMavenBomDependency))
    }
    DeclarationOfVariantShadowDependency -> when (peekValueAsKey(value)?.firstOrNull()) {
        '.' -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyLocal))
        '$' -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyCatalog))
        else -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyMaven))
    }
    is TaskActionVariantDeclaration -> {
        val tag = value.tag
        if (tag == null) {
            reporter.reportMessage(MissingTaskActionType(element = value.psi, taskActionType = type.declaration))
            return null
        }
        val requestedTypeName = tag.text.removePrefix("!")
        val variant = type.declaration.variants.find { it.qualifiedName == requestedTypeName }
        if (variant == null) {
            reporter.reportMessage(
                InvalidTaskActionType(
                    element = tag,
                    invalidType = requestedTypeName,
                    taskActionType = type.declaration,
                )
            )
            null
        } else {
            parseObject(value, variant.toType(), allowTypeTag = true)
        }
    }
    else -> {
        // NOTE: When (if) we support user-defined sealed classes based on type tags,
        // replace the error with a meaningful description
        error("Unhandled variant type: ${type.declaration.qualifiedName}")
    }
}

private fun peekValueAsKey(psi: YamlValue): String? = when (psi) {
    is YamlValue.Mapping -> psi.keyValues.singleOrNull()?.key?.psi?.text
    is YamlValue.Scalar -> psi.textValue
    else -> null
}

private fun SchemaType.VariantType.checkSubType(leaf: SchemaObjectDeclaration): SchemaType.ObjectType {
    require(declaration.variantTree.any { it.declaration == leaf }) {
        "Leaf variant declaration not found in variant tree: ${leaf.qualifiedName}"
    }
    return leaf.toType()
}

private fun SchemaType.VariantType.checkSubType(sub: SchemaVariantDeclaration): SchemaType.VariantType {
    require(declaration.variantTree.any { it.declaration == sub }) {
        "Sub-variant declaration not found in variant tree: ${sub.qualifiedName}"
    }
    return sub.toType()
}
