package dev.tweety.reviewqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Mark Reviewed key bindings.
 *
 * A `keymap` name the platform does not recognise is ignored without a warning, so a typo here
 * produces exactly the bug this guards: a shortcut that silently never fires. Reads the shipped
 * resource rather than the platform's keymap registry, so it needs no IDE.
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

    @Test
    fun `no binding uses the chord that collides with smart type completion`() {
        assertFalse(
            "ctrl shift SPACE is SmartTypeCompletion in both \$default and the macOS keymap",
            pluginXml.contains("\"ctrl shift SPACE\""),
        )
    }

    @Test
    fun `the default keymap binds a chord that is free everywhere`() {
        assertTrue(
            markReviewedBlock,
            markReviewedBlock.contains(
                """<keyboard-shortcut keymap="${'$'}default" first-keystroke="ctrl alt shift SPACE" />"""
            ),
        )
    }

    @Test
    fun `macOS binds cmd shift space and drops the inherited chord`() {
        val mac = Regex("""<keyboard-shortcut keymap="Mac OS X 10\.5\+"[^/]*/>""")
            .find(markReviewedBlock)
            ?.value
            ?: error("no macOS keyboard-shortcut in:\n$markReviewedBlock")
        assertTrue(mac, mac.contains("""first-keystroke="meta shift SPACE""""))
        assertTrue("replace-all is what stops \$default's chord being inherited on macOS",
            mac.contains("""replace-all="true""""))
    }

    @Test
    fun `mark reviewed declares exactly the two bindings`() {
        assertEquals(2, Regex("<keyboard-shortcut").findAll(markReviewedBlock).count())
    }
}
