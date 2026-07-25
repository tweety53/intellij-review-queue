package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey

/**
 * Decides what a diff chain should contain and where it should start.
 *
 * Pure and free of platform types so the pairing can be tested without a project or the diff
 * framework. The rules it encodes are the ones that were previously wrong:
 *
 * - a key whose producer is null is dropped, because a null in the chain breaks diff opening for
 *   every file in the queue rather than just that one;
 * - the index is computed against the *filtered* list, so it still points at the selected file;
 * - a selected key that did not survive filtering yields `null` — the caller must not open
 *   anything, rather than falling back to index 0 and showing an unrelated file.
 */
object DiffChainPlanner {

    fun <T : Any> plan(
        keys: List<ReviewKey>,
        producerFor: (ReviewKey) -> T?,
        selected: ReviewKey,
    ): Pair<List<T>, Int>? {
        val entries = keys.mapNotNull { key -> producerFor(key)?.let { key to it } }
        if (entries.isEmpty()) return null
        val index = entries.indexOfFirst { it.first == selected }
        if (index < 0) return null
        return entries.map { it.second } to index
    }
}
