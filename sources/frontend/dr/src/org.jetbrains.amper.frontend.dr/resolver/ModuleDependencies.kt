/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.incrementalcache.IncrementalCache

/**
 * Provides dependencies graphs for all module fragments and for leaf platforms.
 *
 * Graphs are built based on AOM and are unresolved.
 * (i.e., only effective direct dependencies of modules are included,
 *  external transitive dependencies are not resolved and are absent in the resulting graphs,
 *  constructing unresolved graphs is done without NETWORK access)
 */
class ModuleDependencies(val module: AmperModule, userCacheRoot: AmperUserCacheRoot, incrementalCache: IncrementalCache) {

    private val mainDepsPerFragment: Map<Fragment, PerFragmentDependencies> =
        module.perFragmentDependencies( false, userCacheRoot, incrementalCache)

    private val testDepsPerFragment: Map<Fragment, PerFragmentDependencies> =
        module.perFragmentDependencies( true, userCacheRoot, incrementalCache)

    private val mainDepsPerPlatform: Map<Platform, PerFragmentDependencies>
    private val testDepsPerPlatform: Map<Platform, PerFragmentDependencies>

    init {
        mainDepsPerPlatform = perPlatformDependencies(false)
        testDepsPerPlatform = perPlatformDependencies(true)
    }

    private fun perPlatformDependencies(isTest: Boolean): Map<Platform, PerFragmentDependencies> =
        buildMap {
            val depsPerFragment = if (isTest) testDepsPerFragment else mainDepsPerFragment
            depsPerFragment.forEach { (fragment, dependencies) ->
                if (fragment.platforms.size == 1 && fragment.fragmentDependants.none { it.target.isTest == isTest }) {
                    // leaf platform fragment
                    put(fragment.platforms.single(), dependencies)
                }
            }
        }

    private fun AmperModule.perFragmentDependencies(
        isTest: Boolean, userCacheRoot: AmperUserCacheRoot, incrementalCache: IncrementalCache,
    ): Map<Fragment, PerFragmentDependencies> =
        fragments
            .filter { it.isTest == isTest }
            .sortedBy { it.name }
            .associateBy(keySelector = { it }) {
                PerFragmentDependencies(it, userCacheRoot, incrementalCache)
            }

    /**
     * Module dependencies resolution in CLI
     * should be performed based on the entire list returned by this method at once,
     * so that versions of module dependencies are aligned across all module fragments.
     *
     * Note: For CLI, resolution of leaf-platform's dependencies is enough, there is no need to resolve
     * dependencies of other fragments (multiplatform ones) to align library versions accross the module..
     *
     * @return the list of module root nodes (one root node per platform).
     * Each node from the list contains unresolved platform-specific dependencies of the module.
     */
    fun forCLIResolution(isTest: Boolean): List<ModuleDependencyNodeWithModuleAndContext> {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformDeps = if (isTest) testDepsPerPlatform else mainDepsPerPlatform

        return buildList {
            perPlatformDeps.values.forEach {
                add(it.compileDeps)
                it.runtimeDeps?.let { runtimeDeps -> add(runtimeDeps) }
            }
        }
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
     * @return the list of module root nodes (one root node per fragment).
     * Each node from the list contains unresolved dependencies of the fragment.
     */
    fun forIdeResolution(isTest: Boolean): List<ModuleDependencyNodeWithModuleAndContext> {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformDeps = if (isTest) testDepsPerPlatform else mainDepsPerPlatform

        return buildList {
            perPlatformDeps.values.forEach {
                add(it.compileDeps)
                it.runtimeDeps?.let { runtimeDeps -> add(runtimeDeps) }
            }
        }
    }

    /**
     * @return unresolved compile/runtime module dependencies for the particular platform.
     */
    fun forPlatform(platform: Platform, isTest: Boolean): PerFragmentDependencies {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformDeps = if (isTest) testDepsPerPlatform else mainDepsPerPlatform
        return perPlatformDeps[platform]
            ?: error("Dependencies for $platform are not calculated")
    }
}

class PerFragmentDependencies(
    val fragment: Fragment,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache,
) {
    /**
     * This node represents a graph that contains external COMPILE dependencies of the module for the particular platform.
     * It contains direct external dependencies of this module as well as exported dependencies of dependent modules
     * accessible from this module.
     * It doesn't contain transitive external dependencies (no resolution happened actually).
     * This graph is a part of the input for dependency resolution of the module.
     * See [ModuleDependencies.forCLIResolution] for further details.
     */
    val compileDeps: ModuleDependencyNodeWithModuleAndContext by lazy {
        fragment.module.buildDependenciesGraph(
            isTest = fragment.isTest,
            platforms = fragment.platforms,
            dependencyReason = ResolutionScope.COMPILE,
            userCacheRoot = userCacheRoot,
            incrementalCache = incrementalCache,
        )
    }

    /**
     * This node represents a graph that contains external RUNTIME dependencies of the module for the particular platform.
     * It contains direct external dependencies of this module as well as direct external dependencies of all modules
     * this one depends on transitively.
     * It doesn't contain transitive external dependencies although (no resolution happened actually).
     * This graph is a part of the input for dependency resolution of the module.
     * See [ModuleDependencies.forCLIResolution] for further details.
     */
    val runtimeDeps: ModuleDependencyNodeWithModuleAndContext? by lazy {
        when {
            fragment.platforms.singleOrNull()?.isDescendantOf(Platform.NATIVE) == true -> null  // The native world doesn't distinguish compile/runtime classpath
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

/**
 * Returns a dependencies sequence of the given module in the resolution scope
 * of the given [platform], [isTest] and [dependencyReason].
 */
fun AmperModule.getModuleDependencies(
    isTest: Boolean,
    platform: Platform,
    dependencyReason: ResolutionScope,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache
) : Sequence<AmperModule> {
    val fragmentsModuleDependencies =
        buildDependenciesGraph(isTest, platform, dependencyReason, userCacheRoot, incrementalCache)
    return fragmentsModuleDependencies.getModuleDependencies()
}

fun AmperModule.buildDependenciesGraph(
    isTest: Boolean,
    platform: Platform,
    dependencyReason: ResolutionScope,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache
): ModuleDependencyNodeWithModuleAndContext = buildDependenciesGraph(isTest, setOf(platform), dependencyReason, userCacheRoot, incrementalCache)

fun AmperModule.buildDependenciesGraph(
    isTest: Boolean,
    platforms: Set<Platform>,
    dependencyReason: ResolutionScope,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache
): ModuleDependencyNodeWithModuleAndContext {
    val resolutionPlatform = platforms.map { it.toResolutionPlatform()
        ?: throw IllegalArgumentException("Dependency resolution is not supported for the platform $it") }.toSet()

    return with(moduleDependenciesResolver) {
        resolveDependenciesGraph(
            DependenciesFlowType.ClassPathType(dependencyReason, resolutionPlatform, isTest),
            getAmperFileCacheBuilder(userCacheRoot),
            GlobalOpenTelemetry.get(),
            incrementalCache
        )
    }
}

private fun ModuleDependencyNodeWithModuleAndContext.getModuleDependencies(): Sequence<AmperModule> {
    return distinctBfsSequence { child, _ ->  child is ModuleDependencyNodeWithModuleAndContext }
        .drop(1)
        .filterIsInstance<ModuleDependencyNodeWithModuleAndContext>()
        .map { it.module }
}