package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * The review diff's context menu: the per-file actions, the session controls, then whatever the
 * platform puts on a diff's menu — minus Compare with Clipboard.
 *
 * **The platform tail is composed, never enumerated.** `Diff.EditorPopupMenu` is contributed to by
 * `VcsActions.xml` (Annotate with Git Blame), by `intellij.platform.collaborationTools` (review
 * comments) and by the Ultimate customization layer. A hand-written replacement list would silently
 * drop all three, and would keep dropping whatever a future IDE adds to that group. Reading the live
 * children and filtering one id out costs a lookup and stays correct.
 *
 * **The session controls sit below the per-file actions on purpose.** `Reset All` clears every mark
 * in the project. It must not occupy the position a slipped click lands on, directly under the
 * pointer that opened the menu.
 */
class ReviewDiffPopupGroup : DefaultActionGroup() {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = ActionManager.getInstance()
        val perFile = PER_FILE_IDS.mapNotNull {
            manager.getAction(it) ?: run {
                thisLogger().warn("Review Queue: per-file popup action '$it' did not resolve; dropped")
                null
            }
        }
        val session = e?.project?.let { sessionControls(it) } ?: emptyList()
        val platform = platformTail(manager)
        val trailingSeparator = if (session.isNotEmpty()) listOf(Separator.getInstance()) else emptyList()
        return (perFile + Separator.getInstance() + session + trailingSeparator + platform)
            .toTypedArray()
    }

    /**
     * The same confirming instances the diff toolbar builds, so a menu press and a toolbar press ask
     * the same question. Read straight off [ReviewSessionService.sessionControls] — a named contract
     * rather than a scan of [ReviewSessionService.diffActions] for its `Separator`, which is placed
     * there for the toolbar's own layout reasons and was never meant as a lookup marker for this menu.
     */
    private fun sessionControls(project: Project): List<AnAction> {
        val controls = ReviewSessionService.getInstance(project).sessionControls
        if (controls.isEmpty()) {
            thisLogger().warn("Review Queue: ReviewSessionService.sessionControls was empty; " +
                "popup will offer no session controls")
        }
        return controls
    }

    /**
     * `ActionGroup.getChildren(AnActionEvent?)` is `@ApiStatus.OverrideOnly` on IU-2026.2 — it
     * exists for a group to implement, not for outside code to call, and `verifyPlugin` fails on
     * the call regardless of which concrete subtype it is invoked on. `DefaultActionGroup` declares
     * a second, `final` `getChildren(ActionManager)` overload instead: `final` means it cannot be
     * part of the override contract, so it carries no such restriction, and it is what
     * `Diff.EditorPopupMenu` (a plain `<group>` with no `class=`, i.e. a `DefaultActionGroup`)
     * resolves to at runtime. Verified by disassembling `intellij.platform.editor.ui` and
     * `intellij.platform.ide` for IU-2026.2 after the brief's original single-arg call failed
     * `verifyPlugin` with an override-only violation.
     *
     * The `as? DefaultActionGroup ?: return emptyList()` fallback is silent by construction — see
     * `DiffScopeAction.createPopupActionGroup`'s KDoc for why that pattern is used elsewhere in this
     * package — but unlike that one it drops the platform tail (Annotate, review comments, Ultimate's
     * contributions) for the *entire* menu, so a failure here is logged rather than left to a test
     * that would otherwise be the only witness.
     */
    private fun platformTail(manager: ActionManager): List<AnAction> {
        val group = manager.getAction(PLATFORM_GROUP_ID) as? DefaultActionGroup ?: run {
            thisLogger().warn(
                "Review Queue: platform group '$PLATFORM_GROUP_ID' did not resolve to a " +
                    "DefaultActionGroup; platform tail (Annotate, review comments, etc.) dropped",
            )
            return emptyList()
        }
        return group.getChildren(manager).filter { manager.getId(it) != CLIPBOARD_ACTION_ID }
    }

    companion object {
        /**
         * Resolved by id rather than constructed, because that is what makes each menu entry carry
         * its keyboard shortcut — the same reason `ReviewSessionService.diffActions` resolves the
         * navigation actions by id.
         *
         * Lists 7 ids, 2 more than `ReviewSessionService.diffActions`' per-file half (5): this menu
         * also offers `PreviousChange`/`NextChange`, which have no toolbar button. The overlap is
         * deliberate WET, not an oversight — see `ReviewSessionService.sessionControls`'s KDoc for
         * the other side of this cross-reference.
         */
        private val PER_FILE_IDS = listOf(
            "ReviewQueue.MarkReviewed",
            "ReviewQueue.ToggleReviewed",
            "ReviewQueue.ShowFileList",
            "ReviewQueue.PreviousFile",
            "ReviewQueue.NextFile",
            "ReviewQueue.PreviousChange",
            "ReviewQueue.NextChange",
        )

        /** Verified against `PlatformActions.xml:452` for IU-2026.2. */
        private const val PLATFORM_GROUP_ID = "Diff.EditorPopupMenu"
        private const val CLIPBOARD_ACTION_ID = "CompareClipboardWithSelection"
    }
}
