package dev.tweety.reviewqueue.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl

class IdeLayoutControllerTest : HeavyPlatformTestCase() {

    /**
     * The platform's headless tool window hard-codes `isVisible = false` and no-ops `show`/`hide`,
     * so asserting against it would only test the mock. This one actually tracks visibility, which
     * is what lets these tests say whether a window was really reopened rather than merely dropped
     * from the record.
     */
    private class RecordingToolWindow(project: Project, private var visible: Boolean) :
        ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
        var shows = 0
        var hides = 0
        override fun isVisible() = visible
        override fun show(runnable: Runnable?) { shows++; visible = true }
        override fun hide(runnable: Runnable?) { hides++; visible = false }
    }

    private class RecordingToolWindowManager(private val project: Project) :
        ToolWindowHeadlessManagerImpl(project) {
        private val windows = mutableMapOf<String, RecordingToolWindow>()
        fun register(id: String, visible: Boolean): RecordingToolWindow =
            RecordingToolWindow(project, visible).also { windows[id] = it }
        override fun getToolWindow(id: String?): ToolWindow? = windows[id]
    }

    private lateinit var manager: RecordingToolWindowManager

    override fun setUp() {
        super.setUp()
        manager = RecordingToolWindowManager(project)
        project.replaceService(ToolWindowManager::class.java, manager, testRootDisposable)
    }

    private fun controllerWithRecord(vararg ids: String): IdeLayoutController {
        val controller = IdeLayoutController.getInstance(project)
        val state = IdeLayoutController.State()
        state.hiddenByReview = ids.toMutableList()
        controller.loadState(state)
        return controller
    }

    fun testLoadedStateIsReturnedByGetState() {
        val controller = controllerWithRecord("Project", "Review Queue")

        assertEquals(listOf("Project", "Review Queue"), controller.state.hiddenByReview)
    }

    fun testHideRecordsAndHidesOnlyTheVisibleManagedWindows() {
        val projectWindow = manager.register("Project", visible = true)
        val queueWindow = manager.register("Review Queue", visible = false)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "only the windows that were actually visible may be recorded, or restore reopens a " +
                "window the user had closed",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertFalse(projectWindow.isVisible)
        assertEquals(0, queueWindow.hides)
    }

    fun testRestoreReopensAndThenForgetsTheWindowsItReopened() {
        val window = manager.register("Restorable", visible = false)
        val controller = controllerWithRecord("Restorable")

        controller.restore()

        assertTrue(
            "restore must actually reopen the window, not merely forget it",
            window.isVisible,
        )
        assertEquals(1, window.shows)
        assertTrue(
            "restore must forget what it reopened, or a later restore would reopen it again",
            controller.state.hiddenByReview.isEmpty(),
        )
    }

    fun testRestoreKeepsIdsThatDidNotResolveToARegisteredWindow() {
        val controller = controllerWithRecord("NotRegisteredYet")

        controller.restore()

        // Tool-window registration is not guaranteed complete when the post-startup restore runs.
        // Dropping an id that did not resolve would leave that window hidden forever.
        assertEquals(
            "an id that did not resolve must stay on the record, not be silently forgotten",
            listOf("NotRegisteredYet"),
            controller.state.hiddenByReview,
        )
    }

    /**
     * A leftover record must be reclaimed, not treated as a reason to refuse to hide.
     *
     * The old contract here was an early return that left the stale record standing. Combined with
     * [IdeLayoutController.restore] keeping ids it could not resolve, that latched: one unresolved
     * id — a window not yet registered at post-startup, or a stale id in `workspace.xml` — would
     * make the record permanently non-empty and silently stop Start Review from hiding anything
     * ever again.
     */
    fun testHideReclaimsALeftoverRecordInsteadOfRefusingToHide() {
        val leftover = manager.register("Leftover", visible = false)
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord("Leftover")

        controller.hideForReview()

        assertTrue(
            "a leftover from an earlier session must be reopened, not left hidden forever",
            leftover.isVisible,
        )
        assertEquals(
            "the record must describe this hide, not carry the stale contents forward",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertFalse("hiding must still happen; a leftover record may not veto it", projectWindow.isVisible)
    }

    /**
     * The case the removed early return was written for: hiding twice with nothing restored in
     * between must not overwrite the record with an empty one, which would leave both windows
     * hidden with nothing naming them.
     */
    fun testASecondHideDoesNotStrandTheWindowsTheFirstOneHid() {
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()
        controller.hideForReview()

        assertEquals(
            "the second hide must still name the window it left hidden",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertFalse(projectWindow.isVisible)

        controller.restore()

        assertTrue("the window must still be reopenable after a double hide", projectWindow.isVisible)
        assertTrue(controller.state.hiddenByReview.isEmpty())
    }
}
