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
import org.jetbrains.amper.engine.TaskProgressListener
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
) : TaskProgressListener {
    private data class TaskEntry(
        val task: Task,
        val startTime: ComparableTimeMark,
        val elapsed: Duration,
    )

    private val maxTasksOnScreen
        get() = terminal.size.height / 3

    private val updateFlow: MutableStateFlow<List<TaskEntry>> by lazy {
        val flow = MutableStateFlow(emptyList<TaskEntry>())

        coroutineScope.launch(Dispatchers.IO) {
            val animation = terminal.animation<List<TaskEntry>> { threadStates ->
                createTasksProgressWidget(threadStates)
            }

            terminal.cursor.hide(showOnExit = true)

            launch {
                while (true) {
                    updateState()
                    delay(100.milliseconds)
                }
            }

            val mutex = Mutex()
            try {
                flow.debounce(30.milliseconds).collectLatest { snapshot ->
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

        flow
    }

    private fun createTasksProgressWidget(taskEntries: List<TaskEntry>): Widget = verticalLayout {
        // Required to explicitly fill empty space with whitespaces and overwrite old lines
        align = TextAlign.LEFT
        // Required to correctly truncate very long status lines (or on very narrow terminal windows)
        width = ColumnWidth.Expand()

        cell("")

        for (entry in taskEntries.take(maxTasksOnScreen)) {
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
        if (taskEntries.size > maxTasksOnScreen) {
            cell("(+${taskEntries.size - maxTasksOnScreen} more)")
        }
    }

    private fun updateState() {
        updateFlow.update { old ->
            old.map { it.copy(elapsed = it.startTime.elapsedNow().roundToTheSecond()) }
        }
    }

    override fun taskStarted(task: Task): TaskProgressListener.TaskProgressCookie {
        val job = coroutineScope.launch(Dispatchers.IO) {
            val newTaskEntry = TaskEntry(task, startTime = timeSource.markNow(), elapsed = Duration.ZERO)
            delay(200.milliseconds)
            updateFlow.update { current ->
                current + newTaskEntry
            }
        }

        return object : TaskProgressListener.TaskProgressCookie {
            override fun close() {
                job.cancel()

                updateFlow.update { current ->
                    current.filterNot { it.task === task }
                }
            }
        }
    }
}

private fun Duration.roundToTheSecond(): Duration = inWholeSeconds.seconds
