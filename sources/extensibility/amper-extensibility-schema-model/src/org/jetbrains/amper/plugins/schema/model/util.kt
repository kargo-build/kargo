/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins.schema.model

import org.jetbrains.amper.plugins.schema.model.PluginData.Declarations
import kotlin.contracts.contract

operator fun Declarations.plus(other: Declarations): Declarations {
    return Declarations(
        classes = classes + other.classes,
        enums = enums + other.enums,
        variants = variants + other.variants,
    )
}

fun Declarations.withoutOrigin(): Declarations {
    return copy(
        classes = classes.map { it.withoutOrigin() },
        enums = enums.map {
            it.copy(
                entries = it.entries.map { entry ->
                    entry.copy(origin = null)
                },
                origin = null,
            )
        },
        variants = variants.map { it.copy(origin = null) },
        tasks = tasks.map {
            it.copy(
                syntheticType = it.syntheticType.withoutOrigin(),
            )
        },
    )
}

fun PluginSettingsSearchResult?.asSuccessOrNull(): PluginSettingsSearchResult.Success? {
    contract { returnsNotNull() implies (this@asSuccessOrNull != null) }
    return this as? PluginSettingsSearchResult.Success
}

private fun PluginData.ClassData.withoutOrigin() = copy(
    properties = properties.map { property ->
        property.copy(origin = null)
    },
    origin = null,
)