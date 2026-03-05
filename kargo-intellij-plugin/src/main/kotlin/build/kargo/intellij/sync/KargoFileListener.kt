package build.kargo.intellij.sync

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class KargoFileListener : BulkFileListener {
    override fun after(events: MutableList<out VFileEvent>) {
        val hasKargoChanges = events.any { it.file?.name == "project.yaml" || it.file?.name == "module.yaml" }
        if (!hasKargoChanges) return
        
        // Find the corresponding project to trigger synchronization
        val openProjects = ProjectManager.getInstance().openProjects
        
        for (event in events) {
            val file = event.file ?: continue
            if (file.name != "project.yaml" && file.name != "module.yaml") continue
            
            for (project in openProjects) {
                // Check if this file belongs to the project's workspace
                val basePath = project.basePath ?: continue
                if (file.path.startsWith(basePath)) {
                    KargoSyncManager.getInstance(project).scheduleSync()
                    // Allow only a single sync per project per batch of events
                    break
                }
            }
        }
    }
}
