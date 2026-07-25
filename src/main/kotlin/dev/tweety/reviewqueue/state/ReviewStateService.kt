package dev.tweety.reviewqueue.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey

/**
 * Per-project reviewed marks, stored in workspace state so they survive restarts and never touch
 * the repository. A file counts as reviewed only while its current content hash equals the hash
 * recorded when it was marked.
 */
@Service(Service.Level.PROJECT)
@State(name = "ReviewQueueState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewStateService : PersistentStateComponent<ReviewStateService.State> {

    class State {
        @JvmField
        var reviewed: MutableMap<String, String> = mutableMapOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun isReviewed(item: ReviewItem): Boolean =
        myState.reviewed[item.key.storageKey()] == item.contentHash

    fun markReviewed(item: ReviewItem) {
        myState.reviewed[item.key.storageKey()] = item.contentHash
    }

    fun unmark(key: ReviewKey) {
        myState.reviewed.remove(key.storageKey())
    }

    fun resetAll() {
        myState.reviewed.clear()
    }

    /** Drops stored marks for files no longer present in any queue, bounding state growth. */
    fun prune(liveKeys: Set<ReviewKey>) {
        val live = liveKeys.mapTo(mutableSetOf()) { it.storageKey() }
        myState.reviewed.keys.retainAll(live)
    }

    fun reviewedCount(items: List<ReviewItem>): Int = items.count { isReviewed(it) }

    companion object {
        fun getInstance(project: Project): ReviewStateService = project.service()
    }
}
