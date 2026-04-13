/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import org.jetbrains.amper.frontend.api.KnownStringValues
import org.jetbrains.amper.frontend.api.Misnomers
import org.jetbrains.amper.frontend.api.PlatformAgnostic
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.tree.NullLiteralNode
import org.jetbrains.amper.frontend.tree.ReferenceNode
import org.jetbrains.amper.frontend.tree.RefinedTreeNode
import org.jetbrains.amper.frontend.tree.StringNode

class PublishingSettings : SchemaNode() {

    @PlatformAgnostic
    @SchemaDoc("Enables the publication of the module to Maven repositories (via `./amper publish`)")
    val enabled by value(default = false)

    @PlatformAgnostic
    @SchemaDoc("Group ID of the published Maven artifact")
    val group by nullableValue<String>()

    @PlatformAgnostic
    @SchemaDoc("Version of the published Maven artifact")
    val version by nullableValue<String>()

    @Misnomers("artifact", "artifactId")
    @SchemaDoc("Artifact ID of the published Maven artifact")
    val name by nullableValue<String>()

    @PlatformAgnostic
    @SchemaDoc("Custom metadata to configure in the published `pom.xml` file.")
    val pom by nested<PomSettings>()
}

class PomSettings : SchemaNode() {

    @SchemaDoc("A user-readable name for this module. Defaults to the Amper module name.")
    val name by nullableValue<String>()

    @SchemaDoc("A description for this module. Defaults to the Amper module description.")
    val description by nullableValue<String>()

    @SchemaDoc(
        "The URL to the module's homepage in the POM metadata. Required for Maven Central publication.\n\n" +
                "Note: this is usually the same for the whole project, thus configured in a common template."
    )
    val url by nullableValue<String>()

    @SchemaDoc(
        "The licenses that apply to this module. Required for Maven Central publication.\n\n" +
                "Note: this is usually the same for the whole project, thus configured in a common template."
    )
    // TODO should we infer the license based on LICENSE.md by default?
    val licenses by value<List<LicenseInfo>>(emptyList())

    @SchemaDoc(
        "The source control management information for this module. Required for Maven Central publication.\n\n" +
                "Note: this is usually the same for the whole project, thus configured in a common template."
    )
    val scm by nested<ScmInfo>()

    @SchemaDoc(
        "The developers working on this module. Required for Maven Central publication.\n\n" +
                "Note: this is usually the same for the whole project, thus configured in a common template."
    )
    val developers by value<List<DeveloperInfo>>(emptyList())
}

class LicenseInfo : SchemaNode() {

    @SchemaDoc(
        "The full name of this license. There is no strict constraint for it, but people generally " +
                "follow a convention in Maven POMs. " +
                "See https://spdx.org/licenses/ for an almost exhaustive list of licenses."
    )
    @KnownStringValues(
        "The Apache License, Version 2.0",
        "MIT License",
        "BSD 2-Clause License",
        "BSD 3-Clause License",
        "GNU General Public License, version 3.0",
        "GNU Lesser General Public License, version 3.0",
        "Mozilla Public License 2.0",
        "Eclipse Public License 2.0",
        "The Unlicense",
    )
    val name by value<String>()

    @SchemaDoc(
        "The URL to the license's official text. " +
                "See https://spdx.org/licenses/ for an almost exhaustive list of licenses."
    )
    @KnownStringValues(
        "https://www.apache.org/licenses/LICENSE-2.0.txt",
        "https://opensource.org/licenses/MIT",
        "https://opensource.org/licenses/BSD-2-Clause",
        "https://opensource.org/licenses/BSD-3-Clause",
        "https://www.gnu.org/licenses/gpl-3.0.html",
        "https://www.gnu.org/licenses/lgpl-3.0.html",
        "https://www.mozilla.org/en-US/MPL/2.0/",
        "https://www.eclipse.org/legal/epl-2.0/",
        "https://unlicense.org/",
    )
    val url by value<String>()
}

/**
 * See https://maven.apache.org/pom.html#scm
 */
class ScmInfo : SchemaNode() {

    class ScmGitUrlTransform : ReferenceNode.TransformFunction<String?> {
        override fun transform(node: RefinedTreeNode): String? {
            val url = when (node) {
                is StringNode -> node.value
                is NullLiteralNode -> return null
                else -> error("Invalid SCM URL node type: ${node::class.simpleName}")
            }
            return "scm:git:$url"
        }
    }

    @SchemaDoc(
        "The URL to the repository hosting the source code of this module. " +
                "Required for Maven Central publication.\n\n" +
                "Example: `https://github.com/spring-projects/spring-boot.git`"
    )
    val url by nullableValue<String>()

    @SchemaDoc(
        "A URL with `scm:` scheme that Maven uses to connect to the version control system with _read_ access. " +
                "See https://maven.apache.org/pom.html#scm for more details.\n\n" +
                "Example: `scm:git:https://github.com/spring-projects/spring-boot.git`.\n\n" +
                "By default, the `connection` URL is derived from the `url` by prefixing it with `scm:git:`."
    )
    val connection by referenceValue(::url, "SCM connection URL from repo URL", ScmGitUrlTransform())

    @SchemaDoc(
        "A URL with `scm:` scheme that Maven uses to connect to the version control system with _write_ access. " +
                "See https://maven.apache.org/pom.html#scm for more details.\n\n" +
                "Example: `scm:git:https://github.com/spring-projects/spring-boot.git`.\n\n" +
                "By default, the `developerConnection` URL is derived from the `url` by prefixing it with `scm:git:`."
    )
    val developerConnection by referenceValue(::url, "SCM developer connection URL from repo URL", ScmGitUrlTransform())
}

/**
 * See https://maven.apache.org/pom.html#developers
 */
class DeveloperInfo : SchemaNode() {

    @SchemaDoc("Some unique ID for this developer across an organization or in the SCM.")
    val id by nullableValue<String>()

    @SchemaDoc("The full name of this developer. Required for Maven Central publication.")
    val name by value<String>()

    @SchemaDoc("The URL to this developer's website.")
    val url by nullableValue<String>()

    @SchemaDoc("The email address of this developer.")
    val email by nullableValue<String>()

    @SchemaDoc("The organization to which this developer belongs.")
    val organization by nullableValue<String>()

    @SchemaDoc("The URL to the website of this developer's organization.")
    val organizationUrl by nullableValue<String>()
}
