package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHasherTest {
    @Test
    fun `same content hashes the same`() {
        assertEquals(ContentHasher.hash("hello", null), ContentHasher.hash("hello", null))
    }

    @Test
    fun `different content hashes differently`() {
        assertNotEquals(ContentHasher.hash("hello", null), ContentHasher.hash("world", null))
    }

    @Test
    fun `null content falls back to the revision string`() {
        val a = ContentHasher.hash(null, "abc123")
        val b = ContentHasher.hash(null, "abc123")
        val c = ContentHasher.hash(null, "def456")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `null content and null revision is stable and distinct`() {
        assertEquals(ContentHasher.hash(null, null), ContentHasher.hash(null, null))
        assertNotEquals(ContentHasher.hash(null, null), ContentHasher.hash("", null))
    }

    @Test
    fun `review key storage key joins root and path`() {
        assertEquals("/repo|src/Main.kt", ReviewKey("/repo", "src/Main.kt").storageKey())
    }
}
