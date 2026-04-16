/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.incrementalcache.IncrementalCache

/**
 * Returns a dependencies sequence of the given module in the resolution scope
 * of the given [platform], [isTest] and [dependencyReason].
 */
fun AmperModule.getModuleDependencies(
    isTest: Boolean,
    platform: Platform,
    dependencyReason: ResolutionScope,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache
) : Sequence<AmperModule> = with(ModuleDependencies) {
    getDependentAmperModules(isTest, platform, dependencyReason, userCacheRoot, incrementalCache,GlobalOpenTelemetry.get())
}
