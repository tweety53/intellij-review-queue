package dev.tweety.reviewqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Mark Reviewed key bindings, and each way of getting them wrong that has already happened.
 *
 * Reads the shipped resource rather than the platform's keymap registry, so it needs no IDE. It
 * asserts on attribute values rather than whole tags: `patchPluginXml` re-serialises the descriptor
 * and inserts a space before `/>`, and a test that pins that whitespace fails on a serializer change
 * for a reason that has nothing to do with the shortcut.
 *
 * Four chords are bound, on request, so the action is reachable however the reader's hand happens to
 * be resting. Two of them are knowingly shared with bundled actions; see the collision tests, which
 * exist so the trade stays visible instead of being rediscovered.
 */
class MarkReviewedShortcutTest {

    private val pluginXml: String =
        checkNotNull(javaClass.getResource("/META-INF/plugin.xml")) { "plugin.xml not on the test classpath" }
            .readText()

    private val markReviewedBlock: String =
        Regex("""<action id="ReviewQueue\.MarkReviewed".*?</action>""", RegexOption.DOT_MATCHES_ALL)
            .find(pluginXml)
            ?.value
            ?: error("no ReviewQueue.MarkReviewed action block in plugin.xml")

    private val declarations: List<String> =
        Regex("""<keyboard-shortcut[^/]*/>""").findAll(markReviewedBlock).map { it.value }.toList()

    private fun forKeymap(keymap: String): List<String> =
        declarations.filter { it.contains("""keymap="$keymap"""") }

    private val defaults: List<String> get() = forKeymap("${'$'}default")
    private val mac: List<String> get() = forKeymap("Mac OS X 10.5+")

    private fun chords(decls: List<String>): Set<String> =
        decls.mapNotNull { Regex("""first-keystroke="([^"]+)"""").find(it)?.groupValues?.get(1) }.toSet()

    @Test
    fun `the default keymap binds all four platform-neutral chords`() {
        assertEquals(
            markReviewedBlock,
            setOf("alt shift Z", "alt shift SPACE", "alt shift ENTER", "control shift Z"),
            chords(defaults),
        )
    }

    /**
     * macOS gets Cmd+Shift+Z in place of Ctrl+Shift+Z. The `remove` entry is load-bearing: a plugin
     * `<keyboard-shortcut>` on a child keymap *adds* to what that keymap inherits rather than
     * replacing it, so without the removal macOS would answer to Ctrl+Shift+Z as well — which is
     * `$Redo` there.
     */
    @Test
    fun `macOS swaps the ctrl chord for the cmd chord`() {
        assertTrue(mac.toString(), chords(mac).contains("meta shift Z"))
        val removal = mac.single { it.contains("""remove="true"""") }
        assertTrue(
            "macOS must drop the inherited Ctrl+Shift+Z, which is \$Redo there",
            removal.contains("""first-keystroke="control shift Z""""),
        )
    }

    @Test
    fun `macOS does not also keep the ctrl chord as an active binding`() {
        val active = mac.filterNot { it.contains("""remove="true"""") }
        assertFalse(active.toString(), chords(active).contains("control shift Z"))
    }

    /**
     * Two space chords are bound, and both were confirmed working by hand. The two that are banned
     * are banned for different reasons, which is why this test names them individually rather than
     * rejecting the space bar wholesale:
     *
     * - `control shift SPACE` is SmartTypeCompletion in both bundled keymaps.
     * - `meta shift SPACE` — Cmd+Shift+Space with no other modifier — never reaches the IDE on macOS
     *   once a second input source is installed; the OS claims it for input-source switching.
     *
     * `alt meta shift SPACE` adds Option and is not claimed by the OS, which is what makes it usable
     * where the bare Cmd form is not.
     */
    @Test
    fun `the space chords are exactly the two verified to survive`() {
        assertEquals(
            setOf("alt shift SPACE", "alt meta shift SPACE"),
            chords(declarations).filter { it.contains("SPACE") }.toSet(),
        )
    }

    @Test
    fun `neither banned space chord is bound`() {
        val all = chords(declarations)
        assertFalse("ctrl shift SPACE is SmartTypeCompletion", all.contains("control shift SPACE"))
        assertFalse(
            "bare meta shift SPACE is eaten by macOS input-source switching",
            all.contains("meta shift SPACE"),
        )
    }

    @Test
    fun `no binding targets a keymap the platform will not recognise`() {
        val keymaps = declarations.mapNotNull { Regex("""keymap="([^"]+)"""").find(it)?.groupValues?.get(1) }
        assertEquals(
            "\$default and Mac OS X 10.5+ are the only names in play; " +
                "DefaultKeymap.getDefaultKeymapName() returns the latter verbatim on macOS",
            setOf("${'$'}default", "Mac OS X 10.5+"),
            keymaps.toSet(),
        )
    }

    /**
     * Documents two accepted collisions rather than pretending they are absent.
     *
     * - `control shift Z` is `$Redo` in `keymaps/$default.xml`, and the macOS keymap never redeclares
     *   `$Redo`, so Redo answers to Ctrl+Shift+Z on every platform. macOS avoids the clash by the
     *   removal above; Windows and Linux keep it.
     * - `alt shift ENTER` is `SplitChooser` in `$default`, and the Database/Grid plugin binds it too.
     *
     * Both are tolerable because `MarkReviewedAction.update` enables the action only inside the
     * review diff viewer, whose editors are read-only — so the bundled actions are disabled exactly
     * where this one is live. If an ambiguity popup ever appears, these are the chords to suspect.
     */
    @Test
    fun `the knowingly shared chords are still the ones we think they are`() {
        assertTrue(
            "Ctrl+Shift+Z is shared with \$Redo on Windows and Linux",
            chords(defaults).contains("control shift Z"),
        )
        assertTrue(
            "Alt+Shift+Enter is shared with SplitChooser and the Grid plugin",
            chords(defaults).contains("alt shift ENTER"),
        )
    }
}
