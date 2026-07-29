package dev.tweety.reviewqueue.git

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.queue.ReviewQueueService
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

    /**
     * `GitChangeUtils.getStagedChanges` is `git diff --cached`, which by construction diffs HEAD
     * against the index only — an ignored file that was never staged cannot appear in that diff no
     * matter what it's ignored *by*. This pins the guarantee at the boundary that actually matters
     * (the resolved [Change] list), not by re-deriving it from `.gitignore` parsing.
     */
    fun testStagedScopeExcludesIgnoredFiles() {
        File(repoDir, ".gitignore").writeText("ignored.txt\n")
        File(repoDir, "ignored.txt").writeText("must never be staged\n")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)

        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        assertNull(results[0].error)
        val paths = results[0].changes.mapNotNull { it.afterRevision?.file?.name }.sorted()
        assertEquals(listOf("added.txt", "kept.txt"), paths)
    }

    /**
     * `getStagedChanges` throws `VcsException` rather than swallowing it (see the KDoc on
     * `resolveStaged`), so a root that cannot be read for any reason still surfaces git's own message
     * through [RootResult.error] instead of a silent empty queue — the same contract
     * [testUnknownRefInCommitRangeYieldsErrorNotException] pins for `CommitRange`. `resolve()`'s
     * per-root try/catch, which is what lets *other* roots keep contributing when one fails, is
     * exercised at the `QueueAssembler` layer in `ReviewQueueServiceTest` (`assemble collects errors
     * per root and keeps other roots listed`) using synthetic [RootResult]s — exactly what this
     * per-root catch produces — so it is not re-proven here with a second real git root.
     */
    fun testStagedScopeReportsGitsOwnErrorWhenTheRootCannotBeRead() {
        // An unresolvable HEAD is not enough on its own — git diffs a missing HEAD against the empty
        // tree, exactly as it does before the first commit — so this corrupts the index file that
        // `git diff --cached` must actually read, which git refuses outright ("index file smaller
        // than expected"). Verified directly against real git before writing this.
        File(repoDir, ".git/index").writeText("not an index")

        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        assertEquals(1, results.size)
        assertNotNull("a broken HEAD must surface as an error, not an empty queue", results[0].error)
        assertTrue(results[0].changes.isEmpty())
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

    /**
     * `GitChangeUtils.getDiff(project, root, from, to, files)` — the throwing overload the
     * `CommitRange` branch already used before this task, kept for the same swallowing-overload
     * reason recorded at `GitReviewSource.kt:53-55` — turns out to build its git command with `-M`
     * unconditionally (confirmed by disassembling the platform's `vcs-git.jar`: the public 5-arg
     * `getDiff` always passes `detectRenames = true` down to the `git diff` invocation). So this
     * scope already detected renames before task 5 touched it; this test pins that down instead of
     * changing a call that does not need to change.
     */
    fun testCommitRangeDetectsARenameAsOneEntry() {
        git("reset", "--hard", "HEAD")
        val start = git("rev-parse", "HEAD").trim()
        git("mv", "kept.txt", "renamed.txt")
        File(repoDir, "renamed.txt").writeText("original\nedit\n")
        git("add", "renamed.txt")
        git("commit", "-m", "rename and edit")

        val results = GitReviewSource(project).resolve(ReviewScope.CommitRange(start, "HEAD"))
        assertEquals(1, results.size)
        assertNull(results[0].error)

        val change = results[0].changes.single()
        assertEquals("renamed.txt", change.afterRevision?.file?.name)
        assertEquals(
            "a rename must be one entry whose before-side still points at the old path",
            "kept.txt",
            change.beforeRevision?.file?.name,
        )
    }

    /**
     * `getThreeDotDiffOrThrow` disassembles to the same 7-arg `GitChangeUtils.getDiff` as the
     * `CommitRange` branch, also with `detectRenames = true` hardcoded — so `resolveBranchVsBase`
     * needed no call-site change either. Pinned the same way as the `CommitRange` case above.
     */
    fun testBranchVsBaseDetectsARenameAsOneEntry() {
        git("reset", "--hard", "HEAD")
        val defaultBranch = git("rev-parse", "--abbrev-ref", "HEAD").trim()

        git("checkout", "-b", "feature")
        git("mv", "kept.txt", "renamed.txt")
        File(repoDir, "renamed.txt").writeText("original\nedit\n")
        git("add", "renamed.txt")
        git("commit", "-m", "rename and edit")

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()
        val repository = GitRepositoryManager.getInstance(project).repositories.single()
        ApplicationManager.getApplication().executeOnPooledThread { repository.update() }.get()

        val results = GitReviewSource(project).resolve(ReviewScope.BranchVsBase(explicitBase = defaultBranch))
        assertEquals(1, results.size)
        assertNull(results[0].error)

        val change = results[0].changes.single()
        assertEquals("renamed.txt", change.afterRevision?.file?.name)
        assertEquals(
            "a rename must be one entry whose before-side still points at the old path",
            "kept.txt",
            change.beforeRevision?.file?.name,
        )
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
    /**
     * Without rename detection this resolves to three entries — a delete of kept.txt, an add of
     * renamed.txt, and added.txt — which is exactly the limitation KAN-6 removes.
     *
     * Rename detection here is content-similarity based (`git diff --cached -M`), which is git's own
     * mechanism, not something this plugin controls: a `git mv` on top of a *total* rewrite has zero
     * byte overlap between the old and new blob, and git will not pair a zero-similarity delete/add as
     * a rename at **any** `-M` threshold from 1% to 100% — verified directly against git 2.50 with the
     * brief's literal fixture bytes (`"original\n"` to `"staged change\n"`), independent of git4idea or
     * this plugin. A short append (`"original\nedit\n"`, four bytes over the original) keeps enough of
     * the file that git's own diffcore-rename measures 64% similarity and pairs it — comfortably over
     * the 50% default — which is what a real rename-plus-edit looks like in practice; a full rewrite
     * this small just isn't something git itself can key to one entry, in this plugin or any other.
     */
    fun testAStagedRenameIsOneEntryKeyedToTheNewPath() {
        File(repoDir, "kept.txt").writeText("original\nedit\n")
        git("add", "kept.txt")
        git("mv", "kept.txt", "renamed.txt")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)

        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        assertNull(results[0].error)
        val paths = results[0].changes
            .mapNotNull { ChangeMapper.toItem(results[0].rootPath, it) }
            .map { it.key.relPath }
            .sorted()

        assertEquals(
            "a rename must be one entry keyed to the new path, not a delete plus an add",
            listOf("added.txt", "renamed.txt"),
            paths,
        )
    }

    /**
     * The property content-addressed marks exist to provide — a mark stays only with the exact bytes
     * it was made against — exercised on a rename: renaming keys the queue item to the new path (this
     * task's whole point), and then rewriting that renamed file's content must drop the mark exactly
     * as [testStagedContentHashTracksStagedContent] shows for a plain, unrenamed edit.
     */
    fun testMarkOnARenamedFileIsDroppedByAFollowingContentChange() {
        val service = ReviewQueueService.getInstance(project)
        val published = java.util.concurrent.atomic.AtomicInteger()
        service.addListener({ published.incrementAndGet() }, testRootDisposable)

        waitForRebuild(published, { service.setScope(ReviewScope.Staged) }) { service.snapshot().items.size == 2 }

        File(repoDir, "kept.txt").writeText("original\nedit\n")
        git("add", "kept.txt")
        git("mv", "kept.txt", "renamed.txt")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        waitForRebuild(published, service::refresh) {
            service.snapshot().items.map { it.key.relPath }.sorted() == listOf("added.txt", "renamed.txt")
        }

        val renamedKey = service.snapshot().items.single { it.key.relPath == "renamed.txt" }.key
        service.markReviewed(renamedKey)
        assertTrue(
            "the mark must be recorded before the content change that is meant to drop it",
            service.isReviewed(service.snapshot().items.single { it.key.relPath == "renamed.txt" }),
        )

        File(repoDir, "renamed.txt").writeText("original\nedit\nagain\n")
        git("add", "renamed.txt")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        waitForRebuild(published, service::refresh) {
            service.snapshot().items.any { it.key.relPath == "renamed.txt" }
        }

        val afterEdit = service.snapshot().items.single { it.key.relPath == "renamed.txt" }
        assertFalse(
            "rewriting a renamed file's content must drop its mark, exactly as it would unrenamed",
            service.isReviewed(afterEdit),
        )
    }

    /**
     * Waiting on the snapshot alone is not enough when the awaited condition can already be true
     * before the rebuild this call is meant to observe — as it is here, where `renamed.txt` stays in
     * the queue across the content-change refresh. So this waits for `applyRebuild` to actually fire
     * (counted through [published]) **and** for the snapshot to satisfy [condition], the same fix
     * `ReviewMarkRetentionTest.pumpUntil` applies for the same reason.
     */
    private fun waitForRebuild(
        published: java.util.concurrent.atomic.AtomicInteger,
        trigger: () -> Unit = {},
        condition: () -> Boolean,
    ) {
        val service = ReviewQueueService.getInstance(project)
        val before = published.get()
        trigger()
        val deadlineNanos = System.nanoTime() + 60L * 1_000_000_000L
        while (System.nanoTime() < deadlineNanos) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (published.get() > before && condition()) return
        }
        fail(
            "timed out waiting for a rebuild; items=${service.snapshot().items.map { it.key.relPath }}",
        )
    }

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
