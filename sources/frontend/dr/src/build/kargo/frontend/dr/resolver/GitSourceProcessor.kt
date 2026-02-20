/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package build.kargo.frontend.dr.resolver

import build.kargo.frontend.schema.GitSource
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.schema.Module
import java.nio.file.Path

/**
 * Processes Git sources defined in module.yaml, builds them, and returns
 * artifacts ready for injection into dependency resolution.
 */
class GitSourceProcessor(
    private val resolver: GitSourceResolver = GitSourceResolver()
) {
    
    /**
     * Processes all Git sources and returns a map of source to  built artifacts.
     */
    suspend fun processGitSources(
        module: Module,
        targetPlatforms: List<Platform>
    ): List<GitSourceArtifact> {
        val sources = module.sources ?: return emptyList()
        
        return sources.flatMap { source ->
            processSource(source, targetPlatforms)
        }
    }
    
    /**
     * Processes a single Git source.
     */
    private suspend fun processSource(
        source: GitSource,
        targetPlatforms: List<Platform>
    ): List<GitSourceArtifact> {
        // Use platforms from source config if specified, otherwise use target platforms
        val platforms = source.platforms?.map { it.value } ?: targetPlatforms
        
        // Skip resolution if publishOnly is true
        if (source.publishOnly) {
            // TODO: Implement publish-only flow
            return emptyList()
        }
        
        // Resolve the source
        val artifactPaths = resolver.resolve(source, platforms)
        
        // Map to GitSourceArtifact
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
}

/**
 * Represents a built artifact from a Git source.
 */
data class GitSourceArtifact(
    val source: GitSource,
    val artifactPath: Path,
    val platform: Platform
)
