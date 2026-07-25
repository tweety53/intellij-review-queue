package dev.tweety.reviewqueue.model

/**
 * One element of the review queue. Deliberately free of platform types so queue ordering,
 * cursor math and review classification can be unit tested without an IDE.
 */
data class ReviewItem(val key: ReviewKey, val contentHash: String)
