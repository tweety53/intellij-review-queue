package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Clears every stored reviewed mark for this project, after confirmation. */
// AllIcons.General.Reset, not Actions.Rollback: this plugin never mutates a repository, and a
// rollback arrow directly above a diff reads as "revert these changes" rather than "clear marks".
open class ResetAllAction : AnAction("Reset All", "Clear every reviewed mark in this project", AllIcons.General.Reset) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Clear every reviewed mark in this project?", "Reset Review Progress")) {
            ReviewQueueService.getInstance(project).resetAll()
        }
    }
}
