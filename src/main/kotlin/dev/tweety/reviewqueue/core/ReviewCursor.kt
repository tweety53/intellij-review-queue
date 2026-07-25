package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey

/**
 * Cursor math for the queue. Marking a file advances to the next unreviewed one, wrapping past the
 * end exactly once so files above the cursor are not stranded, and never landing back on the item
 * the move started from.
 */
object ReviewCursor {

    fun firstUnreviewed(items: List<ReviewItem>, isReviewed: (ReviewItem) -> Boolean): Int? =
        items.indexOfFirst { !isReviewed(it) }.takeIf { it >= 0 }

    fun nextUnreviewed(items: List<ReviewItem>, from: Int, isReviewed: (ReviewItem) -> Boolean): Int? {
        if (items.isEmpty()) return null
        for (offset in 1..items.size) {
            val index = (from + offset) % items.size
            if (index == from) continue
            if (!isReviewed(items[index])) return index
        }
        return null
    }

    /**
     * Places the cursor after the queue was rebuilt: same file if it survived, otherwise the item
     * that now occupies the old position, clamped into range.
     */
    fun relocate(items: List<ReviewItem>, previousKey: ReviewKey?, previousIndex: Int): Int? {
        if (items.isEmpty()) return null
        if (previousKey != null) {
            val same = items.indexOfFirst { it.key == previousKey }
            if (same >= 0) return same
        }
        return previousIndex.coerceIn(0, items.size - 1)
    }
}
