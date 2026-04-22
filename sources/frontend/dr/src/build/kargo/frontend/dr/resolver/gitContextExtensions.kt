package build.kargo.frontend.dr.resolver

import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.frontend.project.StandaloneAmperProjectContext
import org.jetbrains.amper.problems.reporting.ProblemReporter

/**
 * Expands the module list of this [AmperProjectContext] by pre-scanning all declared Git sources
 * and resolving their local checkout directories.
 *
 * This is a Kargo-specific extension. Git source dependencies are a Kargo feature that allows
 * declaring external Git repositories as source dependencies directly in `module.yaml`.
 *
 * If no new modules are discovered (or if the scan fails), the original context is returned unchanged.
 */
context(reporter: ProblemReporter)
fun AmperProjectContext.withGitSources(): AmperProjectContext {
    val expandedModuleFiles = try {
        GitSourcePreScanner.preScanAndResolveGitSources(amperModuleFiles, frontendPathResolver, reporter)
    } catch (e: Exception) {
        return this
    }

    if (expandedModuleFiles.size == amperModuleFiles.size && expandedModuleFiles.containsAll(amperModuleFiles)) {
        return this
    }

    return StandaloneAmperProjectContext(
        frontendPathResolver = frontendPathResolver,
        projectRootDir = projectRootDir,
        projectBuildDir = projectBuildDir,
        amperModuleFiles = expandedModuleFiles,
        pluginsModuleFiles = pluginsModuleFiles,
        externalMavenPlugins = externalMavenPlugins,
    )
}
