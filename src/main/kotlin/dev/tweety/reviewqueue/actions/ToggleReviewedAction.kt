package dev.tweety.reviewqueue.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * Adds or removes the mark on the file currently displayed, without moving. Paired with
 * Previous File this is the recovery path for a mis-mark; without it the only undo is Reset All,
 * which clears every mark in the project.
 */
class ToggleReviewedAction : AnAction(
    "Toggle Reviewed",
    "Add or remove the reviewed mark on this file, without moving to another file",
    AllIcons.Actions.Undo,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val service = project?.let { ReviewSessionService.getInstance(it) }
        // Diff-viewer only: this acts on the file the diff is showing, so it has no meaning in a
        // normal editor even while a session is running.
        e.presentation.isEnabled = service != null && service.isActive && service.currentKey() != null &&
            e.getData(DiffDataKeys.DIFF_CONTEXT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).toggleCurrent()
    }
}
