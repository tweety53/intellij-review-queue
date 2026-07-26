package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService

/** Begins a guided pass over everything still unreviewed in the current scope. */
class StartReviewAction : AnAction(
    "Start Review",
    "Hide the side panels and walk the unreviewed files one at a time",
    AllIcons.Actions.Execute,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val queue = ReviewQueueService.getInstance(project)
        val hasUnreviewed = queue.snapshot().items.any { !queue.isReviewed(it) }
        e.presentation.isEnabled = !ReviewSessionService.getInstance(project).isActive && hasUnreviewed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).start()
    }
}
