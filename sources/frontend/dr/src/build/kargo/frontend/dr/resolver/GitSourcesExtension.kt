/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package build.kargo.frontend.dr.resolver

import build.kargo.frontend.schema.BitbucketSource
import build.kargo.frontend.schema.GitHubSource
import build.kargo.frontend.schema.GitLabSource
import build.kargo.frontend.schema.GitSource
import build.kargo.frontend.schema.GitUrlSource
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import java.nio.file.Path

/**
 * Extension point for processing Git sources before dependency resolution.
 *
 * This should be called early in the build pipeline, before Maven dependency
 * resolution, to ensure Git sources are built and artifacts are available.
 */
object GitSourcesExtension {

    private val artifactCache = mutableMapOf<String, List<GitSourceArtifact>>()

    /**
     * Process Git sources for a module and cache the results.
     *
     * @param module The Amper module with potential Git sources
     * @param targetPlatforms The platforms to build for
     * @return List of built artifacts
     */
    suspend fun processModuleGitSources(
        module: AmperModule,
        targetPlatforms: List<Platform>
    ): List<GitSourceArtifact> {
        val cacheKey = "${module.userReadableName}-${targetPlatforms.hashCode()}"

        return artifactCache.getOrPut(cacheKey) {
            // TODO: Get sources from AmperModule
            // Currently returns empty until schema binding is complete
            emptyList()
        }
    }

    /**
     * Extracts Git sources from module configuration.
     *
     * TODO: Implement proper extraction from AmperModule
     * This currently returns empty as the binding isn't complete.
     */
    private fun getModuleGitSources(module: AmperModule): List<GitSource> {
        // TODO: Extract from module.parts or module schema
        // For now, return empty until schema integration is complete
        return emptyList()
    }

    /**
     * Process a single Git source.
     */
    private suspend fun processGitSource(
        source: GitSource,
        targetPlatforms: List<Platform>
    ): List<GitSourceArtifact> {
        val platforms = source.platforms?.map { it.value } ?: targetPlatforms

        // Skip if publishOnly is true
        if (source.publishOnly) {
            return emptyList()
        }

        // Use resolver to build the source
        val resolver = GitSourceResolver()
        val artifactPaths = resolver.resolve(source, platforms)

        return artifactPaths.map { path ->
            GitSourceArtifact(
                source = source,
                artifactPath = path,
                platform = determinePlatform(path, platforms)
            )
        }
    }

    /**
     * Determines the platform for a given artifact path.
     * This is a heuristic based on the artifact file name or path.
     */
    private fun determinePlatform(artifactPath: Path, candidatePlatforms: List<Platform>): Platform {
        // TODO: Implement proper platform detection from .klib metadata
        // For now, return the first platform
        return candidatePlatforms.firstOrNull() ?: Platform.COMMON
    }

    /**
     * Extract a display name from GitSource.
     */
    private fun extractSourceName(source: GitSource): String {
        return when (source) {
            is GitHubSource -> source.github
            is GitLabSource -> source.gitlab
            is BitbucketSource -> source.bitbucket
            is GitUrlSource -> source.git
        }
    }

    /**
     * Get artifact paths for a module (for compiler classpath injection).
     */
    fun getArtifactPaths(module: AmperModule): List<Path> {
        val allKeys = artifactCache.keys.filter { it.startsWith("${module.userReadableName}-") }
        return allKeys
            .flatMap { artifactCache[it] ?: emptyList() }
            .map { it.artifactPath }
    }

    /**
     * Clear the artifact cache (useful for testing).
     */
    fun clearCache() {
        artifactCache.clear()
    }
}
