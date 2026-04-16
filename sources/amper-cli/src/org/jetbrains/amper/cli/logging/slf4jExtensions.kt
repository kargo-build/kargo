/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.logging

import org.slf4j.MDC

internal inline fun <T> withMDCEntry(key: String, value: String, block: () -> T): T =
    MDC.putCloseable(key, value).use { block() }
