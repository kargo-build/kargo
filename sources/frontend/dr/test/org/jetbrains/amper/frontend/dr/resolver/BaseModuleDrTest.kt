/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import org.intellij.lang.annotations.Language
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.FileCacheBuilder
import org.jetbrains.amper.dependency.resolution.IncrementalCacheUsage
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.ResolvedGraph
import org.jetbrains.amper.dependency.resolution.Resolver
import org.jetbrains.amper.dependency.resolution.RootDependencyNodeStub
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.SimpleDiagnosticDescriptor
import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
import org.jetbrains.amper.dependency.resolution.getDefaultFileCacheBuilder
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.assertEqualsWithDiff
import org.jetbrains.amper.test.golden.goldenFileOsAware
import org.jetbrains.amper.test.runTestRespectingDelays
import org.junit.jupiter.api.TestInfo
import org.opentest4j.AssertionFailedError
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

abstract class BaseModuleDrTest {
    protected open val testGoldenFilesRoot: Path = Dirs.amperSourcesRoot.resolve("frontend/dr/testData/goldenFiles")
    protected val testDataRoot: Path = Dirs.amperSourcesRoot.resolve("frontend/dr/testData/projects")

    private val defaultMessagesCheck: (DependencyNode) -> Unit = { node ->
        val messages = node.messages.defaultFilterMessages()
        assertTrue(messages.isEmpty(), "There must be no messages for $this:\n${messages.joinToString("\n") { it.detailedMessage }}")
    }

    protected suspend fun doTestByFile(
        testInfo: TestInfo,
        aom: Model,
        resolutionInput: ResolutionInput,
        verifyMessages: Boolean = true,
        module: String? = null,
        fragment: String? = null,
        goldenFileName: String = testInfo.testMethod.get().name,
        filter: ModuleResolutionFilter? = null,
        messagesCheck: (DependencyNode) -> Unit = defaultMessagesCheck
    ): DependencyNode {
        val goldenFile = goldenFileOsAware(
            "${goldenFileName.replace(" ", "_")}.tree.txt")
        val expected = getGoldenFileText(goldenFile, fileDescription = "Golden file for resolved tree")
        return withActualDump(goldenFile) {
            doTest(aom, resolutionInput, verifyMessages, expected, module, fragment, filter, messagesCheck)
        }
    }

    protected suspend fun doTest(
        aom: Model,
        resolutionInput: ResolutionInput,
        verifyMessages: Boolean = true,
        @Language("text") expected: String? = null,
        module: String? = null,
        fragment: String? = null,
        filter: ModuleResolutionFilter? = null,
        messagesCheck: (DependencyNode) -> Unit = defaultMessagesCheck
    ): DependencyNode {
        val resolutionInputCopy = resolutionInput
            .copy(fileCacheBuilder = cacheBuilder(Dirs.userCacheRoot))
            .copy(incrementalCacheUsage = getIncrementalCacheUsage())

        val graph =
            if (module == null && resolutionInput.dependenciesFlowType is DependenciesFlowType.IdeSyncType) {
                with (ModuleDependencies) {
                    aom.resolveProjectDependencies(
                        resolutionInputCopy,
                        AmperUserCacheRoot(Dirs.userCacheRoot)
                    ).also { checkMessages(verifyMessages, it, messagesCheck) }
                        .root
                }
            } else {
                val resolutionType = when {
                    resolutionInput.dependenciesFlowType is DependenciesFlowType.IdeSyncType
                            || (resolutionInput.dependenciesFlowType is DependenciesFlowType.ClassPathType
                                && resolutionInput.dependenciesFlowType.isTest) -> ResolutionType.ALL
                    else -> ResolutionType.MAIN
                }
                val modules = aom.modules.filter { module == null || it.userReadableName == module }
                ModuleDependencies.resolveModuleDependencies(
                    modules,
                    resolutionInputCopy,
                    AmperUserCacheRoot(Dirs.userCacheRoot),
                    filter = filter,
                    resolutionType = resolutionType,
                )
                    .also { checkMessages(verifyMessages, it, messagesCheck) }
                    .let {
                        if (module != null && fragment != null) {
                            if (filter?.platforms != null) error("platforms could not be used as a filter together with fragment")
                            val fragmentDeps = it.root.children
                                .filterIsInstance<ModuleDependencyNode>()
                                .fragmentDependencies(module, fragment, aom)
                            RootDependencyNodeStub(
                                graphEntryName = "Fragment '$module:$fragment' dependencies",
                                children = fragmentDeps
                            )
                        } else {
                            it.root
                        }
                    }
            }


        expected?.let {
            val moduleDeps = graph.children.filterIsInstance<ModuleDependencyNode>()
            assertEquals(moduleDeps.size, graph.children.size,
                "Unexpected dependency type is among root children: " +
                        (graph.children - moduleDeps).map{ it::class.simpleName }.joinToString()
            )
            assertModuleDepsEquals(expected, moduleDeps)
        }

        return graph
    }

    internal fun List<DirectFragmentDependencyNode>.filterByScope(scope: ResolutionScope?) =
        scope?.let {
            filter {
                (it.dependencyNode as? MavenDependencyNode)?.dependency?.resolutionConfig?.scope == scope
            }
        } ?: this

    protected fun cacheBuilder(cacheRoot: Path): FileCacheBuilder.() -> Unit = {
        getDefaultFileCacheBuilder(cacheRoot).invoke(this)
        readOnlyExternalRepositories = emptyList()
    }

    protected fun assertModuleDepsEquals(@Language("text") expected: String, moduleDeps: List<ModuleDependencyNode>, forMavenNode: MavenCoordinates? = null) {
        assertEquals(expected, moduleDeps, forMavenNode)
    }

    private fun assertEquals(@Language("text") expected: String, roots: List<DependencyNode>, forMavenNode: MavenCoordinates? = null) {
        val actual = StringBuilder()
        roots.forEach {
            actual.append(it.prettyPrint(forMavenNode))
        }
        assertEqualsWithDiff(
            expected.trimEnd().lines(),
            actual.trimEnd().lines()
        )
    }

    protected fun assertEquals(@Language("text") expected: String, root: DependencyNode, forMavenNode: MavenCoordinates? = null) {
        assertEquals(expected, listOf(root), forMavenNode)
    }

    protected fun assertFiles(
        testInfo: TestInfo,
        root: DependencyNode,
        withSources: Boolean = false,
        checkExistence: Boolean = false,
        checkAutoAddedDocumentation: Boolean = true,
        scope: ResolutionScope? = null,
    ) {
        val goldenFile = goldenFileOsAware(
            "${testInfo.testMethod.get().name.replace(" ", "_")}.files.txt")
        val expected = getGoldenFileText(goldenFile, fileDescription = "Golden file for files")
        withActualDump(goldenFile) {
            assertFiles(expected.trim().lines(), root, withSources, checkExistence, checkAutoAddedDocumentation, scope)
        }
    }

    // todo (AB) : Reuse utility methods from dependence-resolution test module
    protected fun assertFiles(
        expectedFiles: List<String>,
        root: DependencyNode,
        withSources: Boolean = false,
        checkExistence: Boolean = false,// could be set to true only in case dependency files were downloaded by caller already
        checkAutoAddedDocumentation: Boolean = true, // auto-added documentation files are skipped fom check if this flag is false.
        scope: ResolutionScope? = null,
    ) {
        root.distinctBfsSequence()
            .filterIsInstance<MavenDependencyNode>()
            .groupBy { it.dependency.resolutionConfig.scope } // todo (AB) : Group by module and test/main as well
            .filterKeys { scope == null || it == scope }
            .mapValues {
                it.value.flatMap { it.dependency.files(withSources) }
                    .filterNot { !checkAutoAddedDocumentation && it.isAutoAddedDocumentation }
                    .mapNotNull { it.path }
                    .sortedBy { it.name }
                    .toSet()
            }.also { filesPerScope ->
                val actualList = buildList {
                    for (key in filesPerScope.keys.sorted()) {
                        add("$key")
                        addAll(filesPerScope[key]!!.map { it.name })
                    }
                }
                assertEqualsWithDiff(expectedFiles, actualList)

                if (checkExistence) {
                    filesPerScope.flatMap { it.value }.forEach {
                        check(it.exists()) {
                            "File $it was returned from dependency resolution, but is missing on disk"
                        }
                    }
                }
            }
    }

    protected fun getGoldenFileText(goldenFile: Path, fileDescription: String): String {
        if (!goldenFile.exists()) { goldenFile.createFile() }
        return goldenFile
            .readText()
            .replace("#kotlinVersion", DefaultVersions.kotlin)
            .replace("#composeDefaultVersion", DefaultVersions.compose)
            .trim()
    }

    protected fun goldenFileOsAware(goldenFileBaseName: String) =
        testGoldenFilesRoot.goldenFileOsAware(goldenFileBaseName)

    companion object {
        /**
         * Run every test twice if [checkIncrementalCache] is set to true
         * (the first run without cache, the second with cache populated during the first run)
         */
        internal fun runModuleDependenciesTest(
            checkIncrementalCache: Boolean = true,
            timeout: Duration = 1.minutes,
            testBody: suspend TestScope.() -> Unit
        ) {
            if (checkIncrementalCache) {
                val incrementalCacheUsageContext =
                    IncrementalCacheUsageContextElement(IncrementalCacheUsage.REFRESH_AND_USE)
                runTestRespectingDelays(
                    context = incrementalCacheUsageContext,
                    timeout = timeout,
                    testBody = {
                        executeWithAndWithoutCache(incrementalCacheUsageContext, testBody)
                    }
                )
            } else {
                runTestRespectingDelays(testBody = testBody, timeout = timeout)
            }
        }

        /**
         * Run every test twice if [checkIncrementalCache] is set to true
         * (the first run without cache, the second with cache populated during the first run)
         *
         * test timeout is 5 minutes by default
         */
        internal fun runSlowModuleDependenciesTest(
            checkIncrementalCache: Boolean = true,
            timeout: Duration = 5.minutes,
            testBody: suspend TestScope.() -> Unit
        ) = runModuleDependenciesTest(checkIncrementalCache, timeout, testBody)

        private suspend fun TestScope.executeWithAndWithoutCache(
            incrementalCacheUsageContext: IncrementalCacheUsageContextElement,
            testBody: suspend TestScope.() -> Unit,
        ) {
            try {
                println("Running test with resolutionCacheUsage=${incrementalCacheUsageContext.incrementalCacheUsage}")
                testBody()

                incrementalCacheUsageContext.incrementalCacheUsage = IncrementalCacheUsage.SKIP
                println("Running test with resolutionCacheUsage=${incrementalCacheUsageContext.incrementalCacheUsage}")
                testBody()
            } finally {
                incrementalCacheUsageContext.incrementalCacheUsage = IncrementalCacheUsage.SKIP
            }
        }

        internal suspend fun getIncrementalCacheUsage() =
            currentCoroutineContext()[IncrementalCacheUsageContextElementKey]?.incrementalCacheUsage?: IncrementalCacheUsage.SKIP

        fun List<Message>.defaultFilterMessages(): List<Message> =
            filter { "Downloaded " !in it.message && "Resolved " !in it.message }

        internal fun DependencyNode.verifyOwnMessages(filterMessages: List<Message>.() -> List<Message> = { defaultFilterMessages() }) {
            val messages = this.messages.filterMessages()
            assertTrue(
                messages.isEmpty(),
                "There must be no messages for $this:\n${messages.joinToString("\n") { it.detailedMessage }}"
            )
        }

        internal inline fun <T> withActualDump(expectedResultPath: Path? = null, block: () -> T): T {
            return try {
                block()
            } catch (e: AssertionFailedError) {
                if (e.isExpectedDefined && e.isActualDefined && expectedResultPath != null) {
                    val actualResultPath = expectedResultPath.parent.resolve(expectedResultPath.fileName.name + ".tmp")
                    when(val actualValue = e.actual.value) {
                        is List<*> -> actualResultPath.writeLines(actualValue.map { it.toString().replaceVersionsWithVariables() })
                        is String -> actualResultPath.writeText(actualValue.replaceVersionsWithVariables())
                        else -> { /* do nothing */ }
                    }
                }
                throw e
            }.also {
                expectedResultPath?.parent?.resolve(expectedResultPath.fileName.name + ".tmp")?.deleteIfExists()
            }
        }

        private fun String.replaceVersionsWithVariables(): String =
            replaceArtifactFilenames(
                filePrefix = "kotlin-stdlib",
                version = DefaultVersions.kotlin,
                versionVariableName = "kotlinVersion",
            )
                .replaceCoordinateVersionWithReference(
                    groupPrefix = "org.jetbrains.kotlin",
                    artifactPrefix = "kotlin-",
                    version = DefaultVersions.kotlin,
                    versionVariableName = "kotlinVersion",
                )
                .replaceCoordinateVersionWithReference(
                    groupPrefix = "org.jetbrains.compose",
                    artifactPrefix = "",
                    version = DefaultVersions.compose,
                    versionVariableName = "composeDefaultVersion",
                )

        private fun String.replaceArtifactFilenames(
            filePrefix: String,
            version: String,
            versionVariableName: String,
        ): String = replace(Regex("""${Regex.escape(filePrefix)}.*-${Regex.escape(version)}\.(jar|aar|klib)""")) {
            it.value.replace(version, "#$versionVariableName")
        }
        private fun String.replaceCoordinateVersionWithReference(
            groupPrefix: String,
            artifactPrefix: String,
            version: String,
            versionVariableName: String,
        ): String = replace(Regex("""${Regex.escape(groupPrefix)}[^:]*:${Regex.escape(artifactPrefix)}[^:]*:([\w.\-]+ -> )?${Regex.escape(version)}""")) {
            it.value.replace(version, "#$versionVariableName")
        }
    }

    internal fun assertTheOnlyNonInfoMessage(
        root: DependencyNode,
        diagnostic: SimpleDiagnosticDescriptor,
        severity: Severity = diagnostic.defaultSeverity,
        transitively: Boolean = false
    ) {
        val nodes = if (transitively) root.distinctBfsSequence() else sequenceOf(root)
        val messages = nodes.flatMap{ it.children.flatMap { it.messages.defaultFilterMessages() } }
        val message = messages.singleOrNull()
        assertNotNull(message, "A single error message is expected, but found: ${messages.toSet()}")
        assertEquals(
            diagnostic.id,
            message.id,
            "Unexpected error message"
        )
        assertEquals(
            severity,
            message.severity,
            "Unexpected severity of the error message"
        )
    }

    // todo (AB): [AMPER-4905] Remove on final cleanup
    protected suspend fun <T> timed(text: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val end = System.currentTimeMillis()
            println("#######################")
            println("Execution time: ${end - start} ms ($text)")
        }
    }

    // todo (AB): [AMPER-4905] Remove on final cleanup
    protected fun <T> timedBlocking(text: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val end = System.currentTimeMillis()
            println("#######################")
            println("Execution time: ${end - start} ms ($text)")
        }
    }

    protected fun context(
        scope: ResolutionScope = ResolutionScope.COMPILE,
        platform: Set<ResolutionPlatform> = setOf(ResolutionPlatform.JVM),
        cacheBuilder: FileCacheBuilder.() -> Unit = cacheBuilder(Dirs.userCacheRoot),
        openTelemetry: OpenTelemetry? = null,
        incrementalCache: IncrementalCache? = null
    ) = Context {
        this.scope = scope
        this.platforms = platform
        this.cache = cacheBuilder
        this.openTelemetry = openTelemetry
        this.incrementalCache = incrementalCache
    }
}

private fun checkMessages(
    verifyMessages: Boolean,
    graph: ResolvedGraph,
    messagesCheck: (DependencyNode) -> Unit,
) {
    if (verifyMessages) {
        graph.root.distinctBfsSequence().forEach {
            messagesCheck(it)
        }
    }
}

private object IncrementalCacheUsageContextElementKey: CoroutineContext.Key<IncrementalCacheUsageContextElement>

internal class IncrementalCacheUsageContextElement(
    var incrementalCacheUsage: IncrementalCacheUsage
) : CoroutineContext.Key<IncrementalCacheUsageContextElement>, CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = IncrementalCacheUsageContextElementKey

    override fun toString(): String = "ResolutionCacheUsageContextElement"
}

