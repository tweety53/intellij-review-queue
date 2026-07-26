package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.JPanel

/**
 * The diff toolbar's scope combo — the whole of `review-scope-selection`'s "both surfaces" requirement,
 * and until now asserted by nothing.
 *
 * Two silent failures are what this file is for. Pinning the presentation text to a constant left the
 * combo reading a generic "Scope" with the pass running in some other scope, and every test passed.
 * Mistyping the group id was worse: `createPopupActionGroup`'s `?: DefaultActionGroup()` fallback turns
 * an unresolved id into an **empty popup** rather than an exception, so the only way to choose a scope
 * from inside a pass would quietly stop offering any.
 */
class DiffScopeActionTest : HeavyPlatformTestCase() {

    /**
     * The literal ids `plugin.xml` declares, written out rather than read from the production constant:
     * a test that asked `DiffScopeAction` for its own id would agree with any typo it contained.
     */
    private val scopeGroupId = "ReviewQueue.ScopeMenu"

    private val scopeChildIds = listOf(
        "ReviewQueue.SetStaged",
        "ReviewQueue.SetBranchVsBase",
        "ReviewQueue.SetCommitRange",
    )

    private fun updated(scope: ReviewScope): Presentation {
        ReviewQueueService.getInstance(project).setScope(scope)
        val action = DiffScopeAction()
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        val event =
            AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        action.update(event)
        return event.presentation
    }

    /**
     * Spec scenario "The diff toolbar control names the current scope".
     *
     * Both scopes are checked in one test on purpose: the text has to *follow* the scope. Asserting a
     * single scope's name would still pass for an implementation that hard-coded that one string, which
     * is the closest thing to the mutation this test targets.
     */
    fun testTheComboNamesTheCurrentScopeAndFollowsIt() {
        assertEquals(
            ReviewScope.Staged.displayName(),
            updated(ReviewScope.Staged).text,
        )
        assertEquals(
            "the label must track the scope, not be a fixed string",
            ReviewScope.CommitRange("HEAD~1", "HEAD").displayName(),
            updated(ReviewScope.CommitRange("HEAD~1", "HEAD")).text,
        )
    }

    /** The combo is enabled with a project, which is the reverse of the deleted tool window's rule. */
    fun testTheComboIsEnabledDuringAPass() {
        assertTrue(updated(ReviewScope.Staged).isEnabled)
    }

    /**
     * Spec scenario "Both surfaces offer the same choices" — asserted as identity, not as a matching
     * list. `assertSame` is what makes "there is exactly one set of scope children and one confirm rule"
     * a checked fact rather than a coincidence of two declarations that happen to agree today.
     */
    fun testThePopupIsTheRegisteredScopeGroupItself() {
        val manager = ActionManager.getInstance()
        val registered = manager.getAction(scopeGroupId)
        assertNotNull("$scopeGroupId must be registered, or this test asserts nothing", registered)

        val popup = DiffScopeAction().createPopupActionGroup(JPanel(), DataContext.EMPTY_CONTEXT)

        assertSame(
            "the toolbar must open the Tools menu's own scope group, not a copy of its children",
            registered,
            popup,
        )
        assertEquals(
            "a mistyped id falls back to an empty group, so the children are what proves it resolved",
            scopeChildIds,
            popup.getChildren(manager).map { manager.getId(it) },
        )
    }
}
