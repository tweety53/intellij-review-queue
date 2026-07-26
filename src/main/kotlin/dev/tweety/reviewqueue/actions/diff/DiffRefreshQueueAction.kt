package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.RefreshQueueAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * Refresh on the diff toolbar, grouped at the end of the toolbar behind a separator, and confirming.
 *
 * Confirmed here but not in the tool window, and deliberately so. `ReviewSessionService` does not
 * subscribe to the queue and never re-settles on its own, so a refresh does not move the cursor out
 * from under the reader directly. What it does do is let the session's fixed key list go stale
 * against the freshly rebuilt queue — a discrepancy that only surfaces later, at `markCurrent()`'s
 * "left the queue" branch, far from the click that caused it. That disruption, one click away in the
 * middle of a pass, is enough to justify asking first. In the tool window there is no pass to
 * disrupt, so it stays a cheap re-read of a list.
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
