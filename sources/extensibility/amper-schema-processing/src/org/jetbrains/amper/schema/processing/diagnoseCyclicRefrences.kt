/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.schema.processing

import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.plugins.schema.model.diagnostics.KotlinSchemaBuildProblem
import org.jetbrains.amper.stdlib.graphs.depthFirstDetectLoops

context(resolver: DeclarationsProvider, reporter: DiagnosticsReporter)
internal fun diagnoseCyclicReferences(
    allClasses: Collection<PluginData.ClassData>,
) {
    val loops = depthFirstDetectLoops(
        roots = allClasses,
        adjacent = { classData ->
            classData.properties.mapNotNull { property ->
                val objectType = (property.type as? PluginData.Type.ObjectType)
                    // We don't take nullable edges into account while detecting loops
                    ?.takeUnless { it.isNullable }
                objectType?.declaration
            }
        }
    )

    for (classDataLoop in loops) {
        val properties = (classDataLoop + classDataLoop.first()).zipWithNext().mapNotNull { (class1, class2) ->
            class1.properties.first {
                // It's guaranteed to be at least one, because the loop is formed.
                it.type == PluginData.Type.ObjectType(class2.name)
            }.origin
        }
        reporter.report(KotlinSchemaBuildProblem.CyclicClassReference(
            typeCycle = classDataLoop.map { it.name },
            propertiesFormingTheLoopLocations = properties,
        ))
    }
}
