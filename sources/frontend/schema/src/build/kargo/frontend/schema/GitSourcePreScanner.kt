package build.kargo.frontend.schema

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.frontend.project.amperModuleFileNames
import org.slf4j.LoggerFactory
import org.jetbrains.amper.frontend.types.generated.DeclarationOfModule
import org.jetbrains.amper.frontend.tree.*
import org.jetbrains.amper.frontend.tree.reading.readTree
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.frontend.contexts.EmptyContexts
import org.jetbrains.amper.frontend.types.SchemaTypingContext

object GitSourcePreScanner {
    private val logger = LoggerFactory.getLogger(GitSourcePreScanner::class.java)

    fun preScanAndResolveGitSources(
        initialModuleFiles: List<VirtualFile>,
        frontendPathResolver: FrontendPathResolver
    ): List<VirtualFile> {
        val cloner = GitSourceCloner()
        val allScannedModuleFiles = mutableSetOf<VirtualFile>()
        val queue = ArrayDeque(initialModuleFiles)
        val reporter = CollectingProblemReporter()
        
        while (queue.isNotEmpty()) {
            val moduleFile = queue.removeFirst()
            if (!allScannedModuleFiles.add(moduleFile)) continue

            with(reporter) {
                with(frontendPathResolver) {
                    val tree = readTree(moduleFile, SchemaTypingContext().moduleDeclaration, reportUnknowns = false)
                    val refined = TreeRefiner().refineTree(tree, EmptyContexts)
                    val gitSources = extractGitSources(refined)

                    for (parsed in gitSources) {
                        logger.debug("Pre-scanning detected Git source: ${parsed.url} (${parsed.version}) in ${moduleFile.path}")
                        val resolvedDir = runCatching { cloner.resolveSourcesDir(parsed.url, parsed.version, parsed.path) }.getOrNull() ?: continue

                        // Directly load the module file instead of iterating VFS `.children` which might be cached as empty
                        val clonedModuleFile = amperModuleFileNames.firstNotNullOfOrNull { name ->
                            val filePath = resolvedDir.resolve(name)
                            if (filePath.toFile().exists()) frontendPathResolver.loadVirtualFileOrNull(filePath) else null
                        }

                        if (clonedModuleFile != null && clonedModuleFile !in allScannedModuleFiles) {
                            queue.addLast(clonedModuleFile)
                        }
                    }
                }
            }
        }
        
        return allScannedModuleFiles.toList()
    }

    private data class ParsedSource(val url: String, val version: String, val path: String?)

    context(_: ProblemReporter)
    private fun extractGitSources(node: RefinedTreeNode?): List<ParsedSource> {
        val results = mutableListOf<ParsedSource>()
        
        fun visit(current: RefinedTreeNode?) {
            if (current == null) return
            
            if (current is RefinedMappingNode) {
                val parsed = tryParseAsGitSource(current)
                if (parsed != null) {
                    results.add(parsed)
                } else {
                    current.children.forEach { visit(it.value) }
                }
            } else if (current is RefinedListNode) {
                current.children.forEach { visit(it) }
            }
        }

        // We scan sources, dependencies, and test-dependencies blocks structuraly
        // but extract the data using the formal schema nodes
        if (node is RefinedMappingNode) {
            visit(node["sources"])
            visit(node["dependencies"])
            visit(node["test-dependencies"])
        }
        
        return results
    }

    /**
     * Tries to convert a [RefinedMappingNode] into a typed [GitSource] schema object.
     * Use best-effort approach to extract resolution parameters.
     */
    context(_: ProblemReporter)
    private fun tryParseAsGitSource(node: RefinedMappingNode): ParsedSource? {
        // Quick check for git-related keys before attempting full tree completion
        val keys = node.refinedChildren.keys
        if ("git" !in keys && "github" !in keys && "gitlab" !in keys && "bitbucket" !in keys) return null
        
        // Use Amper's tree completion to get a typed SchemaNode instance
        // Best-effort: if it doesn't match GitSource schema, we skip it
        val complete = node.completeTree() ?: return null
        val source = complete.instance as? GitSource ?: return null
        
        val cloner = GitSourceCloner()
        return ParsedSource(
            url = cloner.extractRepositoryUrl(source),
            version = source.version.value,
            path = source.path?.toString()
        )
    }
}
