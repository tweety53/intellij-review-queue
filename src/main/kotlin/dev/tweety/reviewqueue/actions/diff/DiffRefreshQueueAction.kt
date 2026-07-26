package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.RefreshQueueAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * Refresh on the diff toolbar, right-aligned and confirming.
 *
 * Confirmed here but not in the tool window, and deliberately so: a refresh mid-pass can move the
 * cursor underneath the reader, whereas in the tool window it is a cheap re-read of a list.
 */
class DiffRefreshQueueAction : RefreshQueueAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Re-read the review scope now?", "Refresh")) {
            super.actionPerformed(e)
        }
    }
}
