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

    /**
     * The product's central promise: a mark is addressed to content, so re-staging different bytes
     * must return the file to the unreviewed set. Verified without any UI.
     */
    fun testStagedContentHashTracksStagedContent() {
        val first = stagedHashOf("kept.txt")
        assertFalse(
            "content-addressing is inert: no bytes could be read for an ordinary staged file",
            first.startsWith("unresolved:"),
        )
        assertEquals("re-resolving unchanged content must give the same hash", first, stagedHashOf("kept.txt"))

        File(repoDir, "kept.txt").writeText("second staged change\n")
        git("add", "kept.txt")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)

        val second = stagedHashOf("kept.txt")
        assertFalse("content-addressing is inert after a rewrite", second.startsWith("unresolved:"))
        assertFalse("rewriting and re-staging the file must change its hash", first == second)
    }

    fun testStagedDeletionHashesTheDeletedBytes() {
        // -f because setUp leaves kept.txt with staged changes; the result is still a staged deletion.
        git("rm", "-f", "kept.txt")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)

        val hash = stagedHashOf("kept.txt")
        assertFalse("a staged deletion must hash the bytes that were deleted", hash.startsWith("unresolved:"))
        // "original\n" is what HEAD holds for kept.txt, so the deletion hashes exactly those bytes.
        assertEquals(dev.tweety.reviewqueue.core.ContentHasher.hash("original\n".toByteArray()), hash)
    }

    private fun stagedHashOf(relPath: String): String {
        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        assertEquals(1, results.size)
        assertNull(results[0].error)
        val items = results[0].changes.mapNotNull { ChangeMapper.toItem(results[0].rootPath, it) }
        return items.single { it.key.relPath == relPath }.contentHash
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

    /**
     * A branch name is untrusted input, and this asserts on the **file system**, not on an error string.
     *
     * Branch names may begin with `-` — `git check-ref-format 'refs/heads/--output=.git/index'` exits 0 —
     * and `git clone` checks the remote's HEAD branch out for you. git4idea resolves the current branch
     * with `git rev-list --timestamp --max-count=1 <ref>`, placing the ref *before* `endOptions()`, so
     * git's parse-options reads it as an option and `--output=<file>` opens that file for writing before
     * rejecting the missing commit argument. Cloning a hostile repository and pressing Start Review in
     * Branch vs Base was therefore enough to zero `.git/index`, destroying the staged state, with the
     * reviewer typing nothing at all. Reproduced end to end against real git: 137 bytes → 0.
     *
     * An earlier fix validated only the ref the *user* typed. `base` (which may come from the tracked
     * branch) and `head` (a branch name straight from the repository) were left unchecked precisely
     * because they are not user input — which is backwards, since the repository is the untrusted party.
     *
     * Asserting `error != null` alone would be **vacuous**: git fails either way, *after* truncating. The
     * index size is the only assertion that distinguishes "rejected" from "executed, then reported".
     */
    fun testAHostileBranchNameCannotMakeGitTruncateAFileInTheRepository() {
        git("reset", "--hard", "HEAD")
        val safeBase = git("rev-parse", "--abbrev-ref", "HEAD").trim()
        // `git checkout -b` refuses this name, but `update-ref` + `symbolic-ref` does not — and neither
        // does `git clone`, which checks out whatever the remote's HEAD points at. That is the real
        // delivery route: the reviewer never types or creates the branch.
        git("update-ref", "refs/heads/--output=.git/index", "HEAD")
        git("symbolic-ref", "HEAD", "refs/heads/--output=.git/index")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()
        val repository = GitRepositoryManager.getInstance(project).repositories.single()
        ApplicationManager.getApplication().executeOnPooledThread { repository.update() }.get()
        assertEquals(
            "the fixture must actually be on the hostile branch, or this asserts nothing",
            "--output=.git/index",
            repository.currentBranch?.name,
        )
        val index = File(repoDir, ".git/index")
        val sizeBefore = index.length()
        assertTrue("the index must be non-empty before the resolve", sizeBefore > 0L)

        // An explicit, resolvable base on purpose. With no base given, resolution falls back to
        // `origin/HEAD`, which this fixture has no remote for — so the resolve dies on the base before
        // `head` ever reaches `git rev-list`, and the index survives for a reason that has nothing to do
        // with the guard. Supplying a valid base is what makes the hostile `head` actually reachable, and
        // therefore what makes this test able to observe the write.
        val results = GitReviewSource(project).resolve(ReviewScope.BranchVsBase(explicitBase = safeBase))

        assertEquals(
            "the repository index must be untouched — a review must never write to the repository",
            sizeBefore,
            index.length(),
        )
        val error = results.single().error
        assertNotNull("the root must report why it could not be read", error)
        assertTrue(
            "the message must name the option hazard, not git's own failure: got \"$error\"",
            error!!.contains("option"),
        )
    }

    /**
     * The boundary re-check exists so that a `ReviewScope` built by **any** caller is safe, not only one
     * built by the input dialogs — the dialogs are one caller of a public data class.
     *
     * **Both** range refs are exercised. Asserting only `from` left the `to` guard deletable with the
     * whole suite green, which is the same could-not-fail shape this branch has repeatedly caught: the
     * validator was covered, the *call site* was not.
     */
    fun testBothCommitRangeRefsAreRejectedAtTheServiceBoundaryNotOnlyInTheDialog() {
        val index = File(repoDir, ".git/index")
        val sizeBefore = index.length()
        val source = GitReviewSource(project)

        listOf(
            ReviewScope.CommitRange("--output=.git/index", "HEAD"),
            ReviewScope.CommitRange("HEAD", "--output=.git/index"),
        ).forEach { scope ->
            val results = source.resolve(scope)

            assertEquals("the index must be untouched for $scope", sizeBefore, index.length())
            val error = results.single().error
            assertNotNull("$scope must be rejected", error)
            assertTrue("$scope: got \"$error\"", error!!.contains("option"))
        }
    }

}
