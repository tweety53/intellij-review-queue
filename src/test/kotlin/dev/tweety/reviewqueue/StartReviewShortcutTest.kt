package dev.tweety.reviewqueue

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.testFramework.HeavyPlatformTestCase
import javax.swing.KeyStroke

/**
 * Pins Start Review's chord **together with its known overlap**, so a platform change in either
 * direction fails here instead of passing silently.
 *
 * Deliberately not shaped like `MarkReviewedShortcutTest`, which reads only the shipped `plugin.xml`
 * and needs no IDE. That shape is exactly why an earlier draft of this change chose `Alt+Shift+R`
 * without noticing `RerunTests` already owns it on every keymap.
 *
 * The keymap lookups go through [keymap], which **fails** rather than returning when a keymap is
 * missing. The plan's original `?: return` would have made every assertion below conditional on an
 * environment detail: if the keymaps did not resolve headlessly the whole class would pass while
 * asserting nothing. They do resolve under `HeavyPlatformTestCase` — verified by running this test
 * against an unmodified `plugin.xml` and watching the shortcut assertions themselves fail — and this
 * helper is what keeps that true if it ever changes.
 */
class StartReviewShortcutTest : HeavyPlatformTestCase() {

    private val chord: KeyStroke = KeyStroke.getKeyStroke("meta alt shift R")

    private fun keymap(name: String): Keymap =
        checkNotNull(KeymapManagerEx.getInstanceEx().getKeymap(name)) {
            "keymap '$name' did not resolve; this test asserts nothing without it"
        }

    /**
     * `getActionIdList` rather than the `getActionIds` array overload: the latter is deprecated, and
     * this project's standing rule is to fix deprecated usage rather than suppress the warning.
     */
    private fun boundIds(keymapName: String): Set<String> =
        keymap(keymapName).getActionIdList(KeyboardShortcut(chord, null)).toSet()

    fun testStartReviewIsBoundOnTheMacKeymap() {
        val ids = boundIds("Mac OS X 10.5+")

        assertTrue(
            "Start Review must answer to the chosen chord on macOS",
            "ReviewQueue.StartReview" in ids,
        )
        assertEquals(
            "KAN-5 accepted the overlap on this chord knowingly. The design named ForceRefresh, the " +
                "EmptyAction shortcut holder; querying the real keymap shows the holder has two " +
                "borrowers registered against the same chord as well. None can be unbound from our " +
                "side — remove=\"true\" strips a shortcut from our own action only. The set is pinned " +
                "exactly so that a change in either direction — a new id joining, or any of these " +
                "leaving — fails here instead of passing silently.",
            setOf(
                "ReviewQueue.StartReview",
                "ForceRefresh",
                "ReloadScriptConfiguration",
                "DatabaseView.ForceRefresh",
            ),
            ids,
        )
    }

    /**
     * Two assertions, because the first alone maps onto the spec scenario without covering it.
     *
     * Querying by *chord* only proves that nothing else claims `meta alt shift R` on `$default`. The
     * scenario is about the **action**: "the default keymap is queried for the Start Review action →
     * it has no keyboard shortcut". Adding `<keyboard-shortcut keymap="$default" first-keystroke="alt
     * shift R"/>` to the StartReview block would violate that, hand Windows and Linux a binding that
     * collides with `RerunTests` — the exact mistake this change already made once — and leave the
     * chord-only check untouched, because no other test looks at Start Review on `$default`.
     */
    fun testTheChordIsMacOnly() {
        assertFalse(
            "the Cmd+Option+Shift cluster is macOS-only: meta is the Windows key on \$default, so " +
                "Start Review ships shortcutless there and is reached from Tools > Review Queue",
            "ReviewQueue.StartReview" in boundIds("\$default"),
        )
        assertEquals(
            "Start Review must carry no shortcut at all on \$default, not merely a different one",
            emptyList<Shortcut>(),
            keymap("\$default").getShortcuts("ReviewQueue.StartReview").toList(),
        )
    }

    fun testTheActionIsRegistered() {
        assertNotNull(ActionManager.getInstance().getAction("ReviewQueue.StartReview"))
        assertNotNull(
            "End Review must keep a registry home after ReviewQueue.Toolbar is deleted",
            ActionManager.getInstance().getAction("ReviewQueue.EndReview"),
        )
        assertNotNull(
            "the shared scope group must be resolvable, since DiffScopeAction looks it up by id",
            ActionManager.getInstance().getAction("ReviewQueue.ScopeMenu"),
        )
    }

    /**
     * The menu group's `<reference>` children resolve only if the referenced ids were already
     * registered when the group was read. An unresolvable reference is logged and dropped, not
     * fatal — so without this the group could ship missing entries and every other test here would
     * still pass. Asserting the child ids is what makes the declaration order load-bearing.
     */
    fun testTheToolsMenuGroupResolvesEveryChild() {
        val manager = ActionManager.getInstance()
        val menu = manager.getAction("ReviewQueue.Menu") as? DefaultActionGroup
        assertNotNull("Tools > Review Queue must be registered as a group", menu)

        // The separator is rendered as an id-less child, so `mapNotNull { getId(it) }` would drop it
        // silently — and deleting <separator/> would merge Refresh and Reset All into the Start Review
        // block with nothing failing. Mapping it to a placeholder keeps its position asserted.
        val children = menu!!.getChildren(manager).map { manager.getId(it) ?: if (it is Separator) "—" else "?" }

        assertEquals(
            "every command the deleted tool window carried must be present, in menu order, with the " +
                "separator dividing the per-review commands from the queue-wide ones",
            listOf(
                "ReviewQueue.ScopeMenu",
                "ReviewQueue.StartReview",
                "ReviewQueue.ShowFileList",
                "—",
                "ReviewQueue.Refresh",
                "ReviewQueue.ResetAll",
            ),
            children,
        )
    }

    /**
     * `popup="true"` is what makes each group render as a submenu. Without it the children are
     * inlined flat into the parent, which is a silent presentation bug: the actions all still work,
     * so nothing else in this suite would notice.
     */
    fun testBothGroupsRenderAsNamedSubmenus() {
        val manager = ActionManager.getInstance()
        listOf("ReviewQueue.Menu" to "Review Queue", "ReviewQueue.ScopeMenu" to "Scope")
            .forEach { (id, text) ->
                val group = manager.getAction(id) as DefaultActionGroup
                assertTrue("$id must be popup=true or its children inline flat", group.isPopup)
                assertEquals(
                    "$id must carry a display text or the submenu renders unnamed",
                    text,
                    group.templatePresentation.text,
                )
            }
    }
}
