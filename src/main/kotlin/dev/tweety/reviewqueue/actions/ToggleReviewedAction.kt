package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.ui.ReviewQueueDataKeys

/**
 * Toggles the reviewed mark on the selected row, which is the only way to un-mark a single file
 * without Reset All destroying every mark in the project. Deliberately does not move the cursor.
 */
class ToggleReviewedAction : AnAction(
    "Toggle Reviewed",
    "Add or remove the reviewed mark on the selected file, without moving the cursor",
    AllIcons.Actions.Undo,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled =
            e.getData(CommonDataKeys.PROJECT) != null && e.getData(ReviewQueueDataKeys.SELECTED_KEY) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val key = e.getData(ReviewQueueDataKeys.SELECTED_KEY) ?: return
        ReviewQueueService.getInstance(project).toggleReviewed(key)
    }
}
