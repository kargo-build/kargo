/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package build.kargo.frontend.dr.resolver

import build.kargo.frontend.schema.BitbucketSource
import build.kargo.frontend.schema.GitHubSource
import build.kargo.frontend.schema.GitLabSource
import build.kargo.frontend.schema.GitSource
import build.kargo.frontend.schema.GitUrlSource
import org.jetbrains.amper.frontend.Platform
import java.nio.file.Path
import kotlin.io.path.*
/**
 * Resolves Git-backed sources by cloning, checking out specific versions,
 * and building them locally using Kargo.
 */
class GitSourceResolver(
    // Use default user cache directory
    private val cacheRoot: Path = Path(System.getProperty("user.home")).resolve(".amper/sources-cache")
) {

    /**
     * Resolves a Git source to a set of built artifacts.
     *
     * @param source The Git source configuration
     * @param platforms Target platforms to build
     * @return Paths to the built artifacts (.klib files)
     */
    fun resolve(source: GitSource, platforms: List<Platform>): List<Path> {
        // TODO: Implement credential management for private repos
        val repoUrl = extractRepositoryUrl(source)
        val version = source.version.toString()
        val subPath = source.path

        // Resolve version to concrete commit hash
        val commitHash = resolveVersionToCommit(repoUrl, version)

        // Create cache key based on repo URL and commit
        val cacheKey = generateCacheKey(repoUrl, commitHash)
        val cacheDir = cacheRoot.resolve(cacheKey)

        // Check if already built
        if (isCached(cacheDir, platforms)) {
            return collectArtifacts(cacheDir, platforms)
        }

        // Clone or update repository
        val repoDir = cloneOrUpdate(repoUrl, cacheDir.resolve("repo"))

        // Checkout specific version
        checkout(repoDir, commitHash)

        // Determine actual project directory (consider subPath)
        val projectDir = if (subPath != null) {
            repoDir.resolve(subPath)
        } else {
            repoDir
        }

        // Build the source
        val artifacts = buildSource(projectDir, platforms)

        // Store metadata
        storeMetadata(cacheDir, repoUrl, version, commitHash, platforms)

        return artifacts
    }

    /**
     * Extracts the repository URL from different GitSource types.
     */
    private fun extractRepositoryUrl(source: GitSource): String {
        return when (source) {
            is GitHubSource -> "https://github.com/${source.github}.git"
            is GitLabSource -> "https://gitlab.com/${source.gitlab}.git"
            is BitbucketSource -> "https://bitbucket.org/${source.bitbucket}.git"
            is GitUrlSource -> source.git
        }
    }

    /**
     * Clones the repository or updates if it already exists.
     */
    private fun cloneOrUpdate(repoUrl: String, targetDir: Path): Path {
        if (targetDir.exists()) {
            // Update existing repository
            executeGitCommand(targetDir, "fetch", "--all")
        } else {
            // Clone fresh
            targetDir.parent.createDirectories()
            executeGitCommand(
                workingDir = targetDir.parent,
                "clone", repoUrl, targetDir.name
            )
        }
        return targetDir
    }

    /**
     * Checks out a specific version (tag, branch, or commit).
     */
    private fun checkout(repoDir: Path, ref: String) {
        executeGitCommand(repoDir, "checkout", ref)
    }

    /**
     * Resolves a version string (tag/branch/commit) to a concrete commit hash.
     */
    private fun resolveVersionToCommit(repoUrl: String, version: String): String {
        // For now, use the version as-is
        // TODO: Implement proper resolution (ls-remote for tags/branches)
        return version
    }

    /**
     * Builds the source using Kargo CLI.
     */
    private fun buildSource(projectDir: Path, platforms: List<Platform>): List<Path> {
        // Verify module.yaml exists
        val moduleFile = projectDir.resolve("module.yaml")
        require(moduleFile.exists()) {
            "No module.yaml found in ${projectDir.absolute()}"
        }

        // Execute Kargo build
        // TODO: Determine correct Kargo CLI path and build command
        val buildDir = projectDir.resolve("build")

        executeKargoBuild(projectDir, platforms)

        // Collect artifacts
        return collectArtifacts(buildDir, platforms)
    }

    /**
     * Executes a Git command.
     */
    private fun executeGitCommand(workingDir: Path, vararg args: String): String {
        val processBuilder = ProcessBuilder("git", *args)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            error("Git command failed: git ${args.joinToString(" ")}\nOutput: $output")
        }

        return output
    }

    /**
     * Executes Kargo build command.
     */
    private fun executeKargoBuild(projectDir: Path, platforms: List<Platform>) {
        // TODO: Implement actual Kargo CLI invocation
        // For now, placeholder
        val kargoCli = findKargoCli()

        val processBuilder = ProcessBuilder(
            kargoCli.absolutePathString(),
            "build"
            // TODO: Add platform-specific arguments
        ).directory(projectDir.toFile())
            .redirectErrorStream(true)

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            error("Kargo build failed in ${projectDir.absolute()}\nOutput: $output")
        }
    }

    /**
     * Finds the Kargo CLI executable.
     */
    private fun findKargoCli(): Path {
        // TODO: Implement proper CLI discovery
        // For now, assume it's in PATH
        return Path("./kargo")
    }

    /**
     * Collects built artifacts from the build directory.
     */
    private fun collectArtifacts(buildDir: Path, platforms: List<Platform>): List<Path> {
        // TODO: Implement artifact collection logic
        // Look for .klib files in build directory
        val artifacts = mutableListOf<Path>()

        if (buildDir.exists()) {
            buildDir.walk()
                .filter { it.extension == "klib" }
                .forEach { artifacts.add(it) }
        }

        require(artifacts.isNotEmpty()) {
            "No artifacts produced in ${buildDir.absolute()}"
        }

        return artifacts
    }

    /**
     * Checks if artifacts are already cached.
     */
    private fun isCached(cacheDir: Path, platforms: List<Platform>): Boolean {
        val metadataFile = cacheDir.resolve("metadata.json")
        return metadataFile.exists() && cacheDir.resolve("artifacts").exists()
    }

    /**
     * Generates a cache key from repository URL and commit hash.
     */
    private fun generateCacheKey(repoUrl: String, commitHash: String): String {
        // Simple hash-based key
        val repoName = repoUrl.substringAfterLast('/').removeSuffix(".git")
        val shortHash = commitHash.take(7)
        return "$repoName/$shortHash"
    }

    /**
     * Stores metadata about the built source.
     */
    private fun storeMetadata(
        cacheDir: Path,
        repoUrl: String,
        originalVersion: String,
        resolvedCommit: String,
        platforms: List<Platform>
    ) {
        cacheDir.createDirectories()
        val metadataFile = cacheDir.resolve("metadata.json")

        // TODO: Use proper JSON serialization
        val metadata = """
            {
                "repositoryUrl": "$repoUrl",
                "originalVersion": "$originalVersion",
                "resolvedCommit": "$resolvedCommit",
                "platforms": ${platforms.map { "\"$it\"" }},
                "buildTimestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        metadataFile.writeText(metadata)
    }
}
