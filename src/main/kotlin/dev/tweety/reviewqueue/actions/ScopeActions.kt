package dev.tweety.reviewqueue.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.git.GitRoots
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.ui.ScopePrompts

/**
 * The one place the "choose a scope" rule lives, so the Tools menu group and the diff toolbar cannot
 * drift apart.
 *
 * `actionPerformed` is final on purpose: subclasses supply only the prompt. The confirmation lives
 * here rather than in `ReviewSessionService.switchScope` because `ReviewSessionServiceTest` drives
 * the real service headlessly, and a `Messages` call inside it would hang or fail the suite.
 *
 * Prompt first, then confirm — so the confirmation can name the scope being switched to, and
 * cancelling the ref prompt costs no confirmation dialog at all.
 */
abstract class SetScopeAction(text: String) : AnAction(text) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    /**
     * Gated on a git root, so the whole Tools menu group enables and disables as one. Without it,
     * `Commit Range…` in a repo-less project would prompt for refs and record a scope nothing can ever
     * resolve — harmless, but an inconsistency across five entries registered side by side, and these
     * three are reachable from Find Action too.
     *
     * `GitRoots.exist` rather than the queue service, because `update()` must not construct it. The
     * diff toolbar reaches these same instances through `ReviewQueue.ScopeMenu`, where a pass is running
     * and a root therefore exists, so the gate is invisible there.
     */
    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null && GitRoots.exist(project)
    }

    protected abstract fun promptScope(project: Project): ReviewScope?

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val scope = promptScope(project) ?: return
        val session = ReviewSessionService.getInstance(project)
        if (session.isActive && !confirmed(
                project,
                "Switch the review scope to ${scope.displayName()}? The current pass restarts and " +
                    "every mark made so far is kept.",
                "Switch Scope",
            )
        ) {
            return
        }
        session.switchScope(scope)
    }
}

class SetStagedAction : SetScopeAction("Staged") {
    override fun promptScope(project: Project) = ReviewScope.Staged
}

class SetBranchVsBaseAction : SetScopeAction("Branch vs Base…") {
    override fun promptScope(project: Project) = ScopePrompts.branchVsBase(project)
}

class SetCommitRangeAction : SetScopeAction("Commit Range…") {
    override fun promptScope(project: Project) = ScopePrompts.commitRange(project)
}
