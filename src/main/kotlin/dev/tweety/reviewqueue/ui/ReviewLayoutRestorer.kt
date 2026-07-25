package dev.tweety.reviewqueue.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Restores the layout after an IDE quit that happened mid-session. A session is not persisted, so
 * on the next open there is nothing running — but the windows it hid would still be hidden.
 */
class ReviewLayoutRestorer : ProjectActivity {
    override suspend fun execute(project: Project) {
        IdeLayoutController.getInstance(project).restore()
    }
}
