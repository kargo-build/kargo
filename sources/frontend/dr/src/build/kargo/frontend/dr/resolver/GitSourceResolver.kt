package build.kargo.frontend.dr.resolver

import build.kargo.frontend.schema.BitbucketSource
import build.kargo.frontend.schema.GitHubSource
import build.kargo.frontend.schema.GitLabSource
import build.kargo.frontend.schema.GitSource
import build.kargo.frontend.schema.GitUrlSource
import org.jetbrains.amper.frontend.Platform
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.*
/**
 * Resolves Git-backed sources by cloning, checking out specific versions,
 * and building them locally using Kargo.
 */
class GitSourceResolver(
    private val cacheRoot: Path = Path(System.getProperty("user.home")).resolve(".kargo/sources-cache")
) {
    // Per-cache-key locks to prevent concurrent clones to the same directory
    private val resolveLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Resolves a Git source to a set of built artifacts.
     *
     * @param source The Git source configuration
     * @param platforms Target platforms to build
     * @return Paths to the built artifacts (.klib files)
     */
    fun resolve(source: GitSource, platforms: List<Platform>): List<Path> {
        val repoUrl = extractRepositoryUrl(source)
        val sourceName = extractSourceName(source)
        val version = source.version.toString()
        val subPath = source.path

        val commitHash = resolveVersionToCommit(repoUrl, version)

        val cacheKey = generateCacheKey(repoUrl, commitHash)
        val cacheDir = cacheRoot.resolve(cacheKey)

        val lock = resolveLocks.computeIfAbsent(cacheKey) { ReentrantLock() }
        lock.lock()
        try {
            if (isCached(cacheDir, platforms)) {
                logger.info("Using cached git source '$sourceName' ($version)")
                return collectArtifacts(cacheDir, platforms)
            }

            logger.info("Fetching git source '$sourceName' ($version)...")
            val repoDir = try {
                cloneOrUpdate(repoUrl, cacheDir.resolve("repo"))
            } catch (e: GitSourceException) {
                throw e
            } catch (e: Exception) {
                throw GitSourceException(
                    "Failed to fetch git source '$sourceName' from $repoUrl",
                    details = e.message,
                    cause = e
                )
            }

            logger.info("Checking out git source '$sourceName' ($version)...")
            try {
                checkout(repoDir, commitHash)
            } catch (e: Exception) {
                throw GitSourceException(
                    "Failed to checkout version '$version' for git source '$sourceName'",
                    details = "Repository: $repoUrl\nVersion: $version\n${e.message}",
                    cause = e
                )
            }

            val projectDir = if (subPath != null) {
                repoDir.resolve(subPath)
            } else {
                repoDir
            }

            logger.info("Building git source '$sourceName'...")
            val artifacts = buildSource(projectDir, platforms, sourceName)

            storeMetadata(cacheDir, repoUrl, version, commitHash, platforms)

            logger.info("Installed git source '$sourceName' (${artifacts.size} artifact(s))")
            return artifacts
        } finally {
            lock.unlock()
        }
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

    private fun extractSourceName(source: GitSource): String {
        return when (source) {
            is GitHubSource -> source.github
            is GitLabSource -> source.gitlab
            is BitbucketSource -> source.bitbucket
            is GitUrlSource -> source.git.substringAfterLast('/').removeSuffix(".git")
        }
    }

    /**
     * Clones the repository or updates if it already exists.
     */
    private fun cloneOrUpdate(repoUrl: String, targetDir: Path): Path {
        if (targetDir.exists()) {
            val gitDir = targetDir.resolve(".git")
            if (gitDir.exists() && gitDir.isDirectory()) {
                executeGitCommand(targetDir, "fetch", "--all", "--quiet")
            } else {
                targetDir.toFile().deleteRecursively()
                targetDir.parent.createDirectories()
                executeGitCommand(
                    workingDir = targetDir.parent,
                    "clone", "--quiet", repoUrl, targetDir.name
                )
            }
        } else {
            targetDir.parent.createDirectories()
            executeGitCommand(
                workingDir = targetDir.parent,
                "clone", "--quiet", repoUrl, targetDir.name
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
    private fun buildSource(projectDir: Path, platforms: List<Platform>, sourceName: String): List<Path> {
        val moduleFile = projectDir.resolve("module.yaml")
        if (!moduleFile.exists()) {
            throw GitSourceException(
                "Git source '$sourceName' is not a valid Kargo project",
                details = "No module.yaml found in ${projectDir.absolute()}\nGit sources must contain a valid module.yaml file."
            )
        }

        val buildDir = projectDir.resolve("build")

        try {
            executeKargoBuild(projectDir, platforms)
        } catch (e: Exception) {
            throw GitSourceException(
                "Failed to build git source '$sourceName'",
                details = "Project directory: ${projectDir.absolute()}\n${e.message}",
                cause = e
            )
        }

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
            throw GitSourceException(
                "Git command failed: git ${args.joinToString(" ")}",
                details = output.trim()
            )
        }

        return output
    }

    /**
     * Executes Kargo build command.
     */
    private fun executeKargoBuild(projectDir: Path, platforms: List<Platform>) {
        val kargoCli = findKargoCli(projectDir)

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
    private fun findKargoCli(projectDir: Path): Path {
        // TODO: Implement proper CLI discovery
        return if (projectDir.resolve("kargo").exists()) {
            Path(projectDir.absolutePathString(), "./kargo")
        } else if (projectDir.resolve("amper").exists()) {
            // Support older projects that still use 'amper' as the CLI name
            Path(projectDir.absolutePathString(), "./amper")
        } else {
            throw GitSourceException(
                "Kargo CLI not found in git source project",
                details = "Expected 'kargo' or 'amper' executable in ${projectDir.absolute()}\n" +
                        "Ensure that the project contains the Kargo CLI for building."
            )
        }
    }

    /**
     * Collects built artifacts from the build directory.
     */
    private fun collectArtifacts(buildDir: Path, platforms: List<Platform>): List<Path> {
        val artifacts = mutableListOf<Path>()

        if (buildDir.exists()) {
            buildDir.walk()
                .filter { it.extension == "klib" }
                .forEach { artifacts.add(it) }
        }

        if (artifacts.isEmpty()) {
            throw GitSourceException(
                "No artifacts produced by git source build",
                details = "Build directory: ${buildDir.absolute()}\nExpected .klib files but none were found."
            )
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
    companion object {
        private val logger = LoggerFactory.getLogger(GitSourceResolver::class.java)
    }
}

/**
 * User-friendly exception for git source resolution failures.
 */
class GitSourceException(
    message: String,
    val details: String? = null,
    cause: Throwable? = null
) : RuntimeException(buildMessage(message, details), cause) {
    companion object {
        private fun buildMessage(message: String, details: String?): String = buildString {
            appendLine()
            appendLine("  Git Source Error: $message")
            if (details != null) {
                appendLine()
                details.lines().forEach { appendLine("    $it") }
            }
            appendLine()
        }
    }
}

