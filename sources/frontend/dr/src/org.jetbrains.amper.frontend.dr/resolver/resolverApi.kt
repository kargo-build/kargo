/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.frontend.dr.resolver

import io.opentelemetry.api.OpenTelemetry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.CacheEntryKey
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyGraphContext
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.DependencyNodeHolder
import org.jetbrains.amper.dependency.resolution.DependencyNodeHolderWithContext
import org.jetbrains.amper.dependency.resolution.DependencyNodeReference
import org.jetbrains.amper.dependency.resolution.DependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.FileCacheBuilder
import org.jetbrains.amper.dependency.resolution.IncrementalCacheUsage
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.ResolutionConfig
import org.jetbrains.amper.dependency.resolution.ResolutionConfigPlain
import org.jetbrains.amper.dependency.resolution.ResolutionLevel
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.ResolvedGraph
import org.jetbrains.amper.dependency.resolution.RootDependencyNode
import org.jetbrains.amper.dependency.resolution.RootDependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNodeHolderBase
import org.jetbrains.amper.dependency.resolution.currentGraphContext
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.nodeParents
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.api.BuiltinCatalogTrace
import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.PsiTrace
import org.jetbrains.amper.frontend.api.ResolvedReferenceTrace
import org.jetbrains.amper.frontend.api.TransformedValueTrace
import org.jetbrains.amper.frontend.dr.resolver.flow.toPlatform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import kotlin.error

val moduleDependenciesResolver: ModuleDependenciesResolver = ModuleDependenciesResolverImpl()

enum class ResolutionDepth {
    GRAPH_ONLY,
    GRAPH_WITH_DIRECT_DEPENDENCIES,
    GRAPH_FULL
}

data class ResolutionInput(
    val dependenciesFlowType: DependenciesFlowType,
    val resolutionDepth: ResolutionDepth,
    val resolutionLevel: ResolutionLevel = ResolutionLevel.NETWORK,
    val downloadSources: Boolean = false,
    val incrementalCacheUsage: IncrementalCacheUsage = IncrementalCacheUsage.USE,
    val fileCacheBuilder: FileCacheBuilder.() -> Unit,
    val openTelemetry: OpenTelemetry? = null,
    val incrementalCache: IncrementalCache? = null,
)

sealed interface DependenciesFlowType {
    data class ClassPathType(
        val scope: ResolutionScope,
        val platforms: Set<ResolutionPlatform>,
        val isTest: Boolean,
        val includeNonExportedNative: Boolean = true,
    ) : DependenciesFlowType

    data class IdeSyncType(val aom: Model) : DependenciesFlowType
}

interface ModuleDependenciesResolver {
    // todo (AB) : [AMPER-4905] Move to ModuleDependencies
    fun AmperModule.resolveDependenciesGraph(
        dependenciesFlowType: DependenciesFlowType.ClassPathType,
        fileCacheBuilder: FileCacheBuilder.() -> Unit,
        openTelemetry: OpenTelemetry?,
        incrementalCache: IncrementalCache?
    ): ModuleDependencyNodeWithModuleAndContext

    @Deprecated("[AMPER-4905] To be removed")
    fun List<AmperModule>.resolveDependenciesGraph(
        dependenciesFlowType: DependenciesFlowType,
        fileCacheBuilder: FileCacheBuilder.() -> Unit,
        openTelemetry: OpenTelemetry?,
        incrementalCache: IncrementalCache?
    ): RootDependencyNodeWithContext

    /**
     * @return dependency node representing the root of the resolved graph
     *
     * todo (AB) : This functional expose low-level API
     * todo (AB) : making calling side to prepare DependencyNodeHolderWithContext with properly initialized context.
     * todo (AB) : Though this method makes sense in general, it is not a part of module resolver.
     *  It should be split on two parts:
     *   1. Resolution of maven coordinates (a method that takes the list of maven coordinates and resolution parameters
     *      and create context and nodes inside, hiding those details from caller)
     *   2. Resolution of [Classpath] used in plugins
     *      (a method that takes list of modules and external dependencies and resolve it module-wide aligning version between compile/runtime)
     *
     */
    @Deprecated("[AMPER-4905] To be redesigned")
    suspend fun DependencyNodeHolderWithContext.resolveDependencies(
        resolutionDepth: ResolutionDepth,
        resolutionLevel: ResolutionLevel = ResolutionLevel.NETWORK,
        downloadSources: Boolean = false,
        incrementalCacheUsage: IncrementalCacheUsage = IncrementalCacheUsage.USE,
    ): ResolvedGraph

    @Deprecated("[AMPER-4905] Use resolveModuleDependencies instead. To be removed")
    suspend fun AmperModule.resolveDependencies(resolutionInput: ResolutionInput): ModuleDependencyNode

    @Deprecated("[AMPER-4905] in favour of [resolveModuleDependencies]")
    suspend fun List<AmperModule>.resolveDependencies(resolutionInput: ResolutionInput): DependencyNode

    @Deprecated("[AMPER-4905] To be removed")
    suspend fun List<AmperModule>.resolveModuleDependencies(
        resolutionInput: ResolutionInput,
        userCacheRoot: AmperUserCacheRoot, // todo (AB) : Looks like a part of [ResolutionInput]
        leafPlatformsOnly: Boolean = false,
        filter: ModuleResolutionFilter? = null,
        resolutionType: ResolutionType,
    ): ResolvedGraph
}

/**
 * Filter resolution results.
 * Resolution is executed module wide, aligning versions for all platforms' dependencies and across RUNTIME/COMPILE scopes.
 *
 * This filter is intended to be used If the caller needs resolution results for specific platforms/scope only.
 */
data class ModuleResolutionFilter(
    val scope: ResolutionScope? = null,
    val platforms: Set<ResolutionPlatform>? = null,
)

abstract class DependencyNodeHolderWithNotationAndContext(
    graphEntryName: String,
    children: List<DependencyNodeWithContext>,
    templateContext: Context,
    open val notation: Notation? = null,
    parentNodes: Set<DependencyNodeWithContext> = emptySet(),
) : DependencyNodeHolderWithContext(graphEntryName, children, templateContext, parentNodes)

interface ModuleDependencyNode: DependencyNodeHolder {
    val moduleName: String
    val notation: LocalModuleDependency?
    val isForTests: Boolean
    val resolutionConfig: ResolutionConfig

    fun attachToNewRoot(parent: RootDependencyNode)
}

fun ModuleDependencyNode.getKey(): Key<DependencyNodeHolder> = Key<DependencyNodeHolder>(
    CacheEntryKey.CompositeCacheEntryKey(
        listOf(
        moduleName,
        isForTests,
        resolutionConfig.scope,
        resolutionConfig.platforms
    )).computeKey()
)

class ModuleDependencyNodeWithModuleAndContext(
    val module: AmperModule,
    override val isForTests: Boolean,
    children: List<DependencyNodeWithContext>,
    templateContext: Context,
    override val notation: LocalModuleDependency? = null,
    parentNodes: Set<DependencyNodeWithContext> = emptySet(),
    topLevel: Boolean,
) : ModuleDependencyNode,
    DependencyNodeHolderWithNotationAndContext(
        module.getGraphEntryName(topLevel, isForTests, templateContext.settings),
        children, templateContext, notation, parentNodes = parentNodes)
{
    override val moduleName = module.userReadableName

    override val resolutionConfig: ResolutionConfig
        get() = context.settings

    override val cacheEntryKey: CacheEntryKey.CompositeCacheEntryKey
        get() = CacheEntryKey.CompositeCacheEntryKey(listOf
            (module.uniqueModuleKey(),
            isForTests,
            context.settings.scope,
            context.settings.platforms))

    override fun attachToNewRoot(parent: RootDependencyNode) {
        context.nodeParents.clear()
        context.nodeParents.add(parent)
    }

    override val key = getKey()
}

private fun AmperModule.getGraphEntryName(
    topLevel: Boolean,
    isForTests: Boolean,
    resolutionConfig: ResolutionConfig,
): String {
    val moduleName = StringBuilder("Module ${this.userReadableName}")
    if (topLevel) {
        moduleName
            .append("\n")
            .append(
                """│ - ${if (isForTests) "test" else "main"}
                  |│ - scope = ${resolutionConfig.scope.name}
                  |│ - platforms = [${resolutionConfig.platforms.joinToString { it.toPlatform().pretty }}]
                  """.trimMargin()
            )
    }
    return moduleName.toString()
}

@Serializable
@SerialName("ModuleDN")
internal class SerializableModuleDependencyNodeWithModule internal constructor(
    override val moduleName: String,
    override val graphEntryName: String,
    override val isForTests: Boolean,
    override val resolutionConfig: ResolutionConfigPlain,
    override val childrenRefs: List<DependencyNodeReference> = mutableListOf(),
    @Transient
    private val graphContext: DependencyGraphContext = currentGraphContext(),
): ModuleDependencyNode, SerializableDependencyNodeHolderBase(graphContext) {

    override val messages: List<Message> = emptyList()

    @Transient
    override var notation: LocalModuleDependency? = null

    override fun attachToNewRoot(parent: RootDependencyNode) {
        parents.clear()
        parents.add(parent)
    }

    override val key by lazy { getKey() }

}

interface DirectFragmentDependencyNode: DependencyNodeHolder {
    val fragmentName: String
    val dependencyNode: DependencyNode
    val notation: MavenDependencyBase
}

class DirectFragmentDependencyNodeHolderWithContext(
    override val dependencyNode: MavenDependencyNodeWithContext,
    val fragment: Fragment,
    templateContext: Context,
    override val notation: MavenDependencyBase,
    parentNodes: Set<DependencyNodeWithContext> = emptySet(),
    override val messages: List<Message> = emptyList(),
) : DirectFragmentDependencyNode,
    DependencyNodeHolderWithNotationAndContext(
        graphEntryName = "${fragment.module.userReadableName}:${fragment.name}:${dependencyNode}${traceInfo(notation)}",
        children = listOf(dependencyNode), templateContext, notation, parentNodes = parentNodes
) {
    override val fragmentName: String = fragment.name

    override val cacheEntryKey: CacheEntryKey.CompositeCacheEntryKey
        get() = CacheEntryKey.CompositeCacheEntryKey(listOf(
            fragment.module.uniqueModuleKey(),
            fragment.name,
            dependencyNode.context.settings.scope,
            dependencyNode.context.settings.platforms,
        ))
}

private fun traceInfo(notation: Notation): String {
    val sourceInfo = when (val trace = notation.trace) {
        is DefaultTrace -> "implicit"
        is TransformedValueTrace -> "implicit (${trace.description})"
        is ResolvedReferenceTrace -> trace.description
        is BuiltinCatalogTrace -> null // should never happen for dependency Notation
        // TODO maybe write something if the dependency comes from a template?
        is PsiTrace -> null // we don't want to clutter the output for 'regular' dependencies declared in files
    }
    return sourceInfo?.let { ", $it" } ?: ""
}

@Serializable
@SerialName("DirectDN")
internal class SerializableDirectFragmentDependencyNodeHolder internal constructor(
    override val fragmentName: String,
    override val graphEntryName: String,
    override val messages: List<Message> = emptyList(),
    override val childrenRefs: List<DependencyNodeReference> = mutableListOf(),
    @Transient
    private val graphContext: DependencyGraphContext = currentGraphContext()
): DirectFragmentDependencyNode, SerializableDependencyNodeHolderBase(graphContext) {

    lateinit var dependencyNodeRef: DependencyNodeReference

    override val dependencyNode: MavenDependencyNode by lazy {
        dependencyNodeRef.toNodePlain(graphContext)
            .let {
                it as? MavenDependencyNode
                    ?: error("Unexpected dependency node type [${it::class.simpleName}]")
            }
    }

    @Transient
    override lateinit var notation: MavenDependencyBase
}