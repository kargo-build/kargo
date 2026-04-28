/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.engine

/**
 * Receives callbacks when tasks from [TaskExecutor] update their state.
 * Created per [task graph run][TaskExecutor.run]
 *
 * @see TaskGraphExecutionListener.Provider
 */
interface TaskGraphExecutionListener {
    /**
     * Called when the [task] is about to start execution.
     * May be called on arbitrary thread.
     *
     * @return a listener implementation to receive further callbacks about the [task]'s execution.
     */
    fun taskStarted(task: Task): TaskExecutionListener

    /**
     * Called when task graph execution is finished.
     *
     * Is guaranteed to be called after every [started][taskStarted] task in the plan
     * is [finished][TaskExecutionListener.onTaskFinished].
     * No more tasks can be [started][taskStarted] after this.
     *
     * May be called on arbitrary thread.
     */
    fun taskGraphExecutionFinished() = Unit

    /**
     * Receives callbacks about a started task lifecycle.
     * Use [TaskGraphExecutionListener.taskStarted] to subscribe to task execution events.
     */
    interface TaskExecutionListener {
        /**
         * Called when a task execution is finished.
         */
        fun onTaskFinished() {}

        object Noop : TaskExecutionListener
    }

    fun interface Provider {
        /**
         * Invoked once per [TaskExecutor.run] to create a listener for task graph execution events.
         *
         * @param executionPlan a valid execution plan for this session
         */
        fun createListener(
            executionPlan: TaskExecutor.ExecutionPlan,
        ): TaskGraphExecutionListener
    }

    object Noop : TaskGraphExecutionListener {
        override fun taskStarted(task: Task) = TaskExecutionListener.Noop
    }
}
