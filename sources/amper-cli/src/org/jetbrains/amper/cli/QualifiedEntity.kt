/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.frontend.Model

/**
 * Simple trait for entities that potentially come from plugins
 * and need to be identified by their qualified/simple names
 *
 * @see QualifiedName
 */
interface QualifiedEntity {
    val name: QualifiedName
}

/**
 * Potentially qualified name of an entity.
 *
 * @see QualifiedEntity
 */
data class QualifiedName(
    val simpleName: String,
    /**
     * Plugin id if entity comes from a plugin, `null` if builtin.
     */
    val pluginId: String?
) : Comparable<QualifiedName> {
    val qualifiedName = pluginId?.let { "$it:$simpleName" } ?: simpleName
    override fun toString() = qualifiedName

    override fun compareTo(other: QualifiedName) = Comparator.compare(this, other)

    private companion object {
        val Comparator = compareBy(QualifiedName::pluginId, QualifiedName::simpleName)
    }
}

fun List<QualifiedName>.filterByPluginId(model: Model, pluginId: String): List<QualifiedName> {
    val allPluginIdsInProject = model.amperPlugins.mapTo(mutableSetOf()) { it.id.value }
    if (pluginId !in allPluginIdsInProject) {
        userReadableError("Plugin with the id '$pluginId' is not registered in the project.")
    }
    return filter { it.pluginId == pluginId }
}

fun <T : QualifiedEntity> resolveMatchingEntities(
    userProvidedName: String,
    entities: List<T>,
    entityDisplayName: String,
    context: String = "",
): List<T> {
    val allNames = entities.map(QualifiedEntity::name).distinct()

    fun formatAllAvailable() = buildList {
        allNames.groupBy(
            keySelector = QualifiedName::simpleName,
            valueTransform = QualifiedName::qualifiedName,
        ).forEach { (name, qualifiedNames) ->
            val singleQualifiedName = qualifiedNames.singleOrNull()
            if (singleQualifiedName != null) {
                if (name != singleQualifiedName) {
                    add("'$name' (or '$singleQualifiedName')")
                } else {
                    add("'$name'")
                }
            } else {
                qualifiedNames.forEach {
                    add("'$it'")
                }
            }
        }
    }.joinToString(prefix = "Available ${entityDisplayName}s: ")

    val resolvedNames = if (':' in userProvidedName) {
        allNames.filter { it.qualifiedName == userProvidedName }
    } else {
        allNames.filter { it.simpleName == userProvidedName }
    }.toSet()

    if (resolvedNames.isEmpty()) {
        userReadableError("Unknown $entityDisplayName '$userProvidedName'$context. ${formatAllAvailable()}")
    }
    if (resolvedNames.size > 1) {
        userReadableError(
            "Ambiguous $entityDisplayName name '$userProvidedName'$context. " +
                    "Multiple plugins provide a $entityDisplayName with this name. " +
                    "Please use a qualified name: ${resolvedNames.joinToString()}"
        )
    }
    return entities.filter { it.name in resolvedNames }
}
