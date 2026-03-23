/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.frontend.dr.resolver.flow

import org.jetbrains.amper.dependency.resolution.Cache
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.JavaVersion
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.MavenGroupAndArtifact
import org.jetbrains.amper.dependency.resolution.Repository
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.DependenciesFlowType
import org.jetbrains.amper.frontend.dr.resolver.DirectFragmentDependencyNodeHolderWithContext
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNodeWithModuleAndContext
import org.jetbrains.amper.frontend.dr.resolver.toDrMavenCoordinates
import org.jetbrains.amper.frontend.jdkSettings
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("resolutionFlow.kt")

internal interface DependenciesFlow<T: DependenciesFlowType> {
    fun directDependenciesGraph(
        module: AmperModule,
        resolutionSettings: AmperResolutionSettings,
        sharedResolutionCache: Cache,
    ): ModuleDependencyNodeWithModuleAndContext
}

internal abstract class AbstractDependenciesFlow<T: DependenciesFlowType>(
    val flowType: T,
): DependenciesFlow<T> {

    private val contextMap: ConcurrentHashMap<ContextKey, Context> = ConcurrentHashMap<ContextKey, Context>()
    private val alreadyAddedMavenDependencyNodes: MutableSet<MavenDependencyNode> = mutableSetOf()

    internal fun MavenDependencyBase.toFragmentDirectDependencyNode(fragment: Fragment, context: Context): DirectFragmentDependencyNodeHolderWithContext? {
        val dependencyNode = context.toMavenDependencyNode(toDrMavenCoordinates(), this is BomDependency)

        return DirectFragmentDependencyNodeHolderWithContext(
            // todo (AB) : [AMPER-4905] Filter out duplicated dependencies
            //   from the same module but from different fragments
//        return if (alreadyAddedMavenDependencyNodes.add(dependencyNode)) {
//            DirectFragmentDependencyNodeHolderWithContext(
                dependencyNode,
                notation = this,
                fragment = fragment,
                templateContext = context,
                messages = emptyList(),
            )
//        } else {
//            null
//        }
    }

    protected fun AmperModule.resolveModuleContext(
        platforms: Set<ResolutionPlatform>,
        scope: ResolutionScope,
        isTest: Boolean,
        resolutionSettings: AmperResolutionSettings,
        sharedResolutionCache: Cache,
    ): Context {
        val repositories = with(ModuleDependencies) {
            getValidRepositories()
        }
        val context = contextMap.computeIfAbsent(
            ContextKey(
                scope,
                platforms,
                isTest,
                repositories.toSet()
            )
        ) { key ->
            Context(sharedResolutionCache) {
                this.scope = key.scope
                this.platforms = key.platforms
                this.repositories = repositories
                this.cache = resolutionSettings.fileCacheBuilder
                this.openTelemetry = resolutionSettings.openTelemetry
                this.incrementalCache = resolutionSettings.incrementalCache
                // todo (AB): [AMPER-4905] It should be taken from the module being resolved, not the nested one from the module classpath
                this.jdkVersion = JavaVersion(jdkSettings.version)
                fragments.firstOrNull()?.let { rootFragment ->
                    this.dependenciesBlocklist = rootFragment.settings.internal.excludeDependencies.mapNotNull {
                        val groupAndArtifact = it.split(":", limit = 2)
                        if (groupAndArtifact.size != 2) {
                            logger.error("Invalid `excludeDependencies` entry: $it"); null
                        } else {
                            MavenGroupAndArtifact(groupAndArtifact[0], groupAndArtifact[1])
                        }
                    }.toSet()
                }
            }
        }
        return context
    }
}

private data class ContextKey(
    val scope: ResolutionScope,
    val platforms: Set<ResolutionPlatform>,
    val isTest: Boolean,
    val repositories: Set<Repository>,
)
