package build.kargo.tasks.native

import org.jetbrains.amper.compilation.singleLeafFragment
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.native.CinteropTask
import org.jetbrains.amper.tasks.native.NativeTaskType
import org.jetbrains.amper.util.BuildType

/**
 * Creates CinteropTasks for cinterop definitions from source lib dependencies.
 * Only called for modules that need linking (apps or tests).
 * Returns the list of created CinteropTask instances.
 */
internal fun ProjectTasksBuilder.createLibCinteropTasks(
    module: AmperModule,
    fragment: Fragment,
    platform: Platform,
    isTest: Boolean,
    buildType: BuildType,
): List<CinteropTask> {
    return fragment.externalDependencies
        .filterIsInstance<LocalModuleDependency>()
        .filter { it.module.type.isLibrary() }
        .flatMap { dep ->
            val libFragment = dep.module.fragments
                .filter { it.platforms.contains(platform) && !it.isTest }
                .singleLeafFragment()
            libFragment.settings.native?.cinterop?.map { (moduleName, cinteropModule) ->
                val cinteropTaskName = NativeTaskType.Cinterop
                    .getTaskName(module, platform, isTest, buildType)
                    .let { TaskName(it.name + "-" + dep.module.userReadableName + "-" + moduleName) }
                CinteropTask(
                    module = module,
                    platform = platform,
                    userCacheRoot = context.userCacheRoot,
                    taskOutputRoot = context.getTaskOutputPath(cinteropTaskName),
                    incrementalCache = context.incrementalCache,
                    taskName = cinteropTaskName,
                    isTest = isTest,
                    buildType = buildType,
                    jdkProvider = context.jdkProvider,
                    defFile = dep.module.source.moduleDir.resolve(cinteropModule.defFile),
                    packageName = cinteropModule.packageName,
                    compilerOpts = cinteropModule.compilerOpts,
                    linkerOpts = cinteropModule.linkerOpts,
                    processRunner = context.processRunner,
                ).also { tasks.registerTask(it) }
            } ?: emptyMap()
        }
}
