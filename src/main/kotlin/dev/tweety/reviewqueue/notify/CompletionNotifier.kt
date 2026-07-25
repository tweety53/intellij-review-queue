package dev.tweety.reviewqueue.notify

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.queue.QueueSnapshot
import git4idea.repo.GitRepositoryManager
import java.awt.datatransfer.StringSelection

/** Recovers a myflow change name from an `openspec/<name>` branch. */
object BranchNameParser {
    private const val PREFIX = "openspec/"

    fun changeName(branch: String?): String? {
        if (branch == null || !branch.startsWith(PREFIX)) return null
        return branch.removePrefix(PREFIX).takeIf { it.isNotBlank() }
    }
}

/**
 * Announces a completed queue once, re-arming only after the queue goes incomplete again so that
 * refreshes cannot repeat the balloon.
 */
class CompletionNotifier(private val project: Project) {

    private var armed = true

    fun onSnapshot(snapshot: QueueSnapshot) {
        val complete = snapshot.items.isNotEmpty() && snapshot.reviewedCount == snapshot.items.size
        if (!complete) {
            armed = true
            return
        }
        if (!armed) return
        armed = false
        notifyComplete(snapshot.items.size)
    }

    private fun notifyComplete(count: Int) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Review Queue")
            .createNotification("All $count files reviewed", NotificationType.INFORMATION)

        changeName()?.let { name ->
            val command = "/myflow-do-done $name"
            notification.addAction(
                NotificationAction.createSimpleExpiring("Copy $command") {
                    CopyPasteManager.getInstance().setContents(StringSelection(command))
                }
            )
        }
        notification.notify(project)
    }

    private fun changeName(): String? =
        GitRepositoryManager.getInstance(project).repositories
            .firstNotNullOfOrNull { BranchNameParser.changeName(it.currentBranch?.name) }
}
