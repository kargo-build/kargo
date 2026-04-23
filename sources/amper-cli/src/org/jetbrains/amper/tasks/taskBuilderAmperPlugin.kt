/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.tasks.plugins.meta.BuildAmperPluginInfoTask
import org.jetbrains.amper.tasks.plugins.meta.PreProcessAmperPluginsTask

internal enum class AmperPluginTaskType(override val prefix: String) : TaskType {
    BuildAmperPluginInfo("buildAmperPluginInfo"),
}

fun ProjectTasksBuilder.setupAmperPluginTasks() {
    val allPluginModules = model.modules.filter { it.type == ProductType.JVM_AMPER_PLUGIN }
    if (allPluginModules.isEmpty()) return

    val appliedPluginModules = model.amperPlugins.mapTo(hashSetOf()) { it.pluginModule }

    // We gather all the plugins in the project that are not registered in the `project.yaml`.
    // That means they are not included in the `preparePlugins` phase.
    val unappliedPluginModules = allPluginModules.filter {
        it !in appliedPluginModules
    }

    // So we process such unapplied plugins separately in a "global task", in batch, as it's more efficient.
    val preProcessUnappliedPluginsTaskName = TaskName("preProcessUnappliedPlugins")
    if (unappliedPluginModules.isNotEmpty()) {
        tasks.registerTask(
            PreProcessAmperPluginsTask(
                taskName = preProcessUnappliedPluginsTaskName,
                projectRoot = context.projectRoot,
                incrementalCache = context.incrementalCache,
                processRunner = context.processRunner,
                unappliedPluginModules = unappliedPluginModules,
            )
        )
    }

    for (module in allPluginModules) {
        val isApplied = module in appliedPluginModules
        val taskName = TaskName.moduleTask(module, AmperPluginTaskType.BuildAmperPluginInfo.prefix)
        tasks.registerTask(
            BuildAmperPluginInfoTask(
                projectContext = context.projectContext,
                module = module,
                isApplied = isApplied,
                taskName = taskName,
            ),
            dependsOn = buildList {
                if (!isApplied) {
                    add(preProcessUnappliedPluginsTaskName)
                }
            }
        )
    }
}
