/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.project

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.amper.frontend.FrontendPathResolver
import org.jetbrains.amper.frontend.VersionCatalog
import org.jetbrains.amper.frontend.schema.MavenPlugin
import org.jetbrains.amper.problems.reporting.ProblemReporter
import java.nio.file.Path
import com.intellij.openapi.project.Project as IJProject

/**
 * Contains information about the project as a whole.
 */
interface AmperProjectContext {

    /**
     * The [FrontendPathResolver] to do the mapping between paths, virtual files, and PSI files.
     */
    val frontendPathResolver: FrontendPathResolver

    /**
     * The root directory of this Amper project.
     * This directory contains either a project file, or a module file, or both.
     */
    val projectRootDir: VirtualFile

    /**
     * The build directory of the project, containing project-specific outputs and caches.
     * It is usually located at `<projectRoot>/build`.
     */
    val projectBuildDir: Path

    /**
     * The version catalog of the project, usually located at `<projectRoot>/libs.versions.toml` or
     * `<projectRoot>/gradle/libs.versions.toml`.
     *
     * Null if the project doesn't have a version catalog file.
     */
    val projectVersionsCatalog: VersionCatalog?

    /**
     * The paths to all Amper module files that belong to this project.
     */
    val amperModuleFiles: List<VirtualFile>

    /**
     * External maven plugins that are declared for this project.
     */
    val externalMavenPlugins: List<MavenPlugin>

    /**
     * Local plugin module files of this project that are *applied* to the project itself.
     * Subset of [amperModuleFiles].
     */
    val enabledLocalAmperPluginModuleFiles: List<VirtualFile>

    /**
     * Local plugin module files of this project. Subset of [amperModuleFiles].
     */
    @Deprecated(
        message = "Use enabledLocalAmperPluginModuleFiles instead",
        replaceWith = ReplaceWith("enabledLocalAmperPluginModuleFiles"),
        level = DeprecationLevel.ERROR,
    )
    val pluginsModuleFiles: List<VirtualFile>
        get() = enabledLocalAmperPluginModuleFiles

    companion object {
        /**
         * Finds an Amper project, starting at the given [start] directory or file, or returns null if no Amper project
         * is found.
         *
         * Conceptually, we first find the closest ancestor directory of [start] that contains a project file or a
         * module file. Then:
         * * If a project file is found, that's our root.
         * * If a module file is found, that's part of our project. In that case we check if a project file higher up
         * contains this module. If that's the case, then the project file defines the root. If not, then our module
         * file defines the root.
         * * If neither a project nor a module file are found, we don't have an Amper project and null is returned. The
         * caller is responsible for handling this situation as desired.
         *
         * The given [buildDir] will be used as a root directory for all build outputs of this project.
         * If null, the build directory defaults to `<projectRoot>/build`.
         *
         * The given [ijProject] is used to resolve virtual files and PSI files. If null, a mock project is
         * created to satisfy the virtual file system's machinery.
         */
        context(_: ProblemReporter)
        fun find(
            start: VirtualFile,
            buildDir: Path?,
            ijProject: IJProject? = null,
        ): AmperProjectContext? {
            val frontendPathResolver = FrontendPathResolver(ijProject)
            return StandaloneAmperProjectContext.find(start, buildDir, frontendPathResolver)
        }

        /**
         * Finds an Amper project, starting at the given [start] directory or file, or returns null if no Amper project
         * is found.
         *
         * Conceptually, we first find the closest ancestor directory of [start] that contains a project file or a
         * module file. Then:
         * * If a project file is found, that's our root.
         * * If a module file is found, that's part of our project. In that case we check if a project file higher up
         * contains this module. If that's the case, then the project file defines the root. If not, then our module
         * file defines the root.
         * * If neither a project nor a module file are found, we don't have an Amper project and null is returned. The
         * caller is responsible for handling this situation as desired.
         *
         * The given [buildDir] will be used as a root directory for all build outputs of this project.
         * If null, the build directory defaults to `<projectRoot>/build`.
         *
         * The given [ijProject] is used to resolve virtual files and PSI files. If null, a mock project is
         * created to satisfy the virtual file system's machinery.
         */
        context(_: ProblemReporter)
        fun find(
            start: Path,
            buildDir: Path?,
            ijProject: IJProject? = null,
        ): AmperProjectContext? {
            val frontendPathResolver = FrontendPathResolver(ijProject)
            val startVirtualFile = frontendPathResolver.loadVirtualFile(start)
            return StandaloneAmperProjectContext.find(startVirtualFile, buildDir, frontendPathResolver)
        }

        /**
         * Creates an [AmperProjectContext] for the specified [rootDir] based on the contained project or module file.
         * If there is no project file nor module file in the given directory, null is returned and the caller is
         * responsible for handling the situation (only the caller knows whether this is a valid situation).
         *
         * The given [buildDir] will be used as a root directory for all build outputs of this project.
         * If null, the build directory defaults to `<projectRoot>/build`.
         *
         * The given [ijProject] is used to resolve virtual files and PSI files. If null, a mock project is
         * created to satisfy the virtual file system's machinery.
         */
        context(_: ProblemReporter)
        fun create(
            rootDir: Path,
            buildDir: Path?,
            ijProject: IJProject? = null,
        ): AmperProjectContext? {
            val pathResolver = FrontendPathResolver(project = ijProject)
            return StandaloneAmperProjectContext.create(
                rootDir = pathResolver.loadVirtualFile(rootDir),
                buildDir = buildDir,
                frontendPathResolver = pathResolver,
            )
        }
    }
}
