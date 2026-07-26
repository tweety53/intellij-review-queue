package dev.tweety.reviewqueue.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.queue.StagedQueueFixture
import dev.tweety.reviewqueue.ui.RecordingDiffPresenter

/**
 * Pins the shared prompt → confirm → switch rule, which is the only thing keeping the Tools menu and
 * the diff toolbar from asking differently.
 *
 * The confirmation is intercepted through `TestDialogManager`, so "no dialog was shown" is an
 * observation about the real `confirmed()` call rather than about a stub.
 */
class SetScopeActionTest : HeavyPlatformTestCase() {

    private val prompts = mutableListOf<String>()
    private var answer = Messages.YES

    /** Supplies a scope without a dialog, so the base class's rule is what is under test. */
    private class FixedScopeAction(private val scope: ReviewScope?) : SetScopeAction("Fixed") {
        override fun promptScope(project: Project) = scope
    }

    override fun setUp() {
        super.setUp()
        TestDialogManager.setTestDialog(
            TestDialog { message ->
                prompts += message
                answer
            },
        )
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    private fun perform(action: FixedScopeAction) {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        action.actionPerformed(
            AnActionEvent.createEvent(action, context, null, "", ActionUiKind.NONE, null),
        )
    }

    private fun passOnTwoStagedFiles(): Pair<ReviewQueueService, ReviewSessionService> {
        val queue = StagedQueueFixture.stagedQueueOfTwoFiles(project)
        queue.progressRunner = { _, work -> work() }
        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingDiffPresenter()
        session.start()
        assertTrue("a pass must be running, or the confirmation branch is never reached", session.isActive)
        return queue to session
    }

    fun testChoosingAScopeOutsideAPassRecordsItWithoutConfirming() {
        val queue = ReviewQueueService.getInstance(project)

        perform(FixedScopeAction(ReviewScope.CommitRange("HEAD~9", "HEAD")))

        assertEquals("no pass is running, so there is nothing to disrupt", emptyList<String>(), prompts)
        assertEquals(ReviewScope.CommitRange("HEAD~9", "HEAD"), queue.snapshot().scope)
    }

    fun testCancellingThePromptShowsNoConfirmationAndChangesNothing() {
        val (queue, session) = passOnTwoStagedFiles()

        perform(FixedScopeAction(null))

        assertEquals("cancelling the ref prompt must cost no confirmation", emptyList<String>(), prompts)
        assertEquals(ReviewScope.Staged, queue.snapshot().scope)
        assertTrue("the pass must be untouched", session.isActive)
    }

    fun testDecliningTheConfirmationLeavesTheScopeAndThePassAlone() {
        val (queue, session) = passOnTwoStagedFiles()
        val current = session.currentKey()

        answer = Messages.NO
        perform(FixedScopeAction(ReviewScope.CommitRange("HEAD~9", "HEAD")))

        assertEquals("a running pass must be confirmed before it is disrupted", 1, prompts.size)
        assertEquals(ReviewScope.Staged, queue.snapshot().scope)
        assertEquals("the pass stays on the same file", current, session.currentKey())
    }

    /** Prompt-before-confirm is observable only here: the message has to name the resolved scope. */
    fun testTheConfirmationNamesTheResolvedScope() {
        passOnTwoStagedFiles()

        answer = Messages.NO
        perform(FixedScopeAction(ReviewScope.CommitRange("HEAD~9", "HEAD")))

        assertTrue(
            "the confirmation must name the scope being switched to, which is why the prompt runs " +
                "first: got \"${prompts.singleOrNull()}\"",
            prompts.single().contains(ReviewScope.CommitRange("HEAD~9", "HEAD").displayName()),
        )
    }

    /**
     * The only test that covers the action's *wiring* to `switchScope`. `ScopeSwitchTest` calls
     * `switchScope` directly, so without this one, deleting the `session.switchScope(scope)` line from
     * [SetScopeAction] would leave the whole suite green.
     *
     * It switches to a scope that resolves to **nothing** (`HEAD~9` is unresolvable in the fixture's
     * commit-less repo), because that is what makes the switch observable here: an empty new scope ends
     * the pass. An earlier version of this test switched to `Staged` — the scope already in effect —
     * and so asserted only that a prompt appeared and the pass still ran, both of which hold whether or
     * not the switch ever happens.
     */
    fun testAcceptingTheConfirmationActuallyPerformsTheSwitch() {
        val (queue, session) = passOnTwoStagedFiles()

        answer = Messages.YES
        perform(FixedScopeAction(ReviewScope.CommitRange("HEAD~9", "HEAD")))

        assertEquals(1, prompts.size)
        assertEquals(
            "accepting must record the new scope — this is what fails if the action never calls " +
                "switchScope",
            ReviewScope.CommitRange("HEAD~9", "HEAD"),
            queue.snapshot().scope,
        )
        assertTrue("the new scope must genuinely be empty", queue.snapshot().items.isEmpty())
        assertFalse(
            "switching into a scope with nothing unreviewed ends the pass, restoring the layout",
            session.isActive,
        )
    }
}
