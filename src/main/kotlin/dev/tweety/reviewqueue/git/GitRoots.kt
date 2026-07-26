package dev.tweety.reviewqueue.git

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Whether this project has any git root at all — the enablement predicate for the review actions.
 *
 * Deliberately top-level rather than a method on `ReviewQueueService`. Actions call this from
 * `update()`, and a service method would construct the project service there, wiring
 * `ChangeListListener` and `GIT_REPO_CHANGE` as a side effect of hovering the Tools menu. This keeps
 * `git4idea` out of the action classes just as well and leaves the service uncreated until a real
 * gesture.
 *
 * `repositories` is cached and EDT-safe, but empty until VCS mappings initialise — so the review
 * actions are briefly disabled after a project opens and re-enable on the next `update()` poll,
 * without needing an event.
 */
object GitRoots {
    fun exist(project: Project): Boolean =
        GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
}
