package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFileListTest {

    private fun item(root: String, path: String) = ReviewItem(ReviewKey(root, path), "hash-$root-$path")

    private val single = listOf(item("/repo", "a.kt"), item("/repo", "src/b.kt"))

    @Test
    fun `rows keep the order they arrive in`() {
        val rows = ReviewFileList.rows(single, reviewed = { false }, current = null)
        assertEquals(listOf("a.kt", "src/b.kt"), rows.map { it.label })
        assertEquals(single.map { it.key }, rows.map { it.key })
    }

    @Test
    fun `a single root needs no prefix`() {
        val rows = ReviewFileList.rows(single, reviewed = { false }, current = null)
        assertEquals("src/b.kt", rows[1].label)
    }

    @Test
    fun `multiple roots prefix each label with the root name`() {
        val items = listOf(item("/work/app", "a.kt"), item("/work/lib", "b.kt"))
        val rows = ReviewFileList.rows(items, reviewed = { false }, current = null)
        assertEquals(listOf("app/a.kt", "lib/b.kt"), rows.map { it.label })
    }

    @Test
    fun `reviewed state comes from the predicate`() {
        val rows = ReviewFileList.rows(single, reviewed = { it.key.relPath == "a.kt" }, current = null)
        assertTrue(rows[0].isReviewed)
        assertFalse(rows[1].isReviewed)
    }

    @Test
    fun `exactly one row is marked current`() {
        val rows = ReviewFileList.rows(single, reviewed = { false }, current = ReviewKey("/repo", "src/b.kt"))
        assertEquals(listOf(false, true), rows.map { it.isCurrent })
    }

    @Test
    fun `no row is current outside a session`() {
        val rows = ReviewFileList.rows(single, reviewed = { false }, current = null)
        assertTrue(rows.none { it.isCurrent })
    }

    @Test
    fun `an empty queue yields no rows`() {
        assertEquals(emptyList<ReviewFileRow>(), ReviewFileList.rows(emptyList(), { false }, null))
    }
}
