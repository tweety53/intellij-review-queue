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
     * `null` until the first snapshot that actually holds a queue. Starting armed would fire the
     * balloon again every time a project whose queue is already fully reviewed is reopened.
     *
     * An empty queue must not seed it: at project open the first applied rebuild is frequently the
     * empty one (VCS mappings are not initialised yet), and seeding `armed = true` from that would
     * reintroduce exactly the balloon-on-reopen this guards against.
     */
    private var armed: Boolean? = null

    /**
     * @param announce pass false when the snapshot is the result of the reviewer asking for a
     * *different queue* rather than of them finishing this one. The arming state is still consumed:
     * the queue on screen really is complete, so the transition has happened, and re-announcing it
     * later would need the queue to go incomplete and complete again — which is the same rule every
     * other caller lives by. Only the balloon is withheld.
     */
    fun onSnapshot(snapshot: QueueSnapshot, announce: Boolean = true) {
        if (snapshot.items.isEmpty()) {
            if (armed != null) armed = true
            return
        }
        val complete = snapshot.reviewedCount == snapshot.items.size
        if (armed == null) armed = !complete
        if (!complete) {
            armed = true
            return
        }
        if (armed != true) return
        armed = false
        if (announce) notifyComplete(snapshot.items.size)
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
