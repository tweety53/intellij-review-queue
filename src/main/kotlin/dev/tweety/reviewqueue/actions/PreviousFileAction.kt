package dev.tweety.reviewqueue.actions

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
        e.presentation.isEnabled = service != null && service.isActive && service.currentKey() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).previous()
    }
}
