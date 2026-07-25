package dev.tweety.reviewqueue.queue

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.util.concurrency.AppExecutorUtil
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
import java.util.IdentityHashMap

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
        /**
         * The authoritative key for each [Change], derived from the root that produced it. The tree
         * looks keys up here rather than re-deriving them from path prefixes, which disagrees with
         * this mapping whenever roots are nested (submodules).
         */
        val keysByChange: Map<Change, ReviewKey>,
    )

    fun assemble(
        results: List<RootResult>,
        rootOrder: List<String>,
    ): Assembled {
        val errors = results.mapNotNull { r -> r.error?.let { r.rootPath to it } }.toMap()

        val items = mutableListOf<ReviewItem>()
        val changesByKey = LinkedHashMap<ReviewKey, Change>()
        val keysByChange = IdentityHashMap<Change, ReviewKey>()

        for (result in results) {
            for (change in result.changes) {
                val item = ChangeMapper.toItem(result.rootPath, change) ?: continue
                // Duplicates are handled explicitly rather than collapsed by a map builder: a
                // silent collapse leaves `items` longer than the change lookup, so the tree renders
                // one row while the progress label counts two.
                if (changesByKey.containsKey(item.key)) {
                    thisLogger().warn(
                        "Review queue: duplicate entry for ${item.key.storageKey()}; keeping the first change"
                    )
                    continue
                }
                items += item
                changesByKey[item.key] = change
                keysByChange[change] = item.key
            }
        }

        return Assembled(ReviewOrdering.order(items, rootOrder), errors, changesByKey, keysByChange)
    }
}

/**
 * Owns the active scope, the ordered queue and the cursor, and rebuilds on VCS change events so
 * a fix round that rewrites files returns exactly those files to the unreviewed set.
 *
 * The rebuild itself (git queries plus a content hash per file) runs on a background thread; only
 * the small apply step touches the EDT.
 */
@Service(Service.Level.PROJECT)
class ReviewQueueService(private val project: Project) : Disposable {

    private val source = GitReviewSource(project)
    private val state get() = ReviewStateService.getInstance(project)
    private val notifier = dev.tweety.reviewqueue.notify.CompletionNotifier(project)
    private val listeners = mutableListOf<() -> Unit>()

    private var scope: ReviewScope = ReviewScope.Staged
    private var items: List<ReviewItem> = emptyList()
    private var errors: Map<String, String> = emptyMap()
    private var changesByKey: Map<ReviewKey, Change> = emptyMap()
    private var keysByChange: Map<Change, ReviewKey> = emptyMap()
    private var cursor: Int? = null

    private data class Rebuild(
        val scope: ReviewScope,
        val assembled: QueueAssembler.Assembled,
    )

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleRefresh()
        })
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { scheduleRefresh() })
    }

    /** Cheap and EDT-safe: reads already-computed state, spawns no processes. */
    fun snapshot(): QueueSnapshot =
        QueueSnapshot(items, cursor, state.reviewedCount(items), errors, scope)

    fun setScope(scope: ReviewScope) {
        this.scope = scope
        refresh()
    }

    /**
     * Resolves the current scope off the EDT and applies the result on it: `getStatus` and the
     * per-file content read are git subprocesses, which must never run on the EDT. `coalesceBy`
     * also collapses refresh storms (every `changeListUpdateDone` lands here) into one round trip.
     */
    fun refresh() {
        if (project.isDisposed) return
        val requestedScope = scope
        ReadAction.nonBlocking<Rebuild> {
            val results = source.resolve(requestedScope)
            Rebuild(requestedScope, QueueAssembler.assemble(results, source.rootOrder()))
        }
            .expireWith(this)
            .coalesceBy(this)
            // Deliberately not ModalityState.any(): that would let the queue rebuild underneath the
            // Commit Range input and the Reset All confirmation, changing what the user is deciding
            // about while they decide. The apply waits until no modal dialog is up.
            .finishOnUiThread(ModalityState.nonModal()) { applyRebuild(it) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun applyRebuild(rebuild: Rebuild) {
        if (project.isDisposed) return
        // A scope change raced this rebuild; the refresh for the new scope is already in flight.
        if (rebuild.scope != scope) return

        val previousKey = cursor?.let { items.getOrNull(it)?.key }
        val previousIndex = cursor ?: 0

        items = rebuild.assembled.items
        errors = rebuild.assembled.errors
        changesByKey = rebuild.assembled.changesByKey
        keysByChange = rebuild.assembled.keysByChange

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

    /** Toggles the stored mark for [key]. Deliberately does not move the cursor. */
    fun toggleReviewed(key: ReviewKey) {
        val item = items.firstOrNull { it.key == key } ?: return
        if (state.isReviewed(item)) state.unmark(key) else state.markReviewed(item)
        fireChanged()
    }

    /**
     * Moves the cursor to [key]. A no-op when the cursor is already there — otherwise the tree's
     * selection listener and the tree refresh that follows `fireChanged()` feed each other.
     */
    fun selectByKey(key: ReviewKey) {
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0 && index != cursor) {
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

    /** The authoritative key for [change], as derived by the root that produced it. */
    fun keyFor(change: Change): ReviewKey? = keysByChange[change]

    fun addListener(listener: () -> Unit, parent: Disposable) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    override fun dispose() = Unit

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
