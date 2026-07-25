package dev.tweety.reviewqueue.state

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStateServiceTest {
    private fun item(path: String, hash: String) = ReviewItem(ReviewKey("/repo", path), hash)

    @Test
    fun `unmarked item is not reviewed`() {
        val service = ReviewStateService()
        assertFalse(service.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `marked item is reviewed`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        assertTrue(service.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `changed content drops the reviewed mark`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        assertFalse(service.isReviewed(item("a.kt", "h2")))
    }

    @Test
    fun `restoring the original content restores the mark`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        assertFalse(service.isReviewed(item("a.kt", "h2")))
        assertTrue(service.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `unmark removes the entry`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        service.unmark(ReviewKey("/repo", "a.kt"))
        assertFalse(service.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `resetAll clears everything`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        service.markReviewed(item("b.kt", "h2"))
        service.resetAll()
        assertFalse(service.isReviewed(item("a.kt", "h1")))
        assertFalse(service.isReviewed(item("b.kt", "h2")))
    }

    /**
     * Pins the *storage-layer* half of the never-prune decision: reading the store — through
     * `reviewedCount` or `isReviewed` — never mutates it, so no amount of querying with a queue
     * that omits a file can cost that file its mark.
     *
     * Scope: this covers pruning folded into `ReviewStateService`'s own read/write methods. It does
     * **not** cover the call site where both real pruning bugs lived, which was
     * `ReviewQueueService.applyRebuild`. That path is guarded by
     * `dev.tweety.reviewqueue.queue.ReviewMarkRetentionTest`, which drives the real service through
     * a scope switch and repeated refreshes; that is the test to look at if you are considering
     * reintroducing pruning.
     */
    @Test
    fun `marks survive a rebuild that does not contain them`() {
        val service = ReviewStateService()
        val staged = item("a.kt", "h1")
        val alsoStaged = ReviewItem(ReviewKey("/other-root", "b.kt"), "h2")
        service.markReviewed(staged)
        service.markReviewed(alsoStaged)

        // A rebuild in some other scope: a completely disjoint queue, and for a while no queue at
        // all. Neither is allowed to touch what is stored.
        val foreignScopeQueue = listOf(ReviewItem(ReviewKey("/repo", "unrelated.kt"), "h9"))
        assertEquals(0, service.reviewedCount(foreignScopeQueue))
        assertEquals(0, service.reviewedCount(emptyList()))

        // The original files come back with unchanged content — the marks must still read reviewed.
        assertTrue(service.isReviewed(staged))
        assertTrue(service.isReviewed(alsoStaged))
        assertEquals(2, service.reviewedCount(listOf(staged, alsoStaged)))
    }

    @Test
    fun `a mark that outlived its file still only applies to identical content`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        // Retaining a stale entry is inert: it reads reviewed only for the exact content reviewed.
        assertTrue(service.isReviewed(item("a.kt", "h1")))
        assertFalse(service.isReviewed(item("a.kt", "h1-edited")))
    }

    @Test
    fun `state round trips`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        val restored = ReviewStateService()
        restored.loadState(service.state)
        assertTrue(restored.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `reviewedCount counts only matching hashes`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        service.markReviewed(item("b.kt", "h2"))
        val items = listOf(item("a.kt", "h1"), item("b.kt", "CHANGED"), item("c.kt", "h3"))
        assertEquals(1, service.reviewedCount(items))
    }
}
