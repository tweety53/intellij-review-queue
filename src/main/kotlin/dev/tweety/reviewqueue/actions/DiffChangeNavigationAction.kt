package dev.tweety.reviewqueue.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import javax.swing.Icon
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * Forwards to one of the diff viewer's own change-navigation actions, so a review pass can carry its
 * own shortcut for it.
 *
 * The delegation exists for one reason: a plugin descriptor cannot add a keyboard shortcut to an
 * action it does not declare — `ActionPluginRegistrarKt` rejects an `<action>` element with no
 * `class` attribute. Binding a chord to the platform's `NextDiff`/`PreviousDiff` therefore has to go
 * through an action of our own. The alternative is asking every user to add the binding by hand in
 * Settings → Keymap, which is also how to override what ships here.
 *
 * Gated to the review diff like every other action in this plugin, so these chords stay inert
 * elsewhere and the platform's own F7 / Shift+F7 keep working everywhere as before.
 */
sealed class DiffChangeNavigationAction(
    text: String,
    description: String,
    icon: Icon,
    private val delegateId: String,
) : AnAction(text, description, icon) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    /**
     * Enablement mirrors this plugin's other diff actions and deliberately does **not** mirror the
     * delegate's own state. Asking the platform action whether it can act would mean running its
     * update against our event, and it no-ops harmlessly at the first or last change anyway.
     */
    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null &&
            ReviewSessionService.getInstance(project).isActive &&
            e.getData(DiffDataKeys.DIFF_CONTEXT) != null &&
            ActionManager.getInstance().getAction(delegateId) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val delegate = ActionManager.getInstance().getAction(delegateId) ?: return
        ActionUtil.performAction(delegate, e)
    }
}

/** Moves to the previous changed region within the file on screen. */
class PreviousChangeAction : DiffChangeNavigationAction(
    "Previous Change",
    "Move to the previous changed region in the file on screen",
    AllIcons.Actions.PreviousOccurence,
    "PreviousDiff",
)

/** Moves to the next changed region within the file on screen. */
class NextChangeAction : DiffChangeNavigationAction(
    "Next Change",
    "Move to the next changed region in the file on screen",
    AllIcons.Actions.NextOccurence,
    "NextDiff",
)
