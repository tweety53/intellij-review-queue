package dev.tweety.reviewqueue.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Restores the layout after an IDE quit that happened mid-session. A session is not persisted, so
 * on the next open there is nothing running — but the windows it hid would still be hidden.
 *
 * The platform runs [execute] on `Dispatchers.Default`, and `ToolWindowImpl.show` opens with
 * `EDT.assertIsEdt()`. Without the switch this throws on exactly the path the persistence exists
 * for, leaving the Project window hidden with nothing to explain why.
 */
class ReviewLayoutRestorer : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            IdeLayoutController.getInstance(project).restore()
        }
    }
}
