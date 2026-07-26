package dev.tweety.reviewqueue.queue

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import dev.tweety.reviewqueue.git.RootResult
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueServiceTest {

    private class FakeRevision(private val path: String, private val bytes: ByteArray) : ByteBackedContentRevision {
        override fun getContentAsBytes(): ByteArray = bytes
        override fun getContent(): String = String(bytes, Charsets.UTF_8)
        override fun getFile(): FilePath = LocalFilePath(path, false)
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    private fun change(absolutePath: String, content: String = "body"): Change =
        Change(null, FakeRevision(absolutePath, content.toByteArray(Charsets.UTF_8)))

    private fun keys(assembled: QueueAssembler.Assembled): List<ReviewKey> = assembled.items.map { it.key }

    @Test
    fun `assemble collects errors per root and keeps other roots listed`() {
        val results = listOf(
            RootResult("/a", emptyList(), "boom"),
            RootResult("/b", emptyList(), null),
        )
        val assembled = QueueAssembler.assemble(results, listOf("/a", "/b"))
        assertTrue(assembled.items.isEmpty())
        assertEquals(mapOf("/a" to "boom"), assembled.errors)
    }

    @Test
    fun `assemble returns no errors when every root succeeds`() {
        val results = listOf(RootResult("/a", emptyList(), null))
        val assembled = QueueAssembler.assemble(results, listOf("/a"))
        assertTrue(assembled.errors.isEmpty())
    }

    @Test
    fun `assemble orders roots by the reported root order, then paths within a root`() {
        val results = listOf(
            RootResult("/a", listOf(change("/a/z.kt"), change("/a/b.kt")), null),
            RootResult("/b", listOf(change("/b/m.kt")), null),
        )
        val assembled = QueueAssembler.assemble(results, listOf("/b", "/a"))
        assertEquals(
            listOf(
                ReviewKey("/b", "m.kt"),
                ReviewKey("/a", "b.kt"),
                ReviewKey("/a", "z.kt"),
            ),
            keys(assembled),
        )
    }

    @Test
    fun `assemble keys a nested root's file against that root, not the enclosing one`() {
        val outer = change("/p/top.kt")
        val inner = change("/p/sub/file.kt")
        val results = listOf(
            RootResult("/p", listOf(outer), null),
            RootResult("/p/sub", listOf(inner), null),
        )
        val assembled = QueueAssembler.assemble(results, listOf("/p", "/p/sub"))
        // Asserted through `changesByKey`, the one lookup the queue still publishes: the inner root's
        // file must be reachable under the inner root's key, not under the enclosing one. Keying by a
        // path prefix instead files both changes under `/p`, and nothing then resolves `/p/sub`.
        assertSame(outer, assembled.changesByKey[ReviewKey("/p", "top.kt")])
        assertSame(inner, assembled.changesByKey[ReviewKey("/p/sub", "file.kt")])
    }

    @Test
    fun `assemble keeps the first of two changes that map to the same key`() {
        val first = change("/a/dup.kt", "one")
        val second = change("/a/dup.kt", "two")
        val results = listOf(RootResult("/a", listOf(first, second), null))

        val assembled = QueueAssembler.assemble(results, listOf("/a"))

        // One entry in the queue and one in the lookup, so nothing can count a file that no
        // lookup-driven gesture is able to reach — the file-list popup's `N / M` title reads `items`.
        assertEquals(listOf(ReviewKey("/a", "dup.kt")), keys(assembled))
        assertEquals(1, assembled.changesByKey.size)
        assertSame(first, assembled.changesByKey[ReviewKey("/a", "dup.kt")])
    }
}
