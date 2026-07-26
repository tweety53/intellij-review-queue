package dev.tweety.reviewqueue.notify

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * Records the balloons a gesture actually publishes.
 *
 * `Notification.notify(project)` reaches `Notifications.TOPIC` on the *project* message bus
 * synchronously in unit-test mode, so a subscriber sees exactly what the user would have been shown —
 * including the notification group id, which no stubbed sink could check.
 *
 * Preferred over injecting a fake notifier into the production classes: what these tests are about is
 * *which* claim is made about an empty resolve, and the claim has to be observed where it is published.
 */
object NotificationCapture {

    fun start(project: Project, parent: Disposable): List<Notification> {
        val recorded = mutableListOf<Notification>()
        project.messageBus.connect(parent).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    recorded += notification
                }
            },
        )
        return recorded
    }

    /** Every title and content line recorded so far, for substring assertions. */
    fun texts(recorded: List<Notification>): List<String> =
        recorded.map { "${it.title} ${it.content}" }
}
