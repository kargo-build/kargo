/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.reading

import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.tree.copyWithTrace
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Bom
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Catalog
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Failed
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Local
import org.jetbrains.amper.frontend.tree.reading.DependencyTypeInferenceResult.Maven
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
): TreeNode = when (type.declaration) {
    DeclarationOfVariantDependency -> when (inferDependencyType(value, isScoped = true)) {
        Bom -> {
            val bomDependency = (value as YamlValue.Mapping).keyValues.single()
            parseNode(bomDependency.value, type.checkSubType(DeclarationOfVariantBomDependency))
                .copyWithTrace(trace = bomDependency.asTrace())
        }
        Local, Catalog, Maven, // Do not parse directly, delegate to another branch for composability and DRY
            -> parseVariant(value, type.checkSubType(DeclarationOfVariantScopedDependency))
        Failed -> {
            reportParsing(value, TreeDiagnosticId.WrongDependencyFormat, "validation.types.dependency.wrong.syntax.scoped")
            errorNode(value, type)
        }
    }
    DeclarationOfVariantBomDependency -> when (inferDependencyType(value, isScoped = false)) {
        Catalog -> parseObject(value, type.checkSubType(DeclarationOfCatalogBomDependency))
        Maven, Bom,  // i.e. the second bom in `bom: bom` will be treated as maven coords
            -> parseObject(value, type.checkSubType(DeclarationOfExternalMavenBomDependency))
        Local -> {
            reportParsing(value, TreeDiagnosticId.LocalBomAreNotSupported, "unexpected.bom.local")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(value, TreeDiagnosticId.WrongDependencyFormat, "validation.types.dependency.wrong.syntax.bom")
            errorNode(value, type)
        }
    }
    DeclarationOfVariantScopedDependency -> when (inferDependencyType(value, isScoped = true)) {
        Local -> parseObject(value, type.checkSubType(DeclarationOfInternalDependency))
        Catalog -> parseObject(value, type.checkSubType(DeclarationOfCatalogDependency))
        Maven -> parseObject(value, type.checkSubType(DeclarationOfExternalMavenDependency))
        Bom -> {
            reportParsing(value, TreeDiagnosticId.BomIsNotSupported, "unexpected.bom")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(value, TreeDiagnosticId.WrongDependencyFormat, "validation.types.dependency.wrong.syntax.scoped")
            errorNode(value, type)
        }
    }
    DeclarationOfVariantUnscopedDependency -> when (inferDependencyType(value, isScoped = false)) {
        Bom -> {
            val bomDependency = (value as YamlValue.Mapping).keyValues.single()
            parseVariant(bomDependency.value, type.checkSubType(DeclarationOfVariantUnscopedBomDependency))
                .copyWithTrace(trace = bomDependency.asTrace())
        }
        Local -> parseObject(value, type.checkSubType(DeclarationOfUnscopedModuleDependency))
        Maven, Catalog, // Do not parse directly, delegate to another branch for composability and DRY
             -> parseVariant(value, type.checkSubType(DeclarationOfVariantUnscopedExternalDependency))
        Failed -> {
            reportParsing(value, TreeDiagnosticId.WrongDependencyFormat, "validation.types.dependency.wrong.syntax.unscoped")
            errorNode(value, type)
        }
    }
    DeclarationOfVariantUnscopedExternalDependency -> when (inferDependencyType(value, isScoped = false)) {
        Catalog -> parseObject(value, type.checkSubType(DeclarationOfUnscopedCatalogDependency))
        Maven -> parseObject(value, type.checkSubType(DeclarationOfUnscopedExternalMavenDependency))
        Local -> {
            reportParsing(value, TreeDiagnosticId.LocalDependenciesAreNotSupported, "unexpected.local.module")
            errorNode(value, type)
        }
        Bom -> {
            reportParsing(value, TreeDiagnosticId.BomIsNotSupported, "unexpected.bom")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(value, TreeDiagnosticId.WrongDependencyFormat, "validation.types.dependency.wrong.syntax.unscoped.external")
            errorNode(value, type)
        }
    }
    DeclarationOfVariantUnscopedBomDependency -> when (inferDependencyType(value, isScoped = false)) {
        Catalog -> parseObject(value, type.checkSubType(DeclarationOfUnscopedCatalogBomDependency))
        Maven, Bom,  // i.e. the second bom in `bom: bom` will be treated as maven coords
            -> parseObject(value, type.checkSubType(DeclarationOfUnscopedExternalMavenBomDependency))
        Local -> {
            reportParsing(value, TreeDiagnosticId.LocalBomAreNotSupported, "unexpected.bom.local")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(value, TreeDiagnosticId.WrongDependencyFormat, "validation.types.dependency.wrong.syntax.bom")
            errorNode(value, type)
        }
    }
    DeclarationOfVariantShadowDependency -> when (inferDependencyType(value, isScoped = false)) {
        Local -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyLocal))
        Catalog -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyCatalog))
        Maven -> parseObject(value, type.checkSubType(DeclarationOfShadowDependencyMaven))
        Bom -> {
            reportParsing(value, TreeDiagnosticId.BomIsNotSupported, "unexpected.bom")
            errorNode(value, type)
        }
        Failed -> {
            reportParsing(value, TreeDiagnosticId.WrongDependencyFormat, "validation.types.dependency.wrong.syntax.unscoped.no.bom")
            errorNode(value, type)
        }
    }
    is TaskActionVariantDeclaration -> {
        val tag = value.tag
        if (tag == null) {
            reporter.reportMessage(MissingTaskActionType(element = value.psi, taskActionType = type.declaration))
            return errorNode(value, type)
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
            errorNode(value, type)
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

private enum class DependencyTypeInferenceResult {
    /** Starts from `.` */
    Local,
    /** Starts from `$` */
    Catalog,
    /** arbitrary string (if the overall YAML structure around is correct) **/
    Maven,
    /** has a single 'bom' property **/
    Bom,
    /** type inference is failed - the value has no chance to be correctly parsed as any type */
    Failed,
}

private fun inferDependencyType(psi: YamlValue, isScoped: Boolean): DependencyTypeInferenceResult {
    val peekedKey: String = when (psi) {
        is YamlValue.Mapping -> psi.keyValues.singleOrNull()?.key?.psi?.text
        is YamlValue.Scalar -> psi.textValue
        else -> null
    } ?: return Failed
    if (peekedKey == "bom" && psi is YamlValue.Mapping) {
        return Bom
    }
    // Check this after checking for 'bom'
    if (psi is YamlValue.Mapping && !isScoped) {
        return Failed
    }
    return when (peekedKey.firstOrNull()) {
        '$' -> Catalog
        '.' -> Local
        else -> Maven
    }
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
