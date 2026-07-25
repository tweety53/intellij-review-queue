package dev.tweety.reviewqueue.git

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking
import java.io.File

class GitReviewSourceIntegrationTest : HeavyPlatformTestCase() {

    private lateinit var repoDir: File

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
        File(repoDir, "kept.txt").writeText("original\n")
        git("add", "kept.txt")
        git("commit", "-m", "initial")

        File(repoDir, "kept.txt").writeText("staged change\n")
        File(repoDir, "added.txt").writeText("brand new\n")
        File(repoDir, "untracked.txt").writeText("not staged\n")
        git("add", "kept.txt", "added.txt")

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        vcsManager.setDirectoryMappings(
            listOf(com.intellij.openapi.vcs.VcsDirectoryMapping(repoDir.absolutePath, "Git"))
        )
        runBlocking { vcsManager.awaitInitialization() }
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()
    }

    fun testStagedScopeListsOnlyStagedFiles() {
        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        assertEquals(1, results.size)
        assertNull(results[0].error)

        val paths = results[0].changes.mapNotNull { it.afterRevision?.file?.name }.sorted()
        assertEquals(listOf("added.txt", "kept.txt"), paths)
    }

    fun testStagedScopeProducesHashableItems() {
        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        val items = results[0].changes.mapNotNull { ChangeMapper.toItem(results[0].rootPath, it) }
        assertEquals(2, items.size)
        items.forEach { assertTrue(it.contentHash.isNotBlank()) }
    }

    fun testUnknownRefInCommitRangeYieldsErrorNotException() {
        val results = GitReviewSource(project).resolve(ReviewScope.CommitRange("nope123", "HEAD"))
        assertEquals(1, results.size)
        assertNotNull(results[0].error)
        assertTrue(results[0].changes.isEmpty())
    }

    fun testBranchVsBaseDiffsAgainstExplicitBase() {
        // Discard the staged/untracked fixture state from setUp so the branch commit below
        // contains exactly one file, isolating the merge-base diff.
        git("reset", "--hard", "HEAD")
        val defaultBranch = git("rev-parse", "--abbrev-ref", "HEAD").trim()

        git("checkout", "-b", "feature")
        File(repoDir, "feature.txt").writeText("feature work\n")
        git("add", "feature.txt")
        git("commit", "-m", "feature commit")

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()
        val repository = GitRepositoryManager.getInstance(project).repositories.single()
        ApplicationManager.getApplication().executeOnPooledThread { repository.update() }.get()

        val results = GitReviewSource(project).resolve(ReviewScope.BranchVsBase(explicitBase = defaultBranch))
        assertEquals(1, results.size)
        assertNull(results[0].error)

        val paths = results[0].changes.mapNotNull { it.afterRevision?.file?.name }.sorted()
        assertEquals(listOf("feature.txt"), paths)
    }
}
