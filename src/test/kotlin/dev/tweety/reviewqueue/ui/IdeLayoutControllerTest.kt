package dev.tweety.reviewqueue.ui

import com.intellij.testFramework.HeavyPlatformTestCase

class IdeLayoutControllerTest : HeavyPlatformTestCase() {

    private lateinit var manager: RecordingToolWindowManager

    override fun setUp() {
        super.setUp()
        manager = RecordingToolWindowManager.install(project, testRootDisposable)
    }

    private fun controllerWithRecord(vararg ids: String): IdeLayoutController {
        val controller = IdeLayoutController.getInstance(project)
        val state = IdeLayoutController.State()
        state.hiddenByReview = ids.toMutableList()
        controller.loadState(state)
        return controller
    }

    fun testLoadedStateIsReturnedByGetState() {
        val controller = controllerWithRecord("Project", "SomeOtherWindow")

        assertEquals(listOf("Project", "SomeOtherWindow"), controller.state.hiddenByReview)
    }

    /**
     * A stale id from a version that still had the Review Queue tool window must be dropped as the
     * state loads. It can never resolve now, so `restore()` would keep it on the `unresolved` record
     * forever — and a permanently non-empty record latches `hideForReview()`'s "leftover means
     * restore first" branch.
     */
    fun testLoadStateDropsAnIdThePluginNoLongerManages() {
        val controller = controllerWithRecord("Project", "Review Queue")

        assertEquals(
            "the retired tool window id must not survive a state load",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
    }

    fun testHideRecordsAndHidesOnlyTheVisibleManagedWindows() {
        val projectWindow = manager.register("Project", visible = true)
        val unmanaged = manager.register("SomeOtherWindow", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "only managed windows that were actually visible may be recorded, or restore reopens a " +
                "window the user had closed — or one the review never touched",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertFalse(projectWindow.isVisible)
        assertEquals("a window this plugin does not manage must not be hidden", 0, unmanaged.hides)
    }

    /**
     * A managed window the user had already closed must be left alone — spec scenario "A window the
     * user had already closed is not reopened".
     *
     * This case existed before KAN-5 as the `visible = false` half of
     * [testHideRecordsAndHidesOnlyTheVisibleManagedWindows], where `Review Queue` played the invisible
     * managed window. Reducing `MANAGED_IDS` to one id left that rewrite with no managed-and-invisible
     * window anywhere in the file, and the coverage went with it: weakening the visibility filter in
     * `hideForReview` to `manager.getToolWindow(it) != null` passed the entire suite, while making the
     * plugin hide a Project window the user had deliberately closed and reopen it at end of pass.
     */
    fun testHideLeavesAManagedWindowTheUserHadAlreadyClosedAlone() {
        val projectWindow = manager.register("Project", visible = false)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "an already-closed managed window must not be recorded, or restore reopens something the " +
                "user closed on purpose",
            emptyList<String>(),
            controller.state.hiddenByReview,
        )
        assertEquals("it must not be hidden either — it already was", 0, projectWindow.hides)
    }

    /**
     * Spec scenario "Hiding still works after a stale entry is loaded": the prune must leave the record
     * empty, so `hideForReview` takes its normal path instead of the leftover-reclaim branch.
     *
     * Distinct from [testHideReclaimsALeftoverRecordInsteadOfRefusingToHide], which seeds `Leftover` —
     * an id that is *not* pruned, and so exercises reclaim rather than prune-then-hide. Without this
     * case, moving the prune from `loadState` into `restore()` — the alternative
     * `layout-state-migration` rejected — would flip this scenario with nothing failing.
     */
    fun testHidingWorksNormallyAfterOnlyAStaleEntryWasLoaded() {
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord("Review Queue")

        assertEquals(
            "the prune must have emptied the record before the hide runs",
            emptyList<String>(),
            controller.state.hiddenByReview,
        )

        controller.hideForReview()

        assertEquals(
            "the hide must record what it actually hid, not be vetoed by a stale leftover",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertFalse(projectWindow.isVisible)
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
