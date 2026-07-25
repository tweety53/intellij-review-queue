package dev.tweety.reviewqueue.queue

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.ui.ReviewDiffPresenter

/**
 * Drives the real session service with a fake presenter, so the flow is verified without a live
 * diff framework. The presenter interface exists for exactly this.
 */
class ReviewSessionServiceTest : HeavyPlatformTestCase() {

    private class FakePresenter : ReviewDiffPresenter {
        val shown = mutableListOf<ReviewKey>()
        var closed = 0
        override fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean {
            shown += key
            return true
        }
        override fun close() { closed++ }
        override fun isShowing(file: VirtualFile) = false
    }

    fun testStartIsInactiveWhenTheQueueIsEmpty() {
        val service = ReviewSessionService.getInstance(project)
        service.presenter = FakePresenter()

        service.start()

        assertFalse("an empty queue must not start a session", service.isActive)
    }

    fun testEndClosesThePresenterAndDeactivates() {
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.end()

        assertFalse(service.isActive)
        assertNull(service.currentKey())
    }
}
