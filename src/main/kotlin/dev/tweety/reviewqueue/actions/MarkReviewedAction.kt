package dev.tweety.reviewqueue.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService

/** Marks the file on screen reviewed and moves to the next one. The core interaction. */
class MarkReviewedAction : AnAction(
    "Mark Reviewed",
    "Mark this file reviewed and open the next one",
    AllIcons.Actions.Checked,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    /**
     * Scoped to the diff viewer, not just to an active session. The Ctrl+Shift+Space binding is
     * registered in `$default`, where it collides with Smart Type Completion; without the
     * DIFF_CONTEXT gate the chord in a normal editor mid-session either silently mis-marks a file
     * or raises a "Choose action" popup instead of completing.
     */
    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null &&
            ReviewSessionService.getInstance(project).isActive &&
            e.getData(DiffDataKeys.DIFF_CONTEXT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).markCurrent()
    }
}
