package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.JComponent

/**
 * Scope selection from inside the diff, which is what KAN-5 asked for.
 *
 * **Enabled during a pass**, unlike the tool window's old selector. That restriction existed because
 * changing the scope rebuilds the queue underneath the session's fixed key list — but the only diff
 * tab that carries a toolbar is the session's own, so under the old rule this control would have
 * been visible *only* where it was always greyed out. `ReviewSessionService.switchScope` handles the
 * rebuild by restarting the pass in place instead, and `SetScopeAction` confirms first.
 *
 * The popup group is the registered `ReviewQueue.ScopeMenu`, shared with the Tools menu group, so
 * there is exactly one set of scope children and one confirm rule. The null fallback matters: the id
 * is resolved at popup time, and an unregistered or renamed group must open an empty menu rather
 * than throw inside the toolbar.
 *
 * See `DiffStartReviewAction`'s KDoc for why `RightAlignedToolbarAction` is implemented here without
 * actually right-aligning anything.
 */
class DiffScopeAction : ComboBoxAction(), RightAlignedToolbarAction {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    /**
     * Reads the queue service from `update()`, which `GitRoots` exists to avoid elsewhere. Safe here,
     * and only here, because the service is provably already constructed: this toolbar exists at all
     * only because `ReviewSessionService.diffActions` was handed to `presenter.show()`, which happens
     * solely from `start()` / `showCurrent()` — both of which have already read `queue.snapshot()`. So
     * `getInstance` is a lookup, never a construction.
     *
     * The enumeration paths that would break that argument are closed too: this action is constructed
     * directly in `diffActions` and is deliberately **not** registered in `plugin.xml`, so Find Action,
     * action search and the keymap dialog never render it, and `ChainDiffVirtualFile` is an in-memory
     * light file that is not restored across IDE restarts.
     *
     * Do not copy this pattern into a registered action — see `GitRoots` for why.
     */
    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val scope = project?.let { ReviewQueueService.getInstance(it).snapshot().scope }
        e.presentation.text = scope?.displayName() ?: "Scope"
        e.presentation.isEnabled = project != null
    }

    /**
     * `public` rather than `ComboBoxAction`'s `protected`, which is the only reason it is stated
     * explicitly here: `DiffScopeActionTest` asserts on the children this returns. That test exists
     * because the `?: DefaultActionGroup()` fallback is silent — a renamed or mistyped [SCOPE_GROUP_ID]
     * opens an **empty** popup rather than throwing, and every other test in the suite passed with the
     * id misspelled. Widening the visibility changes nothing about how the toolbar calls it, and this
     * action is deliberately unregistered, so nothing else can reach it either way.
     */
    public override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup =
        ActionManager.getInstance().getAction(SCOPE_GROUP_ID) as? DefaultActionGroup
            ?: DefaultActionGroup()

    // Kept private on purpose: `DiffScopeActionTest` asserts against the literal id plugin.xml
    // declares, not against this constant, so a mistyped constant cannot make the test agree with it.
    private companion object {
        const val SCOPE_GROUP_ID = "ReviewQueue.ScopeMenu"
    }
}
