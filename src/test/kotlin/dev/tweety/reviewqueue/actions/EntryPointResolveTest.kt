package dev.tweety.reviewqueue.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.queue.StagedQueueFixture
import dev.tweety.reviewqueue.ui.RecordingDiffPresenter
import java.io.File

/**
 * Executes the three `actionPerformed` bodies KAN-5 rewired, against a real git-backed queue.
 *
 * These bodies had no coverage at all: four spec scenarios under `review-queue-resolution` rested on a
 * reviewer reading them, which is precisely how the earlier vacuous tests in this change survived. Each
 * test here names the mutation it fails against.
 *
 * The shape every case relies on: **staging a file behind the IDE's back leaves the queue stale.** The
 * fixture waits for a two-file queue, then a third file is staged with a `git` subprocess, which
 * produces no VFS or VCS event on its own — and nothing in these bodies dispatches the event queue. So
 * the queue holds two items until something resolves it *synchronously*, and "did this gesture resolve?"
 * becomes an assertion about item count rather than about a spy.
 */
class EntryPointResolveTest : HeavyPlatformTestCase() {

    /**
     * Thrown from a `progressRunner` that must never be reached. Deliberately an [Error] and a distinct
     * type: `resolveNow` catches `Exception`, so a `RuntimeException` would be swallowed and reported as
     * "no queue", and a bare `AssertionError` is indistinguishable from the one the popup itself raises
     * headless.
     */
    private class ResolvedWhenItMustNot(message: String) : Error(message)

    /**
     * Runs [action] and lets the file-list popup fail.
     *
     * `ReviewFileListPopup` ends in `showInBestPositionFor`, which asserts on a real component to
     * position against and has none in a headless test. That is the popup's own limitation — it is a
     * Gate C check — and every guard these tests are about runs before it. Only
     * [ResolvedWhenItMustNot] is allowed through, so a resolve that must not happen still fails the
     * test rather than being filed under "the popup threw".
     */
    private fun performToleratingThePopup(action: AnAction) {
        try {
            perform(action)
        } catch (e: ResolvedWhenItMustNot) {
            throw e
        } catch (e: Throwable) {
            if (e.stackTrace.none { it.className.endsWith("ReviewFileListPopup") }) throw e
        }
    }

    private fun perform(action: AnAction) {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        action.actionPerformed(
            AnActionEvent.createEvent(action, context, null, "", ActionUiKind.NONE, null),
        )
    }

    /** A two-file Staged queue whose synchronous resolves run inline instead of under a modal. */
    private fun staleQueueOfThreeStagedFiles(): ReviewQueueService {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        val repoDir = File(project.basePath!!)
        File(repoDir, "c.txt").writeText("c\n")
        StagedQueueFixture.git(repoDir, "add", "c.txt")
        assertEquals(
            "the queue must still be stale going in, or a missing resolve would be invisible",
            2,
            queue.snapshot().items.size,
        )
        return queue
    }

    /**
     * Spec scenario "Start Review resolves before starting".
     *
     * Fails with `if (!queue.resolveNow()) return` deleted from [StartReviewAction.actionPerformed]:
     * the pass then walks whatever the last background rebuild happened to leave behind — two files
     * here, and on a cold project an empty queue, which reports "Nothing unreviewed" for a scope that
     * has never been read.
     */
    fun testStartReviewResolvesTheScopeBeforeStartingThePass() {
        val queue = staleQueueOfThreeStagedFiles()
        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingDiffPresenter()

        perform(StartReviewAction())

        assertEquals("Start Review must resolve first", 3, queue.snapshot().items.size)
        assertTrue("and then start on the resolved queue", session.isActive)
    }

    /**
     * Spec scenario "Refresh resolves synchronously from both surfaces".
     *
     * Fails with `resolveNow()` reverted to `refresh()` in [RefreshQueueAction.actionPerformed]: the
     * rebuild is then asynchronous, so nothing has happened by the time the action returns. With no
     * tool window left to update, that is a Refresh with no observable effect whatsoever — the modal
     * progress is now the only feedback that the command did anything.
     */
    fun testRefreshResolvesSynchronouslyRatherThanScheduling() {
        val queue = staleQueueOfThreeStagedFiles()

        perform(RefreshQueueAction())

        assertEquals(
            "Refresh must have applied its rebuild by the time it returns",
            3,
            queue.snapshot().items.size,
        )
    }

    /**
     * Spec scenario "Show File List resolves when no pass is running", and its mid-pass counterpart.
     *
     * Fails with `!ReviewSessionService.getInstance(project).isActive &&` dropped from
     * [ShowFileListAction.actionPerformed]. Re-resolving mid-pass rebuilds the queue underneath the
     * session's **fixed** key list: every key the pass is still to walk is then compared against a
     * queue it was not built from, and a file whose content changed since the pass started drops out
     * of `liveKeys()` and is skipped without ever being shown.
     *
     * Only the resolve is asserted, not the popup — see [performToleratingThePopup].
     */
    fun testShowFileListDoesNotReResolveDuringAPass() {
        val queue = staleQueueOfThreeStagedFiles()
        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingDiffPresenter()
        assertTrue("a pass must be running, or the guard is not the branch taken", session.start())
        // Resolving now would be a defect, so make it a loud one rather than inferring it from a count.
        queue.progressRunner = { _, _ -> throw ResolvedWhenItMustNot("Show File List re-resolved mid-pass") }

        performToleratingThePopup(ShowFileListAction())

        assertEquals(
            "the session's queue must be left exactly as the pass was built on",
            2,
            queue.snapshot().items.size,
        )
    }

    /** The other half of the same guard: with no pass running, Show File List is what resolves. */
    fun testShowFileListResolvesWhenNoPassIsRunning() {
        val queue = staleQueueOfThreeStagedFiles()
        assertFalse(ReviewSessionService.getInstance(project).isActive)

        performToleratingThePopup(ShowFileListAction())

        assertEquals(
            "outside a pass nothing else has resolved anything, so this gesture must",
            3,
            queue.snapshot().items.size,
        )
    }
}
