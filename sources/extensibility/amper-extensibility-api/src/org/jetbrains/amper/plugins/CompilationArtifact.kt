/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins

import java.nio.file.Path

/**
 * Provides the compilation result of the [given][from] module.
 *
 * Warning: only JVM platform is currently supported.
 */
@Configurable
interface CompilationArtifact {
    /**
     * The local module to get the compilation result from.
     */
    val from: Dependency.Local

    /**
     * The kind of the compilation artifact.
     */
    val kind: Kind

    /**
     * Path to the compilation artifact.
     * The contents are dependent on the [kind].
     */
    @Provided
    val artifact: Path

    /**
     * Kind of the [CompilationArtifact].
     */
    enum class Kind {
        /**
         * A single JAR compiled from module sources.
         * If there is a need to request unpacked classes, use [Classes] instead.
         */
        @EnumValue("jar")
        Jar,

        /**
         * A directory containing all the module's compiled classes.
         */
        @EnumValue("classes")
        Classes,
    }
}
