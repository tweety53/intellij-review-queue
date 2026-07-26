package dev.tweety.reviewqueue.queue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.notify.NotificationCapture
import java.util.concurrent.ExecutionException

/**
 * A test project has no git roots, so every resolve here assembles an empty queue. That makes
 * `items` useless as evidence — it is `emptyList()` whether the apply ran or not — so these tests
 * assert against `scope`, which is the one piece of state a resolve is observably responsible for.
 */
class ResolveNowTest : HeavyPlatformTestCase() {

    fun testResolveNowAppliesThroughTheInjectedRunner() {
        val service = ReviewQueueService.getInstance(project)
        var titleSeen: String? = null
        service.progressRunner = { title, work -> titleSeen = title; work() }

        val applied = service.resolveNow(ReviewScope.CommitRange("HEAD~1", "HEAD"))

        assertTrue("resolveNow must report that it applied a rebuild", applied)
        assertEquals("Resolving Review Scope", titleSeen)
        assertEquals(
            "an applied resolve must record the scope it resolved",
            ReviewScope.CommitRange("HEAD~1", "HEAD"),
            service.snapshot().scope,
        )
    }

    /**
     * The scope must not be recorded unless the queue was actually rebuilt for it.
     *
     * Uses a non-default scope on purpose: asserting against `Staged` — which is already the default —
     * cannot distinguish "left alone" from "assigned", and so cannot see this bug at all. Recording it
     * on a cancelled resolve is not cosmetic: `snapshot()` would name a scope that does not describe
     * `items`, and the next `changeListUpdateDone` would resolve and apply the scope the user had just
     * declined, switching the queue underneath a running pass seconds after they said no.
     */
    fun testACancelledResolveRecordsNeitherQueueNorScope() {
        val service = ReviewQueueService.getInstance(project)
        val scopeBefore = service.snapshot().scope
        val itemsBefore = service.snapshot().items
        // null models the user dismissing the progress dialog.
        service.progressRunner = { _, _ -> null }

        val applied = service.resolveNow(ReviewScope.CommitRange("HEAD~3", "HEAD"))

        assertFalse("a cancelled resolve must not claim to have applied", applied)
        assertEquals(
            "a cancelled resolve must leave the scope exactly as it was",
            scopeBefore,
            service.snapshot().scope,
        )
        assertEquals("a cancelled resolve must not touch the queue", itemsBefore, service.snapshot().items)
    }

    /** Same contract as cancellation, reached the other way: a runner that throws records nothing. */
    fun testAFailedResolveRecordsNoScope() {
        val service = ReviewQueueService.getInstance(project)
        val scopeBefore = service.snapshot().scope
        service.progressRunner = { _, _ -> throw IllegalStateException("git blew up") }

        val applied = service.resolveNow(ReviewScope.BranchVsBase("origin/main"))

        assertFalse("a failed resolve must not claim to have applied", applied)
        assertEquals(
            "a failed resolve must leave the scope exactly as it was",
            scopeBefore,
            service.snapshot().scope,
        )
    }

    /**
     * A cancelled resolve must not throw away a refresh that was already under way.
     *
     * The generation used to be bumped at entry, so a cancelled `resolveNow` invalidated an in-flight
     * [ReviewQueueService.refresh] result — and nothing reschedules one. Mid-pass that is not
     * self-correcting: five gestures read the queue without resolving it, so a file a fix round had
     * just rewritten would keep its mark against stale content and the pass would skip it.
     *
     * The listener is the only observable evidence available here: a test project has no git root, so
     * the applied queue is empty either way, and `fireChanged` is what distinguishes "the rebuild
     * landed" from "the rebuild was discarded". The `refresh()` result cannot have been applied before
     * the cancelled resolve runs, because `applyRebuild` only ever reaches it through `invokeLater`
     * and this method holds the EDT throughout.
     */
    fun testACancelledResolveDoesNotDiscardAnInFlightRefresh() {
        val service = ReviewQueueService.getInstance(project)
        var applies = 0
        service.addListener({ applies++ }, testRootDisposable)

        service.refresh()
        service.progressRunner = { _, _ -> null }
        assertFalse("the resolve must model a cancelled progress", service.resolveNow())

        val deadlineNanos = System.nanoTime() + 10L * 1_000_000_000L
        while (applies == 0 && System.nanoTime() < deadlineNanos) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }

        assertTrue(
            "a cancelled resolve must leave the in-flight refresh able to apply; the queue would " +
                "otherwise stay stale with nothing to reschedule it",
            applies > 0,
        )
    }

    /**
     * Precondition 2 must fail fast rather than deadlock.
     *
     * `ApplicationImpl.runProcessWithProgressSynchronously` short-circuits to running the body inline
     * on the calling thread when `isDispatchThread() && isWriteAccessAllowed()`, so
     * `awaitWithCheckCanceled` would block the EDT under the write lock with no dialog and no cancel
     * button, and no platform assertion would fire because no read action is involved. A hang is not a
     * diagnosable failure, which is why the precondition is checked in code rather than only in prose.
     *
     * The runner is counted, not exercised: it must never be reached, and a runner that returned
     * something would make the RED case hang instead of failing.
     */
    fun testResolveNowRefusesToRunWhileHoldingWriteAccess() {
        val service = ReviewQueueService.getInstance(project)
        val scopeBefore = service.snapshot().scope
        var runnerCalls = 0
        service.progressRunner = { _, _ -> runnerCalls++; null }

        val failure = try {
            WriteAction.run<RuntimeException> { service.resolveNow(ReviewScope.CommitRange("HEAD~4", "HEAD")) }
            null
        } catch (expected: IllegalStateException) {
            expected
        }

        assertNotNull("resolveNow must refuse write access instead of deadlocking the EDT", failure)
        assertEquals("the refusal must come before any progress is raised", 0, runnerCalls)
        assertEquals(scopeBefore, service.snapshot().scope)
    }

    /**
     * Precondition 1 must fail fast rather than race the state the EDT owns.
     *
     * `HeavyPlatformTestCase` runs test bodies *on* the EDT, so the violation has to be staged from a
     * pooled thread and the failure unwrapped from `ExecutionException` — calling `resolveNow()`
     * directly from here cannot break the precondition at all.
     *
     * The message assertion is what keeps this test on precondition 1. Precondition 2's plain `check`
     * also produces an `IllegalStateException`, so a test that accepted any failure would still pass
     * with `assertEventDispatchThread` deleted, on the strength of a different guard entirely.
     * `ThreadingAssertions`' EDT message never contains the lowercase `write access` that precondition
     * 2's message does, which is what makes the two distinguishable by text rather than by type.
     *
     * What `.get()` surfaces is a `TestLoggerFactory.TestLoggerAssertionError`, **not** the platform's
     * own `RuntimeExceptionWithAttachments`: the pooled-thread wrapper catches `Throwable` and routes
     * it through `LOG.error`, and the default `LoggedErrorProcessor` rethrows from there carrying the
     * original message. Two things worth knowing before debugging this test — a *passing* run
     * deliberately prints a platform `ERROR: Access is allowed from Event Dispatch Thread (EDT) only…`
     * to stderr, and the test leans on `LoggedErrorProcessor` still rethrowing and on EDT checks not
     * being disabled. Both would make it fail loudly rather than pass vacuously, so the risk direction
     * is safe; the mechanism simply is not the obvious one.
     */
    fun testResolveNowRefusesToRunOffTheEventDispatchThread() {
        val service = ReviewQueueService.getInstance(project)
        var runnerCalls = 0
        service.progressRunner = { _, _ -> runnerCalls++; null }

        val failure = try {
            ApplicationManager.getApplication().executeOnPooledThread {
                service.resolveNow(ReviewScope.CommitRange("HEAD~5", "HEAD"))
            }.get()
            null
        } catch (e: ExecutionException) {
            e.cause
        }

        val message = failure?.message.orEmpty()
        assertNotNull("resolveNow must refuse a call made off the EDT", failure)
        assertFalse(
            "this must be the threading refusal, not the write-access one: $message",
            message.contains("write access"),
        )
        assertTrue(
            "the refusal must name the EDT precondition: $message",
            message.contains("Event Dispatch Thread"),
        )
        assertEquals("the refusal must come before any progress is raised", 0, runnerCalls)
    }

    /**
     * A resolve that races project close produces no queue, and that is an expected outcome rather
     * than a failure worth logging.
     *
     * `dispose()` calls `refreshExecutor.shutdownNow()`, so `submit` rejects and `resolveNow` turns
     * the `RejectedExecutionException` into `false`. Two things make this test able to see that arm:
     *
     *  - the runner is replaced but the **executor is not**, so `work()` really does reach `submit`.
     *    A stubbed runner that never calls its work lambda bypasses the arm and passes vacuously.
     *  - the assertion is about the *absence of a logged warning*, not about the `false`. Deleting
     *    the arm leaves the generic `catch (e: Exception)` to return `false` too — the only
     *    observable difference is that the generic arm calls `thisLogger().warn`.
     *
     * The service is disposed, not the project: `project.isDisposed` would short-circuit `resolveNow`
     * before the executor is reached, and `HeavyPlatformTestCase` owns the project's lifecycle.
     */
    fun testAResolveAfterDisposalIsNotReportedAsAFailure() {
        val service = ReviewQueueService.getInstance(project)
        service.progressRunner = { _, work -> work() }
        service.dispose()

        val warnings = mutableListOf<String>()
        val recorder = object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return false
            }
        }

        var applied: Boolean? = null
        LoggedErrorProcessor.executeWith<RuntimeException>(recorder) {
            applied = service.resolveNow(ReviewScope.CommitRange("HEAD~6", "HEAD"))
        }

        assertEquals("a resolve with no executor left must report that it applied nothing", false, applied)
        assertEquals(
            "a resolve racing disposal is an expected outcome, not a failure to log: got $warnings",
            emptyList<String>(),
            warnings,
        )

        // Positive control for that negative assertion: the same recorder, on the same path, must see
        // a warning when a resolve genuinely does fail. Without it "no warnings" would be satisfied
        // just as well by nothing ever reaching `processWarn`.
        service.progressRunner = { _, _ -> throw IllegalStateException("git blew up") }
        LoggedErrorProcessor.executeWith<RuntimeException>(recorder) {
            assertFalse(service.resolveNow())
        }
        assertTrue(
            "the recorder must be able to observe a real resolve failure: got $warnings",
            warnings.any { it.contains("Review Queue resolve failed") },
        )
    }

    /**
     * Cancelling is the user's own decision, so it must produce no balloon of any kind — neither an
     * error nor an "empty result" notice explaining a queue they chose not to build.
     *
     * A real two-file queue is needed for the positive control at the end: with no git root every
     * snapshot is empty, and `CompletionNotifier` never speaks for an empty queue, so the capture
     * could not be shown to be listening. Without that control the silence assertion would pass
     * equally well if `NotificationCapture` were subscribed to the wrong bus.
     */
    fun testACancelledResolvePublishesNoNotification() {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        val recorded = NotificationCapture.start(project, testRootDisposable)
        queue.progressRunner = { _, _ -> null }

        assertFalse(
            "the resolve must model a cancelled progress",
            queue.resolveNow(ReviewScope.CommitRange("HEAD~3", "HEAD")),
        )

        assertEquals(
            "a cancelled resolve must say nothing at all",
            emptyList<String>(),
            NotificationCapture.texts(recorded),
        )

        queue.snapshot().items.forEach { assertTrue(queue.markReviewed(it.key)) }
        assertTrue(
            "the capture must be able to see a balloon on this bus, or the silence above proves " +
                "nothing: got ${NotificationCapture.texts(recorded)}",
            NotificationCapture.texts(recorded).any { it.contains("All 2 files reviewed") },
        )
    }

    fun testResolveNowWithNoArgumentKeepsTheCurrentScope() {
        val service = ReviewQueueService.getInstance(project)
        service.progressRunner = { _, work -> work() }
        service.resolveNow(ReviewScope.CommitRange("HEAD~2", "HEAD"))

        val applied = service.resolveNow()

        assertTrue(applied)
        assertEquals(
            "resolveNow() with no argument re-resolves the scope already in effect",
            ReviewScope.CommitRange("HEAD~2", "HEAD"),
            service.snapshot().scope,
        )
    }
}
