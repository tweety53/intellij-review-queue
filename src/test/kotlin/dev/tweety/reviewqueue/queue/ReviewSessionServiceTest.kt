package dev.tweety.reviewqueue.queue

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.ui.ReviewDiffPresenter
import kotlinx.coroutines.runBlocking
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

    /** Builds a two-file Staged queue in this project's own git repo and waits for it to publish. */
    private fun stagedQueueOfTwoFiles(): ReviewQueueService {
        val repoDir = File(project.basePath!!)
        repoDir.mkdirs()
        git(repoDir, "init")
        git(repoDir, "config", "user.email", "test@example.com")
        git(repoDir, "config", "user.name", "Test")
        File(repoDir, "a.txt").writeText("a\n")
        File(repoDir, "b.txt").writeText("b\n")
        git(repoDir, "add", "a.txt", "b.txt")

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        vcsManager.setDirectoryMappings(listOf(VcsDirectoryMapping(repoDir.absolutePath, "Git")))
        runBlocking { vcsManager.awaitInitialization() }
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()

        val queue = ReviewQueueService.getInstance(project)
        queue.setScope(ReviewScope.Staged)
        val deadlineNanos = System.nanoTime() + 60L * 1_000_000_000L
        while (System.nanoTime() < deadlineNanos) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (queue.snapshot().items.size == 2) return queue
        }
        fail("timed out waiting for a two-file Staged queue; got ${queue.snapshot().items.map { it.key.relPath }}")
        error("unreachable")
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
    }
}
