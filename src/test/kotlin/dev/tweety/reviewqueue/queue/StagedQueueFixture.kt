package dev.tweety.reviewqueue.queue

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import dev.tweety.reviewqueue.model.ReviewScope
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Builds a real two-file Staged queue in the test project's own git repo.
 *
 * Shared because a scope-switch test is worthless without a running pass, and a pass needs a real
 * queue: with no git root `ReviewSession.start` always returns null, so every assertion about
 * switching would hold vacuously.
 */
object StagedQueueFixture {

    /** Initialises a repo with `a.txt` and `b.txt` staged, and waits for the queue to publish both. */
    fun stagedQueueOfTwoFiles(project: Project): ReviewQueueService {
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
        throw AssertionError(
            "timed out waiting for a two-file Staged queue; got " +
                queue.snapshot().items.map { it.key.relPath },
        )
    }

    fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
    }
}
