/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.maven

import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.contexts.Context
import org.jetbrains.amper.frontend.contexts.ContextsInheritance

/**
 * The context that is used to resolve conflicts between several contributors to the tree
 */
sealed interface MavenContributorContext : Context {
    val priority: Int
    override val trace: Trace get() = DefaultTrace

    override fun withoutTrace(): Context = this

    /**
     * The default context used by all the contributors.
     */
    object Default : MavenContributorContext {
        override val priority: Int = 0
    }

    /**
     * The context used by the Spring Boot contributor to override values from [Default].
     */
    object SpringBoot : MavenContributorContext {
        override val priority: Int = Default.priority + 1
    }

    /**
     * The context used as a selected context for refining the tree.
     */
    object WithAllContributors : MavenContributorContext {
        override val priority: Int = Int.MAX_VALUE
    }
}

object MavenContributorContextInheritance : ContextsInheritance<MavenContributorContext> {
    override fun Collection<MavenContributorContext>.compareContexts(other: Collection<MavenContributorContext>): ContextsInheritance.Result {
        val thisContextPriority = this.singleOrNull()?.priority ?: MavenContributorContext.Default.priority
        val otherContextPriority = other.singleOrNull()?.priority ?: MavenContributorContext.Default.priority
        return when {
            thisContextPriority == otherContextPriority -> ContextsInheritance.Result.SAME
            thisContextPriority > otherContextPriority -> ContextsInheritance.Result.IS_MORE_SPECIFIC
            else -> ContextsInheritance.Result.IS_LESS_SPECIFIC
        }
    }
}