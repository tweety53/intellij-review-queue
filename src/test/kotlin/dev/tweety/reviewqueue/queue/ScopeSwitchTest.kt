package dev.tweety.reviewqueue.queue

import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.notify.NotificationCapture
import dev.tweety.reviewqueue.ui.RecordingDiffPresenter
import dev.tweety.reviewqueue.ui.RecordingToolWindow
import dev.tweety.reviewqueue.ui.RecordingToolWindowManager
import java.io.File

/**
 * Pins `ReviewSessionService.switchScope`, which is the whole of mid-pass re-scoping, together with
 * the layout wiring the pass depends on.
 *
 * Every mid-pass case drives a real two-file Staged queue: with no git root the pass cannot start at
 * all, and a "the pass ended" assertion against a pass that never began would pass no matter what
 * `switchScope` does.
 *
 * The progress runner is swapped for a direct call, as `ResolveNowTest` does — the modal path is not
 * the headless path.
 *
 * The layout is asserted **directly**, through the project's [com.intellij.openapi.wm.ToolWindowManager]
 * swapped for a recording one. An earlier version of this file recorded that "`IdeLayoutController` has
 * no injectable seam" and counted presenter closes as a proxy for the spec's "no tool window is hidden
 * or restored in the process". That claim was wrong — `IdeLayoutControllerTest` had been swapping the
 * manager through `replaceService` all along — and the proxy was blind to the failure that matters most
 * here: a stray `hideForReview()` added on `switchScope`'s success path closes and reopens the Project
 * window with no effect on `closes` at all. The presenter assertions are kept alongside, because
 * "the tab was replaced, not closed" is a separate fact from "the layout was left alone".
 *
 * What remains a human check is the *flash*: these counters prove no hide or restore was requested,
 * not that nothing visibly blinked. Gate C still watches the window's left edge directly.
 */
class ScopeSwitchTest : HeavyPlatformTestCase() {

    private lateinit var projectWindow: RecordingToolWindow

    override fun setUp() {
        super.setUp()
        projectWindow = RecordingToolWindowManager.install(project, testRootDisposable)
            .register("Project", visible = true)
    }

    /**
     * Deliberately not named "only records the scope", which is what `switchScope`'s KDoc used to claim:
     * `setScope` also fires `refresh()`, so the scope is resolved immediately as well. What is asserted
     * is the part that is true — no pass is started, nothing is shown, and the scope is recorded for the
     * gesture that comes next.
     */
    fun testSwitchingWithNoSessionRecordsTheScopeWithoutStartingAPass() {
        val session = ReviewSessionService.getInstance(project)
        val presenter = RecordingDiffPresenter()
        session.presenter = presenter

        session.switchScope(ReviewScope.CommitRange("HEAD~2", "HEAD"))

        assertFalse("switching outside a pass must not start one", session.isActive)
        assertTrue("nothing should be shown", presenter.shown.isEmpty())
        assertEquals(
            "the chosen scope must be recorded for the next resolve",
            ReviewScope.CommitRange("HEAD~2", "HEAD"),
            ReviewQueueService.getInstance(project).snapshot().scope,
        )
    }

    /**
     * Spec scenario "The Project window is hidden for a pass" together with "Ending a pass restores
     * what it hid" — the two `review-layout-management` scenarios that were manual-only until this
     * file gained a [com.intellij.openapi.wm.ToolWindowManager] seam.
     *
     * Both halves of the wiring were untested: deleting `layout.hideForReview()` from
     * `ReviewSessionService.start()` and deleting `layout.restore()` from `end()` each left the whole
     * suite green, while leaving the reviewer either with a half-width diff or — far worse — with the
     * Project window hidden and nothing that ever reopens it.
     *
     * `hides == 1`, not merely `!isVisible`: the window is invisible after a hide either way, but only
     * the count distinguishes one hide from the double hide-restore-hide churn that going out through
     * `end()` and back in through `start()` would produce.
     */
    fun testAPassHidesTheProjectWindowAndEndingItRestoresIt() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingDiffPresenter()

        assertTrue("the fixture must give a startable pass", session.start())

        assertEquals("starting a pass must hide the Project window exactly once", 1, projectWindow.hides)
        assertFalse(projectWindow.isVisible)

        session.end()

        assertEquals("ending the pass must reopen it", 1, projectWindow.shows)
        assertTrue(projectWindow.isVisible)
    }

    /**
     * The recorded constraint from `resettle-in-place`: the diff tab is replaced, not closed and
     * reopened, and the layout is left exactly as the pass left it. `end()` then `start()` would close
     * the presenter and restore/re-hide the layout, flashing the Project tool window open and shut.
     *
     * Both facts are asserted, because they fail independently. `closes == 0` catches the route through
     * `end()`; the hide/show counts catch a stray `hideForReview()` added on the success path — which
     * touches no presenter and which this test could not see at all while it counted closes alone.
     */
    fun testSwitchingMidPassResettlesInPlaceWithoutClosingTheTab() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        val keys = queue.snapshot().items.map { it.key }
        val session = ReviewSessionService.getInstance(project)
        val presenter = RecordingDiffPresenter()
        session.presenter = presenter
        session.start()
        assertTrue("the fixture must give a startable pass", session.isActive)

        session.switchScope(ReviewScope.Staged)

        assertTrue("a non-empty new scope must keep the pass running", session.isActive)
        assertEquals("the tab must be replaced in place, never closed", 0, presenter.closes)
        assertEquals(
            "the pass must re-show the first unreviewed file of the new scope",
            listOf(keys[0], keys[0]),
            presenter.shown,
        )
        assertEquals(
            "the layout is already hidden and must stay hidden: a second hide is the flash",
            1,
            projectWindow.hides,
        )
        assertEquals("and nothing may reopen the Project window mid-pass", 0, projectWindow.shows)
    }

    fun testMarksSurviveAScopeSwitchAndThePassRestartsOnTheFirstUnreviewedFile() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        val items = queue.snapshot().items
        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingDiffPresenter()
        session.start()
        assertTrue(queue.markReviewed(items[0].key))

        session.switchScope(ReviewScope.Staged)

        assertTrue("marks are kept across a scope change", queue.isReviewed(items[0]))
        assertEquals(
            "the restarted pass must skip what is already reviewed",
            items[1].key,
            session.currentKey(),
        )
    }

    /**
     * `HEAD~1..HEAD` cannot resolve in the fixture's commit-less repo, so the rebuilt queue is empty
     * — the spec's "nothing unreviewed in the new scope" case, reached with a pass genuinely running.
     */
    fun testSwitchingIntoAnEmptyScopeEndsThePass() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        val session = ReviewSessionService.getInstance(project)
        val presenter = RecordingDiffPresenter()
        session.presenter = presenter
        session.start()
        assertTrue("the fixture must give a startable pass", session.isActive)

        session.switchScope(ReviewScope.CommitRange("HEAD~1", "HEAD"))

        assertTrue("the new scope must genuinely be empty", queue.snapshot().items.isEmpty())
        assertFalse("an empty new scope must end the pass rather than leave a dead tab", session.isActive)
        assertEquals("ending the pass closes the tab", 1, presenter.closes)
        assertEquals(
            "and restores the layout by the ordinary path — a dead pass must not leave the Project " +
                "window hidden",
            1,
            projectWindow.shows,
        )
        assertTrue(projectWindow.isVisible)
    }

    /**
     * Switching into a scope with nothing left to do is the one resolve that leaves the gesture
     * nothing and used to say nothing, while announcing a completion the reviewer never earned.
     *
     * Marks are content-addressed, so files carried into the new scope arrive already marked: the
     * queue this switch lands on is complete, `CompletionNotifier` is armed by the file that is about
     * to leave the scope, and the balloon it fires carries the `/myflow-do-done` copy action — a
     * workflow prompt for a pass that was never run. The pass that *did* have unreviewed work then
     * ended in silence.
     */
    fun testSwitchingIntoAnAllReviewedScopeSaysSoWithoutAnnouncingCompletion() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        val repoDir = File(project.basePath!!)
        File(repoDir, "c.txt").writeText("c\n")
        StagedQueueFixture.git(repoDir, "add", "c.txt")
        assertTrue(queue.resolveNow())
        assertEquals(
            "the pass needs unreviewed work, or nothing arms the completion notifier",
            3,
            queue.snapshot().items.size,
        )

        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingDiffPresenter()
        session.start()
        queue.snapshot().items.filter { it.key.relPath != "c.txt" }.forEach { queue.markReviewed(it.key) }

        // c.txt leaves the scope, so the switch resolves onto two files that are already reviewed.
        StagedQueueFixture.git(repoDir, "rm", "--cached", "c.txt")
        val recorded = NotificationCapture.start(project, testRootDisposable)

        session.switchScope(ReviewScope.Staged)

        val texts = NotificationCapture.texts(recorded)
        assertFalse("the pass has nothing left to walk", session.isActive)
        assertTrue(
            "the new scope must genuinely be complete, or this asserts nothing",
            queue.snapshot().items.isNotEmpty() &&
                queue.snapshot().reviewedCount == queue.snapshot().items.size,
        )
        assertTrue(
            "switching into a scope with nothing unreviewed must say so: got $texts",
            texts.any { it.contains("Nothing unreviewed") },
        )
        assertTrue(
            "a scope switch must not congratulate the reviewer for a pass they never ran: got $texts",
            texts.none { it.contains("files reviewed") },
        )
    }

    /**
     * The *second* route out of `switchScope` that ends the pass, which the empty-result notice
     * originally missed.
     *
     * `ReviewSession.start(unreviewedShowableKeys())` only asks the queue, so a scope full of files the
     * diff framework refuses — binaries, which `ChangeDiffRequestProducer.create` will not build a
     * request for — rebuilds a perfectly good session. `showCurrent()` then walks it, fails to render
     * anything, and calls `end()` itself. Both other empty-scope cases in this file take the
     * `rebuilt == null` branch, so neither could see this one, and for a while `switchScope`'s KDoc
     * named this exact route while nothing announced it: the pass died with no balloon at all, which is
     * narrowly the defect the notice was added to close.
     *
     * `canShow = false` is swapped in *after* `start()`, because a presenter that can show nothing
     * cannot start a pass either and the switch would then have no pass to end.
     */
    fun testSwitchingIntoAScopeWithNothingRenderableSaysSoRatherThanDyingQuietly() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingDiffPresenter()
        session.start()
        assertTrue("the fixture must give a startable pass", session.isActive)

        session.presenter = RecordingDiffPresenter(canShow = false)
        val recorded = NotificationCapture.start(project, testRootDisposable)

        session.switchScope(ReviewScope.Staged)

        val texts = NotificationCapture.texts(recorded)
        assertTrue(
            "the new scope must genuinely still hold unreviewed files, or this takes the " +
                "rebuilt == null branch and asserts nothing about this route",
            queue.snapshot().reviewedCount < queue.snapshot().items.size,
        )
        assertFalse("nothing renderable means the pass cannot continue", session.isActive)
        assertTrue(
            "a switch that ends the pass because nothing renders must still report it: got $texts",
            texts.any { it.contains("could be displayed") },
        )
        assertEquals("and the layout must be restored by the ordinary path", 1, projectWindow.shows)
    }

    /**
     * `resolveNow` runs before `session` is reassigned, so a cancelled progress leaves the pass on
     * the file it was on and the scope unapplied.
     */
    fun testACancelledResolveLeavesThePassAndScopeUntouched() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        val keys = queue.snapshot().items.map { it.key }
        val session = ReviewSessionService.getInstance(project)
        val presenter = RecordingDiffPresenter()
        session.presenter = presenter
        session.start()
        assertEquals(keys[0], session.currentKey())
        // null models the user dismissing the progress dialog.
        queue.progressRunner = { _, _ -> null }

        session.switchScope(ReviewScope.CommitRange("HEAD~1", "HEAD"))

        assertTrue("a cancelled rebuild must leave the pass running", session.isActive)
        assertEquals("on the same file", keys[0], session.currentKey())
        assertEquals("with no tab churn", listOf(keys[0]), presenter.shown)
        assertEquals(0, presenter.closes)
        assertEquals(
            "a cancelled rebuild must not record the new scope",
            ReviewScope.Staged,
            queue.snapshot().scope,
        )
    }
}
