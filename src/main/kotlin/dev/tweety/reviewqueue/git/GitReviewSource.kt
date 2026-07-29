package dev.tweety.reviewqueue.git

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import dev.tweety.reviewqueue.core.BaseRefResolver
import dev.tweety.reviewqueue.core.ContentHasher
import dev.tweety.reviewqueue.model.CommitRangeValidator
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import git4idea.changes.GitChangeUtils
import git4idea.index.ContentVersion
import git4idea.index.GitFileStatus
import git4idea.index.createChange
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/** Per-root outcome. A failing root reports [error] instead of taking the whole queue down. */
data class RootResult(val rootPath: String, val changes: List<Change>, val error: String?)

/**
 * Translates a [ReviewScope] into platform [Change] objects, one result per git root. Read-only:
 * every git command issued here is a query.
 */
class GitReviewSource(private val project: Project) {

    fun rootOrder(): List<String> = repositories().map { it.root.path }

    fun resolve(scope: ReviewScope): List<RootResult> = repositories().map { repository ->
        val rootPath = repository.root.path
        try {
            RootResult(rootPath, resolveInRoot(repository, scope), null)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: VcsException) {
            RootResult(rootPath, emptyList(), e.message ?: "Failed to read changes")
        } catch (e: Exception) {
            RootResult(rootPath, emptyList(), e.message ?: "Failed to read changes")
        }
    }

    private fun repositories(): List<GitRepository> =
        GitRepositoryManager.getInstance(project).repositories

    private fun resolveInRoot(repository: GitRepository, scope: ReviewScope): List<Change> =
        when (scope) {
            is ReviewScope.Staged -> resolveStaged(repository)
            is ReviewScope.BranchVsBase -> resolveBranchVsBase(repository, scope.explicitBase)
            // The (repository, from, to, detectRenames) overload swallows the VcsException and
            // returns null, which would degrade git's real message to a generic one. This overload
            // throws, so RootResult.error carries what git actually said.
            is ReviewScope.CommitRange -> {
                rejectUnsafeRef(scope.from, "The starting ref")
                rejectUnsafeRef(scope.to, "The ending ref")
                GitChangeUtils.getDiff(project, repository.root, scope.from, scope.to, null).toList()
            }
        }

    /**
     * Refs are validated here as well as in the input dialogs, because this is the boundary that
     * actually matters: a `ReviewScope` can be constructed by any caller, and the dialog is only one of
     * them. A ref beginning with `-` is read by git as an option, and
     * `git rev-list --output=<file>` truncates that file before failing — a repository write from a
     * plugin that must only ever query. See `CommitRangeValidator` for the full mechanism.
     *
     * A `VcsException` here surfaces as this root's `error`, so the user gets the reason in the failed-
     * roots balloon rather than a silent empty queue.
     */
    private fun rejectUnsafeRef(ref: String, label: String) {
        CommitRangeValidator.validateRef(ref, label)?.let { throw VcsException(it) }
    }

    /**
     * `git4idea.index.getStatus` reads `git status --porcelain=v2`, which has no similarity pass: no
     * combination of its three trailing booleans turns on rename detection, so a `git mv` staged on
     * top of a modification used to resolve as a delete of the old path plus an add of the new one —
     * two queue entries for one edit, and a mark on the old path that could never carry over.
     *
     * `GitChangeUtils.getStagedChanges` is `git diff --cached -M`, which does have a similarity pass,
     * and — unlike the `(repository, from, to, detectRenames)` overload the `CommitRange` branch's
     * comment above warns about — it **throws** `VcsException` rather than swallowing it, so a
     * failing root still reports git's own message through [RootResult.error].
     *
     * It returns `GitChangeUtils.GitDiffChange`, not a platform [Change], so each entry is rebuilt
     * into one here through the same [GitFileStatus] the old `getStatus`-based path already fed to
     * [createChange]. Only the `index` character constructed here is synthetic, and `createChange`
     * (via `git4idea.index.GitFileStatusKt`) is what actually reads it: `'A'` (added) has no HEAD
     * side, `'D'` (deleted) has no STAGED side, and `'R'` keeps both — with the STAGED side reading
     * `path` (the new path) and the HEAD side reading `origPath` (the old one). A same-path
     * modification is given `'R'` too, with `origPath` equal to `path`, which is inert: both sides
     * land on the one path either way. It is exactly that `'R'`-plus-`origPath` combination that
     * points the HEAD-side revision at the old path while the STAGED-side revision stays on the new
     * one, keying the rename to one entry under the new path.
     *
     * `getStagedChanges` is `--cached` by construction — a working-tree diff against nothing — so it
     * cannot surface untracked or ignored files; they never appear in its output, unlike the old
     * porcelain-based path where an explicit filter was needed to keep them out.
     */
    private fun resolveStaged(repository: GitRepository): List<Change> =
        GitChangeUtils.getStagedChanges(project, repository.root).mapNotNull { diffChange ->
            val before = diffChange.beforePath
            val after = diffChange.afterPath
            val path = after ?: before ?: throw VcsException("a diff entry must have at least one side")
            val (index, origPath) = when {
                before == null -> 'A' to null
                after == null -> 'D' to null
                // A plain same-path modification also has both sides non-null, so it lands here too,
                // with origPath == path. That is inert today — createChange's 'R' handling only ever
                // reads origPath for the HEAD side and path for the STAGED side, and both point at the
                // same file when they're equal — but it is load-bearing on createChange never
                // special-casing origPath == path (e.g. to short-circuit rename bookkeeping). If it
                // ever does, this same-path case needs its own branch.
                else -> 'R' to before
            }
            val status = GitFileStatus(index, ' ', path, origPath)
            createChange(project, repository.root, status, ContentVersion.HEAD, ContentVersion.STAGED)
        }

    /**
     * **Every** ref handed to git is validated here, not only the one the user typed.
     *
     * Validating `explicitBase` alone was not enough, and the gap was worse than the typed-ref case it
     * was meant to close. Branch names beginning with `-` are legal refnames — `git check-ref-format
     * 'refs/heads/--output=.git/index'` exits 0 — and `git clone` checks the remote's HEAD branch out for
     * you. So cloning a hostile repository and pressing Start Review in Branch vs Base was enough to
     * make `git rev-list --timestamp --max-count=1 <head-branch>` truncate `.git/index` to zero bytes,
     * destroying the staged state, with the reviewer typing nothing at all. Reproduced end to end against
     * real git: 137 bytes → 0.
     *
     * `base` therefore needs checking after [BaseRefResolver.resolve], because it may come from the
     * tracked branch rather than from `explicitBase`, and `head` needs checking because it is a branch
     * name straight from the repository. Neither is user input, which is exactly why neither was
     * validated — and exactly why they must be: the repository is the untrusted party here.
     *
     * **Coverage, stated honestly.** `GitReviewSourceIntegrationTest` pins the `head` guard by asserting
     * that the index size survives; deleting that line fails it. The **`base` guard is pinned by no
     * test.** Reaching it requires `findTrackedBranch` to return a hostile name, which requires a remote
     * git4idea has indexed; two fixture attempts — a hand-written `branch.*.merge` config, then a real
     * bare remote with an upstream — both left resolution falling back to the `origin/HEAD` literal, so
     * the test passed with this line deleted. A test that cannot fail is worse than none, so it was
     * removed and the gap recorded here instead. Do not delete this line because no test covers it: that
     * is what this paragraph is warning about, not permission.
     */
    private fun resolveBranchVsBase(repository: GitRepository, explicitBase: String?): List<Change> {
        explicitBase?.let { rejectUnsafeRef(it, "The base ref") }
        val tracked = repository.currentBranch?.findTrackedBranch(repository)?.name
        val base = BaseRefResolver.resolve(explicitBase, tracked, "origin/HEAD")
            ?: throw VcsException("No base ref: the branch tracks nothing and origin/HEAD is unset")
        val head = repository.currentBranch?.name
            ?: throw VcsException("Detached HEAD — choose a commit range instead")
        rejectUnsafeRef(base, "The resolved base ref")
        rejectUnsafeRef(head, "The current branch name")
        return GitChangeUtils.getThreeDotDiffOrThrow(repository, base, head).toList()
    }
}

/** Maps a platform [Change] onto the pure queue element used everywhere else. */
object ChangeMapper {
    fun toItem(rootPath: String, change: Change): ReviewItem? {
        // For a deletion there is no "after" side, so the mark is addressed to the bytes that were
        // deleted. Hashing a "deleted:<revision>" literal instead — as this used to — produced a
        // constant, and a constant hash means the mark can never go stale.
        val revision = change.afterRevision ?: change.beforeRevision ?: return null
        val absolutePath = revision.file.path
        val relPath = absolutePath.removePrefix(rootPath).removePrefix("/")
        val bytes = runCatching { (revision as? ByteBackedContentRevision)?.contentAsBytes }.getOrNull()
        val hash = if (bytes != null) ContentHasher.hash(bytes) else ContentHasher.unresolved()
        return ReviewItem(ReviewKey(rootPath, relPath), hash)
    }
}
