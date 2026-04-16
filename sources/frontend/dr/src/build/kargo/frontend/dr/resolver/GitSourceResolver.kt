package build.kargo.frontend.dr.resolver

import build.kargo.frontend.schema.GitSource
import build.kargo.frontend.schema.GitSourceCloner
import build.kargo.frontend.schema.GitSourceException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.*

/**
 * Resolves Git-backed sources by cloning, checking out specific versions,
 * and building them locally using Kargo.
 *
 * Uses [GitSourceCloner] for the clone/checkout step.
 */
class GitSourceResolver(
    private val cacheRoot: Path = Path(System.getProperty("user.home")).resolve(".kargo/sources-cache")
) {
    private val cloner = GitSourceCloner(cacheRoot)
    // Per-cache-key locks to prevent concurrent clones to the same directory
    private val resolveLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Resolves a Git source by cloning and checking out specific versions.
     *
     * @param repoUrl The Git repository URL
     * @param sourceName A human readable source name
     * @param version The tag, branch or commit SHA to checkout
     * @param subPath An optional subdirectory 
     * @return Path to the checked-out repository root
     */
    fun resolve(repoUrl: String, sourceName: String, version: String, subPath: String? = null): Path {
        val cacheKey = cloner.generateCacheKey(repoUrl, version)
        val cacheDir = cacheRoot.resolve(cacheKey)
        val repoDir = cacheDir.resolve("repo")
        val projectDir = if (subPath != null) repoDir.resolve(subPath) else repoDir

        val lock = resolveLocks.computeIfAbsent(cacheKey) { ReentrantLock() }
        lock.lock()
        try {
            if (isCached(cacheDir)) {
                if (isMutableRef(version)) {
                    // Check if branch advanced upstream
                    val remoteSha = fetchRemoteSha(repoUrl, version)
                    val storedSha = readResolvedCommit(cacheDir)
                    when {
                        remoteSha == null ->
                            logger.warn("Git source '$sourceName' ($version): offline, using cached build.")
                        remoteSha != storedSha -> {
                            logger.info("Git source '$sourceName' ($version) updated upstream, rebuilding...")
                            invalidateCache(cacheDir)
                        }
                        else -> logger.debug("Using cached git source '$sourceName' ($version)")
                    }
                    if (isCached(cacheDir)) return projectDir
                } else {
                    logger.debug("Using cached git source '$sourceName' ($version)")
                    return projectDir
                }
            }

            logger.info("Fetching git source '$sourceName' ($version)...")
            val repoDir = try {
                cloner.cloneOrUpdate(repoUrl, cacheDir.resolve("repo"))
            } catch (e: GitSourceException) {
                throw e
            } catch (e: Exception) {
                throw GitSourceException(
                    rawMessage = "Failed to fetch git source",
                    details = "Source: $sourceName\nRepository: $repoUrl\nError: ${e.message}",
                    cause = e
                )
            }

            logger.debug("Checking out git source '$sourceName' ($version)...")
            try {
                cloner.checkout(repoDir, version)
            } catch (e: Exception) {
                throw GitSourceException(
                    rawMessage = "Failed to checkout version",
                    details = "Repository: $repoUrl\n${e.message}",
                    cause = e
                )
            }

            // Resolve the real commit SHA after checkout (local op, no network)
            val resolvedSha = resolveCurrentSha(repoDir)

            storeMetadata(cacheDir, repoUrl, version, resolvedSha)

            logger.debug("Installed git source '$sourceName' at ${projectDir.absolutePathString()}")
            return projectDir
        } finally {
            lock.unlock()
        }
    }

    private fun isCached(cacheDir: Path): Boolean {
        return cacheDir.resolve(METADATA_FILE_NAME).exists()
            && cacheDir.resolve("repo").exists()
    }

    /** Fetches the current SHA for [version] from the remote. Returns null if offline or on error. */
    private fun fetchRemoteSha(repoUrl: String, version: String): String? = try {
        val output = cloner.executeGitCommand(cacheRoot.also { it.createDirectories() }, "ls-remote", repoUrl, version)
        output.trim().substringBefore("\t").takeIf { isCommitSha(it) }
    } catch (_: Exception) { null }

    /** Resolves the current HEAD commit SHA from a local repo (no network). */
    private fun resolveCurrentSha(repoDir: Path): String =
        cloner.executeGitCommand(repoDir, "rev-parse", "HEAD").trim()

    private fun readResolvedCommit(cacheDir: Path): String? {
        val metadataFile = cacheDir.resolve(METADATA_FILE_NAME)
        if (!metadataFile.exists()) return null
        return try {
            val metadata = json.decodeFromString<GitSourceMetadata>(metadataFile.readText())
            metadata.resolvedCommit
        } catch (e: Exception) {
            null
        }
    }

    private fun invalidateCache(cacheDir: Path) {
        val repoDir = cacheDir.resolve("repo").toFile()
        if (repoDir.exists()) {
            repoDir.deleteRecursively()
        }
        cacheDir.resolve(METADATA_FILE_NAME).deleteIfExists()
    }

    private fun storeMetadata(cacheDir: Path, repoUrl: String, originalVersion: String, resolvedCommit: String) {
        cacheDir.createDirectories()
        val metadata = GitSourceMetadata(
            repositoryUrl = repoUrl,
            originalVersion = originalVersion,
            resolvedCommit = resolvedCommit,
            buildTimestamp = System.currentTimeMillis()
        )
        cacheDir.resolve(METADATA_FILE_NAME).writeText(json.encodeToString(metadata))
    }

    /** Returns true if [version] is a branch or tag name rather than a full commit SHA. */
    private fun isMutableRef(version: String): Boolean = !isCommitSha(version) && !isSemverTag(version)

    private fun isCommitSha(version: String) = version.matches(Regex("[0-9a-f]{40}"))

    // Matches v1.0.0, v2.3.1-beta, 1.4.0, etc.
    private fun isSemverTag(version: String) = version.matches(Regex("v?\\d+\\.\\d+.*"))

    companion object {
        private val logger = LoggerFactory.getLogger(GitSourceResolver::class.java)
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        private const val METADATA_FILE_NAME = "metadata.json"
    }
}

@Serializable
internal data class GitSourceMetadata(
    val repositoryUrl: String,
    val originalVersion: String,
    val resolvedCommit: String,
    val buildTimestamp: Long
)
