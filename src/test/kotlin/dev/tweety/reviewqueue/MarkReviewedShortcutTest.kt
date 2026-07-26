package dev.tweety.reviewqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Mark Reviewed key binding, and each way of getting it wrong that has already happened.
 *
 * Reads the shipped resource rather than the platform's keymap registry, so it needs no IDE. It
 * asserts on attribute values rather than whole tags: `patchPluginXml` re-serialises the descriptor
 * and inserts a space before `/>`, and a test that pins that whitespace fails on a serializer change
 * for a reason that has nothing to do with the shortcut.
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

    private val shortcut: String =
        Regex("""<keyboard-shortcut[^/]*/>""")
            .find(markReviewedBlock)
            ?.value
            ?: error("no keyboard-shortcut in:\n$markReviewedBlock")

    @Test
    fun `one declaration on the default keymap, which every other keymap inherits`() {
        assertEquals(
            "targeting a keymap by name is what silently dropped the binding before; a single " +
                "\$default entry is inherited by the macOS keymap and needs no name to match",
            1,
            Regex("<keyboard-shortcut").findAll(markReviewedBlock).count(),
        )
        assertTrue(shortcut, shortcut.contains("""keymap="${'$'}default""""))
    }

    @Test
    fun `the chord is alt shift V`() {
        assertTrue(shortcut, shortcut.contains("""first-keystroke="alt shift V""""))
    }

    /**
     * `ctrl shift SPACE` is SmartTypeCompletion in both bundled keymaps, and on macOS the whole
     * Cmd+Space family is consumed by input-source switching once more than one source is installed
     * — the OS takes the key before the IDE sees it, which no amount of keymap wrangling fixes.
     */
    @Test
    fun `no binding uses the space key`() {
        // Scoped to the binding, not the whole action block: the block carries a comment that names
        // SPACE to explain why it is avoided, and that comment must not fail this test.
        assertFalse(
            "SPACE chords have failed twice: SmartTypeCompletion, then macOS input-source switching",
            shortcut.contains("SPACE"),
        )
    }

    @Test
    fun `no keymap-specific targeting and no replace-all`() {
        assertFalse(
            "a keymap name the platform does not recognise is ignored without a warning",
            markReviewedBlock.contains("""keymap="Mac OS X"""),
        )
        assertFalse(
            "replace-all exists only to suppress an inherited chord; with one declaration there is " +
                "nothing to suppress",
            markReviewedBlock.contains("replace-all"),
        )
    }
}
