package build.kargo.frontend.dr.resolver

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.problems.reporting.BuildProblemImpl
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.WholeFileBuildProblemSource
import org.jetbrains.amper.frontend.diagnostics.FrontendDiagnosticId
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.frontend.aomBuilder.asPsi
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText

import org.jetbrains.amper.frontend.project.amperModuleFileNames

@OptIn(NonIdealDiagnostic::class)
object GitSourcePreScanner {
    private val logger = LoggerFactory.getLogger(GitSourcePreScanner::class.java)

    fun preScanAndResolveGitSources(
        initialModuleFiles: List<VirtualFile>,
        frontendPathResolver: FrontendPathResolver,
        reporter: ProblemReporter
    ): List<VirtualFile> {
        val cloner = GitSourceCloner()
        val allScannedModuleFiles = mutableSetOf<VirtualFile>()
        val queue = ArrayDeque(initialModuleFiles)

        while (queue.isNotEmpty()) {
            val moduleFile = queue.removeFirst()
            if (!allScannedModuleFiles.add(moduleFile)) continue

            val gitSources = with(frontendPathResolver) { extractGitSources(moduleFile) }

            for (parsed in gitSources) {
                logger.debug("Pre-scanning detected Git source: ${parsed.url} (${parsed.version}) in ${moduleFile.path}")
                val resolvedDir = runCatching {
                    cloner.resolveSourcesDir(parsed.url, parsed.version, parsed.path)
                }.getOrElse { error ->
                    logger.warn("Failed to resolve Git source: ${parsed.url}@${parsed.version} - ${error.message}")
                    val errorMessage = when (error) {
                        is GitSourceException -> error.details ?: error.rawMessage
                        else -> error.message ?: "Unknown error"
                    }
                    with(reporter) {
                        reportMessage(
                            BuildProblemImpl(
                                source = WholeFileBuildProblemSource(Path(moduleFile.path)),
                                message = "Failed to resolve Git dependency: ${parsed.url}@${parsed.version}\n$errorMessage",
                                level = Level.Error,
                                type = BuildProblemType.UnresolvedReference,
                                diagnosticId = FrontendDiagnosticId.GitSourceResolutionFailed
                            )
                        )
                    }
                    null
                } ?: continue

                val clonedModuleFile = amperModuleFileNames.firstNotNullOfOrNull { name ->
                    val filePath = resolvedDir.resolve(name)
                    if (filePath.toFile().exists()) frontendPathResolver.loadVirtualFileOrNull(filePath) else null
                }

                if (clonedModuleFile != null && clonedModuleFile !in allScannedModuleFiles) {
                    queue.addLast(clonedModuleFile)
                }
            }
        }

        return allScannedModuleFiles.toList()
    }

    private data class ParsedSource(val url: String, val version: String, val path: String?)

    /**
     * Extracts Git source declarations from a module file using IntelliJ YAML PSI.
     * Avoids regex and uses a robust YAML traversal.
     */
    context(_: FrontendPathResolver)
    private fun extractGitSources(moduleFile: VirtualFile): List<ParsedSource> {
        val psiFile = moduleFile.asPsi() as? org.jetbrains.yaml.psi.YAMLFile ?: return emptyList()
        val results = mutableListOf<ParsedSource>()

        val documents = psiFile.documents
        if (documents.isEmpty()) return emptyList()
        val mapping = documents.first().topLevelValue as? org.jetbrains.yaml.psi.YAMLMapping ?: return emptyList()

        for (sectionKey in listOf("sources", "dependencies", "test-dependencies")) {
            val section = mapping.getKeyValueByKey(sectionKey)?.value as? org.jetbrains.yaml.psi.YAMLSequence ?: continue
            for (item in section.items) {
                val itemMapping = item.value as? org.jetbrains.yaml.psi.YAMLMapping ?: continue
                
                var type: String? = null
                var identifier: String? = null
                
                for (key in listOf("github", "gitlab", "bitbucket", "git")) {
                    val kv = itemMapping.getKeyValueByKey(key)
                    if (kv != null) {
                        type = key
                        identifier = kv.valueText
                        break
                    }
                }
                
                if (type != null && identifier != null) {
                    val version = itemMapping.getKeyValueByKey("version")?.valueText ?: continue
                    val subPath = itemMapping.getKeyValueByKey("path")?.valueText

                    val url = when (type) {
                        "github" -> "https://github.com/$identifier.git"
                        "gitlab" -> "https://gitlab.com/$identifier.git"
                        "bitbucket" -> "https://bitbucket.org/$identifier.git"
                        "git" -> identifier
                        else -> continue
                    }

                    results.add(ParsedSource(url = url, version = version, path = subPath))
                }
            }
        }

        return results
    }
}
