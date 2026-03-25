/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.maven

import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.TransformedValueTrace
import org.jetbrains.amper.frontend.contexts.Context
import org.jetbrains.amper.frontend.contexts.TestCtx
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.ObjectBuilderBlock
import org.jetbrains.amper.frontend.tree.buildTree
import org.jetbrains.amper.frontend.tree.mergeTrees
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.frontend.types.generated.*
import java.nio.file.Path

internal typealias ModuleBuilderBlock = ObjectBuilderBlock<DeclarationOfModule>
internal typealias ProjectBuilderBlock = ObjectBuilderBlock<DeclarationOfProject>
internal typealias YamlKeyPath = List<String>
internal typealias YamlComments = Map<YamlKeyPath, YamlComment>

internal data class YamlComment(
    val path: List<String>,
    val beforeKeyComment: String? = null,
    val afterValueComment: String? = null,
    val test: Boolean = false,
)

internal data class ModuleWithComments(
    val tree: MappingNode,
    val comments: YamlComments,
)

internal data class AmperForest(
    val projectPath: Path,
    val projectTree: MappingNode,
    val modules: Map<Path, ModuleWithComments>,
)

internal fun amperProjectTreeBuilder(
    projectPath: Path,
    mavenPlugins: List<MavenPluginXml> = emptyList(),
    block: ProjectTreeBuilder.() -> Unit,
): ProjectTreeBuilder =
    ProjectTreeBuilder(projectPath, mavenPlugins).also { block(it) }

internal class ProjectTreeBuilder(val projectPath: Path, mavenPlugins: List<MavenPluginXml> = emptyList()) {

    // pure DefaultTrace can't be used here because default trace can't override anything
    private val dummyTransformedTrace = TransformedValueTrace(
        "dummy",
        TraceableString("dummy", DefaultTrace),
    )

    private val types = SchemaTypingContext(emptyList(), mavenPlugins)

    private fun buildModuleDefault(
        extraContexts: List<Context> = listOf(MavenContributorContext.Default),
        block: ModuleBuilderBlock,
    ) = buildTree(
        types.moduleDeclaration,
        dummyTransformedTrace,
        contexts = extraContexts,
        block = block,
    )

    private fun buildModuleTest(
        extraContexts: List<Context> = listOf(MavenContributorContext.Default),
        block: ModuleBuilderBlock,
    ) = buildTree(
        types.moduleDeclaration,
        dummyTransformedTrace,
        contexts = listOf(TestCtx) + extraContexts,
        block = block,
    )

    private var projectTree: MappingNode? = null

    private val modules: MutableMap<Path, ModuleTreeBuilder> = mutableMapOf()

    internal inner class ModuleTreeBuilder {
        private var defaultTree: MappingNode? = null
        private var testTree: MappingNode? = null
        private val yamlComments: MutableMap<YamlKeyPath, YamlComment> = mutableMapOf()

        fun withDefaultContext(
            extraContexts: List<Context> = listOf(MavenContributorContext.Default),
            block: ObjectBuilderBlock<DeclarationOfModule>,
        ) {
            val newTree = buildModuleDefault(extraContexts, block)
            defaultTree = defaultTree?.let { mergeTrees(listOf(it, newTree)) } ?: newTree
        }

        fun withTestContext(
            extraContexts: List<Context> = listOf(MavenContributorContext.Default),
            block: ObjectBuilderBlock<DeclarationOfModule>,
        ) {
            val newTree = buildModuleTest(extraContexts, block)
            testTree = testTree?.let { mergeTrees(listOf(it, newTree)) } ?: newTree
        }

        fun addYamlComment(comment: YamlComment) {
            yamlComments[comment.path] = comment
        }

        fun merge(other: ModuleTreeBuilder) {
            other.defaultTree?.let { otherDefaultTree ->
                defaultTree = defaultTree?.let { mergeTrees(listOf(it, otherDefaultTree)) } ?: otherDefaultTree
            }
            other.testTree?.let { otherTestTree ->
                testTree = testTree?.let { mergeTrees(listOf(it, otherTestTree)) } ?: otherTestTree
            }
            yamlComments.putAll(other.yamlComments)
        }

        fun build(): ModuleWithComments = ModuleWithComments(
            tree = mergeTrees(
                listOf(
                    defaultTree ?: buildModuleDefault { },
                    testTree ?: buildModuleTest { },
                )
            ),
            comments = yamlComments.toMap()
        )
    }

    inline fun module(modulePath: Path, block: ModuleTreeBuilder.() -> Unit) {
        val existing = modules[modulePath]
        if (existing != null) {
            existing.apply(block)
        } else {
            modules[modulePath] = ModuleTreeBuilder().apply(block)
        }
    }

    fun project(block: ProjectBuilderBlock) {
        val newTree = buildTree(DeclarationOfProject, dummyTransformedTrace, block = block)
        projectTree = projectTree?.let { mergeTrees(listOf(it, newTree)) } ?: newTree
    }

    fun merge(other: ProjectTreeBuilder): ProjectTreeBuilder {
        other.projectTree?.let { otherProjectTree ->
            projectTree = projectTree?.let { mergeTrees(listOf(it, otherProjectTree)) } ?: otherProjectTree
        }

        for ((path, otherModuleBuilder) in other.modules) {
            val existingModuleBuilder = modules[path]
            if (existingModuleBuilder != null) {
                existingModuleBuilder.merge(otherModuleBuilder)
            } else {
                val newBuilder = ModuleTreeBuilder()
                newBuilder.merge(otherModuleBuilder)
                modules[path] = newBuilder
            }
        }

        return this
    }

    fun build(): AmperForest = AmperForest(
        projectPath = projectPath,
        projectTree = projectTree ?: buildTree(DeclarationOfProject) { },
        modules = modules.mapValues { (_, builder) ->
            builder.build()
        }
    )
}
