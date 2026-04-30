/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.Level

/**
 * See [org.jetbrains.amper.frontend.api.DeprecatedSchema].
 */
class DeprecatedPropertyProblem(
    val info: SchemaObjectDeclaration.Property.DeprecatedInfo,
    val property: KeyValue,
) : BuildProblem {
    init {
        require(info === property.propertyDeclaration?.deprecated)
    }

    override val source get() = property.keyTrace.asBuildProblemSource()
    override val message get() = info.message
    override val diagnosticId get() = TreeDiagnosticId.DeprecatedProperty
    override val level get() = if (info.isError) Level.Error else Level.Warning
    override val type get() = BuildProblemType.ObsoleteDeclaration
}