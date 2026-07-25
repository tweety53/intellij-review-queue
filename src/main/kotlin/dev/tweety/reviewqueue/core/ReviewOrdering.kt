package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem

/**
 * Deterministic queue order: git roots in the order the repository manager reports them, then
 * path-sorted within each root. Stability matters — the cursor is an index into this order.
 */
object ReviewOrdering {
    fun order(items: List<ReviewItem>, rootOrder: List<String>): List<ReviewItem> {
        val rank = rootOrder.withIndex().associate { (i, root) -> root to i }
        return items.sortedWith(
            compareBy<ReviewItem> { rank[it.key.rootPath] ?: Int.MAX_VALUE }
                .thenBy { it.key.rootPath }
                .thenBy { it.key.relPath }
        )
    }
}
