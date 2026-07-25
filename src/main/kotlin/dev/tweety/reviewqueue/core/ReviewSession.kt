package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey

/**
 * The position of one guided review pass.
 *
 * The list is fixed when the pass starts: a fix round landing mid-review must not reshuffle what
 * the reviewer is walking through. [settleOn] handles the one case that cannot be ignored — a file
 * that has since left the queue entirely and can no longer be shown.
 *
 * Deliberately free of platform types. Every transition is a pure function, so the whole flow is
 * testable without an IDE.
 */
data class ReviewSession(val keys: List<ReviewKey>, val index: Int) {

    val current: ReviewKey? get() = keys.getOrNull(index)

    val isAtFirst: Boolean get() = index <= 0

    /** 1-based position, for display. */
    val position: Int get() = index + 1

    val total: Int get() = keys.size

    /** The next file, or null when the pass is finished. */
    fun advance(): ReviewSession? =
        if (index + 1 >= keys.size) null else copy(index = index + 1)

    /** The previous file. Marks are untouched; at the first file this is a no-op. */
    fun back(): ReviewSession = if (index <= 0) this else copy(index = index - 1)

    /**
     * Moves forward to the first file at or after the cursor that is still in [live], or returns
     * null when none remain. Without this, a file removed from the queue mid-pass would leave the
     * session pointing at something that can never be displayed or marked.
     */
    fun settleOn(live: Set<ReviewKey>): ReviewSession? {
        var candidate = index
        while (candidate < keys.size && keys[candidate] !in live) candidate++
        return if (candidate >= keys.size) null else copy(index = candidate)
    }

    companion object {
        fun start(keys: List<ReviewKey>): ReviewSession? =
            if (keys.isEmpty()) null else ReviewSession(keys, 0)
    }
}
