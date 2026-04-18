/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.messages

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findDocument
import org.jetbrains.amper.frontend.getLineAndColumnRangeInDocument
import org.jetbrains.amper.frontend.getLineAndColumnRangeInPsiFile
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import org.jetbrains.amper.problems.reporting.LineAndColumn
import org.jetbrains.amper.problems.reporting.LineAndColumnRange

/**
 * Range of problematic code expressed in terms of lines and columns.
 * Can be used by clients to render the links to the exact location in the file or display an erroneous part of the
 * code.
 */
fun FileWithRangesBuildProblemSource.computeRange(): LineAndColumnRange = when (this) {
    is PsiBuildProblemSource -> getLineAndColumnRangeInPsiFile(psiElement)
    else -> runReadAction {  // Fixme: do this via FrontendPathResolver?
        VirtualFileManager.getInstance().findFileByNioPath(file)
    }?.findDocument()?.let {
        getLineAndColumnRangeInDocument(it, offsetRange)
    } ?: LineAndColumnRange(LineAndColumn.NONE, LineAndColumn.NONE)
}
