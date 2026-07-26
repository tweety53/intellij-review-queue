package dev.tweety.reviewqueue.ui

import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.ui.components.JBList
import dev.tweety.reviewqueue.core.ReviewFileRow
import dev.tweety.reviewqueue.model.ReviewKey
import javax.swing.JLabel

/**
 * The file-list popup's row label, which is a **repository-controlled string rendered by Swing**.
 *
 * `SimpleListCellRenderer` is a `JBLabel`, and Swing's label renders its text as HTML whenever that text
 * begins with `<html>`. A repo-relative path is put there verbatim, and a tracked file may legally be
 * named `<html><img src="http://…">` on every platform this plugin runs on — so listing that file used to
 * be enough to make the IDE issue an outbound request to a host the file name chose, from a plugin whose
 * one invariant is that it only ever reads. Nobody has to click anything: drawing the row is the trigger.
 *
 * The popup itself is still a Gate C check, because `showInBestPositionFor` needs a live component. What
 * is machine-checked here is the renderer, which is where the decision is made.
 */
class ReviewFileListPopupTest : HeavyPlatformTestCase() {

    private fun render(label: String): String {
        val row = ReviewFileRow(ReviewKey("/repo", label), label, isReviewed = false, isCurrent = false)
        val component = ReviewFileListPopup.rowRenderer()
            .getListCellRendererComponent(JBList(row), row, 0, false, false)
        return (component as JLabel).text
    }

    fun testAFileNameThatBeginsWithAnHtmlTagIsNotRenderedAsMarkup() {
        val text = render("""<html><img src="http://example.invalid/beacon.png">""")

        assertFalse(
            "no markup from a file name may survive into the label, or drawing the row fetches a " +
                "URL the repository chose: got $text",
            text.contains("<img"),
        )
        assertTrue(
            "the name must still be legible to the reviewer: got $text",
            text.contains("beacon.png"),
        )
    }

    /**
     * The ordinary case, asserted so the escaping cannot be "fixed" by mangling every path. Pinned
     * exactly, because the label is deliberately put into HTML mode — a bare escaped string would show
     * the reviewer literal `&lt;` entities.
     */
    fun testAnOrdinaryPathIsShownUnchanged() {
        assertEquals("<html>src/main/kotlin/Main.kt</html>", render("src/main/kotlin/Main.kt"))
    }

    /** `&` is legal in a filename and must read as `&`, not as the start of an entity. */
    fun testAnAmpersandInAPathIsShownAsItself() {
        val text = render("docs/a&b.md")

        assertEquals("<html>docs/a&amp;b.md</html>", text)
    }
}
