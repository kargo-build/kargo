/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.isDefault
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.contexts.MinimalModule
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.schema.ModuleProduct
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * A property path in a tree with associated value traces.
 * Example: `["settings" to <trace-1>, "kotlin" to <trace-2>, "version" to <trace-2>]`
 */
private typealias ValuePath = List<Pair<String, Trace>>

/**
 * Convert a refined mapping node into a complete object node, if possible.
 *
 * Incomplete (invalid, unresolved, etc.) nodes are skipped as list/map elements.
 * If encountered in an object, they lead to the whole object being skipped because it'd no longer be complete.
 * If this leads to the root node being skipped, then this routine returns `null`.
 */
context(_: ProblemReporter)
fun RefinedMappingNode.completeTree(): CompleteObjectNode? {
    return ensureCompleteTreeNode(this, emptyList()) as CompleteObjectNode?
}

context(_: ProblemReporter)
private fun ensureCompleteTreeNode(
    node: RefinedTreeNode,
    valuePath: ValuePath,
): CompleteTreeNode? {
    when (node) {
        is ErrorNode,
            // Do not report: reported during parsing
        is ReferenceNode, is StringInterpolationNode,
            -> {
            // Do not report: must be already reported during reference resolution
            return null
        }
        else -> {}
    }

    return when (node) {
        // Already a complete node - return as is
        is CompleteTreeNode -> node
        is RefinedListNode -> {
            // Complete children filtering errors (represented by `null`) out.
            // Nothing to report - they are already reported.
            val completeChildren = node.children.mapNotNull {
                ensureCompleteTreeNode(it, valuePath + ("[]" to it.trace))
            }
            CompleteListNode(completeChildren, node.trace, node.contexts)
        }
        is RefinedMappingNode -> when (val declaration = node.declaration) {
            null -> {  // Map
                val completeKeyValues = node.refinedChildren.mapValues { (key, keyValue) ->
                    val completeValue = ensureCompleteTreeNode(
                        keyValue.value,
                        valuePath + (key to keyValue.value.trace)
                    )
                    completeValue?.let { keyValue.asCompleteForMap(it) }
                }.filterValues { it != null }.mapValues { it.value!! }

                CompleteMapNode(completeKeyValues, node.trace, node.contexts)
            }
            else -> {  // Object
                for (mapLikePropertyValue in node.refinedChildren.values) {
                    propertyCheckTypeLevelIntegrity(declaration, mapLikePropertyValue)
                }

                val completeKeyValues = mutableMapOf<String, CompletePropertyKeyValue>()
                var hasMissingRequiredProps = false
                val missingProperties = mutableListOf<MissingPropertyInfo>()
                for (property in declaration.properties) {
                    val propertyValuePath = valuePath + (property.name to node.trace)
                    val mapLikePropertyValue = node.refinedChildren[property.name]
                    propertyCheckDefaultIntegrity(property, mapLikePropertyValue)
                    if (mapLikePropertyValue == null) {
                        // Property is not mentioned at all
                        if (property.isUserSettable) {
                            // Find the last non-default trace in the path - that's the *base* trace for our missing value
                            val baseTraceIndex = propertyValuePath.indexOfLast { (_, trace) -> !trace.isDefault }
                            val baseTrace = propertyValuePath[baseTraceIndex].second
                            missingProperties += MissingPropertyInfo(
                                trace = baseTrace,
                                valuePath = propertyValuePath.map { (name, _) -> name },
                                relativeValuePath = propertyValuePath.drop(baseTraceIndex).map { (name, _) -> name },
                                propertyDeclaration = property,
                            )
                        }
                        hasMissingRequiredProps = true
                        continue
                    }
                    val completeChild = ensureCompleteTreeNode(
                        node = mapLikePropertyValue.value,
                        valuePath = propertyValuePath,
                    )
                    if (completeChild == null) {
                        // we don't report here because the error must have been reported when parsing the property
                        hasMissingRequiredProps = true
                        continue
                    }
                    completeKeyValues[property.name] = mapLikePropertyValue.asCompleteForObject(completeChild)
                }
                // Group missing properties by trace and report once per group
                missingProperties.groupBy { it.trace }.values.forEach { group ->
                    reportMissingProperties(group, declaration)
                }
                if (hasMissingRequiredProps) {
                    // We don't allow incomplete objects
                    null
                } else {
                    CompleteObjectNode(completeKeyValues, declaration, node.trace, node.contexts)
                }
            }
        }
    }
}

context(reporter: ProblemReporter)
private fun reportMissingProperties(
    properties: List<MissingPropertyInfo>,
    inside: SchemaObjectDeclaration,
) {
    val reportedIndividually = mutableSetOf<MissingPropertyInfo>()
    when (inside) {
        is DeclarationOfMinimalModule -> for (info in properties) {
            if (info.valuePath == listOf(MinimalModule::product.name)) {
                reporter.reportBundleError(
                    source = info.trace.asBuildProblemSource(),
                    diagnosticId = FrontendDiagnosticId.ProductNotDefined,
                    messageKey = "product.not.defined.empty",
                )
                reportedIndividually += info
            }
        }
        is DeclarationOfModuleProduct -> for (info in properties) {
            if (info.valuePath == listOf(ModuleProduct::type.name)) {
                reporter.reportBundleError(
                    source = info.trace.asBuildProblemSource(),
                    diagnosticId = FrontendDiagnosticId.ProductNotDefined,
                    messageKey = "product.not.defined",
                )
                reportedIndividually += info
            }
        }
    }
    val remainingBatch = properties - reportedIndividually
    if (remainingBatch.isNotEmpty()) {
        reporter.reportMessage(MissingPropertiesProblem(remainingBatch, inside))
    }
}

/**
 * Information about a single missing required property.
 * @see MissingPropertiesProblem
 */
class MissingPropertyInfo(
    /**
     * The trace of the outermost explicitly present (non-default) construct.
     */
    val trace: Trace,
    /**
     * The path (from the document root) for which the value is missing.
     * E.g., `["product", "type"]` or `["repositories", "[]", "url"]"`
     */
    val valuePath: List<String>,
    /**
     * a path relative to the construct with the [trace].
     * E.g., when the [valuePath] is `["tasks", "myTask", "action", "classpath", "dependencies"]`,
     * and the [trace] points to the `"action"` object,
     * then the `relativeValuePath` is `["classpath", "dependencies"]`.
     */
    val relativeValuePath: List<String>,
    /**
     * the declaration of the missing property.
     */
    val propertyDeclaration: SchemaObjectDeclaration.Property,
)

private fun propertyCheckDefaultIntegrity(
    propertyDeclaration: SchemaObjectDeclaration.Property,
    pValue: KeyValue?,
) {
    check(propertyDeclaration.default == null || pValue != null) {
        "A property ${propertyDeclaration.name} has a default ${propertyDeclaration.default}, " +
                "but the value is missing nevertheless. " +
                "This is a sign that the default was not properly added on the tree level. " +
                "Please check that defaults are correctly appended for this tree."
    }
}

private fun propertyCheckTypeLevelIntegrity(
    declaration: SchemaObjectDeclaration,
    pValue: KeyValue,
) {
    val typeProperty = checkNotNull(pValue.propertyDeclaration) {
        "Property `${pValue.key}` is present on the tree value level, " +
                "but it's not present in the type declaration for $declaration. " +
                "This is a sign that the property was not properly filtered out on the tree level. " +
                "Please check that extra properties are correctly filtered for this tree."
    }
    check(typeProperty.name == pValue.key) {
        "Property name mismatch: expected `${typeProperty.name}`, got `${pValue.key}`"
    }
}
