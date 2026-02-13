/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.diagnostics.reporters

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.MavenDependency
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.filterGraph
import org.jetbrains.amper.dependency.resolution.group
import org.jetbrains.amper.dependency.resolution.module
import org.jetbrains.amper.dependency.resolution.orUnspecified
import org.jetbrains.amper.dependency.resolution.originalVersion
import org.jetbrains.amper.dependency.resolution.resolvedVersion
import org.jetbrains.amper.dependency.resolution.version
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.frontend.dr.resolver.DirectFragmentDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.FrontendDrBundle
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.DrDiagnosticsReporter
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.DrReporterContext
import org.jetbrains.amper.frontend.dr.resolver.flow.IdeSync
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.annotations.Nls
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(IdeSync::class.java)

open class OverriddenDirectModuleDependencies : DrDiagnosticsReporter {
    override val level = Level.Warning

    override fun reportBuildProblemsForNode(
        node: DependencyNode,
        problemReporter: ProblemReporter,
        level: Level,
        context: DrReporterContext,
    ) {
        if (node !is DirectFragmentDependencyNode) return
        val dependencyNode = node.dependencyNode as? MavenDependencyNode ?: return

        val moduleName = node.parents.filterIsInstance<ModuleDependencyNode>().singleOrNull()?.moduleName ?: return
        val isForTestsModule = node.parents.filterIsInstance<ModuleDependencyNode>().singleOrNull()?.isForTests ?: return
        if (isForTestsModule) return // do not report diagnostic for tests (avoiding double calculation of insights for test/main resolution)

        val originalVersion = dependencyNode.originalVersion() ?: return

        if (originalVersion != dependencyNode.resolvedVersion()) {
            // for every direct module dependency referencing this dependency node

            // We prefer trace of the coordinates to the trace of the notation as it's more specific.
            // E.g., in case of implicit dependencies it prefers 'version' over 'enabled'.
            val psiElement = node.notation.coordinates.extractPsiElementOrNull() ?: node.notation.extractPsiElementOrNull()
            // TODO: This is somewhat bad, because if we messed up with traces, the override goes unnoticed and might leave a user perplexed in the runtime.
            if (psiElement != null) {
                val insightsCache = context.cache.computeIfAbsent(insightsCacheKey) { mutableMapOf() }
                val dependencyInsight = insightsCache.computeIfAbsent(
                    DependencyInsightKey(dependencyNode.key, moduleName, isForTestsModule)) {
                    // todo (AB) : This call assume that conflict resolution is globally applied to the entire graph.
                    // todo (AB) : If graph contains more than one cluster of nodes resolved with help of different
                    // todo (AB) : conflict resolvers, the code won't work any longer.
                    // todo (AB) : Rule of thumb: this method should be called on the complete (!) subgraph that contains
                    // todo (AB) : all nodes resolved with the same conflict resolver.
                    timedBlocking(
                        "insight ($moduleName-${dependencyNode.group}:${dependencyNode.module})"
                    ) {
                        context.graphRoot.filterGraph(
                            dependencyNode.group,
                            dependencyNode.module,
                            resolvedVersionOnly = true,
                        )
                    }
                }
                problemReporter.reportMessage(
                    ModuleDependencyWithOverriddenVersion(
                        node,
                        overrideInsight = dependencyInsight,
                        psiElement
                    )
                )
            }
        }
    }

    companion object {
        private val insightsCacheKey =
            Key<MutableMap<DependencyInsightKey, DependencyNode>>("OverriddenDirectModuleDependencies::insightsCache")

        // todo (AB): [AMPER-4905] Remove on final cleanup
        private fun <T> timedBlocking(text: String, block: () -> T): T {
            val start = System.currentTimeMillis()
            return try {
                block()
            } finally {
                val end = System.currentTimeMillis()
                logger.debug("#######################")
                logger.debug("Execution time: ${end - start} ms ($text)")
            }
        }
    }
}

private data class DependencyInsightKey(
    val key: Key<MavenDependency>,
    val moduleName: String,
    val isForTests: Boolean
)

class ModuleDependencyWithOverriddenVersion(
    @field:UsedInIdePlugin
    val originalNode: DirectFragmentDependencyNode,
    @field:UsedInIdePlugin
    val overrideInsight: DependencyNode,
    @field:UsedInIdePlugin
    override val element: PsiElement,
) : PsiBuildProblem(Level.Warning, BuildProblemType.Generic) {
    val dependencyNode: MavenDependencyNode
        get() = originalNode.dependencyNode as MavenDependencyNode
    val originalVersion: String
        get() = dependencyNode.originalVersion().orUnspecified()
    val effectiveVersion: String
        get() = dependencyNode.dependency.version.orUnspecified()
    val effectiveCoordinates: String
        get() = dependencyNode.key.name

    override val diagnosticId: DiagnosticId = FrontendDiagnosticId.DependencyVersionIsOverridden
    override val message: @Nls String
        get() = when {
            dependencyNode.originalVersion != null -> FrontendDrBundle.message(
                messageKey = "dependency.version.is.overridden",
                dependencyNode.originalVersion, effectiveCoordinates, effectiveVersion
            )
            dependencyNode.versionFromBom != null -> FrontendDrBundle.message(
                messageKey = VERSION_FROM_BOM_IS_OVERRIDDEN_MESSAGE_ID,
                dependencyNode.versionFromBom, effectiveCoordinates, effectiveVersion
            )
            else -> error ("Version is not specified, should never happen at this stage")
        }

    companion object {
        const val VERSION_FROM_BOM_IS_OVERRIDDEN_MESSAGE_ID = "dependency.version.from.bom.is.overridden"
    }
}
