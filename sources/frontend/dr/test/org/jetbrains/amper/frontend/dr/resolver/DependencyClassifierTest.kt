/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.div

class DependencyClassifierTest: BaseModuleDrTest() {

    override val testGoldenFilesRoot: Path = super.testGoldenFilesRoot / "classifier"

    /**
     * Checks that direct dependency with classifier is correctly resolved.
     * In particular, the module 'shared' of the test project 'classifier-support'
     * declares a direct exported dependency on the 'org.bytedeco:opencv:4.5.5-1.5.7:windows-x86_64'.
     * The classifier 'windows-x86_64' is a part of coordinates and should be taken into account.
     */
    @Test
    fun `direct dependency with classifier`(testInfo: TestInfo) = runModuleDependenciesTest {
        val aom = getTestProjectModel("classifier-support", testDataRoot)

        val jvmAppDeps = doTestByFile(
            testInfo,
            aom,
            ResolutionInput(
                DependenciesFlowType.ClassPathType(
                    ResolutionScope.COMPILE,
                    setOf(ResolutionPlatform.JVM),
                    false,
                    false)
                ,
                ResolutionDepth.GRAPH_FULL,
                fileCacheBuilder = getAmperFileCacheBuilder(amperUserCacheRoot),
            ),
            module = "jvm-app",
        )

        assertFiles(
            testInfo,
            root = jvmAppDeps,
        )
    }
}