package dev.tweety.reviewqueue.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.notify.NotificationCapture
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.queue.StagedQueueFixture
import dev.tweety.reviewqueue.ui.RecordingDiffPresenter
import dev.tweety.reviewqueue.ui.RecordingToolWindowManager

/**
 * What the entry points say when a resolve leaves them nothing to act on.
 *
 * `items` is empty for three unrelated reasons — nothing unreviewed, every root failed, no git root at
 * all — and in a plugin whose job is stopping files going unreviewed, a confident "nothing unreviewed"
 * for either of the other two is the worst available output. Balloons are observed where they are
 * published (see [NotificationCapture]) rather than through a stub, so the assertions are about what
 * the user would have read.
 */
class EmptyResolveNoticeTest : HeavyPlatformTestCase() {

    private fun perform(action: AnAction) {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        action.actionPerformed(
            AnActionEvent.createEvent(action, context, null, "", ActionUiKind.NONE, null),
        )
    }

    private fun queueWithDirectProgress(): ReviewQueueService =
        ReviewQueueService.getInstance(project).also { it.progressRunner = { _, work -> work() } }

    /**
     * A scope whose every root fails resolves to an empty queue, which is *not* the same fact as
     * "nothing is unreviewed". Reproduces the panel's own repro: a base ref that does not exist.
     */
    fun testAFailedScopeIsNotReportedAsNothingUnreviewed() {
        StagedQueueFixture.stagedQueueOfTwoFiles(project)
        val queue = queueWithDirectProgress()
        queue.resolveNow(ReviewScope.BranchVsBase("no-such-ref-xyz"))
        assertTrue(
            "the scope must genuinely have failed to resolve, or this asserts nothing",
            queue.snapshot().errors.isNotEmpty(),
        )
        assertTrue(queue.snapshot().items.isEmpty())
        val recorded = NotificationCapture.start(project, testRootDisposable)

        perform(StartReviewAction())

        val texts = NotificationCapture.texts(recorded)
        assertTrue(
            "a failed scope must not be announced as nothing unreviewed: got $texts",
            texts.none { it.contains("Nothing unreviewed") },
        )
        assertTrue(
            "the failure is what the reviewer needs to hear: got $texts",
            texts.any { it.contains("could not be read") },
        )
    }

    /**
     * The worst form of the defect. `ScopeErrorNotifier` deduplicates by error map — required, because
     * `changeListUpdateDone` lands on every VCS event — so on a second press its balloon is silent and
     * the entry point's own message is the only thing the user sees.
     */
    fun testASecondPressStillReportsTheFailureAfterTheErrorBalloonHasDeduped() {
        StagedQueueFixture.stagedQueueOfTwoFiles(project)
        val queue = queueWithDirectProgress()
        queue.resolveNow(ReviewScope.BranchVsBase("no-such-ref-xyz"))
        perform(StartReviewAction())
        val recorded = NotificationCapture.start(project, testRootDisposable)

        perform(StartReviewAction())

        val texts = NotificationCapture.texts(recorded)
        assertTrue(
            "the deduped error balloon must not leave a false claim as the only message: got $texts",
            texts.none { it.contains("Nothing unreviewed") },
        )
        assertTrue(
            "the second press must still describe the real outcome: got $texts",
            texts.any { it.contains("could not be read") },
        )
    }

    /** With no repository at all there is nothing to be unreviewed *in*. */
    fun testNoGitRepositoryIsNotReportedAsNothingUnreviewed() {
        val queue = queueWithDirectProgress()
        assertTrue(queue.resolveNow())
        val recorded = NotificationCapture.start(project, testRootDisposable)

        perform(StartReviewAction())

        val texts = NotificationCapture.texts(recorded)
        assertTrue(
            "a project with no git root must not be told its scope is fully reviewed: got $texts",
            texts.none { it.contains("Nothing unreviewed") },
        )
        assertTrue(
            "it must be told what is actually the matter: got $texts",
            texts.any { it.contains("No git repository") },
        )
    }

    /**
     * The claim must still be made when it is true — otherwise the fix above could be "say nothing",
     * which the `An empty result says so` requirement forbids.
     */
    fun testNothingUnreviewedIsStillSaidWhenThatIsWhatHappened() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        queue.snapshot().items.forEach { assertTrue(queue.markReviewed(it.key)) }
        val recorded = NotificationCapture.start(project, testRootDisposable)

        perform(StartReviewAction())

        val texts = NotificationCapture.texts(recorded)
        assertTrue(
            "an all-reviewed scope must still be announced as such: got $texts",
            texts.any { it.contains("Nothing unreviewed") },
        )
        assertFalse(ReviewSessionService.getInstance(project).isActive)
    }

    /**
     * The two-predicate divergence: the action's guard passes, `start()` finds nothing it can render,
     * and the reviewer is told nothing at all while the Project panel flashes shut and open again.
     */
    fun testStartReviewReportsWhenNoUnreviewedFileCanBeDisplayed() {
        val projectWindow = RecordingToolWindowManager.install(project, testRootDisposable)
            .register("Project", visible = true)
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        ReviewSessionService.getInstance(project).presenter = RecordingDiffPresenter(canShow = false)
        val recorded = NotificationCapture.start(project, testRootDisposable)

        perform(StartReviewAction())

        val texts = NotificationCapture.texts(recorded)
        assertFalse("no pass can be running", ReviewSessionService.getInstance(project).isActive)
        assertTrue(
            "a pass that could not start must say so rather than flashing the panel silently: got $texts",
            texts.any { it.contains("No unreviewed file") && it.contains("could be displayed") },
        )
        assertEquals(
            "the Project panel must not be hidden for a pass that never began",
            0,
            projectWindow.hides,
        )
    }

    /** Show File List shares the predicate, so it must share the honesty about why it is empty. */
    fun testShowFileListDoesNotReportAFailedScopeAsNothingUnreviewed() {
        StagedQueueFixture.stagedQueueOfTwoFiles(project)
        val queue = queueWithDirectProgress()
        queue.resolveNow(ReviewScope.BranchVsBase("no-such-ref-xyz"))
        val recorded = NotificationCapture.start(project, testRootDisposable)

        perform(ShowFileListAction())

        val texts = NotificationCapture.texts(recorded)
        assertTrue(
            "the popup's empty path must not claim the scope is fully reviewed either: got $texts",
            texts.none { it.contains("Nothing unreviewed") },
        )
        assertTrue(texts.any { it.contains("could not be read") })
    }
}
