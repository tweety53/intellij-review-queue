package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Clears every stored reviewed mark for this project, after confirmation. */
class ResetAllAction : AnAction("Reset All", "Clear every reviewed mark in this project", AllIcons.Actions.Rollback) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "Clear every reviewed mark in this project?",
            "Reset Review Progress",
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) {
            ReviewQueueService.getInstance(project).resetAll()
        }
    }
}
