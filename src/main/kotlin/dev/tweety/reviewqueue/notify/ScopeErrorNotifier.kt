package dev.tweety.reviewqueue.notify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import dev.tweety.reviewqueue.queue.QueueSnapshot

/**
 * Announces git roots that failed to resolve. This is what replaces the deleted tool window's error
 * label.
 *
 * Deduplicated by error map, not merely by emptiness: `changeListUpdateDone` lands on every VCS
 * event, so firing whenever the map is non-empty would balloon on every rebuild. A persistently
 * broken root is announced once; one that recovers and breaks again is announced again.
 * `CompletionNotifier.armed` is the precedent for this shape.
 *
 * The `notify` lambda exists so the dedupe rule can be tested without asserting against the
 * notification bus.
 */
class ScopeErrorNotifier(
    // Not a `val`: the default argument below captures the constructor parameter, and nothing else
    // reads it. Holding it as a property would be unused state.
    project: Project,
    private val notify: (Map<String, String>) -> Unit = { balloon(project, it) },
) {
    private var lastReported: Map<String, String> = emptyMap()

    fun onSnapshot(snapshot: QueueSnapshot) {
        if (snapshot.errors == lastReported) return
        // Updated before the empty-map return on purpose: without that, a root that fails, recovers,
        // then fails again would compare equal to the still-recorded failure and stay silent.
        lastReported = snapshot.errors
        if (snapshot.errors.isEmpty()) return
        notify(snapshot.errors)
    }

    private companion object {
        /**
         * The detail lines go through [HtmlChunk], because **the values are git's own stderr** and
         * notification content is rendered as HTML.
         *
         * git quotes back the ref and the file names it choked on — `fatal: bad revision '<ref>'` — and a
         * refname or a path may legally be `<html><img src="http://…">`, so a failed resolve used to make
         * the IDE issue an outbound request to a host the repository picked, in a plugin whose one
         * invariant is that it only ever reads. [HtmlChunk.text] escapes it, and [HtmlChunk.br] fixes the
         * same expression's other bug in passing: the `"\n"` this used to join with produces no line break
         * in HTML at all, so two failed roots ran together on one line.
         */
        fun balloon(project: Project, errors: Map<String, String>) {
            val detail = errors.entries.joinToString(HtmlChunk.br().toString()) {
                HtmlChunk.text("${it.key} — ${it.value}").toString()
            }
            val heading =
                if (errors.size == 1) "A repository could not be read"
                else "${errors.size} repositories could not be read"
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Review Queue")
                .createNotification(heading, detail, NotificationType.WARNING)
                .notify(project)
        }
    }
}
