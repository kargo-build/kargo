/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.AmperBuildOutputRoot
import org.jetbrains.amper.compilation.downloadNativeCompiler
import org.jetbrains.amper.compilation.serializableKotlinSettings
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.extract.cleanDirectory
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.CinteropDefFileArtifact
import org.jetbrains.amper.tasks.artifacts.CinteropKlibsArtifact
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

internal class NativeCInteropGenerateKlibTask(
    override val module: AmperModule,
    override val platform: Platform,
    private val fragments: List<Fragment>,
    private val buildOutputRoot: AmperBuildOutputRoot,
    private val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
    private val jdkProvider: JdkProvider,
    private val processRunner: ProcessRunner,
) : ArtifactTaskBase(), BuildTask {
    private val defFileArtifacts by Selectors.fromMatchingFragments(
        type = CinteropDefFileArtifact::class,
        module = module,
        isTest = false,
        hasPlatforms = setOf(platform),
        quantifier = Quantifier.AnyOrNone,
    )

    private val outputKlibsDirectoryArtifact by CinteropKlibsArtifact(
        buildOutputRoot = buildOutputRoot,
        module = module,
        platform = platform,
    )

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext,
    ): TaskResult {
        val inputDefFiles = buildList {
            defFileArtifacts.forEach { add(it.path) }
            for (fragment in fragments) {
                val path = fragment.cinteropPath?.takeIf { it.isDirectory() } ?: continue
                addAll(path.listDirectoryEntries("*.def"))
            }
        }

        if (inputDefFiles.isEmpty()) {
            logger.debug("No .def files found, bailing out")
            cleanDirectory(outputKlibsDirectoryArtifact.path)
            return EmptyTaskResult
        }

        val targetLeafFragment = module.leafFragments.single { it.platform == platform && !it.isTest }
        val kotlinCompilerVersion = targetLeafFragment.serializableKotlinSettings().compilerVersion

        coroutineScope {
            val relevantOutputs = inputDefFiles.map { defFile ->
                async {
                    incrementalCache.execute(
                        key = taskName.name,
                        inputValues = mapOf(
                            "target" to platform.nameForCompiler,
                            "kotlinVersion" to kotlinCompilerVersion,
                        ),
                        inputFiles = listOf(defFile),
                    ) {
                        val cinteropName = defFile.nameWithoutExtension
                        val outputKlib = outputKlibsDirectoryArtifact.path / "$cinteropName.klib"
                        outputKlib.deleteIfExists()

                        val nativeCompiler =
                            downloadNativeCompiler(kotlinCompilerVersion, userCacheRoot, jdkProvider)
                        val args = buildList {
                            add("-def")
                            add(defFile.pathString)
                            add("-target")
                            add(platform.nameForCompiler)
                            add("-o")
                            add(outputKlib.pathString)
                        }

                        logger.info("Running cinterop '$cinteropName' for platform '${platform.pretty}'...")
                        nativeCompiler.cinterop(processRunner, args)

                        IncrementalCache.ExecutionResult(listOf(outputKlib))
                    }.outputFiles.single()
                }
            }.awaitAll()

            // Clean any stale output Klibs
            outputKlibsDirectoryArtifact.path.listDirectoryEntries()
                .filterNot { it in relevantOutputs }
                .forEach { it.deleteRecursively() }
        }

        return EmptyTaskResult
    }

    override val buildType: BuildType?
        get() = null

    override val isTest: Boolean
        get() = false

    companion object {
        private val logger = LoggerFactory.getLogger(NativeCInteropGenerateKlibTask::class.java)
    }
}
