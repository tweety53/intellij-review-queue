package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.StartReviewAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * Start Review on the diff toolbar, grouped at the end of the toolbar behind a separator, and
 * confirming.
 *
 * Subclassed rather than wrapped so `update()` is inherited untouched: StartReviewAction already
 * disables itself while a session is active, which is exactly the presentation wanted here.
 *
 * The confirmation is therefore unreachable today — ending a pass closes the diff tab, so the
 * button is only ever visible while disabled. It is written anyway so the group is uniform, and so
 * this does not quietly become a live unconfirmed press if that ever changes.
 *
 * This class, like its three siblings in this package, also implements `RightAlignedToolbarAction`.
 * That marker is honoured — `ActionToolbarImpl.fillToolBar` pushes marked actions to the right edge
 * of the component it lays out. But the diff viewer's header does not hand this toolbar a component
 * with a right edge to push against: `DiffHeaderToolbarUtil.createLayoutPanel` places it with
 * `AlignX.LEFT`, anchored at its preferred width, so the toolbar's own right edge sits immediately
 * past its last button. The marker therefore buys nothing visible in this toolbar as laid out today.
 * It is kept anyway — it costs nothing, it documents the original intent, and it would take effect
 * unchanged if that layout ever gave the toolbar room to flush against. What actually produces the
 * grouping seen on screen is the `Separator` placed ahead of these four actions in
 * `ReviewSessionService.diffActions`. Losing that fact is the risk of deleting the marker: it would
 * look like dead code to a reader who has not read this far.
 */
class DiffStartReviewAction : StartReviewAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Start a new review pass over everything unreviewed?", "Start Review")) {
            super.actionPerformed(e)
        }
    }
}
