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

    private val macShortcut: String =
        Regex("""<keyboard-shortcut keymap="Mac OS X 10\.5\+"[^/]*/>""")
            .find(markReviewedBlock)
            ?.value
            ?: error("no macOS keyboard-shortcut in:\n$markReviewedBlock")

    @Test
    fun `two declarations, one per platform`() {
        assertEquals(2, Regex("<keyboard-shortcut").findAll(markReviewedBlock).count())
        assertTrue(shortcut, shortcut.contains("""keymap="${'$'}default""""))
    }

    @Test
    fun `windows and linux get ctrl shift ENTER`() {
        assertTrue(shortcut, shortcut.contains("""first-keystroke="control shift ENTER""""))
    }

    /**
     * Cmd+Shift+Enter, verified free in both bundled keymaps and in every plugin bundled with
     * 2026.2. `replace-all` is what stops macOS also inheriting the `$default` chord, which is a
     * bundled action there (see [the collision test][ctrlShiftEnterShadowsCompleteCurrentStatement]).
     */
    @Test
    fun `macOS gets cmd shift ENTER and does not inherit the default chord`() {
        assertTrue(macShortcut, macShortcut.contains("""first-keystroke="meta shift ENTER""""))
        assertTrue(
            "without replace-all macOS would also answer to the \$default chord, which is " +
                "EditorCompleteStatement there",
            macShortcut.contains("""replace-all="true""""),
        )
    }

    /**
     * `ctrl shift SPACE` is SmartTypeCompletion in both bundled keymaps, and on macOS the whole
     * Cmd+Space family is consumed by input-source switching once more than one source is installed
     * — the OS takes the key before the IDE sees it, which no amount of keymap wrangling fixes.
     */
    @Test
    fun `no binding uses the space key`() {
        // Scoped to the bindings, not the whole action block: the block carries a comment that names
        // SPACE to explain why it is avoided, and that comment must not fail this test.
        assertFalse(
            "SPACE chords have failed twice: SmartTypeCompletion, then macOS input-source switching",
            shortcut.contains("SPACE") || macShortcut.contains("SPACE"),
        )
    }

    /**
     * Documents a known, accepted collision rather than pretending it is absent.
     *
     * `control shift ENTER` is `EditorCompleteStatement` in `keymaps/$default.xml`, and the macOS
     * keymap does not redeclare that action — so Complete Current Statement answers to
     * Ctrl+Shift+Enter on every platform, macOS included. Mark Reviewed is chosen deliberately
     * anyway: `MarkReviewedAction.update` enables it only inside the review diff viewer, where the
     * editors are read-only and Complete Current Statement is disabled, so the two do not compete
     * for the chord in practice.
     *
     * This test exists so the trade is visible. If an ambiguity popup ever appears on Windows or
     * Linux, this is the reason and the fix is to give `$default` a chord of its own.
     */
    @Test
    fun ctrlShiftEnterShadowsCompleteCurrentStatement() {
        assertTrue(
            "the \$default chord is knowingly shared with EditorCompleteStatement; if this changes, " +
                "revisit the comment on this test",
            shortcut.contains("""first-keystroke="control shift ENTER""""),
        )
    }
}
