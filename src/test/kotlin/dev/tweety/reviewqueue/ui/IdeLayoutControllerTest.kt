package dev.tweety.reviewqueue.ui

import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.HeavyPlatformTestCase

class IdeLayoutControllerTest : HeavyPlatformTestCase() {

    fun testLoadedStateIsReturnedByGetState() {
        val controller = IdeLayoutController.getInstance(project)
        val state = IdeLayoutController.State()
        state.hiddenByReview = mutableListOf("Project", "Review Queue")

        controller.loadState(state)

        assertEquals(listOf("Project", "Review Queue"), controller.state.hiddenByReview)
    }

    fun testRestoreForgetsTheWindowsItReopened() {
        val manager = ToolWindowManager.getInstance(project)
        manager.registerToolWindow(RegisterToolWindowTask.notClosable("Restorable"))
        val controller = IdeLayoutController.getInstance(project)
        val state = IdeLayoutController.State()
        state.hiddenByReview = mutableListOf("Restorable")
        controller.loadState(state)

        controller.restore()

        assertTrue(
            "restore must forget what it reopened, or a later restore would reopen it again",
            controller.state.hiddenByReview.isEmpty(),
        )
    }

    fun testRestoreKeepsIdsThatDidNotResolveToARegisteredWindow() {
        val controller = IdeLayoutController.getInstance(project)
        val state = IdeLayoutController.State()
        state.hiddenByReview = mutableListOf("NotRegisteredYet")
        controller.loadState(state)

        controller.restore()

        // Tool-window registration is not guaranteed complete when the post-startup restore runs.
        // Dropping an id that did not resolve would leave that window hidden forever.
        assertEquals(
            "an id that did not resolve must stay on the record, not be silently forgotten",
            listOf("NotRegisteredYet"),
            controller.state.hiddenByReview,
        )
    }

    fun testHideDoesNotClobberAnAlreadyRememberedLayout() {
        val controller = IdeLayoutController.getInstance(project)
        val state = IdeLayoutController.State()
        state.hiddenByReview = mutableListOf("Project")
        controller.loadState(state)

        controller.hideForReview()

        assertEquals(
            "a second hide must not overwrite the first record, or the window is never reopened",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
    }
}
