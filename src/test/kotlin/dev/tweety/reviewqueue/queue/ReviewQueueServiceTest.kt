package dev.tweety.reviewqueue.queue

import dev.tweety.reviewqueue.git.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueServiceTest {
    @Test
    fun `assemble collects errors per root and keeps other roots listed`() {
        val results = listOf(
            RootResult("/a", emptyList(), "boom"),
            RootResult("/b", emptyList(), null),
        )
        val (items, errors) = QueueAssembler.assemble(results, listOf("/a", "/b"))
        assertTrue(items.isEmpty())
        assertEquals(mapOf("/a" to "boom"), errors)
    }

    @Test
    fun `assemble returns no errors when every root succeeds`() {
        val results = listOf(RootResult("/a", emptyList(), null))
        val (_, errors) = QueueAssembler.assemble(results, listOf("/a"))
        assertTrue(errors.isEmpty())
    }
}
