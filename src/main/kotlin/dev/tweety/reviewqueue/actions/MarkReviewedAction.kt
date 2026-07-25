package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Marks the current file reviewed and advances to the next unreviewed one. */
class MarkReviewedAction : AnAction("Mark Reviewed", "Mark the current file reviewed and go to the next unreviewed file", AllIcons.Actions.Checked) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val snapshot = project?.let { ReviewQueueService.getInstance(it).snapshot() }
        e.presentation.isEnabled = snapshot?.cursor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewQueueService.getInstance(project).markCurrentReviewed()
    }
}
