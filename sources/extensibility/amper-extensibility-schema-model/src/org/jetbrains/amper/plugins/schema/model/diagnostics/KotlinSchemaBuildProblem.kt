/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins.schema.model.diagnostics

import kotlinx.serialization.Serializable
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.plugins.schema.model.SourceLocation
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.annotations.Nls
import java.text.MessageFormat
import java.util.*

@Serializable
sealed class KotlinSchemaBuildProblem : BuildProblem, DiagnosticId {
    override val level: Level get() = Level.Error
    override val type: BuildProblemType get() = BuildProblemType.Generic
    override val message: @Nls String get() = buildString {
        append(SchemaMessagePrefix).append(' ')
        append(MessageFormat(SchemaProcessorBundle.getString(messageKey)).format(args))
    }
    protected abstract val messageKey: String
    protected open val args: Array<out Any?> get() = emptyArray()

    final override val diagnosticId get() = this

    @Serializable
    class DefaultsRedundantNull(
        override val source: SourceLocation,
    ) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.defaults.redundant.null"
        override val level get() = Level.WeakWarning
        override val type get() = BuildProblemType.RedundantDeclaration
    }

    @Serializable
    class DefaultsInvalidConstant(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.defaults.invalid.constant"
    }

    @Serializable
    class DefaultsInvalidEnum(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.defaults.invalid.enum"
    }

    @Serializable
    class DefaultsInvalidList(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.defaults.invalid.list"
    }

    @Serializable
    class DefaultsInvalidMap(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.defaults.invalid.map"
    }

    @Serializable
    class DefaultsInvalidObject(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.defaults.invalid.object"
    }

    @Serializable
    class DefaultsInvalidPath(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.defaults.invalid.path"
    }

    @Serializable
    class DefaultsInvalidGetterBlock(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.defaults.invalid.getter.block"
    }

    @Serializable
    class ForbiddenContextReceivers(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.context.receivers"
    }

    @Serializable
    class ForbiddenFunction(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.function"
    }

    @Serializable
    class ForbiddenGenerics(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.generics"
    }

    @Serializable
    class ForbiddenLocal(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.local"
    }

    @Serializable
    class ForbiddenMixins(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.mixins"
    }

    @Serializable
    class ForbiddenPropertyEnabled(
        override val source: SourceLocation,
        val settingsClass: String,
    ) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.property.enabled"
        override val args get() = arrayOf(settingsClass)
    }

    @Serializable
    class ForbiddenPropertyExtension(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.property.extension"
    }

    @Serializable
    class ForbiddenPropertyMutable(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.property.mutable"
    }

    @Serializable
    class ForbiddenPropertyOverride(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.property.override"
    }

    @Serializable
    class ForbiddenSealed(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.sealed"
    }

    @Serializable
    class ForbiddenTaskActionContextReceivers(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.task.action.context.receivers"
    }

    @Serializable
    class ForbiddenTaskActionExtension(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.task.action.extension"
    }

    @Serializable
    class ForbiddenTaskActionGeneric(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.task.action.generic"
    }

    @Serializable
    class ForbiddenTaskActionInline(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.task.action.inline"
    }

    @Serializable
    class ForbiddenTaskActionOverloads(
        override val source: SourceLocation,
        val taskActionName: String,
    ) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.task.action.overloads"
        override val args get() = arrayOf(taskActionName)
    }

    @Serializable
    class ForbiddenTaskActionReturn(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.task.action.return"
    }

    @Serializable
    class ForbiddenTaskActionSuspend(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.forbidden.task.action.suspend"
    }

    @Serializable
    class MustBePublic(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.must.be.public"
    }

    @Serializable
    class NotInterface(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.not.interface"
    }

    @Serializable
    class TaskActionMustBePublic(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.task.action.must.be.public"
    }

    @Serializable
    class TaskActionNotToplevel(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.task.action.not.toplevel"
    }

    @Serializable
    class TaskActionParameterNotPath(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.task.action.parameter.not.path"
    }

    @Serializable
    class TaskActionParameterPathConflicting(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.task.action.parameter.path.conflicting"
    }

    @Serializable
    class TaskActionParameterPathUnmarked(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.task.action.parameter.path.unmarked"
    }

    @Serializable
    class TypeForbiddenProjection(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.type.forbidden.projection"
    }

    @Serializable
    class TypeMapKeyUnexpected(override val source: SourceLocation) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.type.map.key.unexpected"
    }

    @Serializable
    class TypeUnexpected(
        override val source: SourceLocation,
        val typeName: String,
    ) : KotlinSchemaBuildProblem() {
        override val messageKey get() = "schema.type.unexpected"
        override val args get() = arrayOf(typeName)
        override val type get() = BuildProblemType.UnknownSymbol
    }

    @Serializable
    class CyclicClassReference(
        val typeCycle: List<PluginData.SchemaName>,
        val propertiesFormingTheLoopLocations: List<SourceLocation>,
    ) : KotlinSchemaBuildProblem() {
        override val source: BuildProblemSource
            get() = MultipleLocationsBuildProblemSource(
                sources = propertiesFormingTheLoopLocations,
                groupingMessage = SchemaProcessorBundle.getString("schema.type.object.cyclic.reference.grouping"),
            )

        override val args get() = arrayOf(typeCycle.joinToString { "`${it.qualifiedName}`" })
        override val messageKey get() = "schema.type.object.cyclic.reference"
    }
}

private val SchemaProcessorBundle: ResourceBundle = ResourceBundle.getBundle("messages.SchemaProcessorBundle")

/**
 * Message prefix that shows that the diagnostic on the Kotlin source is from Amper Schema Parser,
 * not from Kotlin compiler.
 */
val SchemaMessagePrefix: String = SchemaProcessorBundle.getString("0.schema.message.prefix")
