package build.kargo.intellij.sync

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * A dialog to display detailed errors and warnings from Kargo sync.
 */
class KargoSyncErrorDialog(project: Project, private val messages: List<SyncMessage>) : DialogWrapper(project) {
    
    init {
        title = "Kargo Sync Details"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val htmlContent = buildString {
            append("<html><body style='font-family: sans-serif; padding: 10px;'>")
            messages.forEach { msg ->
                val severityLabel = when (msg.severity) {
                    SyncSeverity.ERROR -> "<span style='color: #e53935; font-weight: bold;'>[Error]</span>"
                    SyncSeverity.WARNING -> "<span style='color: #fb8c00; font-weight: bold;'>[Warning]</span>"
                }
                append("<div style='margin-bottom: 15px;'>$severityLabel ${msg.content}</div>")
            }
            append("</body></html>")
        }

        val htmlPane = JBHtmlPane()
        htmlPane.isEditable = false
        htmlPane.text = htmlContent
        
        val scrollPane = JBScrollPane(htmlPane)
        scrollPane.preferredSize = Dimension(900, 500)
        return scrollPane
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
