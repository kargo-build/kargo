/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.messages

import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import java.nio.file.Path

fun FileWithRangesBuildProblemSource(
    file: Path,
    offsetRange: IntRange,
): FileWithRangesBuildProblemSource = object : FileWithRangesBuildProblemSource {
    override val offsetRange: IntRange get() = offsetRange
    override val file: Path get() = file
}
