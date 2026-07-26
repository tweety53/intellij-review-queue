package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.git.GitRoots
import dev.tweety.reviewqueue.notify.QueueNotices
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService

/** Begins a guided pass over everything still unreviewed in the current scope. */
open class StartReviewAction : AnAction(
    "Start Review",
    "Hide the Project panel and walk the unreviewed files one at a time",
    AllIcons.Actions.Execute,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        // Deliberately not "the queue has unreviewed items": nothing resolves the queue until a
        // gesture asks, so a contents-based check would leave this permanently disabled.
        e.presentation.isEnabled = project != null &&
            GitRoots.exist(project) &&
            !ReviewSessionService.getInstance(project).isActive
    }

    /**
     * Deliberately has no emptiness guard of its own. It used to check
     * `items.none { !isReviewed(it) }`, which is a *weaker* condition than the one `start()` applies:
     * `start()` also needs the diff framework to render a file. One unreviewed-but-unrenderable file
     * therefore passed the guard, started nothing, and reported nothing. Asking `start()` whether it
     * started is the same question with no second predicate to drift from it.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val queue = ReviewQueueService.getInstance(project)
        if (!queue.resolveNow()) return
        if (!ReviewSessionService.getInstance(project).start()) {
            QueueNotices.emptyResult(project, queue.snapshot())
        }
    }
}
