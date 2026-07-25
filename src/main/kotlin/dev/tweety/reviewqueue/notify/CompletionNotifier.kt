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

    /**
     * `null` until the first snapshot, which seeds it. Starting armed would fire the balloon again
     * every time a project whose queue is already fully reviewed is reopened.
     */
    private var armed: Boolean? = null

    fun onSnapshot(snapshot: QueueSnapshot) {
        val complete = snapshot.items.isNotEmpty() && snapshot.reviewedCount == snapshot.items.size
        if (armed == null) armed = !complete
        if (!complete) {
            armed = true
            return
        }
        if (armed != true) return
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
