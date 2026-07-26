package dev.tweety.reviewqueue.notify

import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.queue.QueueSnapshot

class ScopeErrorNotifierTest : HeavyPlatformTestCase() {

    private fun snapshot(errors: Map<String, String>) =
        QueueSnapshot(items = emptyList(), reviewedCount = 0, errors = errors, scope = ReviewScope.Staged)

    fun testAnEmptyErrorMapSaysNothing() {
        val fired = mutableListOf<Map<String, String>>()
        val notifier = ScopeErrorNotifier(project) { fired += it }

        notifier.onSnapshot(snapshot(emptyMap()))

        assertTrue("no errors means no balloon", fired.isEmpty())
    }

    fun testTheSameErrorMapIsAnnouncedOnlyOnce() {
        val fired = mutableListOf<Map<String, String>>()
        val notifier = ScopeErrorNotifier(project) { fired += it }
        val errors = mapOf("/repo" to "not a git repository")

        notifier.onSnapshot(snapshot(errors))
        notifier.onSnapshot(snapshot(errors))
        notifier.onSnapshot(snapshot(errors))

        assertEquals(
            "changeListUpdateDone lands on every VCS event; an unchanged failure must not repeat",
            1,
            fired.size,
        )
    }

    fun testARecurrenceIsAnnouncedAgain() {
        val fired = mutableListOf<Map<String, String>>()
        val notifier = ScopeErrorNotifier(project) { fired += it }
        val errors = mapOf("/repo" to "bad revision")

        notifier.onSnapshot(snapshot(errors))
        notifier.onSnapshot(snapshot(emptyMap()))
        notifier.onSnapshot(snapshot(errors))

        assertEquals("a root that recovers and breaks again is news again", 2, fired.size)
    }

    /**
     * The balloon's detail line is **git's own stderr**, and git quotes back the ref and the file names it
     * choked on: `fatal: bad revision '<ref>'`. Notification content is rendered as HTML, so a branch or
     * path named `<html><img src="http://…">` — a legal refname, and a legal filename — turned a failed
     * resolve into an outbound request to a host the repository picked, from a plugin whose one invariant
     * is that it only ever reads.
     *
     * Asserted through the real bus with the *default* `notify`, because the escaping lives in the
     * publishing half that the injected lambda in every other test here deliberately replaces.
     */
    fun testGitStderrIsNotRenderedAsMarkup() {
        val recorded = NotificationCapture.start(project, testRootDisposable)
        val hostile = """fatal: bad revision '<html><img src="http://example.invalid/beacon.png">'"""

        ScopeErrorNotifier(project).onSnapshot(snapshot(mapOf("/repo" to hostile)))

        val content = recorded.single().content
        assertFalse(
            "nothing git echoed back may reach the balloon as markup: got $content",
            content.contains("<img"),
        )
        assertTrue(
            "and the reviewer must still see what git said: got $content",
            content.contains("beacon.png"),
        )
    }

    /**
     * The cosmetic half of the same bug: the detail lines were joined with `"\n"`, which produces no break
     * at all in HTML content — two failed roots ran together on one line.
     */
    fun testMultipleFailedRootsAreOnSeparateLines() {
        val recorded = NotificationCapture.start(project, testRootDisposable)

        ScopeErrorNotifier(project)
            .onSnapshot(snapshot(mapOf("/a" to "not a git repository", "/b" to "bad revision")))

        val content = recorded.single().content
        assertTrue(
            "two failed roots must be separated by a real HTML break: got $content",
            content.contains("<br"),
        )
        assertFalse("a raw newline is invisible in HTML content: got $content", content.contains("\n"))
    }

    fun testADifferentFailureIsAnnounced() {
        val fired = mutableListOf<Map<String, String>>()
        val notifier = ScopeErrorNotifier(project) { fired += it }

        notifier.onSnapshot(snapshot(mapOf("/a" to "x")))
        notifier.onSnapshot(snapshot(mapOf("/a" to "x", "/b" to "y")))

        assertEquals("a newly broken root is a different failure", 2, fired.size)
    }
}
