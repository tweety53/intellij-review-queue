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
     * Scoped to the diff viewer, not just to an active session. The shortcut is registered
     * IDE-wide, so mid-session it is live in every editor — and marking "the file on screen" means
     * nothing in a normal editor. Without this gate the chord there would silently mark whichever
     * file the review happens to be sitting on.
     *
     * This is not about a keymap collision, and it is not what makes a shortcut fail to fire: the
     * gate was twice suspected of that and twice innocent. It is verified working on the keyboard
     * path — the same chord that reaches this action from the toolbar reaches it from the diff
     * editor. See MarkReviewedShortcutTest for the chord's own constraints.
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
