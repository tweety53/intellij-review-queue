package dev.tweety.reviewqueue.queue

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.actions.EndReviewAction
import dev.tweety.reviewqueue.actions.diff.DiffEndReviewAction
import dev.tweety.reviewqueue.actions.diff.DiffRefreshQueueAction
import dev.tweety.reviewqueue.actions.diff.DiffResetAllAction
import dev.tweety.reviewqueue.actions.diff.DiffScopeAction
import dev.tweety.reviewqueue.actions.diff.DiffStartReviewAction
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.ui.ReviewDiffPresenter
import java.io.File

/**
 * Drives the real session service with a fake presenter, so the flow is verified without a live
 * diff framework. The presenter interface exists for exactly this.
 */
class ReviewSessionServiceTest : HeavyPlatformTestCase() {

    private open class FakePresenter : ReviewDiffPresenter {
        val shown = mutableListOf<ReviewKey>()
        val attempted = mutableListOf<ReviewKey>()
        var closed = 0
        override fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean {
            attempted += key
            if (!canShow(key)) return false
            shown += key
            return true
        }
        open fun canShow(key: ReviewKey): Boolean = true
        override fun close() { closed++ }
        override fun isShowing(file: VirtualFile) = false
    }

    fun testStartIsInactiveWhenTheQueueIsEmpty() {
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()

        assertFalse("an empty queue must not start a session", service.isActive)
        assertTrue("an empty queue must not open any diff", presenter.shown.isEmpty())
    }

    fun testEndClosesThePresenterAndDeactivates() {
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.end()

        assertFalse(service.isActive)
        assertNull(service.currentKey())
        assertEquals("end() must close the presenter's tab", 1, presenter.closed)
    }

    /**
     * The skip loop in `showCurrent()` is the only thing that keeps an unrenderable file from ending
     * the pass early — unrenderable files are handled reactively here, not filtered out up front.
     */
    fun testAnUnrenderableFileIsSkippedRatherThanEndingThePass() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        val presenter = object : FakePresenter() {
            override fun canShow(key: ReviewKey) = key != keys[0]
        }
        service.presenter = presenter

        service.start()

        assertTrue("an unrenderable first file must not end the pass", service.isActive)
        assertEquals("the unrenderable file must be tried, then skipped", keys, presenter.attempted)
        assertEquals(listOf(keys[1]), presenter.shown)
        assertEquals(keys[1], service.currentKey())
    }

    /**
     * Shared with `ScopeSwitchTest` and `SetScopeActionTest`, which need the same real queue: a pass
     * cannot start without one, so assertions about a running pass would hold vacuously.
     */
    private fun stagedQueueOfTwoFiles(): ReviewQueueService =
        StagedQueueFixture.stagedQueueOfTwoFiles(project)

    fun testTheDiffToolbarSplitsNavigationFromRightAlignedSessionControls() {
        val actions = ReviewSessionService.getInstance(project).diffActions
        val manager = ActionManager.getInstance()

        val navigation = actions.filterNot { it is RightAlignedToolbarAction || it is Separator }
        val sessionControls = actions.filterIsInstance<RightAlignedToolbarAction>()

        assertEquals(
            "navigation actions must be resolved by id, or their tooltips lose the shortcut",
            listOf(
                manager.getAction("ReviewQueue.ShowFileList"),
                manager.getAction("ReviewQueue.PreviousFile"),
                manager.getAction("ReviewQueue.NextFile"),
                manager.getAction("ReviewQueue.MarkReviewed"),
                manager.getAction("ReviewQueue.ToggleReviewed"),
            ),
            navigation,
        )
        assertEquals(
            listOf(
                DiffScopeAction::class.java,
                DiffStartReviewAction::class.java,
                DiffEndReviewAction::class.java,
                DiffRefreshQueueAction::class.java,
                DiffResetAllAction::class.java,
            ),
            sessionControls.map { it.javaClass },
        )
        // The marker interface no longer flush-aligns anything in this toolbar's layout (see
        // ReviewSessionService.diffActions); this separator is the entire visual grouping mechanism
        // now, so it earns its own pinned assertion rather than riding along inside the partition.
        assertEquals(
            "a separator must sit between the navigation group and the session controls",
            navigation.size,
            actions.indexOfFirst { it is Separator },
        )
    }

    fun testTheScopeControlLeadsTheSessionControlGroup() {
        val service = ReviewSessionService.getInstance(project)
        val actions = service.diffActions
        val separatorAt = actions.indexOfFirst { it is Separator }

        assertTrue("the toolbar must still be split by a separator", separatorAt >= 0)
        assertTrue(
            "the scope control must lead the session-control group, so re-scoping sits with the " +
                "other session commands rather than among per-file navigation",
            actions[separatorAt + 1] is DiffScopeAction,
        )
    }

    fun testTheFileListActionIsRegistered() {
        assertNotNull(
            "an unregistered id resolves to null and listOfNotNull drops it silently",
            ActionManager.getInstance().getAction("ReviewQueue.ShowFileList"),
        )
    }

    /** Two End buttons, one confirming and one not, is a trap: muscle memory fires the wrong one. */
    fun testEndReviewAppearsExactlyOnceOnTheDiffToolbar() {
        val ends = ReviewSessionService.getInstance(project).diffActions
            .filter { it is EndReviewAction }
        assertEquals("End Review must appear once, as the confirming variant", 1, ends.size)
        assertTrue(ends.single() is DiffEndReviewAction)
    }

    /** Overriding actionPerformed here would stack a second dialog on the parent's own. */
    fun testResetAllIsNotDoubleConfirmed() {
        try {
            DiffResetAllAction::class.java.getDeclaredMethod("actionPerformed", AnActionEvent::class.java)
            fail("DiffResetAllAction must not override actionPerformed; ResetAllAction already confirms")
        } catch (expected: NoSuchMethodException) {
            // The marker interface is the whole of this subclass.
        }
    }

    fun testJumpToShowsAnotherFileInTheSamePass() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()
        assertEquals(keys[0], service.currentKey())

        assertTrue("a key in the pass must be accepted", service.jumpTo(keys[1]))

        assertEquals(keys[1], service.currentKey())
        assertEquals(listOf(keys[0], keys[1]), presenter.shown)
    }

    fun testJumpToAFileOutsideThePassIsRefusedAndChangesNothing() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()

        assertFalse(
            "refusing is what lets the caller fall back to a browsing diff",
            service.jumpTo(ReviewKey("/nowhere", "absent.txt")),
        )
        assertEquals(keys[0], service.currentKey())
        assertEquals(listOf(keys[0]), presenter.shown)
    }

    fun testJumpToWithNoSessionIsRefused() {
        val service = ReviewSessionService.getInstance(project)
        service.presenter = FakePresenter()
        assertFalse(service.jumpTo(ReviewKey("/repo", "a.txt")))
    }

    fun testJumpToReturnsFalseWhenTheJumpEndsThePass() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        service.presenter = FakePresenter()

        service.start()
        assertTrue("start must succeed", service.isActive)
        assertEquals(keys[0], service.currentKey())

        // Swap to a presenter that rejects everything.
        service.presenter = object : FakePresenter() {
            override fun canShow(key: ReviewKey) = false
        }

        assertFalse(
            "a jump that ends the pass must return false",
            service.jumpTo(keys[1]),
        )
        assertFalse("the pass must be closed", service.isActive)
        assertNull("no file is current", service.currentKey())
    }

    /** The counterpart to Previous File: forward one file, marks untouched. */
    fun testNextFileAdvancesWithoutMarkingAnything() {
        val queue = stagedQueueOfTwoFiles()
        val items = queue.snapshot().items
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()
        assertEquals(items[0].key, service.currentKey())

        service.nextFile()

        assertEquals(items[1].key, service.currentKey())
        assertFalse("Next File must not mark the file it leaves", queue.isReviewed(items[0]))
        assertFalse(queue.isReviewed(items[1]))
        assertEquals(listOf(items[0].key, items[1].key), presenter.shown)
    }

    /**
     * Next File at the last file must not end the pass. `markCurrent` ends it there deliberately —
     * the pass is finished once the last file is marked — but a plain forward move has nothing to do
     * and must leave the reviewer where they are.
     */
    fun testNextFileAtTheLastFileIsANoOpAndKeepsThePassRunning() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()
        service.nextFile()
        assertEquals(keys[1], service.currentKey())
        assertTrue(service.isAtLastFile)

        service.nextFile()

        assertTrue("Next File must never end the pass", service.isActive)
        assertEquals(keys[1], service.currentKey())
        assertEquals("a no-op must not re-show the file", 2, presenter.shown.size)
        assertEquals(0, presenter.closed)
    }

    /**
     * The case `nextFile`'s KDoc forbids and `showCurrent`'s settle branch used to cause: the file
     * ahead leaves the scope mid-pass, and a plain forward move ends the pass.
     *
     * Reachable with two in-pass button presses since `refresh-semantics` made the diff toolbar's
     * Refresh synchronous, which is what `resolveNow` stands in for here.
     */
    fun testNextFileStaysPutWhenNothingAheadIsStillInTheQueue() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()
        assertEquals(keys[0], service.currentKey())

        StagedQueueFixture.git(File(project.basePath!!), "rm", "--cached", keys[1].relPath)
        queue.progressRunner = { _, work -> work() }
        assertTrue(queue.resolveNow())
        assertEquals(
            "the file ahead must genuinely have left the queue, or this asserts nothing",
            listOf(keys[0]),
            queue.snapshot().items.map { it.key },
        )

        service.nextFile()

        assertTrue("a plain forward move must never end the pass", service.isActive)
        assertEquals(
            "with nothing live ahead the reviewer must stay on the file they are reading",
            keys[0],
            service.currentKey(),
        )
        assertEquals("staying put must not re-show the file", listOf(keys[0]), presenter.shown)
        assertEquals(0, presenter.closed)
    }

    /**
     * The milder form of the same defect: `back()` then a forward settle lands on the file already on
     * screen, so the tab is closed and reopened and the scroll position is lost.
     */
    fun testPreviousStaysPutWhenTheFileBehindHasLeftTheQueue() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()
        service.nextFile()
        assertEquals(keys[1], service.currentKey())

        StagedQueueFixture.git(File(project.basePath!!), "rm", "--cached", keys[0].relPath)
        queue.progressRunner = { _, work -> work() }
        assertTrue(queue.resolveNow())
        assertEquals(listOf(keys[1]), queue.snapshot().items.map { it.key })

        service.previous()

        assertTrue(service.isActive)
        assertEquals(keys[1], service.currentKey())
        assertEquals(
            "with nothing live behind, Previous File must not re-show the current file and lose the " +
                "scroll position",
            listOf(keys[0], keys[1]),
            presenter.shown,
        )
    }

    fun testIsAtLastFileTracksTheCursor() {
        val queue = stagedQueueOfTwoFiles()
        val service = ReviewSessionService.getInstance(project)
        service.presenter = FakePresenter()

        service.start()
        assertFalse("two files: the first is not the last", service.isAtLastFile)
        service.nextFile()
        assertTrue(service.isAtLastFile)
    }
}
