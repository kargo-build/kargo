/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.resolution

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.Default
import org.jetbrains.amper.frontend.api.TransformedValueTrace
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.ErrorNode
import org.jetbrains.amper.frontend.tree.LeafTreeNode
import org.jetbrains.amper.frontend.tree.PathNode
import org.jetbrains.amper.frontend.tree.RecurringRefinedTreeVisitorUnit
import org.jetbrains.amper.frontend.tree.ReferenceNode
import org.jetbrains.amper.frontend.tree.RefinedListNode
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.RefinedTreeNode
import org.jetbrains.amper.frontend.tree.RefinedTreeTransformer
import org.jetbrains.amper.frontend.tree.ResolvableNode
import org.jetbrains.amper.frontend.tree.StringInterpolationNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.frontend.tree.cast
import org.jetbrains.amper.frontend.tree.copy
import org.jetbrains.amper.frontend.tree.copyWithTrace
import org.jetbrains.amper.frontend.tree.isAssignableFrom
import org.jetbrains.amper.frontend.tree.toTreeValue
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.render
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.stdlib.collections.joinToString
import java.nio.file.InvalidPathException
import kotlin.contracts.contract
import kotlin.io.path.Path

/**
 * Resolve references in the given tree, return the result.
 * Reports diagnostics in case of errors. Supports trees with missing values, resorts to type-only resolution.
 *
 * Instead of [ResolvableNode]s the resulting tree will contain
 * 1. Normal [RefinedTreeNode] when the resolution succeeds.
 * 2. [ErrorNode], when the resolution fails, type-check fails, etc.
 * 3. [ResolvableNode], when the resolution succeeds on the type-level, but no final value was present in the tree.
 *    In that case, the resulting tree may be [merged][org.jetbrains.amper.frontend.tree.mergeTrees] with another one
 *    and then [refined][org.jetbrains.amper.frontend.tree.TreeRefiner.refineTree] again to finish reference resolution.
 *
 * **Type-only resolution**
 *
 * When there is no physical value present in the tree, the resolution will be performed conservatively
 * on the type-level.
 * This means that more conservative type-checks will be performed, e.g., one can't go past the nullable value,
 * as it can be `null` in the general case.
 * Type-level resolution makes no assumptions about the presence of map keys,
 * so resolution down maps is not valid either.
 * It's only possible to follow [properties][org.jetbrains.amper.frontend.types.SchemaObjectDeclaration.Property] in
 * this case.
 */
context(_: ProblemReporter)
internal fun RefinedTreeNode.resolveReferences(): RefinedTreeNode {
    return ReferenceResolutionSession(this).perform()
}

/**
 * Algorithm:
 * 1. Pre-compute [ResolutionContext] per each [ResolvableNode] in [root], store them into [resolutionContexts] helper.
 * 2. Traverse [root], resolve each [ResolvableNode], store the result into [resolvedNodes] helper.
 *    If resolution fails, report and store [ErrorNode].
 *    Invoke resolution recursively on the resolution result (DFS-like). Detect loops.
 * 3. Copy-transform the [root], replacing [ResolvableNode]s with their resolution results from [resolvedNodes].
 *    Do that only if the resolved subtrees do not contain references themselves.
 *
 * The resulting tree may still contain [ResolvableNode]s, because they were not resolved to a final value.
 * However, they all have been "dry-run" resolved in a type-only mode, so they are guaranteed to be valid.
 */
private class ReferenceResolutionSession(
    val root: RefinedTreeNode,
) {
    /** A list of cascading maps/objects from the root node that contain a particular ResolvableNode */
    private typealias ResolutionContext = List<RefinedMappingNode>

    /*
      WARNING: This is sensitive to `ResolvableNode`s instances identity, because they are used as keys.
      Technically, the same reference node may be present in different tree locations more than once in the same tree.
      We don't have such cases yet, but if we do, this implementation will behave incorrectly.
    */
    private val resolutionContexts: Map<ResolvableNode, ResolutionContext> = buildMap {
        object : RecurringRefinedTreeVisitorUnit() {
            val ancestorMappings = mutableListOf<RefinedMappingNode>()

            override fun visitMap(node: RefinedMappingNode) {
                // Maintain the `ancestorMappings` list
                ancestorMappings.add(node)
                super.visitMap(node)
                ancestorMappings.removeLast()
            }

            override fun visitReference(node: ReferenceNode) {
                put(node, ancestorMappings.toList())
            }

            override fun visitStringInterpolation(node: StringInterpolationNode) {
                put(node, ancestorMappings.toList())
            }
        }.visit(root)
    }

    // null means resolution happened in type-only mode or the resolved value contained some nested references.
    // This means that the corresponding key node should not be replaced with anything and left as is for now.
    // In the case of errors, `ErrorNode` is here, not null.
    private val resolvedNodes = mutableMapOf<ResolvableNode, RefinedTreeNode?>()  // black
    private val currentlyResolvingStack = mutableListOf<ResolvableNode>()        // gray

    /**
     * Main entry-point
     */
    context(_: ProblemReporter)
    fun perform(): RefinedTreeNode {
        // Perform the resolution DFS, detecting loops
        ensureEverythingInSubtreeResolved(root)
        // As a result, we have `resolvedNodes` populated.

        // Now make a tree copy, substituting all the fully resolved references, where applicable
        //  (those without holes and without transitive unresolved references)
        return object : RefinedTreeTransformer() {
            override fun visitReference(node: ReferenceNode) = resolvedOrSelf(node)
            override fun visitStringInterpolation(node: StringInterpolationNode) = resolvedOrSelf(node)
            private fun resolvedOrSelf(node: ResolvableNode): RefinedTreeNode {
                // If the resolved subtree contains
                val resolvedNode = resolvedNodes[node] ?: return node
                return visit(resolvedNode)!!
            }
        }.visit(root) as RefinedMappingNode
    }

    context(reporter: ProblemReporter)
    private fun ensureResolved(resolvableNode: ResolvableNode, context: ResolutionContext) {
        if (resolvableNode in resolvedNodes) {
            return // already resolved - OK
        }

        if (resolvableNode in currentlyResolvingStack) {
            // Loop detected
            resolvedNodes[resolvableNode] = ErrorNode(resolvableNode)

            val loop = currentlyResolvingStack.dropWhile { it != resolvableNode }
            val source = MultipleLocationsBuildProblemSource(
                sources = loop.mapNotNull { it.trace.asBuildProblemSource() as? FileBuildProblemSource }.toList(),
                groupingMessage = SchemaBundle.message("validation.reference.resolution.loops.grouping")
            )
            reporter.reportBundleError(
                source = source,
                diagnosticId = TreeDiagnosticId.ReferenceResolutionLoop,
                messageKey = "validation.reference.resolution.loops"
            )
            return
        }

        currentlyResolvingStack.add(resolvableNode)
        resolvedNodes[resolvableNode] = when (resolvableNode) {
            is ReferenceNode -> resolveReferenceNode(resolvableNode, context)
            is StringInterpolationNode -> resolveStringInterpolationNode(resolvableNode, context)
        }
        currentlyResolvingStack.removeLast()
    }

    context(reporter: ProblemReporter)
    private fun resolveReferenceNode(
        referenceNode: ReferenceNode,
        context: ResolutionContext,
    ): RefinedTreeNode? = when (val result = doResolve(referenceNode, context, referenceNode.referencedPath)) {
        is ResolutionStep.Value -> when {
            result.value.subtreeContainsResolvableNodes() -> {
                if (referenceNode.expectedType == SchemaType.UndefinedType) {
                    // Replace the type with a more precise one from the hole.
                    // Some clients (like unknown properties diagnostics) may be interested in as narrow types as possible.
                    @OptIn(DelicateSchemaTypeInferenceApi::class)
                    referenceNode.copy(expectedType = result.value.inferPossibleExpectedTypeBestEffort())
                } else null // Reference (nested) with a "hole", leave as is
            }
            else -> {
                var value = result.value
                val transform = referenceNode.transform
                val trace = if (transform != null) {
                    referenceNode.transformedTrace(value, transform)
                } else {
                    referenceNode.resolvedTrace(value)
                }
                if (transform != null) {
                    value = Default.Static(transform.function.transform(value))
                        .toTreeValue(referenceNode.expectedType, trace)
                }

                value = referenceNode.expectedType.cast(value) ?: run {
                    reporter.reportBundleError(
                        source = referenceNode.trace.asBuildProblemSource(),
                        diagnosticId = TreeDiagnosticId.ReferenceHasUnexpectedType,
                        messageKey = "validation.reference.unexpected.type",
                        renderTypeOf(value),
                        referenceNode.expectedType.render(includeSyntax = false)
                    )
                    ErrorNode(referenceNode)
                }
                value.copyWithTrace(trace = trace)
            }
        }
        is ResolutionStep.Hole -> if (referenceNode.expectedType.isAssignableFrom(result.type)) {
            if (referenceNode.expectedType == SchemaType.UndefinedType) {
                // Replace the type with a more precise one from the hole.
                // Some clients (like unknown properties diagnostics) may be interested in as narrow types as possible.
                referenceNode.copy(expectedType = result.type)
            } else null  // Type-check - OK, leave as is
        } else {
            reporter.reportBundleError(
                source = referenceNode.trace.asBuildProblemSource(),
                diagnosticId = TreeDiagnosticId.ReferenceHasUnexpectedType,
                messageKey = "validation.reference.unexpected.type",
                result.type.render(includeSyntax = false),
                referenceNode.expectedType.render(includeSyntax = false)
            )
            ErrorNode(referenceNode)
        }
        null -> ErrorNode(referenceNode)
    }

    context(reporter: ProblemReporter)
    private fun resolveStringInterpolationNode(
        interpolationNode: StringInterpolationNode,
        context: ResolutionContext,
    ): LeafTreeNode? {
        val resolvedReferenceParts: Map<StringInterpolationNode.Part.Reference, ResolutionStep?> = interpolationNode.parts
            .filterIsInstance<StringInterpolationNode.Part.Reference>()
            .distinct()  // Resolve identical parts only once
            .associateWith { doResolve(interpolationNode, context, it.referencePath) }

        val resolvedParts = interpolationNode.parts.map { part ->
            when (part) {
                is StringInterpolationNode.Part.Reference -> {
                    when (val result = resolvedReferenceParts.getValue(part)) {
                        is ResolutionStep.Hole -> {
                            if (StringInterpolationExpectedType.isAssignableFrom(result.type)) {
                                // Type-check - OK, leave the same part unresolved
                                part
                            } else {
                                reporter.reportBundleError(
                                    source = interpolationNode.trace.asBuildProblemSource(),
                                    diagnosticId = TreeDiagnosticId.ReferenceCannotBeUsedInStringInterpolation,
                                    messageKey = "validation.reference.unexpected.type.interpolation",
                                    result.type.render(includeSyntax = false),
                                )
                                null
                            }
                        }
                        is ResolutionStep.Value -> when (
                            val value = StringInterpolationExpectedType.cast(result.value)
                        ) {
                            is StringNode -> {
                                StringInterpolationNode.Part.Text(value.value)
                            }
                            is ErrorNode -> {
                                // Type-check (cast) succeeded, but the node is not a string, then
                                // something went wrong upstream (e.g., transitive resolution failed),
                                // so quietly fail here
                                null
                            }
                            null -> {  // Type-check failed
                                reporter.reportBundleError(
                                    source = interpolationNode.trace.asBuildProblemSource(),
                                    diagnosticId = TreeDiagnosticId.ReferenceCannotBeUsedInStringInterpolation,
                                    messageKey = "validation.reference.unexpected.type.interpolation",
                                    renderTypeOf(result.value),
                                )
                                null
                            }
                            is ResolvableNode -> part  // transitive reference with holes
                            else -> error("Unexpected node after `cast`: $value")
                        }
                        null -> null
                    }
                }
                is StringInterpolationNode.Part.Text -> part
            }
        }

        @OptIn(DelicateSchemaTypeInferenceApi::class)
        val preciseExpectedType = when (val type = interpolationNode.expectedType) {
            is SchemaType.UndefinedType -> {
                // Use "path" type if at least one reference part resolves to a path
                val resolvesToAnyPaths = resolvedReferenceParts.any { (_, result) ->
                    when (result) {
                        is ResolutionStep.Hole -> result.type
                        is ResolutionStep.Value -> result.value.inferPossibleExpectedTypeBestEffort()
                        null -> null
                    } is SchemaType.PathType
                }
                if (resolvesToAnyPaths) SchemaType.PathType else SchemaType.StringType
            }
            else -> type
        }

        return when {
            // At least one part is unresolved - the whole interpolation becomes an error node
            resolvedParts.anyNull() -> ErrorNode(interpolationNode, expectedType = preciseExpectedType)

            // Partial resolution - update the node with the resolved parts
            resolvedParts.any { it is StringInterpolationNode.Part.Reference } -> {
                if (interpolationNode.expectedType != preciseExpectedType) {
                    // Replace the type with a more precise one from the hole.
                    // Some clients (like unknown properties diagnostics) are interested in as narrow types as possible.
                    interpolationNode.copy(expectedType = preciseExpectedType)
                } else null  // Contains holes, keep as is
            }
            else -> {  // All parts are resolved - do the interpolation
                val interpolated = resolvedParts.joinToString(separator = "") {
                    (it as StringInterpolationNode.Part.Text).text
                }

                val trace = TransformedValueTrace(
                    description = "string interpolation: ${interpolationNode.parts.joinToString("") {
                        when(it) {
                            is StringInterpolationNode.Part.Reference -> $$"${$${it.referencePath.joinToString(".")}}"
                            is StringInterpolationNode.Part.Text -> it.text
                        }
                    }}",
                    definitionTrace = interpolationNode.trace,
                    // TODO: Support multi-source traces
                    // first() is safe because of StringInterpolationValue contract.
                    sourceValue = resolvedReferenceParts.mapNotNull { it.value as? ResolutionStep.Value }.first().value,
                )

                when (preciseExpectedType) {
                    is SchemaType.PathType -> try {
                        PathNode(Path(interpolated).normalize(), trace, interpolationNode.contexts)
                    } catch (e: InvalidPathException) {
                        /*
                         TODO: To think. Now, this is tricky, because formally we violate the type contract here,
                          as we allow arbitrary strings to be "assigned" to a path value.
                         CASE: The plugin author exposes a *string* via the settings, but this string
                         is used as a path element in the plugin.yaml.
                         Then if the user specifies a character that is not usable in the
                         path, then it gets an error from inside the `plugin.yaml`. That's probably not ideal.
                         The ideal behavior is that no user input could lead to errors in the `plugin.yaml` itself.
                         Ideally, we allow only paths to be interpolated to paths. But this doesn't work now,
                         as relative paths are not supported in the parser - it always resolves them to absolute ones.
                         */
                        reporter.reportBundleError(
                            source = trace.asBuildProblemSource(),
                            diagnosticId = TreeDiagnosticId.InvalidPath,
                            messageKey = "validation.types.invalid.path",
                            e.message,
                        )
                        ErrorNode(interpolationNode.expectedType, trace, interpolationNode.contexts)
                    }
                    is SchemaType.StringType -> {
                        StringNode(interpolated, preciseExpectedType.semantics, trace, interpolationNode.contexts)
                    }
                    is SchemaType.UndefinedType -> error("Not reached: should be replaced with a precise type above")
                }
            }
        }
    }

    context(_: ProblemReporter)
    private fun ensureEverythingInSubtreeResolved(node: RefinedTreeNode): RefinedTreeNode {
        object : RecurringRefinedTreeVisitorUnit() {
            override fun visitReference(node: ReferenceNode) =
                ensureResolved(node, resolutionContexts[node]!!)
            override fun visitStringInterpolation(node: StringInterpolationNode) =
                ensureResolved(node, resolutionContexts[node]!!)
        }.visit(node)

        // If the node was resolvable, return the updated node.
        return resolvedNodes[node] ?: node
    }

    context(reporter: ProblemReporter)
    private fun doResolve(
        origin: ResolvableNode,
        resolutionContext: ResolutionContext,
        referencePath: List<String>,
    ): ResolutionStep? {
        val firstElement = referencePath.first()

        // Find a starting node
        val startingEdge: ResolutionEdge = resolutionContext.asReversed().firstNotNullOfOrNull {
            it.resolveEdge(firstElement)
        } ?: run {
            reporter.reportBundleError(
                origin.trace.asBuildProblemSource(),
                diagnosticId = TreeDiagnosticId.ReferenceResolutionRootNotFound,
                "validation.reference.resolution.root.not.found",
                firstElement,
            )
            return null
        }

        // Iterate over `referencePath` building a list of `ResolutionEdge`.
        val resolvedEdges: List<ResolutionEdge> = buildList {
            add(startingEdge)
            for (refPart in referencePath.drop(1)) {
                this@buildList += when (val currentStep = last().step) {
                    is ResolutionStep.Hole -> doResolveEdgeFromHole(currentStep, refPart, origin)
                    is ResolutionStep.Value -> doResolveEdgeFromValue(currentStep, refPart, origin)
                } ?: break
            }
        }

        if (resolvedEdges.size < referencePath.size) {
            // The final value/type is not resolved, no need to check further
            return null
        }

        val lastResolvedEdge = resolvedEdges.last()

        if (lastResolvedEdge is ResolutionEdge.Property && !lastResolvedEdge.property.canBeReferenced) {
            reporter.reportBundleError(
                source = origin.trace.asBuildProblemSource(),
                diagnosticId = TreeDiagnosticId.NonReferenceableElement,
                messageKey = "validation.reference.resolution.not.referencable", lastResolvedEdge.text,
            )
            return null
        }

        return when (val step = lastResolvedEdge.step) {
            is ResolutionStep.Hole -> step
            is ResolutionStep.Value -> {
                // Here is the recursion of the algorithm.
                // The resolved value itself may be a reference, so we replace the `value`.
                step.copy(value = ensureEverythingInSubtreeResolved(step.value))
            }
        }
    }

    context(reporter: ProblemReporter)
    private fun doResolveEdgeFromValue(
        currentStep: ResolutionStep.Value,
        refPart: String,
        origin: ResolvableNode,
    ): ResolutionEdge? = when (val value = currentStep.value) {
        is RefinedMappingNode -> value.resolveEdge(refPart).also {
            if (it == null) {
                val where = when (val declaration = value.declaration) {
                    null -> "the map"
                    else -> "type `${declaration.displayName}`"
                }
                reporter.reportBundleError(
                    source = origin.trace.asBuildProblemSource(),
                    diagnosticId = TreeDiagnosticId.UnresolvedReference,
                    messageKey = "validation.reference.resolution.not.found",
                    refPart, where,
                )
            }
        }
        is ReferenceNode -> {
            check(value in resolvedNodes) { "Not reached: value should already have been visited" }
            val resolved = resolvedNodes[value]
            if (resolved == null) {
                // Transition to hole resolution
                doResolveEdgeFromHole(ResolutionStep.Hole(value.expectedType), refPart, origin)
            } else {
                // Continue value resolution
                doResolveEdgeFromValue(ResolutionStep.Value(resolved), refPart, origin)
            }
        }
        else -> {
            reporter.reportBundleError(
                source = origin.trace.asBuildProblemSource(),
                diagnosticId = TreeDiagnosticId.ReferenceSegmentIsNotMapping,
                messageKey = "validation.reference.resolution.not.a.mapping",
                refPart, renderTypeOf(value),
            )
            null
        }
    }

    context(reporter: ProblemReporter)
    private fun doResolveEdgeFromHole(
        currentStep: ResolutionStep.Hole,
        refPart: String,
        origin: ResolvableNode,
    ): ResolutionEdge? = when (val type = currentStep.type) {
        is SchemaType.ObjectType -> when {
            type.isMarkedNullable -> {
                reporter.reportBundleError(
                    source = origin.trace.asBuildProblemSource(),
                    diagnosticId = TreeDiagnosticId.ReferenceMemberAccessOnNullable,
                    messageKey = "validation.reference.resolution.nullable.access",
                    type.render(includeSyntax = false),
                )
                null
            }
            else -> type.declaration.resolveEdge(refPart).also {
                if (it == null) {
                    reporter.reportBundleError(
                        source = origin.trace.asBuildProblemSource(),
                        diagnosticId = TreeDiagnosticId.UnresolvedReference,
                        messageKey = "validation.reference.resolution.not.found.type",
                        refPart, type.render(includeSyntax = false),
                    )
                }  // else - GOOD PATH
            }
        }
        // NOTE: We don't handle SchemaType.MapType here, as we can't assume any keys are present.
        else -> {
            reporter.reportBundleError(
                source = origin.trace.asBuildProblemSource(),
                diagnosticId = TreeDiagnosticId.ReferenceSegmentIsNotMapping,
                messageKey = "validation.reference.resolution.not.an.object",
                refPart, type.render(includeSyntax = false),
            )
            null
        }
    }

    private fun RefinedTreeNode.subtreeContainsResolvableNodes(): Boolean = when(this) {
        is RefinedListNode -> children.any { it.subtreeContainsResolvableNodes() }
        is RefinedMappingNode -> children.any { it.value.subtreeContainsResolvableNodes() }
        is ResolvableNode -> resolvedNodes[this]?.subtreeContainsResolvableNodes() ?: true
        else -> false
    }
}

private fun RefinedMappingNode.resolveEdge(
    text: String,
): ResolutionEdge? = when (val property = declaration?.getProperty(text)) {
    // Map or unknown property in an object
    null -> when (val keyValue = refinedChildren[text]) {
        null -> null
        else -> ResolutionEdge.Key(ResolutionStep.Value(keyValue.value), text)
    }
    // Known object property
    else -> when (val keyValue = refinedChildren[text]) {
        null -> ResolutionEdge.Property(ResolutionStep.Hole(property.type), property)
        else -> ResolutionEdge.Property(ResolutionStep.Value(keyValue.value), property)
    }
}

private fun SchemaObjectDeclaration.resolveEdge(
    text: String,
): ResolutionEdge? = getProperty(text)?.let {
    ResolutionEdge.Property(ResolutionStep.Hole(it.type), it)
}

private val StringInterpolationExpectedType = SchemaType.StringType

@Suppress("KotlinConstantConditions")
private fun <T : Any> List<T?>.anyNull(): Boolean {
    contract { returns(false) implies (this@anyNull is List<T>) }
    return any { it == null }
}
