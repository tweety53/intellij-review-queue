package dev.tweety.reviewqueue.notify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.git.GitRoots
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.QueueSnapshot

/**
 * Says so when a resolve leaves nothing for the gesture that asked for it — and says *which* nothing.
 *
 * Necessary because enablement is now based on "this project has a git root", not on queue contents:
 * every gesture is clickable with an empty queue, and each would otherwise do nothing visible. Under
 * the old design the tool window's empty list was that feedback; removing the panel removes it, so
 * silence here would be a regression rather than the status quo.
 *
 * An empty queue has more than one cause, and only one of them is "nothing is unreviewed". Announcing
 * that one unconditionally is the worst available output for a plugin whose whole job is stopping
 * files going unreviewed, and it is what the user is left with in the failure case: `ScopeErrorNotifier`
 * deduplicates by error map — it has to, because `changeListUpdateDone` lands on every VCS event — so
 * from the second press onward its balloon is silent and this message is the only one shown. Hence
 * [emptyMessage], which consults the snapshot before making any positive claim.
 *
 * A function rather than a class: unlike the other two notifiers this holds no arming state, because
 * it fires only from an explicit user gesture and so cannot be triggered by a background rebuild.
 */
object QueueNotices {

    /** What an empty resolve is being reported as, kept separate from publishing so it can be tested. */
    internal data class Notice(val text: String, val type: NotificationType)

    /**
     * Chooses the message for a gesture whose resolve left it nothing to act on.
     *
     * Ordered by how badly a wrong answer would mislead. A failed root is reported as a failure even
     * when a git root exists, because an empty queue then says nothing about how much is unreviewed.
     * The renderability arm is reached by Start Review and by a mid-pass scope switch. Their emptiness
     * test is "no unreviewed file could be *started*": if unreviewed files are in the queue and none of
     * them could be put on screen, the reviewer needs to know that the diff framework refused them, not
     * that their work is done.
     *
     * The rootless arm is defensive rather than routine — every entry point gates on
     * [GitRoots.exist] — but `repositories` is a cache that can empty between an `update()` poll and
     * the gesture, and "nothing unreviewed in Staged" for a project with no repository at all is
     * exactly the claim this object exists to prevent.
     */
    internal fun emptyMessage(snapshot: QueueSnapshot, hasGitRoot: Boolean): Notice = when {
        !hasGitRoot -> Notice("No git repository in this project", NotificationType.WARNING)

        snapshot.errors.isNotEmpty() -> Notice(
            if (snapshot.errors.size == 1) {
                "The review scope could not be read — one repository failed to resolve"
            } else {
                "The review scope could not be read — ${snapshot.errors.size} repositories failed to resolve"
            },
            NotificationType.WARNING,
        )

        snapshot.reviewedCount < snapshot.items.size -> Notice(
            "No unreviewed file in ${snapshot.scope.displayName()} could be displayed",
            NotificationType.WARNING,
        )

        else -> Notice("Nothing unreviewed in ${snapshot.scope.displayName()}", NotificationType.INFORMATION)
    }

    /** Announces the outcome [snapshot] actually represents. */
    fun emptyResult(project: Project, snapshot: QueueSnapshot) {
        val notice = emptyMessage(snapshot, GitRoots.exist(project))
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Review Queue")
            .createNotification(notice.text, notice.type)
            .notify(project)
    }
}
