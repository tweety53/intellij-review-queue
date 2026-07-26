package dev.tweety.reviewqueue.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * Steps forward one file without changing any mark — the counterpart to [PreviousFileAction].
 *
 * Distinct from Mark Reviewed, which marks the file on screen and then advances. This is for reading
 * ahead, or for coming back forward after stepping back to check something, without recording a
 * judgement on the file being left.
 */
class NextFileAction : AnAction(
    "Next File",
    "Go forward one file without changing its reviewed mark",
    AllIcons.Actions.Forward,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val service = project?.let { ReviewSessionService.getInstance(it) }
        // Diff-viewer only, and never at the last file: there is nothing to step forward to, and
        // running off the end here must not end the pass the way marking the last file does.
        e.presentation.isEnabled = service != null && service.isActive && service.currentKey() != null &&
            !service.isAtLastFile &&
            e.getData(DiffDataKeys.DIFF_CONTEXT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).nextFile()
    }
}
