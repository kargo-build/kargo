/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.CacheEntryKey
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyGraph
import org.jetbrains.amper.dependency.resolution.DependencyGraph.Companion.toGraph
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.DependencyNodeHolderWithContext
import org.jetbrains.amper.dependency.resolution.FileCacheBuilder
import org.jetbrains.amper.dependency.resolution.GraphJson
import org.jetbrains.amper.dependency.resolution.IncrementalCacheUsage
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenLocal
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.amper.dependency.resolution.Repository
import org.jetbrains.amper.dependency.resolution.ResolutionLevel
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.ResolvedGraph
import org.jetbrains.amper.dependency.resolution.Resolver
import org.jetbrains.amper.dependency.resolution.RootDependencyNode
import org.jetbrains.amper.dependency.resolution.RootDependencyNodeStub
import org.jetbrains.amper.dependency.resolution.RootDependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNode
import org.jetbrains.amper.dependency.resolution.SerializableRootDependencyNode
import org.jetbrains.amper.dependency.resolution.asRootCacheEntryKey
import org.jetbrains.amper.dependency.resolution.getDependenciesGraphInput
import org.jetbrains.amper.dependency.resolution.infoSpanBuilder
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RepositoriesModulePart
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.moduleDependencies
import org.jetbrains.amper.frontend.dr.resolver.flow.defaultRepositories
import org.jetbrains.amper.frontend.dr.resolver.flow.toRepository
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.ResultWithSerializable
import org.jetbrains.amper.incrementalcache.execute
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString

private val logger = LoggerFactory.getLogger(ModuleDependencies::class.java)

/**
 * Provides dependencies graphs for all module fragments and for leaf platforms.
 *
 * Graphs are built based on AOM and are unresolved.
 * (i.e., only effective direct dependencies of modules are included,
 *  external transitive dependencies are not resolved and are absent in the resulting graphs,
 *  constructing unresolved graphs is done without NETWORK access)
 */
class ModuleDependencies(
    val module: AmperModule,
    internal val userCacheRoot: AmperUserCacheRoot,
    internal val incrementalCache: IncrementalCache?,
    internal val openTelemetry: OpenTelemetry?,
    internal val includeNonExportedNative: Boolean = true,
    internal val fileCacheBuilder: FileCacheBuilder.() -> Unit = { getAmperFileCacheBuilder(userCacheRoot) }
) {

    private val mainDepsPerFragment: Map<Fragment, PerFragmentDependencies> =
        module.perFragmentDependencies( false, userCacheRoot, incrementalCache)

    private val testDepsPerFragment: Map<Fragment, PerFragmentDependencies> =
        module.perFragmentDependencies( true, userCacheRoot, incrementalCache)

    private val mainDepsPerPlatforms: Map<Set<Platform>, PerFragmentDependencies>
    private val testDepsPerPlatforms: Map<Set<Platform>, PerFragmentDependencies>

    private val mainDepsPerLeafPlatform: Map<Platform, PerFragmentDependencies>
    private val testDepsPerLeafPlatform: Map<Platform, PerFragmentDependencies>

    private val contextMap: ConcurrentHashMap<ContextKey, Context> = ConcurrentHashMap<ContextKey, Context>()

    init {
        mainDepsPerPlatforms = perPlatformDependencies(false)
        testDepsPerPlatforms = perPlatformDependencies(true)

        mainDepsPerLeafPlatform = mainDepsPerPlatforms.mapNotNull { (platforms, deps) -> platforms.singleOrNull()?.let { it to deps} }.toMap()
        testDepsPerLeafPlatform = testDepsPerPlatforms.mapNotNull { (platforms, deps) -> platforms.singleOrNull()?.let { it to deps} }.toMap()
    }

    private fun perPlatformDependencies(isTest: Boolean): Map<Set<Platform>, PerFragmentDependencies> =
        buildMap {
            val depsPerFragment = if (isTest) testDepsPerFragment else mainDepsPerFragment
            depsPerFragment.forEach { (fragment, dependencies) ->
                if (fragment.fragmentDependants.none { it.target.isTest == isTest && it.target.platforms == fragment.platforms }) {
                    // most specific fragment corresponding to the platforms set
                    put(fragment.platforms, dependencies)
                }
            }
        }

    private fun AmperModule.perFragmentDependencies(
        isTest: Boolean, userCacheRoot: AmperUserCacheRoot, incrementalCache: IncrementalCache?,
    ): Map<Fragment, PerFragmentDependencies> =
        fragments
            .filter { it.isTest == isTest }
            .sortedBy { it.name }
            .associateBy(keySelector = { it }) {
                PerFragmentDependencies(it, userCacheRoot, incrementalCache, includeNonExportedNative)
            }

    /**
     * Module dependencies resolution in CLI
     * should be performed based on the entire list returned by this method at once,
     * so that versions of module dependencies are aligned across all module fragments.
     *
     * Note: For CLI, resolution of leaf-platform's dependencies is enough, there is no need to resolve
     * dependencies of other fragments (multiplatform ones) to align library versions accross the module..
     *
     * @return the root umbrella node with the list of module nodes (one root module node per platform).
     * Each node from the list contains unresolved platform-specific dependencies of the module.
     */
    fun allLeafPlatformsGraph(isForTests: Boolean): RootDependencyNodeWithContext {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformDeps = if (isForTests) testDepsPerLeafPlatform else mainDepsPerLeafPlatform

        val leafPlatformDependencies = buildList {
            perPlatformDeps.values.forEach {
                add(it.compileDeps)
                it.runtimeDeps?.let { runtimeDeps -> add(runtimeDeps) }
            }
        }

        return RootDependencyNodeWithContext(
            // If the incremental cache is on, a separate cache entry is calculated and maintained for every unique combination of parameters:
            //  - module
            //  - leaf-platforms/all-platforms mode
            //  - isTest flag
            rootCacheEntryKey = CacheEntryKey.CompositeCacheEntryKey(listOfNotNull(
                module.uniqueModuleKey(),
                "leaf platforms",
                isForTests
            )).asRootCacheEntryKey(),
            children = leafPlatformDependencies,
            templateContext = emptyContext(
                userCacheRoot = userCacheRoot,
                openTelemetry = openTelemetry,
                incrementalCache = incrementalCache
            )
        )
    }

    /**
     * @return unresolved compile/runtime module dependencies for the particular platform.
     */
    fun forPlatform(platform: Platform, isTest: Boolean): PerFragmentDependencies {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformDeps = if (isTest) testDepsPerLeafPlatform else mainDepsPerLeafPlatform
        return perPlatformDeps[platform]
            ?: error("Dependencies for $platform are not calculated")
    }

    /**
     * Module dependencies resolution in IDE
     * should be performed based on the entire list returned by this method at once,
     * so that versions of module dependencies are aligned across all module fragments.
     *
     * IDe converts every fragment into a separate module in the Workspace model.
     * The same dependency is resolved for each module fragment independently
     * (since different subsets of dependency source sets are added to the classpath
     * for different fragments depending on the list of fragment platforms).
     * This way in IDE resolution of all fragment dependencies should be done (including multiplatform ones)
     * versions of libraries should be aligned across all module fragments (the same way as it is done for CLI).
     *
     * @return the root umbrella node with the list of module nodes (one root module node per fragment).
     * Each node from the list contains unresolved platform-specific dependencies of the module fragment.
     */
    internal fun allFragmentsGraph(isForTests: Boolean, flattenGraph: Boolean): DependencyNodeHolderWithContext {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformsDeps = if (isForTests) testDepsPerPlatforms else mainDepsPerPlatforms

        return if (!flattenGraph) {
            val fragmentDependencies = buildList {
                perPlatformsDeps.values.forEach {
                    add(it.compileDeps)
                    it.runtimeDeps?.let { runtimeDeps -> add(runtimeDeps) }
                }
            }
            RootDependencyNodeWithContext(
                // If the incremental cache is on, a separate cache entry is calculated and maintained
                // for every unique combination of parameters:
                //  - module
                //  - leaf-platforms/all-platforms mode
                //  - isTest flag
                rootCacheEntryKey = CacheEntryKey.CompositeCacheEntryKey(listOfNotNull(
                    module.uniqueModuleKey(),
                    "all platforms",
                    isForTests
                )).asRootCacheEntryKey(),
                children = fragmentDependencies,
                templateContext = emptyContext(
                    userCacheRoot = userCacheRoot,
                    openTelemetry = openTelemetry,
                    incrementalCache = incrementalCache
                )
            )
        } else {
            buildList {
                perPlatformsDeps.values.forEach {
                    add(it.compileDeps.toFlatGraph(it.fragment))
                    it.runtimeDeps?.let { runtimeDeps -> add(runtimeDeps.toFlatGraph(it.fragment)) }
                }
            }
                .let {
                    // todo (AB) : It might be useful to decrease the number of changes in logic and make it step-by-step.
                    //  At first, we could keep returning plain Graph for fragment dependencies as it is done now in IdeSync.
                    //  This way, it might be simpler to adopt changes on Ide plugin side.
                    //  As the second step, CLI graph structure might be reused "as is" without flattening.
                    ModuleDependencyNodeWithModuleAndContext(
                        // ':full' is here to distinguish from CLI resolution,
                        // which is leaf-platforms only and should be a separate entry in the cache
                        isForTests = isForTests,
                        children = it.flatten(),
                        module = module,
                        templateContext = emptyContext(fileCacheBuilder, openTelemetry, incrementalCache),
                        topLevel = true,
                    )
                }
        }
    }

    private fun ModuleDependencyNodeWithModuleAndContext.toFlatGraph(fragment: Fragment) : List<DirectFragmentDependencyNodeHolderWithContext> {
        val repositories = fragment.module.getValidRepositories().toSet()

        val allMavenDeps = this
            .distinctBfsSequence()
            .filterIsInstance<DirectFragmentDependencyNodeHolderWithContext>()
            .sortedByDescending { it.fragment == this }
            .distinctBy { it.dependencyNode }
            .map {
               val context = adjustContext(fragment, it, repositories)
                it.notation.toFlattenedFragmentDirectDependencyNode(fragment, context)
            }.toList()

        return allMavenDeps
    }

    private fun adjustContext(
        fragment: Fragment,
        directFragmentDependencyNode: DirectFragmentDependencyNodeHolderWithContext,
        repositories: Set<Repository>,
    ): Context =
        if (fragment.module == directFragmentDependencyNode.fragment.module
            || repositories == directFragmentDependencyNode.dependencyNode.context.settings.repositories.toSet()
        ) {
            directFragmentDependencyNode.dependencyNode.context
        } else {
            // Dependency belongs to another module with different list of repositories
            directFragmentDependencyNode.dependencyNode.context.copyWithNewNodeCache(
                parentNodes = emptySet(),
                repositories = repositories.toList()
            )
        }

    fun AmperModule.getValidRepositories(): List<Repository> {
        val acceptedRepositories = mutableListOf<Repository>()
        for (repository in resolvableRepositories()) {
            if (repository is MavenRepository) {
                @Suppress("HttpUrlsUsage")
                if (repository.url.startsWith("http://")) {
                    // TODO: Special --insecure-http-repositories option or some flag in project.yaml
                    // to acknowledge http:// usage

                    // report only once per `url`
                    if (alreadyReportedHttpRepositories.put(repository.url, true) == null) {
                        logger.warn("http:// repositories are not secure and should not be used: ${repository.url}")
                    }

                    continue
                }

                if (!repository.url.startsWith("https://") && repository != MavenLocal) {

                    // report only once per `url`
                    if (alreadyReportedNonHttpsRepositories.put(repository.url, true) == null) {
                        logger.warn("Non-https repositories are not supported, skipping url: ${repository.url}")
                    }

                    continue
                }
            }

            acceptedRepositories.add(repository)
        }

        return acceptedRepositories
    }

    private fun AmperModule.resolvableRepositories(): List<Repository> =
        parts
            .filterIsInstance<RepositoriesModulePart>()
            .firstOrNull()
            ?.mavenRepositories
            ?.filter { it.resolve }
            ?.map { it.toRepository() }
            ?: defaultRepositories.map { it.toRepository() }


    private fun MavenDependencyBase.toFlattenedFragmentDirectDependencyNode(fragment: Fragment, context: Context): DirectFragmentDependencyNodeHolderWithContext {
        val dependencyNode = context.toMavenDependencyNode(toDrMavenCoordinates(), this is BomDependency)

        val node = DirectFragmentDependencyNodeHolderWithContext(
            dependencyNode,
            fragment = fragment,
            templateContext = context,
            notation = this,
        )

        return node
    }

    companion object {

        private val alreadyReportedHttpRepositories = ConcurrentHashMap<String, Boolean>()
        private val alreadyReportedNonHttpsRepositories = ConcurrentHashMap<String, Boolean>()

        /**
         * This method calculates unresolved graphs for every fragment of every module and
         * then performs resolution of dependencies for all project modules.
         *
         * It might be useful to cache the result of the mmethod [Model.moduleDependencies]
         * and call resoltion based on the cached List of [ModuleDependencies] instead of calling this method directly.
         */
        suspend fun Model.resolveProjectDependencies(
            resolutionInput: ResolutionInput,
            // todo (AB) : Unify approach: use either fileCacheBuilder or userCacheRoot, not both
            userCacheRoot: AmperUserCacheRoot,
            moduleDependencies: List<ModuleDependencies>? = null
        ) =
            with (resolutionInput) {
                resolveProjectDependencies(
                    moduleDependenciesList = moduleDependencies?.checkModules(this@resolveProjectDependencies)
                        ?: moduleDependencies(userCacheRoot, incrementalCache, openTelemetry),
                    resolutionInput,
                    projectRoot.root,
                )
            }

        /**
         * This is an entry point into the module-wide resolution of the project.
         * It resolves dependencies of all modules independently.
         *
         * Versions of dependency are aligned across all module main fragments and across all module test fragments.
         * I.e., different main fragments of the module can't have different versions of the same library in their resolved graphs (including transitive)
         * And the same is true for test fragments.
         * At the same time, main and test fragments of the module could have different versions of the same library
         * in their resolved graphs.
         * Note: The latter is subject to change (ideally, it should be enforced that test fragments have dependencies of the same
         * versions as main fragments, but could not cause overriding of the versions of main fragments dependencies)
         *
         * Two different modules could have different versions of the same library in their resolved graphs (including transitive).
         */
        private suspend fun resolveProjectDependencies(
            moduleDependenciesList: List<ModuleDependencies>,
            resolutionInput: ResolutionInput,
            projectRoot: Path,
        ): ResolvedGraph {
            // Wrapping into per-project cache entry" +
            // Goal: if nothing has changed, check inputs once, instead of checking inputs for every module where" +
            //       one library from shared module is checked as an input as many times as many modules depend on it transitively"
            return with (resolutionInput) {
                openTelemetry.spanBuilder("DR: Resolving project dependencies").use {
                    val moduleGraphs = buildList {
                        moduleDependenciesList.forEach {
                            //  1. Tests and main should be resolved separately in IdeSync-mode
                            add(it.allFragmentsGraph(isForTests = false, flattenGraph = true))
                            add(it.allFragmentsGraph(isForTests = true, flattenGraph = true))
                        }
                    }

                    val resolutionId = CacheEntryKey.CompositeCacheEntryKey(
                        listOf(
                            "Project dependencies",
                            projectRoot.absolutePathString()
                        )
                    ).computeKey()

                    if (incrementalCacheUsage == IncrementalCacheUsage.SKIP
                        || incrementalCache == null
                    ) {
                        resolveDependenciesBatch(moduleGraphs, null)
                    } else {
                        val graphEntryKeys = moduleGraphs.flatMap{ it.getDependenciesGraphInput() }
                        if (graphEntryKeys.contains(CacheEntryKey.NotCached)) {
                            resolveDependenciesBatch(moduleGraphs, null)
                        } else {
                            val cacheInputValues = mapOf(
                                "userCacheRoot" to moduleGraphs.first().context.settings.fileCache.amperCache.pathString,
                                "dependencies" to graphEntryKeys.joinToString("|") { "${ it.computeKey() }" },
                            )

                            incrementalCache.execute(
                                key = resolutionId,
                                inputValues = cacheInputValues,
                                inputFiles = emptyList(),
                                serializer = DependencyGraph.serializer(),
                                json = GraphJson.json,
                                forceRecalculation = (incrementalCacheUsage == IncrementalCacheUsage.REFRESH_AND_USE),
                            ) {
                                openTelemetry.spanBuilder("DR.graph:resolution multi-module")
                                    .use {
                                        // todo (AB):
                                        //  2. All resolutions should share MavenDependency cache to avoid multiple parsing of library metadata
                                        //  key should include scope, platforms and most probably repositories (think about reducing hash/equal execution time for such keys).

                                        val compositeGraph = resolveDependenciesBatch(moduleGraphs, null)
                                        val serializableGraph = compositeGraph.root.toGraph()

                                        ResultWithSerializable(
                                            outputValue = serializableGraph,
                                            outputFiles = compositeGraph.root.children.flatMap { it.dependencyPaths() },
                                            expirationTime = compositeGraph.expirationTime,
                                        )
                                    }
                            }.let { result ->
                                try {
                                    result.outputValue.root.fillNotation(
                                        RootDependencyNodeWithContext(
                                            children = moduleGraphs,
                                            templateContext = emptyContext(fileCacheBuilder, openTelemetry, incrementalCache),
                                        )
                                    )
                                    ResolvedGraph(result.outputValue.root, result.expirationTime)
                                } catch (e: Exception) {
                                    logger.error("Unable to post-process the serializable dependency graph. " +
                                            "Falling back to uncached resolution", e)
                                    resolveDependenciesBatch(moduleGraphs, null)
                                }
                            }
                        }
                    }
                }
            }
        }

        suspend fun resolveModuleDependencies(
            modules: List<AmperModule>,
            resolutionInput: ResolutionInput,
            userCacheRoot: AmperUserCacheRoot, // todo (AB) : Looks like a part of [ResolutionInput]
            leafPlatformsOnly: Boolean = false,
            filter: ModuleResolutionFilter?,
            resolutionType: ResolutionType,
        ): ResolvedGraph {
            val moduleDependenciesList = with(resolutionInput) {
                buildList {
                    modules.forEach {
                        add(ModuleDependencies(it, userCacheRoot, incrementalCache, openTelemetry))
                    }
                }
            }
            return resolveModuleDependencies(
                moduleDependenciesList = moduleDependenciesList,
                resolutionInput,
                leafPlatformsOnly,
                filter,
                resolutionType,
            )
        }

        suspend fun resolveModuleDependencies(
            moduleDependenciesList: List<ModuleDependencies>,
            resolutionInput: ResolutionInput,
            leafPlatformsOnly: Boolean,
            filter: ModuleResolutionFilter?,
            resolutionType: ResolutionType,
        ): ResolvedGraph {
            return with (resolutionInput) {
                openTelemetry.spanBuilder("DR: Resolving dependencies for the list of modules").use {
                    val moduleGraphs = buildList {
                        moduleDependenciesList.forEach {
                            if (leafPlatformsOnly) {
                                //  1. Tests and main should be resolved separately in IdeSync-mode
                                if (resolutionType.includeMain) {
                                    add(it.allLeafPlatformsGraph(isForTests = false))
                                }
                                if (resolutionType.includeTest) {
                                    add(it.allLeafPlatformsGraph(isForTests = true))
                                }
                            } else {
                                //  1. Tests and main should be resolved separately in IdeSync-mode
                                if (resolutionType.includeMain) {
                                    add(it.allFragmentsGraph(isForTests = false, flattenGraph = false))
                                }
                                if (resolutionType.includeTest) {
                                    add(it.allFragmentsGraph(isForTests = true, flattenGraph = false))
                                }
                            }
                        }
                    }

                    resolveDependenciesBatch(moduleGraphs, filter)
                }
            }
        }

        private suspend fun ResolutionInput.resolveDependenciesBatch(
            moduleGraphs: List<DependencyNodeHolderWithContext>,
            filter: ModuleResolutionFilter?,
        ): ResolvedGraph {
            val resolvedGraphs = coroutineScope {
                moduleGraphs.map {
                    async {
                        it.resolveDependencies(
                            resolutionDepth = resolutionDepth,
                            resolutionLevel = resolutionLevel,
                            downloadSources = downloadSources,
                            incrementalCacheUsage = incrementalCacheUsage
                        )
                    }
                }
            }.awaitAll()

            val resolvedGraphsUnwrapped =
                resolvedGraphs
                    .flatMap { if (it.root is RootDependencyNode) it.root.children else listOf(it.root) }
                    .filter {
                        filter == null || with(filter) {
                            // todo (AB) : This filtering works for non-flattened list only.
                            it is ModuleDependencyNode
                                    && (platforms == null || platforms == it.resolutionConfig.platforms)
                                    && (scope == null || scope == it.resolutionConfig.scope)
                        }
                    }

            val compositeGraph = ResolvedGraph(
                RootDependencyNodeStub(children = resolvedGraphsUnwrapped).also { root ->
                    root.children.filterIsInstance<ModuleDependencyNode>().forEach {
                        it.attachToNewRoot(root)
                    }
                },
                resolvedGraphs.mapNotNull { it.expirationTime }.minByOrNull { it },
            )
            return compositeGraph
        }

       /**
         * todo (AB) : This functional expose low-level API
         * todo (AB) : Make it private after refactoring of the usage in [ModuleDependenciesResolverImpl]
         */
        internal suspend fun DependencyNodeHolderWithContext.resolveDependencies(
            resolutionDepth: ResolutionDepth,
            resolutionLevel: ResolutionLevel = ResolutionLevel.NETWORK,
            downloadSources: Boolean,
            incrementalCacheUsage: IncrementalCacheUsage = IncrementalCacheUsage.USE,
        ): ResolvedGraph {
            val root = this@resolveDependencies
            return context.infoSpanBuilder("DR.graph:resolveDependencies").use {
                when (resolutionDepth) {
                    ResolutionDepth.GRAPH_ONLY -> {
                        /* Do nothing, graph is already given */
                        ResolvedGraph(
                            root,
                            null
                        )
                    }

                    ResolutionDepth.GRAPH_WITH_DIRECT_DEPENDENCIES,
                    ResolutionDepth.GRAPH_FULL,
                        -> {
                        val resolvedGraph = Resolver().resolveDependencies(
                            root = root,
                            resolutionLevel,
                            downloadSources,
                            resolutionDepth != ResolutionDepth.GRAPH_WITH_DIRECT_DEPENDENCIES,
                            incrementalCacheUsage = incrementalCacheUsage,
                            DirectMavenDependencyUnspecifiedVersionResolver(),
                            postProcessGraph = {
                                // Merge the input graph (that has PSI references) with the deserialized one
                                it.fillNotation(root)
                            }
                        )
                        resolvedGraph
                    }
                }
            }
        }

        private fun SerializableDependencyNode.fillNotation(sourceNode: DependencyNodeHolderWithContext) {
            val sourceDirectDeps = sourceNode.children.groupBy { it.key }
            this.children.forEach { node ->
                when (node) {
                    is SerializableDirectFragmentDependencyNodeHolder -> {
                        val sourceNode = sourceDirectDeps[node.key].resolveCorrespondingSourceNode<DirectFragmentDependencyNodeHolderWithContext>(node) {
                            node.dependencyNode.getOriginalMavenCoordinates() == notation.coordinates
                        }
                        node.notation = sourceNode.notation
                    }
                    is SerializableModuleDependencyNodeWithModule -> {
                        val sourceNode = sourceDirectDeps[node.key].resolveCorrespondingSourceNode<ModuleDependencyNodeWithModuleAndContext>(node)
                        node.notation = sourceNode.notation
                        node.fillNotation(sourceNode)
                    }
                    is SerializableRootDependencyNode -> {
                        val sourceNode = sourceDirectDeps[node.key].resolveCorrespondingSourceNode<RootDependencyNodeWithContext>(node)
                        node.fillNotation(sourceNode)
                    }
                }
            }
        }

        private inline fun <reified T: DependencyNode> List<DependencyNode>?.resolveCorrespondingSourceNode(
            node: SerializableDependencyNode,
            additionalMatch: T.() -> Boolean = { true }
        ): T {
            if (this == null || this.isEmpty())
                error("Deserialized node with key ${node.key} has no corresponding input node")

            this.forEach {
                (it as? T) ?: error(
                    "Deserialized node corresponds to unexpected input node of type " +
                            "${this::class.simpleName} while ${node::class.simpleName} is expected"
                )
                if (it.additionalMatch()) return it
            }

            return (this.first() as T)
        }

        /**
         * Provide unresolved [ModuleDependencies] for all modules of the given AOM [Model].
         *
         * This method could be used for caching [ModuleDependencies] per AOM instance to avoid recalculation of
         * direct graph for the same model.
         *
         * The resulting list could be used as an entry point into module-wide dependency resoluion for the entire project
         * (represented by the given [Model])
         */
        private fun Model.moduleDependencies(
            userCacheRoot: AmperUserCacheRoot,
            incrementalCache: IncrementalCache?,
            openTelemetry: OpenTelemetry?,
            includeNonExportedNative: Boolean = false,
        ): List<ModuleDependencies> =
            modules.map { ModuleDependencies(it, userCacheRoot, incrementalCache, openTelemetry, includeNonExportedNative) }

        /**
         * Provide unresolved [ModuleDependencies] for all modules of the given AOM [Model].
         *
         * This method could be used for caching [ModuleDependencies] per AOM instance to avoid recalculation of
         * direct graph for the same model.
         *
         * The resulting list could be used as an entry point into module-wide dependency resoluion for the entire project
         * (represented by the given [Model])
         */
        private fun List<ModuleDependencies>.checkModules(aom: Model): List<ModuleDependencies> = also {
            if (aom.modules.toSet() != this.map { it.module }.toSet())
                error("Modules from the given list do not match modules from the Project Model")
        }
    }
}

fun List<ModuleDependencyNode>.fragmentDependencies(module: String, fragment: String, aom: Model) : List<ModuleDependencyNode> {
    val amperModule = aom.modules.singleOrNull { it.userReadableName == module } ?: error("Module $module is not found")
    val fragment = amperModule.fragments.singleOrNull { it.name == fragment } ?: error("Fragment $fragment does not exist in module $module")

    val platforms = fragment.platforms.map { it.toResolutionPlatform()!! }.toSet()
    val isTest = fragment.isTest

    return filter {
        it.moduleName == module
                && it.resolutionConfig.platforms == platforms
                && it.isForTests == isTest
    }
}

// todo (AB) : Reuse common dependencies for leaf platform in single-platform modules.
class PerFragmentDependencies(
    val fragment: Fragment,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache?,
    internal val includeNonExportedNative: Boolean = true,
) {
    /**
     * This node represents a graph that contains external COMPILE dependencies of the module for the particular platform.
     * It contains direct external dependencies of this module as well as exported dependencies of dependent modules
     * accessible from this module.
     * It doesn't contain transitive external dependencies (no resolution happened actually).
     * This graph is a part of the input for dependency resolution of the module.
     * See [ModuleDependencies.allLeafPlatformsGraph] for further details.
     */
    val compileDeps: ModuleDependencyNodeWithModuleAndContext by lazy {
        fragment.module.buildDependenciesGraph(
            isTest = fragment.isTest,
            platforms = fragment.platforms,
            dependencyReason = ResolutionScope.COMPILE,
            userCacheRoot = userCacheRoot,
            incrementalCache = incrementalCache,
            includeNonExportedNative = includeNonExportedNative
        )
    }

    /**
     * This node represents a graph that contains external RUNTIME dependencies of the module for the particular platform.
     * It contains direct external dependencies of this module as well as direct external dependencies of all modules
     * this one depends on transitively.
     * It doesn't contain transitive external dependencies although (no resolution happened actually).
     * This graph is a part of the input for dependency resolution of the module.
     * See [ModuleDependencies.allLeafPlatformsGraph] for further details.
     */
    val runtimeDeps: ModuleDependencyNodeWithModuleAndContext? by lazy {
        when {
            fragment.platforms.singleOrNull()?.isDescendantOf(Platform.NATIVE) == true
                    && includeNonExportedNative -> null  // The native world doesn't distinguish compile/runtime classpath
            else -> fragment.module.buildDependenciesGraph(
                isTest = fragment.isTest,
                platforms = fragment.platforms,
                dependencyReason = ResolutionScope.RUNTIME,
                userCacheRoot = userCacheRoot,
                incrementalCache = incrementalCache,
            )
        }
    }

    val compileDepsCoordinates: List<MavenCoordinates> by lazy { compileDeps.getExternalDependencies() }
    val runtimeDepsCoordinates: List<MavenCoordinates> by lazy { runtimeDeps?.getExternalDependencies() ?: emptyList() }
}

fun AmperModule.buildDependenciesGraph(
    isTest: Boolean,
    platform: Platform,
    dependencyReason: ResolutionScope,
    includeNonExportedNative: Boolean = true,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache
): ModuleDependencyNodeWithModuleAndContext = buildDependenciesGraph(
    isTest, setOf(platform), dependencyReason, includeNonExportedNative, userCacheRoot, incrementalCache)

fun AmperModule.buildDependenciesGraph(
    isTest: Boolean,
    platforms: Set<Platform>,
    dependencyReason: ResolutionScope,
    includeNonExportedNative: Boolean = true,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache?
): ModuleDependencyNodeWithModuleAndContext {
    val resolutionPlatform = platforms.map { it.toResolutionPlatform()
        ?: throw IllegalArgumentException("Dependency resolution is not supported for the platform $it") }.toSet()

    return with(moduleDependenciesResolver) {
        resolveDependenciesGraph(
            DependenciesFlowType.ClassPathType(dependencyReason, resolutionPlatform, isTest, includeNonExportedNative),
            getAmperFileCacheBuilder(userCacheRoot),
            GlobalOpenTelemetry.get(),
            incrementalCache
        )
    }
}

private data class ContextKey(
    val scope: ResolutionScope,
    val platforms: Set<ResolutionPlatform>,
    val isTest: Boolean,
    val repositories: Set<Repository>
)

enum class ResolutionType(
    val includeMain: Boolean,
    val includeTest: Boolean,
) {
    TEST(false, true),
    MAIN(true, false),
    ALL(true, true);
}