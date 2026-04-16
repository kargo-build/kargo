/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Configurable
import org.jetbrains.amper.plugins.EnumValue
import java.nio.file.Path

@Configurable
interface DistributionSettings {
    val extraClasspaths: Map<String, Classpath> get() = emptyMap()
    val extraFilteredClasspaths: Map<String, FilteredClasspath> get() = emptyMap()
    val embedClasspathAsResources: EmbedClasspathAsResources
}

@Configurable
interface FilteredClasspath {
    /**
     * Classpath to include
     */
    // Ah, shorthand would be nice here
    val classpath: Classpath

    /**
     * Include jars, whose name contains any string from this list.
     * If this list is empty (default), all jars are included.
     */
    val includeIfFileNameContains: List<String> get() = emptyList()
}

@Configurable
interface EmbedClasspathAsResources {
    val classpath: Classpath
    val resourceDirName: String
}

enum class Repository {
    @EnumValue("maven-local")
    MavenLocal,
    @EnumValue("jetbrains-team-amper-maven")
    JetBrainsTeamAmperMaven,
}

@Configurable
interface Distribution {
    val cliTgz: Path
    val wrappersDir: Path
}
