/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.contexts.Contexts
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.annotations.Nls

class ConflictingProperties(
    /**
     * Refinement context in which the conflict has occurred.
     *
     * @see TreeRefiner.refineTree
     */
    val contexts: Contexts,
    /**
     * List of conflicting key-value pairs.
     */
    @field:UsedInIdePlugin
    val keyValues: List<KeyValue>,
) : BuildProblem {
    override val diagnosticId: DiagnosticId = TreeDiagnosticId.ConflictingProperties
    override val source: BuildProblemSource = MultipleLocationsBuildProblemSource(
        keyValues.mapNotNull { it.trace.asBuildProblemSource() as? FileBuildProblemSource },
        groupingMessage = SchemaBundle.message("conflicting.properties.grouping")
    )
    override val message: @Nls String
        get() = SchemaBundle.message("conflicting.properties", keyValues.first().key)
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.InconsistentConfiguration
}