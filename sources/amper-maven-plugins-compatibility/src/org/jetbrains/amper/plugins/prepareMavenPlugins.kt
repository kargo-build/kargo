/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.withJarEntry
import org.jetbrains.amper.frontend.dr.resolver.MavenResolver
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.maven.MavenPluginXml
import org.jetbrains.amper.maven.download.downloadSingleArtifactJar
import org.jetbrains.amper.maven.parseMavenPluginXml
import org.slf4j.LoggerFactory

/**
 * Download specified maven plugins jars, extract their `plugin.xml` metadata and parse it.
 */
@UsedInIdePlugin
suspend fun prepareMavenPlugins(
    projectContext: AmperProjectContext,
    incrementalCache: IncrementalCache,
): List<MavenPluginXml> = coroutineScope prepare@{
    val userCacheRoot = AmperUserCacheRoot.fromCurrentUserResult() as? AmperUserCacheRoot ?: return@prepare emptyList()
    
    val mavenResolver = MavenResolver(userCacheRoot, incrementalCache)
    projectContext.externalMavenPlugins.map { mavenPlugin ->
        async {
            val pluginCoordinates = MavenCoordinates(
                groupId = mavenPlugin.groupId,
                artifactId = mavenPlugin.artifactId,
                version = mavenPlugin.version,
                packagingType = null,
                classifier = null,
            )
            val pluginJarFile = mavenResolver.downloadSingleArtifactJar(pluginCoordinates) ?: return@async null
            withJarEntry(pluginJarFile, "META-INF/maven/plugin.xml") {
                try {
                    parseMavenPluginXml(it)
                } catch (e: Exception) {
                    val coordinatesString = "${mavenPlugin.groupId}:${mavenPlugin.artifactId}:${mavenPlugin.version}"
                    logger.warn("Failed to parse plugin.xml for $coordinatesString", e)
                    null
                }
            }
        }
    }.awaitAll().filterNotNull()
}

private val logger = LoggerFactory.getLogger("PrepareMavenPluginsKt")