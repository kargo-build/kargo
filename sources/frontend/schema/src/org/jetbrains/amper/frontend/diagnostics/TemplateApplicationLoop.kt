/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.api.TraceablePath
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.annotations.Nls
import java.nio.file.Path
import kotlin.io.path.name

class TemplateApplicationLoop(
    val loopStart: Path,
    val loop: List<TraceablePath>,
) : BuildProblem {
    override val source: BuildProblemSource
        get() = MultipleLocationsBuildProblemSource(
            sources = loop.mapNotNull { it.extractPsiElementOrNull() }.map(::PsiBuildProblemSource),
            groupingMessage = SchemaBundle.message("template.application.loop.grouping.message"),
        )
    override val level: Level = Level.Error
    override val type: BuildProblemType = BuildProblemType.Generic
    override val diagnosticId: DiagnosticId
        get() = FrontendDiagnosticId.TemplateApplicationLoop
    override val message: @Nls String
        get() = SchemaBundle.message(
            "template.application.loop", (listOf(loopStart) + loop.map { it.value }).joinToString(
                separator = " -> ",
                transform = { it.name.removeSuffix(".module-template.yaml") },
            )
        )
}