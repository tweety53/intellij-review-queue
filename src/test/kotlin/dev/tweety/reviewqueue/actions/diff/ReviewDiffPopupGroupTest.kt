package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.HeavyPlatformTestCase

class ReviewDiffPopupGroupTest : HeavyPlatformTestCase() {

    private fun childIds(): List<String?> = childActions().map { ActionManager.getInstance().getId(it) }

    private fun childActions(event: AnActionEvent? = null): List<AnAction> {
        val group = ActionManager.getInstance().getAction("ReviewQueue.DiffPopup")
        require(group is ReviewDiffPopupGroup) { "ReviewQueue.DiffPopup must be registered" }
        return group.getChildren(event).toList()
    }

    /**
     * A null event is what every other test in this file passes — `sessionControls` short-circuits
     * to `emptyList()` without a project, so that path can never exercise the actual session-control
     * contract. This builds a project-bearing event headlessly, the same way `DiffScopeActionTest`
     * and `SetScopeActionTest` do, so the delta spec's "session controls sit below a separator"
     * scenario is asserted for real rather than always trivially true.
     */
    private fun eventWithProject(): AnActionEvent {
        val group = ActionManager.getInstance().getAction("ReviewQueue.DiffPopup")
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        return AnActionEvent.createEvent(group, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
    }

    fun testMarkReviewedLeadsTheMenu() {
        assertEquals("ReviewQueue.MarkReviewed", childIds().first())
    }

    fun testEveryPerFileActionIsOffered() {
        val ids = childIds()
        listOf(
            "ReviewQueue.MarkReviewed",
            "ReviewQueue.ToggleReviewed",
            "ReviewQueue.ShowFileList",
            "ReviewQueue.PreviousFile",
            "ReviewQueue.NextFile",
            "ReviewQueue.PreviousChange",
            "ReviewQueue.NextChange",
        ).forEach { assertTrue("$it must be in the popup", ids.contains(it)) }
    }

    /**
     * The point of composing the platform tail from the live group rather than enumerating it: an
     * entry contributed by another plugin has to survive. Annotate comes from VcsActions.xml.
     */
    fun testAContributedPlatformEntrySurvives() {
        val platform = ActionManager.getInstance().getAction("Diff.EditorPopupMenu")
        val contributed = (platform as com.intellij.openapi.actionSystem.ActionGroup)
            .getChildren(null)
            .map { ActionManager.getInstance().getId(it) }
            .filterNotNull()
            .filter { it != "CompareClipboardWithSelection" }
        val ids = childIds()
        contributed.forEach { assertTrue("$it must survive into the review popup", ids.contains(it)) }
    }

    fun testCompareWithClipboardIsRemoved() {
        assertFalse(
            "Compare with Clipboard has no use during a review pass",
            childIds().contains("CompareClipboardWithSelection"),
        )
    }

    /**
     * The scenario every other test in this file cannot reach: with a null event, `sessionControls`
     * short-circuits to `emptyList()` before the five session controls are ever built, so a regression
     * that broke `ReviewSessionService.sessionControls` would still pass `getChildren(null)`. A
     * project-bearing event (see [eventWithProject]) exercises the real path.
     */
    fun testTheSessionControlsAreOfferedBelowTheFirstSeparator() {
        val children = childActions(eventWithProject())
        val separatorIndex = children.indexOfFirst { it is Separator }
        assertTrue("the popup must contain a separator before the session controls", separatorIndex >= 0)

        val afterSeparator = children.drop(separatorIndex + 1)
        val expected = listOf(
            DiffScopeAction::class.java,
            DiffStartReviewAction::class.java,
            DiffEndReviewAction::class.java,
            DiffRefreshQueueAction::class.java,
            DiffResetAllAction::class.java,
        )
        assertEquals(
            "Scope, Start Review, End Review, Refresh and Reset All must follow the separator, in order",
            expected,
            afterSeparator.take(expected.size).map { it::class.java },
        )
    }

    /**
     * With a null event, `sessionControls` short-circuits to `emptyList()` (see
     * [testTheSessionControlsAreOfferedBelowTheFirstSeparator]'s KDoc), so the trailing separator
     * between the (empty) session controls and the platform tail must not render — otherwise a
     * null-project event shows two adjacent separators with nothing between them.
     */
    fun testANullEventDoesNotRenderAdjacentSeparators() {
        val children = childActions(null)
        val adjacentSeparators = children.zipWithNext().count { (first, second) ->
            first is Separator && second is Separator
        }
        assertEquals(
            "no two separators should ever be adjacent, even when the session controls are empty",
            0,
            adjacentSeparators,
        )
    }
}
