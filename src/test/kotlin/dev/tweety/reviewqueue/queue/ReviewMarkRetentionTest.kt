package dev.tweety.reviewqueue.queue

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.tweety.reviewqueue.model.ReviewScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test for the guarantee that cost two attempts to get right: **a rebuild whose queue
 * does not contain a file must never remove that file's stored mark.**
 *
 * This runs at the layer where both pruning bugs actually lived — `ReviewQueueService.refresh()`
 * and `applyRebuild`, not `ReviewStateService` in isolation. The original defect was
 * `applyRebuild` calling `state.prune(...)` unconditionally; the partial fix was a gate in the same
 * place whose grace lasted exactly one rebuild. Both shapes are exercised here:
 *
 * - marks are made in the Staged scope,
 * - the service is driven into Commit Range for a range touching entirely different files,
 * - and refreshed **again** in that scope, which is precisely where the gated version leaked,
 * - then returned to Staged, where every mark must still be present.
 *
 * Fails on the original bug, fails on the partially-fixed version, passes now. If pruning is ever
 * reintroduced at the `applyRebuild` call site, this is what fails.
 */
class ReviewMarkRetentionTest : HeavyPlatformTestCase() {

    private lateinit var repoDir: File

    /** Counts rebuilds actually published by the service, so waits can require a fresh one. */
    private val published = AtomicInteger()

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
        return output
    }

    override fun setUp() {
        super.setUp()
        repoDir = File(project.basePath!!)
        repoDir.mkdirs()
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")

        // HEAD~1..HEAD touches only ranged.txt, so the Commit Range queue is disjoint from the
        // Staged queue below. That disjointness is what makes an unwanted prune destructive.
        File(repoDir, "ranged.txt").writeText("v1\n")
        git("add", "ranged.txt")
        git("commit", "-m", "first")
        File(repoDir, "ranged.txt").writeText("v2\n")
        git("add", "ranged.txt")
        git("commit", "-m", "second")

        File(repoDir, "staged_a.txt").writeText("a\n")
        File(repoDir, "staged_b.txt").writeText("b\n")
        git("add", "staged_a.txt", "staged_b.txt")

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        vcsManager.setDirectoryMappings(listOf(VcsDirectoryMapping(repoDir.absolutePath, "Git")))
        runBlocking { vcsManager.awaitInitialization() }
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()
    }

    fun testMarksSurviveRebuildsWhoseQueueDoesNotContainThem() {
        val service = ReviewQueueService.getInstance(project)
        service.addListener({ published.incrementAndGet() }, testRootDisposable)

        // 1. Staged scope lists the two staged files.
        setScopeAndWait(service, ReviewScope.Staged) { it.items.size == 2 }
        assertEquals(
            listOf("staged_a.txt", "staged_b.txt"),
            service.snapshot().items.map { it.key.relPath }.sorted(),
        )

        // 2. Mark both reviewed through the service, exactly as the toolbar action does.
        service.markCurrentReviewed()
        service.markCurrentReviewed()
        assertEquals(2, service.snapshot().reviewedCount)

        // 3. A rebuild whose queue contains neither file.
        setScopeAndWait(service, ReviewScope.CommitRange("HEAD~1", "HEAD")) { snapshot ->
            snapshot.errors.isEmpty() && snapshot.items.map { it.key.relPath } == listOf("ranged.txt")
        }
        assertEquals(0, service.snapshot().reviewedCount)

        // 4. Refresh again *in that scope*. This is the rebuild the gated version pruned on: by now
        //    the "scope changed since the last rebuild" grace has been spent.
        refreshAndWait(service) { it.items.map { item -> item.key.relPath } == listOf("ranged.txt") }
        refreshAndWait(service) { it.items.map { item -> item.key.relPath } == listOf("ranged.txt") }

        // 5. Back to Staged: every mark must still be there and still read reviewed.
        setScopeAndWait(service, ReviewScope.Staged) { it.items.size == 2 }
        val snapshot = service.snapshot()
        assertEquals(
            listOf("staged_a.txt", "staged_b.txt"),
            snapshot.items.map { it.key.relPath }.sorted(),
        )
        assertEquals("stored marks were destroyed by a rebuild that did not contain them", 2, snapshot.reviewedCount)
        snapshot.items.forEach { assertTrue("${it.key.relPath} lost its mark", service.isReviewed(it)) }
    }

    private fun setScopeAndWait(
        service: ReviewQueueService,
        scope: ReviewScope,
        until: (QueueSnapshot) -> Boolean,
    ) = pumpUntil(service, until) { service.setScope(scope) }

    private fun refreshAndWait(service: ReviewQueueService, until: (QueueSnapshot) -> Boolean) =
        pumpUntil(service, until) { service.refresh() }

    /**
     * `refresh()` resolves on a background thread and applies on the EDT, so the test must let both
     * happen before asserting.
     *
     * Waiting on the snapshot alone is not enough and was a real bug in the first draft of this
     * test: when a refresh is issued in the scope that is already displayed, the expected snapshot
     * is *already* true, so the wait returned before the new rebuild had applied and step 4 below
     * silently tested nothing. So this waits for the service to actually publish a rebuild —
     * counted through a listener, which `applyRebuild` fires — **and** for the snapshot to satisfy
     * [until]. It dispatches pending EDT events each round and fails loudly on timeout rather than
     * sleeping a fixed interval and hoping.
     */
    private fun pumpUntil(
        service: ReviewQueueService,
        until: (QueueSnapshot) -> Boolean,
        trigger: () -> Unit,
    ) {
        val before = published.get()
        trigger()
        val deadlineNanos = System.nanoTime() + TIMEOUT_SECONDS * 1_000_000_000L
        while (System.nanoTime() < deadlineNanos) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (published.get() > before && until(service.snapshot())) return
        }
        val snapshot = service.snapshot()
        fail(
            "timed out after ${TIMEOUT_SECONDS}s waiting for a rebuild to be published; " +
                "publishes=${published.get()} (was $before), scope=${snapshot.scope}, " +
                "items=${snapshot.items.map { it.key.relPath }}, " +
                "reviewed=${snapshot.reviewedCount}, errors=${snapshot.errors}"
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
    }
}
