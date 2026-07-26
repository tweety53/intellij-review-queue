package dev.tweety.reviewqueue.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * The one yes/no shape in the plugin, so every destructive or disruptive button asks the same way.
 *
 * Returns true when the user said yes. Closing the dialog counts as no.
 */
fun confirmed(project: Project, message: String, title: String): Boolean =
    Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon()) == Messages.YES
