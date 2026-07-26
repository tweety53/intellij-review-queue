package dev.tweety.reviewqueue

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.actions.diff.DiffScopeAction

/**
 * Facts about `plugin.xml` that code elsewhere in the plugin depends on but never states.
 *
 * Every assertion here closes a gap of the same shape: a declaration file and a prose argument that agree
 * only because nobody has changed either. When they stop agreeing, the failure is silent — a notification
 * that never reaches the user, a safety argument that has quietly become false, or the tool window this
 * whole change exists to delete quietly coming back.
 */
class PluginRegistrationTest : HeavyPlatformTestCase() {

    private val pluginXml: String =
        checkNotNull(javaClass.getResource("/META-INF/plugin.xml")) { "plugin.xml not on the test classpath" }
            .readText()

    /**
     * The change's headline requirement — *"no tool window with id `Review Queue` is registered"* — which
     * was asserted by nothing at all. Its only evidence was a grep recorded in a ledger, and a grep run
     * once is not a gate: re-adding a `<toolWindow>` to `plugin.xml` would have left the whole suite green
     * while restoring the entire surface KAN-5 exists to delete.
     *
     * Checked two ways, for the same reason the `DiffScopeAction` check below is. The descriptor check is
     * the complete answer for declarative registration — `plugin.xml` is the plugin's only descriptor and
     * carries no `xi:include` — but it is a text match on the extension-point name rather than a statement
     * about the running IDE. The live check reads the tool windows the platform has actually registered,
     * which is what `ReviewLayoutRestorer`'s pruning of the legacy id and every "the pass hides only the
     * Project window" claim actually rest on.
     *
     * The live half is **deliberately not** `ToolWindowManager.getToolWindow("Review Queue")`, which is the
     * obvious spelling and is vacuous here: `HeavyPlatformTestCase` installs a headless manager that
     * resolves *no* id at all — `getToolWindow("Project")` is null too — so that assertion would hold
     * against a re-registered window exactly as happily as against a deleted one. Verified by asserting
     * `Project` resolves and watching it fail. [ToolWindowEP] is the registry the manager is built from and
     * is populated headlessly, so it can distinguish the two.
     *
     * The extension list is asserted non-empty first, so that an environment which registered nothing
     * cannot make the filter pass for the wrong reason.
     */
    fun testThePluginRegistersNoToolWindow() {
        assertFalse(
            "plugin.xml must declare no toolWindow extension: the panel, its tree and its factory were " +
                "deleted by KAN-5, and the workflow now runs from the Tools menu, the keymap and the " +
                "diff toolbar",
            pluginXml.contains("toolWindow"),
        )

        val registered = ToolWindowEP.EP_NAME.extensionList
        assertTrue(
            "the platform's own tool windows must be registered, or this filter asserts nothing",
            registered.isNotEmpty(),
        )
        assertEquals(
            "no tool window may be registered under the plugin's old id: persisted layout state naming " +
                "it is pruned on load precisely because it can never be resolved again",
            emptyList<String>(),
            registered.map { it.id }.filter { it == "Review Queue" },
        )
    }

    /**
     * Three call sites hardcode the string `Review Queue` — `CompletionNotifier`, `ScopeErrorNotifier`
     * and `QueueNotices` — and `NotificationGroupManager.getNotificationGroup` returns null for an id it
     * does not know. Renaming or dropping the `<notificationGroup>` therefore does not fail a build; it
     * fails every balloon the plugin has, at runtime, and the removal of the tool window made those
     * balloons the *only* way a failed root or an empty scope is reported.
     */
    fun testTheReviewQueueNotificationGroupIsRegistered() {
        assertNotNull(
            "the notification group every notifier looks up by name must exist",
            NotificationGroupManager.getInstance().getNotificationGroup("Review Queue"),
        )
    }

    /**
     * `DiffScopeAction` reads `ReviewQueueService` from `update()`, which `GitRoots` exists specifically
     * to avoid. Its KDoc argues that this is safe *because* the action is constructed only by
     * `ReviewSessionService.diffActions`, after the service is provably already built — and every clause
     * of that argument rests on the action being unregistered, so that Find Action, action search and the
     * keymap dialog never call `update()` on it. Registering it would construct the queue service from a
     * keystroke in the Find Action dialog, on the EDT, in a project that may have no git root at all.
     *
     * Asserted twice, and the two are **not** independent in the way an earlier version of this KDoc
     * claimed. The descriptor check is the load-bearing one and is complete for declarative registration:
     * it matches the class name under any id, and `plugin.xml` is the plugin's only descriptor — no
     * `xi:include`, and nothing in `src/main` calls `ActionManager.registerAction`. The registry walk is
     * narrower than "a second independent way": it scans the `ReviewQueue` id prefix, so a runtime
     * `registerAction("Foo", DiffScopeAction())` under a foreign id would escape it. What it does add is
     * the behavioural half — the action is absent from the registry the Find Action dialog and the keymap
     * UI actually read — and it is what fails if the class is ever registered under one of our own ids.
     */
    fun testTheDiffToolbarsScopeComboIsNotRegistered() {
        assertFalse(
            "plugin.xml must not declare DiffScopeAction: it is a combo box with no meaning outside " +
                "the review diff toolbar, and its update() is only safe where the toolbar constructs it",
            pluginXml.contains("DiffScopeAction"),
        )

        val manager = ActionManager.getInstance()
        val ids = manager.getActionIdList("ReviewQueue")
        assertTrue("the plugin's own ids must resolve, or this walk asserts nothing", ids.isNotEmpty())
        assertEquals(
            "no registered ReviewQueue action may be a DiffScopeAction",
            emptyList<String>(),
            ids.filter { manager.getAction(it) is DiffScopeAction },
        )
    }

    /**
     * The same argument covers the four confirming session copies, for a second reason recorded in
     * `ReviewSessionService.diffActions`: registering them would list eight entries in Find Action for
     * four commands, half confirming and half not. Kept in this test rather than a separate one because
     * it is one line of the same declaration and fails for the same edit.
     */
    fun testNoneOfTheDiffToolbarsOwnActionsAreRegistered() {
        listOf(
            "DiffStartReviewAction",
            "DiffEndReviewAction",
            "DiffRefreshQueueAction",
            "DiffResetAllAction",
        ).forEach { className ->
            assertFalse(
                "$className is the confirming copy of a registered command; registering it would " +
                    "duplicate that command in Find Action",
                pluginXml.contains(className),
            )
        }
    }
}
