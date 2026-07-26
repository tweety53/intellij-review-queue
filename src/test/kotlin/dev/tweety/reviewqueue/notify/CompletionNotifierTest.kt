package dev.tweety.reviewqueue.notify

import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.core.ContentHasher
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.queue.QueueSnapshot

/**
 * Pins the `armed: Boolean?` three-state machine, which had no test class at all.
 *
 * Three of its rules are documented in KDoc and were machine-checked nowhere: the balloon must not
 * fire when a project whose queue is already fully reviewed is *reopened*; the empty rebuild that
 * lands first at project open must not seed the arming state; and a **suppressed** completion — a
 * scope switch — must still *consume* the arming state, so that the next complete snapshot does not
 * congratulate the reviewer for a pass they never ran.
 *
 * Asserted against the real `Notifications.TOPIC` bus via [NotificationCapture] rather than a stubbed
 * sink, because the notification group id and the `/myflow-do-done` copy action are part of what is
 * being withheld, and only the published balloon carries them. The text is read off `content` rather
 * than `title`: `createNotification(String, NotificationType)` is the content-only overload, so these
 * balloons have no title at all.
 */
class CompletionNotifierTest : HeavyPlatformTestCase() {

    private fun snapshot(size: Int, reviewed: Int) = QueueSnapshot(
        items = (0 until size).map { ReviewItem(ReviewKey("/repo", "f$it.txt"), ContentHasher.unresolved()) },
        reviewedCount = reviewed,
        errors = emptyMap(),
        scope = ReviewScope.Staged,
    )

    fun testFinishingTheQueueIsAnnouncedOnce() {
        val recorded = NotificationCapture.start(project, testRootDisposable)
        val notifier = CompletionNotifier(project)

        notifier.onSnapshot(snapshot(size = 2, reviewed = 1))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2))

        assertEquals(
            "finishing the queue must be announced",
            listOf("All 2 files reviewed"),
            recorded.map { it.content },
        )
    }

    /** `changeListUpdateDone` lands on every VCS event, so a complete queue is re-seen constantly. */
    fun testRepeatedCompleteSnapshotsDoNotRepeatTheBalloon() {
        val recorded = NotificationCapture.start(project, testRootDisposable)
        val notifier = CompletionNotifier(project)

        notifier.onSnapshot(snapshot(size = 2, reviewed = 1))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2))

        assertEquals("re-seeing a queue that was already complete is not news", 1, recorded.size)
    }

    /**
     * The `armed == null` hazard the field's KDoc names first: a project whose queue is already fully
     * reviewed publishes a complete snapshot as its *first* real one, every single time it is opened.
     */
    fun testReopeningAProjectWithAnAlreadyCompleteQueueSaysNothing() {
        val recorded = NotificationCapture.start(project, testRootDisposable)
        val notifier = CompletionNotifier(project)

        notifier.onSnapshot(snapshot(size = 3, reviewed = 3))

        assertEquals(
            "a completion never observed as a transition must not be announced on reopen: got " +
                NotificationCapture.texts(recorded),
            0,
            recorded.size,
        )
    }

    /**
     * The field's second documented hazard: at project open the first applied rebuild is frequently
     * the empty one, because VCS mappings are not initialised yet. Arming from that would reintroduce
     * the balloon-on-reopen this guards against, one rebuild later.
     */
    fun testTheEmptyStartupRebuildDoesNotArmTheNotifier() {
        val recorded = NotificationCapture.start(project, testRootDisposable)
        val notifier = CompletionNotifier(project)

        notifier.onSnapshot(snapshot(size = 0, reviewed = 0))
        notifier.onSnapshot(snapshot(size = 3, reviewed = 3))

        assertEquals(
            "an empty queue is not evidence of unreviewed work: got " +
                NotificationCapture.texts(recorded),
            0,
            recorded.size,
        )
    }

    /**
     * Once a queue has been seen, emptying it *is* a transition — Reset All, or a scope with nothing in
     * it — so the next completion is genuine news again.
     */
    fun testAnEmptyQueueRearmsOnceAQueueHasBeenSeen() {
        val recorded = NotificationCapture.start(project, testRootDisposable)
        val notifier = CompletionNotifier(project)

        notifier.onSnapshot(snapshot(size = 2, reviewed = 1))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2))
        notifier.onSnapshot(snapshot(size = 0, reviewed = 0))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2))

        assertEquals("a queue that emptied and completed again is news again", 2, recorded.size)
    }

    /**
     * The `announce = false` half that no test covered: a scope switch's completion is *withheld*, and
     * the tested part was only the withholding.
     *
     * Consuming the arming state is the other half of the contract, and the whole point of it. Marks
     * are content-addressed, so the queue a switch lands on really is complete — the transition has
     * happened. Leaving `armed = true` means the very next complete snapshot, which
     * `changeListUpdateDone` delivers on any VCS event, fires "All N files reviewed" with its
     * `/myflow-do-done` copy action, without the queue ever having gone incomplete: the
     * congratulation-for-a-pass-you-never-ran bug, deferred rather than fixed.
     */
    fun testASuppressedCompletionConsumesTheArmingStateSoTheNextOneIsNotAnnounced() {
        val recorded = NotificationCapture.start(project, testRootDisposable)
        val notifier = CompletionNotifier(project)

        notifier.onSnapshot(snapshot(size = 2, reviewed = 1))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2), announce = false)
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2))

        assertEquals(
            "a withheld completion must still consume the arming state, or the next rebuild " +
                "announces the completion the switch was not allowed to: got " +
                NotificationCapture.texts(recorded),
            0,
            recorded.size,
        )
    }

    /**
     * The suppression must not be a permanent mute either: after a suppressed completion, work that
     * really is finished by the reviewer is announced, on the ordinary incomplete-then-complete rule.
     */
    fun testAGenuineCompletionAfterASuppressedOneIsStillAnnounced() {
        val recorded = NotificationCapture.start(project, testRootDisposable)
        val notifier = CompletionNotifier(project)

        notifier.onSnapshot(snapshot(size = 2, reviewed = 1))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2), announce = false)
        notifier.onSnapshot(snapshot(size = 2, reviewed = 1))
        notifier.onSnapshot(snapshot(size = 2, reviewed = 2))

        assertEquals(
            "the next genuine completion must behave exactly as it would have",
            listOf("All 2 files reviewed"),
            recorded.map { it.content },
        )
    }
}
