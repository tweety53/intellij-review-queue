package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.StartReviewAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * Start Review on the diff toolbar, right-aligned and confirming.
 *
 * Subclassed rather than wrapped so `update()` is inherited untouched: StartReviewAction already
 * disables itself while a session is active, which is exactly the presentation wanted here.
 *
 * The confirmation is therefore unreachable today — ending a pass closes the diff tab, so the
 * button is only ever visible while disabled. It is written anyway so the group is uniform, and so
 * this does not quietly become a live unconfirmed press if that ever changes.
 */
class DiffStartReviewAction : StartReviewAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Start a new review pass over everything unreviewed?", "Start Review")) {
            super.actionPerformed(e)
        }
    }
}
