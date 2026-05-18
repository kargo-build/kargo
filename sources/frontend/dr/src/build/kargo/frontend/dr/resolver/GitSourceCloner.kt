package build.kargo.frontend.dr.resolver

import build.kargo.frontend.schema.BitbucketSource
import build.kargo.frontend.schema.GitHubSource
import build.kargo.frontend.schema.GitLabSource
import build.kargo.frontend.schema.GitSource
import build.kargo.frontend.schema.GitUrlSource
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.*

/**
 * Handles Git repository cloning and checkout. Lightweight: no build execution, no artifact
 * collection. Used by IDE sync to expose source directories for navigation.
 */
class GitSourceCloner(
    private val cacheRoot: Path =
        Path(System.getProperty("user.home")).resolve(".kargo/sources-cache")
) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Clones (or updates) the repository and checks out the given version. Returns the path to the
     * source directory (respecting [subPath] if set).
     */
    fun resolveSourcesDir(repoUrl: String, version: String, subPath: String? = null): Path {
        val cacheKey = generateCacheKey(repoUrl, version)
        val repoDir = cacheRoot.resolve(cacheKey).resolve("repo")

        val lock = locks.computeIfAbsent(cacheKey) { ReentrantLock() }
        lock.lock()
        try {
            cloneOrUpdate(repoUrl, repoDir)
            checkout(repoDir, version)

            val resolvedDir = if (subPath != null) repoDir.resolve(subPath) else repoDir
            writeModuleNameMetadata(repoUrl, repoDir)
            return resolvedDir
        } finally {
            lock.unlock()
        }
    }

    /** Overload for [resolveSourcesDir] that takes a [GitSource] schema object. */
    fun resolveSourcesDir(source: GitSource) = resolveSourcesDir(
        repoUrl = extractRepositoryUrl(source),
        version = source.version.value,
        subPath = source.path?.toString()
    )

    private fun writeModuleNameMetadata(repoUrl: String, repoDir: Path) {
        val repoIdentifier = repoUrl.removeSuffix(".git")
            .substringAfter("://")
            .substringAfter('/')
            .replace('/', '-')
            .lowercase()

        val baseName = "vendor.$repoIdentifier"

        try {
            repoDir.createDirectories()
            java.nio.file.Files.walk(repoDir).use { stream ->
                stream.filter {
                    val pathStr = it.toString()
                    !pathStr.contains("/.git/") && !pathStr.contains("\\.git\\") &&
                    !pathStr.contains("/build/") && !pathStr.contains("\\build\\") &&
                    it.toFile().isFile && (it.fileName.toString() == "module.yaml" || it.fileName.toString() == "module.amper")
                }.forEach { moduleFile ->
                    val moduleDir = moduleFile.parent
                    val relPath = repoDir.relativize(moduleDir).toString().replace('\\', '.').replace('/', '.')
                    val finalName = if (relPath.isEmpty()) baseName else "$baseName.$relPath"
                    moduleDir.resolve(".amper-module-name").writeText(finalName)
                }
            }
        } catch (_: Exception) {
            // Best effort
        }
    }

    fun extractRepositoryUrl(source: GitSource) = when (source) {
        is GitHubSource -> "https://github.com/${source.github}.git"
        is GitLabSource -> "https://gitlab.com/${source.gitlab}.git"
        is BitbucketSource -> "https://bitbucket.org/${source.bitbucket}.git"
        is GitUrlSource -> source.git
    }

    fun extractSourceName(source: GitSource) = when (source) {
        is GitHubSource -> source.github
        is GitLabSource -> source.gitlab
        is BitbucketSource -> source.bitbucket
        is GitUrlSource -> source.git.substringAfterLast('/').removeSuffix(".git")
    }

    fun generateCacheKey(repoUrl: String, version: String): String {
        val repoIdentifier = repoUrl.removeSuffix(".git")
            .substringAfter("://")
            .substringAfter('/')
            .replace('/', '-')
            .lowercase()

        val shortRef = version.take(7)
        return "$repoIdentifier/$shortRef"
    }

    fun cloneOrUpdate(repoUrl: String, targetDir: Path): Path {
        if (targetDir.exists()) {
            val gitDir = targetDir.resolve(".git")
            if (gitDir.exists() && gitDir.isDirectory()) {
                executeGitCommand(targetDir, "fetch", "--all", "--quiet")
            } else {
                targetDir.toFile().deleteRecursively()
                targetDir.parent.createDirectories()
                executeGitCommand(workingDir = targetDir.parent, "clone", "--quiet", repoUrl, targetDir.name)
            }
        } else {
            targetDir.parent.createDirectories()
            executeGitCommand(workingDir = targetDir.parent, "clone", "--quiet", repoUrl, targetDir.name)
        }
        return targetDir
    }

    fun checkout(repoDir: Path, ref: String) {
        executeGitCommand(repoDir, "checkout", ref)
        try {
            executeGitCommand(repoDir, "reset", "--hard", "origin/$ref")
        } catch (_: Exception) {}
    }

    fun executeGitCommand(workingDir: Path, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GitSourceException(
                rawMessage = "Git command failed: git ${args.joinToString(" ")}",
                details = output.trim()
            )
        }
        return output
    }
}

/** User-friendly exception for git source resolution failures. */
class GitSourceException(
    val rawMessage: String,
    val details: String? = null,
    cause: Throwable? = null,
) : RuntimeException(rawMessage, cause) {
    val cliFormattedMessage: String
        get() = buildString {
            appendLine()
            appendLine("  Git Source Error: $rawMessage")
            details?.lines()?.forEach { appendLine("    $it") }
            appendLine()
        }
}
