package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.git.GitRoots
import dev.tweety.reviewqueue.notify.QueueNotices
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.ui.ReviewFileListPopup

/**
 * The whole change and its reviewed state, as a popup.
 *
 * Since KAN-5 removed the tool window, this is the *only* way to browse the queue, so it no longer
 * requires a running pass or a focused diff viewer — the old gates existed because the tool window's
 * tree was the better way to browse outside a pass, and that tree no longer exists.
 */
class ShowFileListAction : AnAction(
    "Show File List",
    "List every file in the review scope with its reviewed state",
    AllIcons.Actions.ListFiles,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null && GitRoots.exist(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val queue = ReviewQueueService.getInstance(project)
        // Inside a pass the session already holds a resolved queue and re-resolving would let the
        // session's fixed key list go stale against a freshly rebuilt one. Outside a pass, nothing
        // else has resolved anything.
        if (!ReviewSessionService.getInstance(project).isActive && !queue.resolveNow()) return
        val snapshot = queue.snapshot()
        if (snapshot.items.isEmpty()) {
            QueueNotices.emptyResult(project, snapshot)
            return
        }
        ReviewFileListPopup.show(project, e.dataContext)
    }
}
