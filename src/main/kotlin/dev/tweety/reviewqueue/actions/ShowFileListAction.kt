package dev.tweety.reviewqueue.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.ui.ReviewFileListPopup

/**
 * Opens the file list from the diff toolbar: the whole change and its reviewed state, without
 * ending the pass to go and look at the tool window that the pass has hidden.
 */
class ShowFileListAction : AnAction(
    "Show File List",
    "List every file in the review scope with its reviewed state",
    AllIcons.Actions.ListFiles,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        // Diff-viewer only, and only during a pass: outside one the tool window's own tree is
        // already on screen and is the better way to browse.
        e.presentation.isEnabled = project != null &&
            ReviewSessionService.getInstance(project).isActive &&
            e.getData(DiffDataKeys.DIFF_CONTEXT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewFileListPopup.show(project, e.dataContext)
    }
}
