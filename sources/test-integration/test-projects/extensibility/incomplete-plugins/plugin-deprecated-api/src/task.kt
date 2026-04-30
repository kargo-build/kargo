/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.plugins.*
import java.nio.file.Path

@TaskAction fun someTask(
    @Output val output: Path,
) {}