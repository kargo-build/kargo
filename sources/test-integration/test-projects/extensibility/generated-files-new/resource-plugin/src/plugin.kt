/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.example

import org.jetbrains.amper.plugins.*
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

@TaskAction
fun generateResources(
    @Output outputDir: Path,
) {
    outputDir.createDirectories()
    (outputDir / "generated.properties").writeText("greeting=hello from generated resources")
}
