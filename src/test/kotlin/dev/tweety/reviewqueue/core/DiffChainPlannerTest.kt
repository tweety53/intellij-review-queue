package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffChainPlannerTest {

    private fun key(path: String) = ReviewKey("/repo", path)

    private val a = key("a.kt")
    private val b = key("b.png")
    private val c = key("c.kt")

    /** Producers named after their key, except those listed as unrenderable, which produce null. */
    private fun producers(vararg unrenderable: ReviewKey): (ReviewKey) -> String? =
        { k -> if (k in unrenderable) null else "producer(${k.relPath})" }

    @Test
    fun `happy path keeps every producer and points the index at the selected key`() {
        val plan = DiffChainPlanner.plan(listOf(a, b, c), producers(), selected = c)
        assertEquals(listOf("producer(a.kt)", "producer(b.png)", "producer(c.kt)"), plan?.first)
        assertEquals(2, plan?.second)
    }

    @Test
    fun `a null producer in the middle is dropped and the index shifts to follow the selection`() {
        val plan = DiffChainPlanner.plan(listOf(a, b, c), producers(b), selected = c)
        assertEquals(listOf("producer(a.kt)", "producer(c.kt)"), plan?.first)
        // Without recomputing against the filtered list this would still say 2 — off the end.
        assertEquals(1, plan?.second)
    }

    @Test
    fun `the selected key surviving filtering is still found when earlier keys drop out`() {
        val plan = DiffChainPlanner.plan(listOf(a, b, c), producers(a, b), selected = c)
        assertEquals(listOf("producer(c.kt)"), plan?.first)
        assertEquals(0, plan?.second)
    }

    @Test
    fun `every producer null yields no plan`() {
        assertNull(DiffChainPlanner.plan(listOf(a, b, c), producers(a, b, c), selected = a))
    }

    @Test
    fun `an empty key list yields no plan`() {
        assertNull(DiffChainPlanner.plan(emptyList(), producers(), selected = a))
    }

    @Test
    fun `a selected key that did not survive filtering yields no plan, not index zero`() {
        // b is the file the user clicked and it cannot be rendered. Opening index 0 here would show
        // a.kt instead — an unrelated file the user did not ask for.
        assertNull(DiffChainPlanner.plan(listOf(a, b, c), producers(b), selected = b))
    }

    @Test
    fun `a selected key absent from the queue entirely yields no plan`() {
        assertNull(DiffChainPlanner.plan(listOf(a, c), producers(), selected = b))
    }
}
