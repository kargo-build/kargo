/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.KOTLIN_GROUP_ID
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenRepository.Companion.MavenCentral
import org.jetbrains.amper.dependency.resolution.Repository
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.CliReportingMavenResolver
import org.jetbrains.amper.frontend.dr.resolver.toIncrementalCacheResult
import org.jetbrains.amper.incrementalcache.IncrementalCache
import java.nio.file.Path
import kotlin.io.path.name

internal class KotlinArtifactsDownloader(
    val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
) {
    private val mavenResolver = CliReportingMavenResolver(userCacheRoot, incrementalCache)

    /**
     * Downloads the implementation of the Kotlin Build Tools API (and its dependencies) in the given [version].
     *
     * The [version] should match the Kotlin version requested by the user, it is the version of the Kotlin compiler
     * that will be used behind the scenes.
     */
    suspend fun downloadKotlinBuildToolsImpl(version: String): Collection<Path> = downloadMavenArtifact(
        groupId = KOTLIN_GROUP_ID,
        artifactId = "kotlin-build-tools-impl",
        version = version,
    )

    /**
     * Downloads the implementation of the embeddable Kotlin compiler in the given [version].
     *
     * The [version] should match the Kotlin version requested by the user, it is the version of the Kotlin compiler
     * that will be used behind the scenes.
     */
    suspend fun downloadKotlinCompilerEmbeddable(version: String): List<Path> = downloadMavenArtifact(
        groupId = KOTLIN_GROUP_ID,
        artifactId = "kotlin-compiler-embeddable",
        version = version,
    )

    /**
     * Downloads the implementation of the embeddable Kotlin commonizer in the given [version].
     */
    suspend fun downloadKotlinCommonizerEmbeddable(version: String): List<Path> = downloadMavenArtifact(
        groupId = KOTLIN_GROUP_ID,
        artifactId = "kotlin-klib-commonizer-embeddable",
        version = version,
    )

    suspend fun downloadKotlinCompilerPlugin(
        pluginConfig: SCompilerPluginConfig,
        repositories: List<Repository>,
    ): List<Path> {
        val artifacts = downloadMavenArtifact(
            groupId = pluginConfig.coordinates.groupId,
            artifactId = pluginConfig.coordinates.artifactId,
            version = pluginConfig.coordinates.version,
            repositories = repositories,
        )
        // Some plugins have a dependency on the embeddable compiler, but we already have a compiler.
        // It must be excluded from the classpath that we pass to the compiler for the plugin.
        return artifacts.filterNot { it.name.startsWith("kotlin-compiler-embeddable") }
    }

    private suspend fun downloadMavenArtifact(
        groupId: String,
        artifactId: String,
        version: String,
        repositories: List<Repository> = listOf(MavenCentral),
    ): List<Path> =
        // using incrementalCache because currently DR takes ~3s even when the artifact is already cached
        incrementalCache.execute("resolve-$groupId-$artifactId-$version", emptyMap(), emptyList()) {
            val coordinates = MavenCoordinates(groupId, artifactId, version)
            val resolved = mavenResolver.resolve(
                coordinates = listOf(coordinates),
                repositories = repositories,
                scope = ResolutionScope.RUNTIME,
                platform = ResolutionPlatform.JVM,
                resolveSourceMoniker = coordinates.toString(),
            )
            return@execute resolved.toIncrementalCacheResult()
        }.outputFiles
}
