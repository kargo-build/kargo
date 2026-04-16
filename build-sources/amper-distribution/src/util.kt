/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import java.nio.file.Path
import kotlin.io.path.name

val FilteredClasspath.resolvedFiles: List<Path>
    get() = classpath.resolvedFiles.filter { path -> includeIfFileNameContains.any { it in path.name } }