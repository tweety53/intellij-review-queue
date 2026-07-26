package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey

/** One row of the file list: what to draw, and the key to act on when it is picked. */
data class ReviewFileRow(
    val key: ReviewKey,
    val label: String,
    val isReviewed: Boolean,
    val isCurrent: Boolean,
)

/**
 * Turns a queue snapshot into rows for the file-list popup.
 *
 * Order is the caller's: the queue is already sorted by git root then path by [ReviewOrdering], and
 * that order is what the review cursor indexes into, so re-sorting here would make the popup
 * disagree with the pass.
 *
 * The list is flat rather than a tree. A tree inside a chooser popup costs keyboard-navigable rows,
 * and buys nothing in the single-root case — which is the normal one. The root name appears as a
 * label prefix only when there is more than one root to tell apart.
 */
object ReviewFileList {

    fun rows(
        items: List<ReviewItem>,
        reviewed: (ReviewItem) -> Boolean,
        current: ReviewKey?,
    ): List<ReviewFileRow> {
        val multiRoot = items.mapTo(mutableSetOf()) { it.key.rootPath }.size > 1
        return items.map { item ->
            val prefix = if (multiRoot) item.key.rootPath.substringAfterLast('/') + "/" else ""
            ReviewFileRow(
                key = item.key,
                label = prefix + item.key.relPath,
                isReviewed = reviewed(item),
                isCurrent = item.key == current,
            )
        }
    }
}
