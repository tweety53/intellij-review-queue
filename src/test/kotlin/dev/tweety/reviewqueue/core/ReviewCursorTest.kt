package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewCursorTest {
    private fun item(path: String) = ReviewItem(ReviewKey("/r", path), "h")

    private val items = listOf(item("a.kt"), item("b.kt"), item("c.kt"), item("d.kt"))

    private fun reviewed(vararg paths: String): (ReviewItem) -> Boolean =
        { it.key.relPath in paths.toSet() }

    @Test
    fun `firstUnreviewed finds the first unreviewed item`() {
        assertEquals(1, ReviewCursor.firstUnreviewed(items, reviewed("a.kt")))
    }

    @Test
    fun `firstUnreviewed returns null when everything is reviewed`() {
        assertNull(ReviewCursor.firstUnreviewed(items, reviewed("a.kt", "b.kt", "c.kt", "d.kt")))
    }

    @Test
    fun `firstUnreviewed returns null for an empty queue`() {
        assertNull(ReviewCursor.firstUnreviewed(emptyList(), reviewed()))
    }

    @Test
    fun `nextUnreviewed moves forward past the current item`() {
        assertEquals(2, ReviewCursor.nextUnreviewed(items, from = 1, isReviewed = reviewed("a.kt", "b.kt")))
    }

    @Test
    fun `nextUnreviewed wraps to an earlier unreviewed item`() {
        assertEquals(0, ReviewCursor.nextUnreviewed(items, from = 2, isReviewed = reviewed("b.kt", "c.kt", "d.kt")))
    }

    @Test
    fun `nextUnreviewed never returns the item it started from`() {
        assertNull(ReviewCursor.nextUnreviewed(items, from = 1, isReviewed = reviewed("a.kt", "c.kt", "d.kt")))
    }

    @Test
    fun `nextUnreviewed returns null when everything is reviewed`() {
        assertNull(ReviewCursor.nextUnreviewed(items, from = 0, isReviewed = reviewed("a.kt", "b.kt", "c.kt", "d.kt")))
    }

    @Test
    fun `relocate keeps the cursor on the same path`() {
        val rebuilt = listOf(item("x.kt"), item("b.kt"))
        assertEquals(1, ReviewCursor.relocate(rebuilt, ReviewKey("/r", "b.kt"), previousIndex = 1))
    }

    @Test
    fun `relocate falls to the same index when the path is gone`() {
        val rebuilt = listOf(item("x.kt"), item("y.kt"), item("z.kt"))
        assertEquals(1, ReviewCursor.relocate(rebuilt, ReviewKey("/r", "b.kt"), previousIndex = 1))
    }

    @Test
    fun `relocate clamps to the last item when the queue shrank`() {
        val rebuilt = listOf(item("x.kt"))
        assertEquals(0, ReviewCursor.relocate(rebuilt, ReviewKey("/r", "d.kt"), previousIndex = 3))
    }

    @Test
    fun `relocate returns null for an empty queue`() {
        assertNull(ReviewCursor.relocate(emptyList(), ReviewKey("/r", "a.kt"), previousIndex = 0))
    }
}
