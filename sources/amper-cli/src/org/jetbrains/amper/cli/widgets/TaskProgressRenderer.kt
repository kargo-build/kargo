/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.ProgressBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskExecutor
import org.jetbrains.amper.engine.TaskGraphExecutionListener
import kotlin.math.min
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(FlowPreview::class)
class TaskProgressRenderer(
    private val terminal: Terminal,
    private val coroutineScope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    executionPlan: TaskExecutor.ExecutionPlan,
) : TaskGraphExecutionListener {
    private data class TaskEntry(
        val task: Task,
        val startTime: ComparableTimeMark,
        val elapsed: Duration,
    )

    private data class ProgressState(
        val taskEntries: List<TaskEntry> = emptyList(),
        val completeTasksCount: Int = 0,
    )

    private val maxTasksOnScreen
        get() = terminal.size.height / 3

    private val totalTasksCount = executionPlan.totalTasksCount

    private val progressStateFlow = MutableStateFlow(ProgressState())

    private val animationJob = coroutineScope.launch(Dispatchers.IO) {
        val animation = terminal.animation<ProgressState> { state ->
            createTasksProgressWidget(state)
        }

        terminal.cursor.hide(showOnExit = true)

        launch {
            while (true) {
                updateElapsedTime()
                delay(100.milliseconds)
            }
        }

        val mutex = Mutex()
        try {
            progressStateFlow.debounce(30.milliseconds).collectLatest { snapshot ->
                // animation code is single-threaded
                mutex.withLock {
                    animation.update(snapshot)
                }
            }
        } finally {
            animation.clear()
            terminal.cursor.show()
        }
    }

    override fun taskGraphExecutionFinished() {
        animationJob.cancel()
    }

    private fun createTasksProgressWidget(state: ProgressState): Widget = verticalLayout {
        // Required to explicitly fill empty space with whitespaces and overwrite old lines
        align = TextAlign.LEFT
        // Required to correctly truncate very long status lines (or on very narrow terminal windows)
        width = ColumnWidth.Expand()

        cell("")

        cell(horizontalLayout {
            cell("[")
            cell(
                ProgressBar(
                    fractionComplete = state.completeTasksCount.toFloat() / totalTasksCount,
                    width = min(40, terminal.size.width),
                    completeStyle = terminal.theme.success,
                )
            )
            cell("]")
            cell(state.completeTasksCount.toString()) {
                style = terminal.theme.success
            }
            cell("/ $totalTasksCount tasks") {
                style = terminal.theme.muted
            }
        })

        for (entry in state.taskEntries.take(maxTasksOnScreen)) {
            cell(horizontalLayout {
                cell(">") {
                    style = terminal.theme.muted
                }

                cell(entry.task.taskName.name) {
                    style = terminal.theme.info
                }

                if (entry.elapsed >= 1.seconds) {
                    cell(entry.elapsed.toString()) {
                        style = terminal.theme.muted
                    }
                }
            })
        }
        if (state.taskEntries.size > maxTasksOnScreen) {
            cell("(+${state.taskEntries.size - maxTasksOnScreen} more)")
        }
    }

    private fun updateElapsedTime() {
        progressStateFlow.update { old ->
            old.copy(
                taskEntries = old.taskEntries.map { it.copy(elapsed = it.startTime.elapsedNow().roundToTheSecond()) },
            )
        }
    }

    override fun taskStarted(task: Task): TaskGraphExecutionListener.TaskExecutionListener {
        val job = coroutineScope.launch(Dispatchers.IO) {
            val newTaskEntry = TaskEntry(task, startTime = timeSource.markNow(), elapsed = Duration.ZERO)
            delay(200.milliseconds)
            progressStateFlow.update { current ->
                current.copy(
                    taskEntries = current.taskEntries + newTaskEntry,
                )
            }
        }

        return object : TaskGraphExecutionListener.TaskExecutionListener {
            override fun onTaskFinished() {
                job.cancel()

                progressStateFlow.update { current ->
                    current.copy(
                        taskEntries = current.taskEntries.filterNot { it.task === task },
                        completeTasksCount = current.completeTasksCount + 1,
                    )
                }
            }
        }
    }
}

private fun Duration.roundToTheSecond(): Duration = inWholeSeconds.seconds
