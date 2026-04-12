/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.LinuxOnly
import org.jetbrains.amper.test.MacOnly
import kotlin.test.Test

class CinteropTest : AmperCliTestBase() {
    @Test
    @MacOnly
    fun `single app - run macosArm64`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/single-app-curl"),
            "run", "--platform=macosArm64",
        ).assertStdoutContains(EXAMPLE_COM_RESPONSE_TEXT)
    }

    @Test
    @MacOnly
    fun `lib + app - run macosArm64`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/lib-and-app-curl"),
            "run", "--module=app-mac", "--platform=macosArm64",
        ).assertStdoutContains(EXAMPLE_COM_RESPONSE_TEXT)
    }

    @Test
    @LinuxOnly
    fun `lib + app - run linuxX64`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/lib-and-app-curl"),
            "run", "--module=app-linux",
        ).assertStdoutContains(EXAMPLE_COM_RESPONSE_TEXT)
    }

    @Test
    @MacOnly
    fun `via plugin`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/cinterop-plugin"),
            "run", "--platform=macosArm64",
        )
    }
}

private const val EXAMPLE_COM_RESPONSE_TEXT = "<title>Example Domain</title>"
