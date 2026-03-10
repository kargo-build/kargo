package build.kargo.intellij.sync

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class KargoFileListener : BulkFileListener {

    override fun after(events: MutableList<out VFileEvent>) {
        val openProjects = ProjectManager.getInstance().openProjects
        if (openProjects.isEmpty()) return

        for (event in events) {
            // Filter events that don't represent possible content/structure changes
            if (event !is VFileContentChangeEvent &&
                event !is VFileCreateEvent &&
                event !is VFileDeleteEvent &&
                event !is VFileMoveEvent &&
                event !is VFileCopyEvent) {
                continue
            }

            val path = event.path
            if (!path.endsWith("/project.yaml") && !path.endsWith("/module.yaml")) {
                continue
            }

            for (project in openProjects) {
                val base = project.basePath ?: continue
                if (path.startsWith(base)) {
                    val file = event.file ?: continue
                    val lastStamp = lastStamps.getOrDefault(path, -1L)
                    val currentStamp = file.modificationCount

                    if (currentStamp != lastStamp) {
                        lastStamps[path] = currentStamp
                        requestSync(project)
                    }
                }
            }
        }
    }

    companion object {
        private val alarms = ConcurrentHashMap<Project, Alarm>()
        private val syncing = ConcurrentHashMap<Project, AtomicBoolean>()
        private val lastStamps = ConcurrentHashMap<String, Long>()

        private fun requestSync(project: Project) {
            val alarm = alarms.computeIfAbsent(project) {
                Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
            }

            val syncFlag = syncing.computeIfAbsent(project) {
                AtomicBoolean(false)
            }

            alarm.cancelAllRequests()
            alarm.addRequest({
                if (!syncFlag.compareAndSet(false, true)) {
                    return@addRequest
                }

                try {
                    KargoSyncManager.getInstance(project).scheduleSync()
                } finally {
                    syncFlag.set(false)
                }
            }, 500) // debounce
        }
    }
}