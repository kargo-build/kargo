/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.schema.processing

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

context(_: KaSession)
internal fun discoverAnnotatedClassesFrom(file: KtFile, annotationClassId: ClassId): List<KtClassOrObject> = buildList {
    file.accept(object : KtTreeVisitorVoid() {
        override fun visitClassOrObject(classOrObject: KtClassOrObject) {
            if (classOrObject.isAnnotatedWith(annotationClassId)) add(classOrObject)
            return super.visitClassOrObject(classOrObject)
        }
    })
}

context(_: KaSession)
internal fun discoverAnnotatedFunctionsFrom(file: KtFile, annotationClassId: ClassId): List<KtNamedFunction> = buildList {
    file.accept(object : KtTreeVisitorVoid() {
        override fun visitNamedFunction(function: KtNamedFunction) {
            if (function.isAnnotatedWith(annotationClassId)) add(function)
            return super.visitNamedFunction(function)
        }
    })
}
