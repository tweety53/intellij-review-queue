package dev.tweety.reviewqueue.queue

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import dev.tweety.reviewqueue.core.ReviewCursor
import dev.tweety.reviewqueue.core.ReviewOrdering
import dev.tweety.reviewqueue.git.ChangeMapper
import dev.tweety.reviewqueue.git.GitReviewSource
import dev.tweety.reviewqueue.git.RootResult
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.state.ReviewStateService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

data class QueueSnapshot(
    val items: List<ReviewItem>,
    val cursor: Int?,
    val reviewedCount: Int,
    val errors: Map<String, String>,
    val scope: ReviewScope,
)

/** Turns per-root results into an ordered queue, a per-root error map, and the change lookup — in a single pass over each change. */
object QueueAssembler {
    data class Assembled(
        val items: List<ReviewItem>,
        val errors: Map<String, String>,
        val changesByKey: Map<ReviewKey, Change>,
    )

    fun assemble(
        results: List<RootResult>,
        rootOrder: List<String>,
    ): Assembled {
        val errors = results.mapNotNull { r -> r.error?.let { r.rootPath to it } }.toMap()
        val pairs = results.flatMap { result ->
            result.changes.mapNotNull { change ->
                ChangeMapper.toItem(result.rootPath, change)?.let { item -> item to change }
            }
        }
        val items = ReviewOrdering.order(pairs.map { it.first }, rootOrder)
        val changesByKey = pairs.associate { (item, change) -> item.key to change }
        return Assembled(items, errors, changesByKey)
    }
}

/**
 * Owns the active scope, the ordered queue and the cursor, and rebuilds on VCS change events so
 * a fix round that rewrites files returns exactly those files to the unreviewed set.
 */
@Service(Service.Level.PROJECT)
class ReviewQueueService(private val project: Project) {

    private val source = GitReviewSource(project)
    private val state get() = ReviewStateService.getInstance(project)
    private val notifier = dev.tweety.reviewqueue.notify.CompletionNotifier(project)
    private val listeners = mutableListOf<() -> Unit>()

    private var scope: ReviewScope = ReviewScope.Staged
    private var items: List<ReviewItem> = emptyList()
    private var errors: Map<String, String> = emptyMap()
    private var changesByKey: Map<ReviewKey, Change> = emptyMap()
    private var cursor: Int? = null

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleRefresh()
        })
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { scheduleRefresh() })
    }

    fun snapshot(): QueueSnapshot =
        QueueSnapshot(items, cursor, state.reviewedCount(items), errors, scope)

    fun setScope(scope: ReviewScope) {
        this.scope = scope
        refresh()
    }

    fun refresh() {
        val previousKey = cursor?.let { items.getOrNull(it)?.key }
        val previousIndex = cursor ?: 0

        val results = source.resolve(scope)
        val rootOrder = source.rootOrder()
        val assembled = QueueAssembler.assemble(results, rootOrder)

        items = assembled.items
        errors = assembled.errors
        changesByKey = assembled.changesByKey

        state.prune(items.mapTo(mutableSetOf()) { it.key })

        cursor = ReviewCursor.relocate(items, previousKey, previousIndex)
            ?: ReviewCursor.firstUnreviewed(items) { state.isReviewed(it) }
        if (previousKey == null) {
            cursor = ReviewCursor.firstUnreviewed(items) { state.isReviewed(it) } ?: cursor
        }
        fireChanged()
    }

    fun markCurrentReviewed() {
        val index = cursor ?: return
        val item = items.getOrNull(index) ?: return
        state.markReviewed(item)
        cursor = ReviewCursor.nextUnreviewed(items, index) { state.isReviewed(it) } ?: index
        fireChanged()
    }

    fun toggleReviewed(key: ReviewKey) {
        val item = items.firstOrNull { it.key == key } ?: return
        if (state.isReviewed(item)) state.unmark(key) else state.markReviewed(item)
        fireChanged()
    }

    fun selectByKey(key: ReviewKey) {
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0) {
            cursor = index
            fireChanged()
        }
    }

    fun resetAll() {
        state.resetAll()
        cursor = ReviewCursor.firstUnreviewed(items) { state.isReviewed(it) }
        fireChanged()
    }

    fun isReviewed(item: ReviewItem): Boolean = state.isReviewed(item)

    fun changeFor(key: ReviewKey): Change? = changesByKey[key]

    fun addListener(listener: () -> Unit, parent: Disposable) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    private fun scheduleRefresh() {
        ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
    }

    private fun fireChanged() {
        notifier.onSnapshot(snapshot())
        listeners.toList().forEach { it() }
    }

    companion object {
        fun getInstance(project: Project): ReviewQueueService = project.service()
    }
}
