package dev.tweety.reviewqueue.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import dev.tweety.reviewqueue.model.CommitRangeValidator
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService
import javax.swing.JComponent

/** Toolbar dropdown choosing the review scope, prompting for refs where a scope needs them. */
class ScopeSelector : ComboBoxAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val scope = project?.let { ReviewQueueService.getInstance(it).snapshot().scope }
        e.presentation.text = scope?.displayName() ?: "Scope"
        // Changing the scope mid-pass would rebuild the queue underneath the session's fixed file
        // list, so the selector is inactive while a review is running.
        e.presentation.isEnabled =
            project != null && !ReviewSessionService.getInstance(project).isActive
    }

    override fun createPopupActionGroup(button: JComponent, context: com.intellij.openapi.actionSystem.DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(SetStagedAction())
        group.add(SetBranchVsBaseAction())
        group.add(SetCommitRangeAction())
        return group
    }

    private class SetStagedAction : AnAction("Staged") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.getData(CommonDataKeys.PROJECT) ?: return
            ReviewQueueService.getInstance(project).setScope(ReviewScope.Staged)
        }
    }

    private class SetBranchVsBaseAction : AnAction("Branch vs Base…") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.getData(CommonDataKeys.PROJECT) ?: return
            val base = Messages.showInputDialog(
                project,
                "Base ref (leave empty to use the tracked branch):",
                "Branch vs Base",
                null,
            ) ?: return
            ReviewQueueService.getInstance(project)
                .setScope(ReviewScope.BranchVsBase(base.takeIf { it.isNotBlank() }))
        }
    }

    private class SetCommitRangeAction : AnAction("Commit Range…") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.getData(CommonDataKeys.PROJECT) ?: return
            val from = Messages.showInputDialog(
                project, "From ref:", "Commit Range", null, "HEAD~1",
                object : InputValidator {
                    override fun checkInput(input: String) = CommitRangeValidator.validate(input, "HEAD") == null
                    override fun canClose(input: String) = checkInput(input)
                },
            ) ?: return
            val to = Messages.showInputDialog(
                project, "To ref:", "Commit Range", null, "HEAD",
                object : InputValidator {
                    override fun checkInput(input: String) = CommitRangeValidator.validate(from, input) == null
                    override fun canClose(input: String) = checkInput(input)
                },
            ) ?: return
            ReviewQueueService.getInstance(project).setScope(ReviewScope.CommitRange(from, to))
        }
    }
}
