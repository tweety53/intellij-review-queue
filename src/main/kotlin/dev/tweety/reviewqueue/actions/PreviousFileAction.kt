package dev.tweety.reviewqueue.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * Steps back one file without changing any mark. This is what makes a mis-mark recoverable:
 * marking advances immediately, so the wrong file is already behind you when you notice.
 */
class PreviousFileAction : AnAction(
    "Previous File",
    "Go back one file without changing its reviewed mark",
    AllIcons.Actions.Back,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val service = project?.let { ReviewSessionService.getInstance(it) }
        // Diff-viewer only, and never at the first file: there is nothing to step back to.
        e.presentation.isEnabled = service != null && service.isActive && service.currentKey() != null &&
            !service.isAtFirstFile &&
            e.getData(DiffDataKeys.DIFF_CONTEXT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).previous()
    }
}
