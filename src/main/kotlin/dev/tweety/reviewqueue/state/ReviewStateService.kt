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
 *
 * Marks are **never** pruned, deliberately. Keys are root+path+hash and carry no notion of scope,
 * so any rule for deciding which stored key is "dead" has to reason about a rebuild it cannot see
 * the whole of — a bad guess silently destroys the user's review progress, which is the one thing
 * this plugin exists to protect. Two attempts at such a rule were both subtly wrong. Against that,
 * the map holds two short strings per file ever reviewed in the project — a few KB after heavy use,
 * in workspace state — and a stale entry is inert: it only reads as reviewed if that exact path
 * comes back with the exact content that was reviewed, which is precisely the correct answer.
 * Only Reset All clears entries.
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

    fun reviewedCount(items: List<ReviewItem>): Int = items.count { isReviewed(it) }

    companion object {
        fun getInstance(project: Project): ReviewStateService = project.service()
    }
}
