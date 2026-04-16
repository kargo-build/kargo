/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.slf4j.LoggerFactory
import java.io.Closeable

class Cache : TypedKeyMap(), Closeable {
    private val logger = LoggerFactory.getLogger("dr/cache.kt")

    override fun close() {
        map
            .filterValues { it is AutoCloseable }
            .forEach {
                if (it.key == httpClientKey) return@forEach  // calling side handles lifecycle of injected HttpClient

                val closeable = it as AutoCloseable
                map.remove(it.key)
                try {
                    closeable.close()
                } catch (e: Exception) {
                    logger.warn("Failed to close DR context resource", e)
                }
            }
    }
}
