package build.kargo.intellij.sync

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.Level

/**
 * Severity levels for Kargo sync messages.
 */
enum class SyncSeverity {
    ERROR, WARNING
}

/**
 * A structured sync message with content and severity.
 */
data class SyncMessage(val content: String, val severity: SyncSeverity)

/**
 * Collects errors and problems during the Kargo sync process.
 */
class KargoSyncErrorCollector {
    private val logger = Logger.getInstance(KargoSyncErrorCollector::class.java)
    private val messagesList = mutableListOf<SyncMessage>()
    
    val messages: List<SyncMessage> get() = messagesList
    
    // For backward compatibility during migration
    val errors: List<String> get() = messagesList.map { it.content }

    fun reportError(message: String, severity: SyncSeverity = SyncSeverity.ERROR) {
        if (severity == SyncSeverity.ERROR) {
            logger.warn("Kargo Sync Error: $message")
        } else {
            logger.info("Kargo Sync Warning: $message")
        }
        messagesList.add(SyncMessage(message, severity))
    }

    fun reportProblem(problem: BuildProblem) {
        val severity = when (problem.level) {
            Level.Error -> SyncSeverity.ERROR
            Level.Warning, Level.WeakWarning -> SyncSeverity.WARNING
        }
        
        if (severity == SyncSeverity.ERROR) {
            logger.warn("Kargo Sync Problem: ${problem.message}")
        } else {
            logger.info("Kargo Sync Warning: ${problem.message}")
        }
        
        val msg = problem.message.trim().replace("\n", "<br>")
        messagesList.add(SyncMessage("<b>Project Problem:</b> $msg", severity))
    }

    fun reportException(t: Throwable) {
        logger.error("Kargo Sync Exception", t)
        val msg = (t.message ?: t.javaClass.simpleName).trim().replace("\n", "<br>")
        messagesList.add(SyncMessage("<b>Sync Exception:</b> $msg", SyncSeverity.ERROR))
    }

    fun hasErrors(): Boolean = messagesList.any { it.severity == SyncSeverity.ERROR }
    
    fun hasAnyMessages(): Boolean = messagesList.isNotEmpty()
    
    fun clear() {
        messagesList.clear()
    }
}
