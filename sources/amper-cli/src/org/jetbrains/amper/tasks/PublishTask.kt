/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.codehaus.plexus.PlexusContainer
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.dependency.resolution.MavenLocalRepository
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.RepositoriesModulePart
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.maven.publish.createPlexusContainer
import org.jetbrains.amper.maven.publish.deployToRemoteRepo
import org.jetbrains.amper.maven.publish.installToMavenLocal
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.telemetry.useWithoutCoroutines
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val mavenLocalRepository by lazy {
    spanBuilder("Initialize maven local repository").useWithoutCoroutines {
        MavenLocalRepository.Default
    }
}

/**
 * A comparator to sort artifacts for publication, just to have some reproducible order.
 */
private val artifactComparator = compareBy<Artifact>(
    { it.groupId },
    { it.artifactId },
    { it.classifier },
    { it.extension },
)

class PublishTask(
    override val taskName: TaskName,
    val module: AmperModule,
    val targetRepository: RepositoriesModulePart.Repository,
) : Task {

    override suspend fun run(dependenciesResult: List<TaskResult>, executionContext: TaskGraphExecutionContext): TaskResult {

        if (!targetRepository.publish) {
            userReadableError(
                "Cannot publish to repository '${targetRepository.id}' because it's not marked as publishable. " +
                        "Please check your configuration and make sure that `publish: true` is set for this repository."
            )
        }

        val localRepositoryPath = mavenLocalRepository.repository
        val artifacts = dependenciesResult.filterIsInstance<PrepareMavenPublishablesTask.Result>()
            .flatMap { it.mavenArtifacts() }
            // We sort for reproducibility, because the artifact order matters for the Eclipse Aether library
            // (it determines the order in maven-metadata.xml, at least when using SNAPSHOTs)
            .sortedWith(artifactComparator)

        /**
         * Publish uses a different code to publish to maven local and to any other remote
         * repositories.
         * Maven local is different, since there are some settings to set it up
         * (https://maven.apache.org/resolver/local-repository.html) and also it does not use checksums
         * https://maven.apache.org/plugins/maven-install-plugin/index.html#important-note-for-version-3-0-0
         * > The `install:install` goal does not support creating checksums anymore via -DcreateChecksum=true cause this
         * > option has been removed. Details can be found in MINSTALL-143.
         */
        spanBuilder("Maven publish").use {
            if (targetRepository.isMavenLocal) {
                logger.info("Installing artifacts of module '${module.userReadableName}' to local maven repository at $localRepositoryPath")

                try {
                    getPlexusContainer(executionContext).installToMavenLocal(localRepositoryPath, artifacts)
                } catch (e: Exception) {
                    userReadableError("Couldn't install artifacts of module '${module.userReadableName}' to maven local: $e")
                }
            } else {
                val remoteRepository = targetRepository.toMavenRemoteRepository()

                logger.info("Publishing artifacts of module '${module.userReadableName}' to " +
                        "remote maven repository at ${remoteRepository.url} (id: '${remoteRepository.id}')")

                try {
                    getPlexusContainer(executionContext).deployToRemoteRepo(remoteRepository, localRepositoryPath, artifacts)
                } catch (e: Exception) {
                    userReadableError("Couldn't publish artifacts of module '${module.userReadableName}' to repository '${remoteRepository.id}': $e")
                }
            }
        }

        return Result()
    }

    private fun PrepareMavenPublishablesTask.Result.mavenArtifacts(): List<Artifact> =
        publishables.map { it.toMavenArtifact() }

    private fun MavenPublishable.toMavenArtifact(): Artifact = DefaultArtifact(
        coordinates.groupId,
        coordinates.artifactId,
        coordinates.classifier,
        mavenArtifactExtension,
        coordinates.version,
    ).setFile(path.toFile())

    private fun RepositoriesModulePart.Repository.toMavenRemoteRepository(): RemoteRepository {
        val builder = RemoteRepository.Builder(id, "default", url)
        if (userName != null && password != null) {
            val authBuilder = AuthenticationBuilder()
            authBuilder.addUsername(userName)
            authBuilder.addPassword(password)
            builder.setAuthentication(authBuilder.build())
        }
        return builder.build()
    }

    class Result : TaskResult

    companion object {
        private val plexusContainerCache = ConcurrentHashMap<String, PlexusContainer>()

        suspend fun getPlexusContainer(executionContext: TaskGraphExecutionContext): PlexusContainer =
            plexusContainerCache.getOrPut(executionContext.executionId) {
                createPlexusContainer().also { container ->
                    executionContext.addPostGraphExecutionHook {
                        spanBuilder("Dispose of Maven's PlexusContainer").use {
                            container.dispose()
                        }
                    }
                }
            }
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}
