package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.RefreshQueueAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * Refresh on the diff toolbar, grouped at the end of the toolbar behind a separator, and confirming.
 *
 * Since KAN-5, Refresh means a synchronous resolve on both surfaces — `RefreshQueueAction` is this
 * class's superclass and `actionPerformed` delegates to it, so the two cannot diverge without
 * duplicating the action. The confirmation is worth more than ever: the effect is now immediate
 * rather than surfacing later at `markCurrent()`'s "left the queue" branch, and it lets the session's
 * fixed key list go stale against a freshly rebuilt queue while the reviewer is mid-file. Cancelling
 * the progress is a supported way out.
 *
 * See `DiffStartReviewAction`'s KDoc for why `RightAlignedToolbarAction` is implemented here without
 * actually right-aligning anything.
 */
class DiffRefreshQueueAction : RefreshQueueAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Re-read the review scope now?", "Refresh")) {
            super.actionPerformed(e)
        }
    }
}
