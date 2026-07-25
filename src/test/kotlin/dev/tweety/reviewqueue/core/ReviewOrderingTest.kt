package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewOrderingTest {
    private fun item(root: String, path: String) = ReviewItem(ReviewKey(root, path), "h")

    @Test
    fun `groups by root order then sorts by path`() {
        val items = listOf(
            item("/b", "z.kt"),
            item("/a", "z.kt"),
            item("/b", "a.kt"),
            item("/a", "a.kt"),
        )
        val ordered = ReviewOrdering.order(items, listOf("/a", "/b"))
        assertEquals(
            listOf("/a|a.kt", "/a|z.kt", "/b|a.kt", "/b|z.kt"),
            ordered.map { it.key.storageKey() },
        )
    }

    @Test
    fun `roots absent from rootOrder sort last, alphabetically`() {
        val items = listOf(item("/z", "a.kt"), item("/a", "a.kt"), item("/m", "a.kt"))
        val ordered = ReviewOrdering.order(items, listOf("/a"))
        assertEquals(listOf("/a", "/m", "/z"), ordered.map { it.key.rootPath })
    }

    @Test
    fun `ordering is stable across repeated calls`() {
        val items = listOf(item("/a", "b.kt"), item("/a", "a.kt"))
        val first = ReviewOrdering.order(items, listOf("/a"))
        val second = ReviewOrdering.order(first, listOf("/a"))
        assertEquals(first.map { it.key }, second.map { it.key })
    }
}
