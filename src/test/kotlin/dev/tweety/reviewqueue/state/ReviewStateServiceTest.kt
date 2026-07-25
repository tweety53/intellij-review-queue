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

    @Test
    fun `prune drops keys not in the live set`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        service.markReviewed(item("b.kt", "h2"))
        service.prune(setOf(ReviewKey("/repo", "a.kt")))
        assertTrue(service.isReviewed(item("a.kt", "h1")))
        assertFalse(service.isReviewed(item("b.kt", "h2")))
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
