package dev.tweety.reviewqueue.git

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import dev.tweety.reviewqueue.core.BaseRefResolver
import dev.tweety.reviewqueue.core.ContentHasher
import dev.tweety.reviewqueue.core.StagedFilter
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import git4idea.changes.GitChangeUtils
import git4idea.index.ContentVersion
import git4idea.index.createChange
import git4idea.index.getStatus
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
            is ReviewScope.CommitRange ->
                GitChangeUtils.getDiff(project, repository.root, scope.from, scope.to, null).toList()
        }

    private fun resolveStaged(repository: GitRepository): List<Change> {
        val statuses = getStatus(project, repository.root, emptyList(), false, false, false)
        return statuses
            .filter { it.isTracked() && StagedFilter.isStaged(it.index) }
            .mapNotNull { createChange(project, repository.root, it, ContentVersion.HEAD, ContentVersion.STAGED) }
    }

    private fun resolveBranchVsBase(repository: GitRepository, explicitBase: String?): List<Change> {
        val tracked = repository.currentBranch?.findTrackedBranch(repository)?.name
        val base = BaseRefResolver.resolve(explicitBase, tracked, "origin/HEAD")
            ?: throw VcsException("No base ref: the branch tracks nothing and origin/HEAD is unset")
        val head = repository.currentBranch?.name
            ?: throw VcsException("Detached HEAD — choose a commit range instead")
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
