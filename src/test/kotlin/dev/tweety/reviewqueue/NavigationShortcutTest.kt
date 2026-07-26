package dev.tweety.reviewqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Cmd+Option+Shift navigation cluster: arrows for moving around a review pass, Space and
 * Enter for marking.
 *
 * The whole cluster is macOS-only by design. Cmd is a Mac key, and the Windows analogue
 * (Win+Alt+Shift+arrow) collides with OS window snapping — so on other platforms these actions ship
 * shortcutless and are reached from the diff toolbar. This test exists to catch the cluster being
 * half-moved to `$default` later, which would bind Win+Alt+Shift+arrow on Windows without anyone
 * meaning to.
 */
class NavigationShortcutTest {

    private val pluginXml: String =
        checkNotNull(javaClass.getResource("/META-INF/plugin.xml")) { "plugin.xml not on the test classpath" }
            .readText()

    /**
     * The self-closing form is tried first on purpose. `[^>]*` cannot cross a `>`, so that pattern
     * matches only a genuinely self-closing tag; testing the container form first would let its
     * non-greedy tail stop at the `/>` of the action's first child and silently truncate the block.
     */
    private fun block(actionId: String): String {
        val id = Regex.escape(actionId)
        return Regex("""<action id="$id"[^>]*/>""").find(pluginXml)?.value
            ?: Regex("""<action id="$id"[^>]*>.*?</action>""", RegexOption.DOT_MATCHES_ALL)
                .find(pluginXml)?.value
            ?: error("no $actionId action in plugin.xml")
    }

    private fun chords(actionId: String, keymap: String): Set<String> =
        Regex("""<keyboard-shortcut[^/]*/>""").findAll(block(actionId))
            .map { it.value }
            .filter { it.contains("""keymap="$keymap"""") }
            .filterNot { it.contains("""remove="true"""") }
            .mapNotNull { Regex("""first-keystroke="([^"]+)"""").find(it)?.groupValues?.get(1) }
            .toSet()

    private val mac = "Mac OS X 10.5+"

    @Test
    fun `the arrow cluster maps to file and change navigation`() {
        assertEquals(setOf("alt meta shift LEFT"), chords("ReviewQueue.PreviousFile", mac))
        assertEquals(setOf("alt meta shift RIGHT"), chords("ReviewQueue.NextFile", mac))
        assertEquals(setOf("alt meta shift UP"), chords("ReviewQueue.PreviousChange", mac))
        assertEquals(setOf("alt meta shift DOWN"), chords("ReviewQueue.NextChange", mac))
    }

    @Test
    fun `mark reviewed also answers to the cluster's space and enter`() {
        val macChords = chords("ReviewQueue.MarkReviewed", mac)
        assertTrue(macChords.toString(), macChords.containsAll(setOf("alt meta shift SPACE", "alt meta shift ENTER")))
    }

    /** Left/right move between files, up/down within one. Swapping them would be a silent misfeature. */
    @Test
    fun `horizontal arrows move between files and vertical arrows within a file`() {
        val horizontal = chords("ReviewQueue.PreviousFile", mac) + chords("ReviewQueue.NextFile", mac)
        val vertical = chords("ReviewQueue.PreviousChange", mac) + chords("ReviewQueue.NextChange", mac)
        assertTrue(horizontal.all { it.endsWith("LEFT") || it.endsWith("RIGHT") })
        assertTrue(vertical.all { it.endsWith("UP") || it.endsWith("DOWN") })
    }

    @Test
    fun `the cluster is macOS-only and binds nothing on the default keymap`() {
        listOf(
            "ReviewQueue.PreviousFile",
            "ReviewQueue.NextFile",
            "ReviewQueue.PreviousChange",
            "ReviewQueue.NextChange",
        ).forEach { id ->
            assertEquals(
                "$id must not bind a Cmd chord on \$default: meta is the Windows key there, and " +
                    "Win+Alt+Shift+arrow is OS window snapping",
                emptySet<String>(),
                chords(id, "${'$'}default"),
            )
        }
    }
}
