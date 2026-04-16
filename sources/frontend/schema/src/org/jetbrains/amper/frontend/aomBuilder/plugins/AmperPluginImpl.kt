/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder.plugins

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.AmperPlugin
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.aomBuilder.ModuleBuildCtx
import org.jetbrains.amper.frontend.aomBuilder.plugins.diagnostics.IsolatedPluginYamlDiagnosticsFactories
import org.jetbrains.amper.frontend.api.isDefault
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.catalogs.substituteCatalogDependencies
import org.jetbrains.amper.frontend.contexts.EmptyContexts
import org.jetbrains.amper.frontend.plugins.PluginYamlRoot
import org.jetbrains.amper.frontend.plugins.generated.ShadowCompilationArtifactKind
import org.jetbrains.amper.frontend.plugins.generated.ShadowResolutionScope
import org.jetbrains.amper.frontend.plugins.generated.ShadowSourcesKind
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.frontend.project.getTaskOutputRoot
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.frontend.tree.BooleanNode
import org.jetbrains.amper.frontend.tree.ErrorNode
import org.jetbrains.amper.frontend.tree.MissingPropertiesHandler
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.TreeRefiner
import org.jetbrains.amper.frontend.tree.add
import org.jetbrains.amper.frontend.tree.buildTree
import org.jetbrains.amper.frontend.tree.completeTree
import org.jetbrains.amper.frontend.tree.get
import org.jetbrains.amper.frontend.tree.instance
import org.jetbrains.amper.frontend.tree.invoke
import org.jetbrains.amper.frontend.tree.mergeTrees
import org.jetbrains.amper.frontend.tree.put
import org.jetbrains.amper.frontend.tree.reading.ReferencesParsingMode
import org.jetbrains.amper.frontend.tree.reading.readTree
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.frontend.types.generated.*
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.problems.reporting.replayProblemsTo

internal class AmperPluginImpl(
    private val projectContext: AmperProjectContext,
    override val pluginModule: AmperModule,
    override val id: PluginData.Id,
    pluginFile: VirtualFile,
    types: SchemaTypingContext,
    problemReporter: ProblemReporter,
    pathResolver: FrontendPathResolver,
) : AmperPlugin {
    private val treeRefiner = TreeRefiner()
    private val pluginYamlDeclaration = types.pluginYamlDeclaration(id)

    // If this tree is null (due to errors), then the plugin will be NOP
    private val pluginTree: RefinedMappingNode? = run {
        val tree = context(problemReporter, pathResolver) {
            readTree(
                file = pluginFile,
                declaration = pluginYamlDeclaration,
                reportUnknowns = true,
                referenceParsingMode = ReferencesParsingMode.Parse,
                parseContexts = false,
            )
        }

        val proxyReporter = CollectingProblemReporter()
        context(proxyReporter) {
            val refinedTree = treeRefiner.refineTree(tree, EmptyContexts)

            for (diagnosticsFactory in IsolatedPluginYamlDiagnosticsFactories) {
                diagnosticsFactory.analyze(refinedTree)
            }

            proxyReporter.replayProblemsTo(problemReporter)
            // If errors are detected, don't save the tree. No point in applying the plugin with errors.
            refinedTree.takeUnless { proxyReporter.problems.any { it.level == Level.Error } }
        }
    }

    context(problemReporter: ProblemReporter)
    fun asAppliedTo(
        module: ModuleBuildCtx,
    ): PluginYamlRoot? = context(problemReporter) {
        if (pluginTree == null) return null
        val moduleRootDir = module.module.source.moduleDir
        val pluginConfiguration = module.pluginsTree[id.value] as? RefinedMappingNode
            ?: return@context null

        val enabled = (pluginConfiguration["enabled"] as? BooleanNode)?.value
        if (enabled != true) {
            reportExplicitValuesWhenDisabled(pluginConfiguration)
            return null
        }

        val taskDirs = (pluginTree[PluginYamlRoot::tasks] as? RefinedMappingNode)
            ?.refinedChildren
            ?.filterValues { it.value !is ErrorNode }
            ?.mapValues { (name, _) ->
                projectContext.getTaskOutputRoot(taskNameFor(module.module, name))
            }.orEmpty()

        // Build a tree with computed "reference-only" values.
        val selfDependency = buildTree(DeclarationOfShadowDependencyLocal) {
            modulePath(moduleRootDir)
        }
        val referenceValuesTree = buildTree(pluginYamlDeclaration) {
            pluginSettings(pluginConfiguration)
            module {
                name(module.module.userReadableName)
                rootDir(moduleRootDir)
                self(selfDependency)
                runtimeClasspath {
                    dependencies { add(selfDependency) }
                }
                compileClasspath {
                    dependencies { add(selfDependency) }
                    scope(ShadowResolutionScope.Compile)
                }
                kotlinJavaSources { from(selfDependency) }
                resources {
                    from(selfDependency)
                    kind(ShadowSourcesKind.Resources)
                }
                jar {
                    from(selfDependency)
                    kind(ShadowCompilationArtifactKind.Jar)
                }
                classes {
                    from(selfDependency)
                    kind(ShadowCompilationArtifactKind.Classes)
                }

                // TODO: This will not include non-common non-main configuration.
                settings(module.moduleCtxModule.settings.backingTree)
                // TODO: Maybe at include test-settings here also?
            }
            project {
                rootDir(projectContext.projectRootDir.toNioPath())
            }
            tasks {
                for ((taskName, taskBuildRoot) in taskDirs) {
                    put[taskName] {
                        taskOutputDir(taskBuildRoot)
                    }
                }
            }
        }

        val mergedTree = mergeTrees(pluginTree, referenceValuesTree)
            .substituteCatalogDependencies(pluginModule.usedCatalog)
        treeRefiner.refineTree(mergedTree, EmptyContexts)
            .completeTree(MissingPropertiesHandler.Noop)?.instance<PluginYamlRoot>()
    }

    fun taskNameFor(module: AmperModule, name: String) =
        TaskName.moduleTask(module, "$name@${id.value}")

    context(problemReporter: ProblemReporter)
    private fun reportExplicitValuesWhenDisabled(pluginConfiguration: RefinedMappingNode) {
        val explicitValues = pluginConfiguration.children
            .filterNot { it.trace.isDefault }
        if (explicitValues.isNotEmpty()) {
            val source = MultipleLocationsBuildProblemSource(
                explicitValues.mapNotNull { it.asBuildProblemSource() as? FileBuildProblemSource },
                groupingMessage = SchemaBundle.message("plugin.unexpected.configuration.when.disabled.grouping"),
            )
            problemReporter.reportBundleError(
                source = source,
                diagnosticId = PluginDiagnosticId.PluginNotEnabledButConfigured,
                messageKey = "plugin.unexpected.configuration.when.disabled", id.value,
                level = Level.Warning,
            )
        }
    }
}
