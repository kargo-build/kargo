package build.kargo.frontend.dr.resolver

import org.jetbrains.amper.frontend.GitSourcesModulePart
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.aomBuilder.DefaultFragment
import org.jetbrains.amper.frontend.aomBuilder.DefaultLocalModuleDependency
import org.jetbrains.amper.frontend.api.DefaultTrace
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("build.kargo.frontend.dr.resolver.gitModelExtensions")

/**
 * Kargo-specific post-processing step: injects Git source modules as local module dependencies.
 *
 * Git sources declared in `module.yaml` under `sources:` are pre-scanned and resolved to local
 * checkout directories via [GitSourcePreScanner.preScanAndResolveGitSources]. This step connects
 * the resolved module dirs back to the consumer modules as [DefaultLocalModuleDependency],
 * completing the dependency graph.
 *
 * Must be called after [readProjectModel] and [withGitSources].
 */
fun Model.withGitDependencies(): Model {
    val cloner = GitSourceCloner()
    val dir2module = modules.associateBy { it.source.buildFile.parent }

    for (module in modules) {
        val gitSources = module.parts
            .filterIsInstance<GitSourcesModulePart>()
            .firstOrNull()
            ?.gitSources
            ?: continue

        val gitDependencies = gitSources.mapNotNull { source ->
            val projectDir = runCatching { cloner.resolveSourcesDir(source) }.getOrElse { e ->
                logger.warn("withGitDependencies: failed to resolve $source, skipping", e)
                return@mapNotNull null
            }
            val targetModule = dir2module[projectDir] ?: return@mapNotNull null
            DefaultLocalModuleDependency(
                module = targetModule,
                path = projectDir,
                trace = DefaultTrace,
                compile = true,
                runtime = true,
                exported = false,
            )
        }

        if (gitDependencies.isNotEmpty()) {
            module.fragments.forEach { fragment ->
                (fragment as? DefaultFragment)?.let {
                    it.externalDependencies = it.externalDependencies + gitDependencies
                }
            }
        }
    }

    return this
}
