/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.schema.processing

import org.jetbrains.amper.plugins.schema.model.PluginSettingsSearchResult
import org.jetbrains.amper.plugins.schema.model.PluginSettingsSearchResult.Invalid
import org.jetbrains.amper.plugins.schema.model.PluginSettingsSearchResult.NotFound
import org.jetbrains.amper.plugins.schema.model.PluginSettingsSearchResult.Success
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

context(provider: DeclarationsProvider, session: KaSession)
fun searchForPluginSettings(
    files: Collection<KtFile>,
    pluginSettingsClassName: String,
): PluginSettingsSearchResult = with(session) {
    val foundSchemaName = files.firstNotNullOfOrNull {
        searchForPluginSettingsClass(it, pluginSettingsClassName)
    }?.classSymbol?.classId?.toSchemaName()
    if (foundSchemaName != null) {
        if (provider.hasClassDeclarationFor(foundSchemaName)) {
            Success(foundSchemaName)
        } else Invalid
    } else NotFound
}

context(_: KaSession)
private fun searchForPluginSettingsClass(file: KtFile, name: String): KtClassOrObject? {
    val visitor = object : KtTreeVisitorVoid() {
        var found: KtClassOrObject? = null

        override fun visitClassOrObject(classOrObject: KtClassOrObject) {
            if (classOrObject.getClassId()?.asFqNameString() == name) {
                found = classOrObject
                return
            }
            return super.visitClassOrObject(classOrObject)
        }
    }
    file.accept(visitor)
    return visitor.found
}