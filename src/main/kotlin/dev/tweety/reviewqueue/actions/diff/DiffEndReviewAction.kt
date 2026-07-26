package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.EndReviewAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * End Review on the diff toolbar, right-aligned and confirming.
 *
 * The only End Review on this toolbar. An unconfirmed twin alongside it would be a trap: the
 * buttons sit directly above the code being read, and muscle memory built on one would fire the
 * other.
 */
class DiffEndReviewAction : EndReviewAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Leave the guided review? Every mark made so far is kept.", "End Review")) {
            super.actionPerformed(e)
        }
    }
}
