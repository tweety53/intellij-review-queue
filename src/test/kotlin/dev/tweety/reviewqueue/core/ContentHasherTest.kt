package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHasherTest {
    private fun hash(text: String) = ContentHasher.hash(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `same content hashes the same`() {
        assertEquals(hash("hello"), hash("hello"))
    }

    @Test
    fun `different content hashes differently`() {
        assertNotEquals(hash("hello"), hash("world"))
    }

    @Test
    fun `a line separator only rewrite changes the hash`() {
        assertNotEquals(hash("a\r\nb\r\n"), hash("a\nb\n"))
    }

    @Test
    fun `empty content still hashes`() {
        assertEquals(hash(""), hash(""))
        assertNotEquals(hash(""), hash("x"))
    }

    @Test
    fun `unresolved never repeats, so an unreadable file never reads reviewed`() {
        assertNotEquals(ContentHasher.unresolved(), ContentHasher.unresolved())
    }

    @Test
    fun `review key storage key joins root and path`() {
        assertEquals("/repo|src/Main.kt", ReviewKey("/repo", "src/Main.kt").storageKey())
    }
}
