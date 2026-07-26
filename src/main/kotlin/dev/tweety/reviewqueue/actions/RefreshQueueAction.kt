package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.git.GitRoots
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Re-resolves and re-hashes the queue on demand, for changes that arrive without a VCS event. */
open class RefreshQueueAction :
    AnAction("Refresh", "Re-read the review scope now", AllIcons.Actions.Refresh) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    /**
     * Gated on a git root, like the other menu commands. Until KAN-5 this action lived only in the tool
     * window, where an always-enabled button was harmless; reached from the Tools menu, an ungated
     * Refresh in a repo-less project opens a modal progress that resolves nothing.
     *
     * `GitRoots.exist` rather than the queue service, because `update()` must not construct it.
     */
    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null && GitRoots.exist(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        // resolveNow, not refresh(): with no tool window there is no list for an async refresh to
        // visibly update, so the modal progress is itself the feedback that anything happened.
        // This necessarily changes DiffRefreshQueueAction too — it calls super.actionPerformed.
        ReviewQueueService.getInstance(project).resolveNow()
    }
}
