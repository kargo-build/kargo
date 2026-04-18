/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.TaskActionDeclaration
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.annotations.Nls

/**
 * An object has one or more missing properties.
 * NOTE: `product` and `product.type` properties are reported ad-hoc, not via this.
 */
class MissingPropertiesProblem(
    val missingProperties: List<MissingPropertyInfo>,
    val inside: SchemaObjectDeclaration,
) : BuildProblem {
    init {
        require(missingProperties.isNotEmpty())
        require(missingProperties.distinctBy { it.trace }.size == 1)
    }

    enum class MissingPropertyKind {
        /**
         * An arbitrary missing property
         */
        Generic,

        /**
         * A task parameter is missing.
         * NOTE: Nested properties of task parameters are already [Generic].
         */
        TaskParameter,
    }

    val kind = when (inside) {
        is TaskActionDeclaration -> MissingPropertyKind.TaskParameter
        else -> MissingPropertyKind.Generic
    }

    override val diagnosticId get() = TreeDiagnosticId.NoValueForRequiredProperty
    override val level get() = Level.Error
    override val type get() = BuildProblemType.Generic

    override val source: BuildProblemSource
        get() = missingProperties.first().trace.asBuildProblemSource()

    override val message: @Nls String by lazy {
        val argument = missingProperties.joinToString() {
            "'${it.relativeValuePath.joinToString(".")}'"
        }
        val messageKey = if (missingProperties.size == 1) when (kind) {
            MissingPropertyKind.Generic -> "validation.missing.value"
            MissingPropertyKind.TaskParameter -> "validation.missing.task.parameter.value"
        } else when (kind) {
            MissingPropertyKind.Generic -> "validation.missing.values"
            MissingPropertyKind.TaskParameter -> "validation.missing.task.parameter.values"
        }
        SchemaBundle.message(messageKey, argument)
    }
}
