package build.kargo.intellij.sync

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.notification.NotificationType.ERROR
import com.intellij.notification.NotificationType.WARNING
import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationAction

@Service(Service.Level.PROJECT)
class KargoSyncManager(
    val project: Project
) {
    private val logger = Logger.getInstance(KargoSyncManager::class.java)
    private val isSyncRunning = AtomicBoolean(false)
    var projectAware: build.kargo.intellij.project.KargoProjectAware? = null

    companion object {
        fun getInstance(project: Project): KargoSyncManager =
            project.getService(KargoSyncManager::class.java)
    }
    
    fun scheduleSync() {
        if (!isSyncRunning.compareAndSet(false, true)) {
            logger.info("Kargo: Sync already in progress for project ${project.name}, dropping redundant request")
            return
        }

        logger.info("Kargo: scheduleSync triggered for project ${project.name}")
        projectAware?.notifySyncStarted()
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Kargo Sync", false) {
            override fun run(indicator: ProgressIndicator) {
                var success = false
                try {
                    indicator.isIndeterminate = true
                    indicator.text = "Kargo: Resolving dependencies..."

                    if (project.isDisposed) return

                    val projectPath = project.basePath?.let { java.nio.file.Path.of(it) } ?: return
                    val errorCollector = KargoSyncErrorCollector()

                    val model = try {
                        KargoModelReader.readModel(projectPath, project, errorCollector)
                    } catch (t: Throwable) {
                        logger.error("Kargo: Model read failed", t)
                        errorCollector.reportException(t)
                        null
                    }

                    if (model != null && !project.isDisposed) {
                        indicator.text = "Kargo: Configuring modules..."
                        val updater = KargoWorkspaceModelUpdater(project)
                        updater.updateWorkspaceModel(model, errorCollector)
                    }

                    if (errorCollector.hasAnyMessages()) {
                        val hasErrors = errorCollector.hasErrors()
                        val title = if (hasErrors) "Kargo Sync: failed" else "Kargo Sync: completed with warnings"
                        val msg = if (hasErrors) "Kargo sync completed with errors. Check details for more information."
                                      else "Kargo sync completed with warnings. Check details for more information."
                        val type = if (hasErrors) ERROR
                                   else WARNING

                        val app = ApplicationManager.getApplication()
                        app.invokeLater {
                            if (!project.isDisposed) {
                                val notification = NotificationGroupManager.getInstance()
                                    .getNotificationGroup("Kargo")
                                    .createNotification(
                                        title,
                                        msg,
                                        type
                                    )

                                notification.addAction(NotificationAction.createSimple("Show Details") {
                                    KargoSyncErrorDialog(project, errorCollector.messages).show()
                                })

                                notification.notify(project)
                            }
                        }
                    }
                    success = !errorCollector.hasErrors()
                } finally {
                    isSyncRunning.set(false)
                    projectAware?.notifySyncFinished(success)
                }
            }
        })
    }
}
