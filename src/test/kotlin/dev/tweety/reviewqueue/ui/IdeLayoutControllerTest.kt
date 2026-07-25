package dev.tweety.reviewqueue.ui

import com.intellij.testFramework.HeavyPlatformTestCase

class IdeLayoutControllerTest : HeavyPlatformTestCase() {

    fun testLoadedStateIsReturnedByGetState() {
        val controller = IdeLayoutController.getInstance(project)
        val state = IdeLayoutController.State()
        state.hiddenByReview = mutableListOf("Project", "Review Queue")

        controller.loadState(state)

        assertEquals(listOf("Project", "Review Queue"), controller.state.hiddenByReview)
    }

    fun testRestoreClearsTheRememberedWindows() {
        val controller = IdeLayoutController.getInstance(project)
        val state = IdeLayoutController.State()
        state.hiddenByReview = mutableListOf("Project")
        controller.loadState(state)

        controller.restore()

        assertTrue(
            "restore must forget what it reopened, or a later restore would reopen it again",
            controller.state.hiddenByReview.isEmpty(),
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
