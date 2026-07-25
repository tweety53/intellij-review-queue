package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionTest {

    private fun key(path: String) = ReviewKey("/repo", path)

    private val keys = listOf(key("a.kt"), key("b.kt"), key("c.kt"))

    @Test
    fun `start on an empty list yields no session`() {
        assertNull(ReviewSession.start(emptyList()))
    }

    @Test
    fun `start positions on the first file`() {
        val session = ReviewSession.start(keys)!!
        assertEquals(key("a.kt"), session.current)
        assertEquals(1, session.position)
        assertEquals(3, session.total)
        assertTrue(session.isAtFirst)
    }

    @Test
    fun `advance walks forward one file at a time`() {
        val first = ReviewSession.start(keys)!!
        val second = first.advance()!!
        assertEquals(key("b.kt"), second.current)
        assertEquals(2, second.position)
        assertEquals(key("c.kt"), second.advance()!!.current)
    }

    @Test
    fun `advance past the last file finishes the pass`() {
        val last = ReviewSession(keys, 2)
        assertNull(last.advance())
    }

    @Test
    fun `back steps to the previous file without changing the list`() {
        val third = ReviewSession(keys, 2)
        val second = third.back()
        assertEquals(key("b.kt"), second.current)
        assertEquals(keys, second.keys)
    }

    @Test
    fun `back at the first file is a no-op`() {
        val first = ReviewSession.start(keys)!!
        assertSame(first, first.back())
    }

    @Test
    fun `settleOn keeps the position when the current file is still live`() {
        val session = ReviewSession(keys, 1)
        assertEquals(key("b.kt"), session.settleOn(keys.toSet())!!.current)
    }

    @Test
    fun `settleOn skips forward over files that vanished from the queue`() {
        val session = ReviewSession(keys, 1)
        val settled = session.settleOn(setOf(key("a.kt"), key("c.kt")))!!
        assertEquals(key("c.kt"), settled.current)
    }

    @Test
    fun `settleOn yields null when nothing at or after the cursor is live`() {
        val session = ReviewSession(keys, 1)
        assertNull(session.settleOn(setOf(key("a.kt"))))
    }
}
