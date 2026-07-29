package dev.tweety.reviewqueue.ui

import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor

/**
 * Every case here runs against [RecordingToolWindowManager], which overrides `toolWindowIds` and
 * `getToolWindow` itself — the manager always answers the way [IdeLayoutController] expects a
 * `ToolWindowManager` to answer. That proves the sweep correct *given* a cooperating manager; it
 * cannot observe the real platform `ToolWindowManager` disagreeing with what this suite assumes.
 * That gap is exactly the one fix round 1 hit with the popup menu — verified against a stub,
 * wrong against the platform — and it is why `hideForReview()` now logs what it saw instead of
 * only being provable against this fixture.
 */
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

    /**
     * The reported symptom (`proposal.md` Fix round 2, Report B): Start Review runs to completion,
     * the diff opens, and the tool windows stay on screen. Against this fixture that can only mean
     * `toolWindowIds` was non-empty but nothing on it was visible.
     *
     * This branch fires for entirely legitimate states too — an already-closed window
     * ([testHideLeavesAnAlreadyClosedWindowAloneWhenNothingElseIsVisible]) or a second hide with
     * nothing restored in between ([testASecondHideDoesNotStrandTheWindowsTheFirstOneHid]) — so
     * fix round 2 (finding C) moved it from `warn` to `info`: warning on those would cry wolf
     * against the signal this round exists to produce.
     *
     * `LoggedErrorProcessor` only intercepts `warn`/`error` (`TestLoggerFactory.TestLogger.info`
     * writes straight past it with no hook), so this test cannot assert on the info line's text
     * directly. What it asserts instead, at the fidelity that tooling allows: that this branch
     * produces **no warning** — with a positive control, on the same recorder and the same method,
     * proving the recorder really would have caught one had this branch still warned. That control
     * is `ids.isEmpty()`, the one branch fix round 2 keeps at `warn` (it really is abnormal), run
     * first against a manager with nothing registered yet.
     */
    fun testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible() {
        val controller = controllerWithRecord()

        val warnings = mutableListOf<String>()
        val recorder = object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return false
            }
        }

        // Positive control: with nothing registered yet, ids.isEmpty() is true and this method
        // still warns — proving the recorder can observe a real warning here before the negative
        // assertion below relies on its silence meaning anything.
        LoggedErrorProcessor.executeWith<RuntimeException>(recorder) {
            controller.hideForReview()
        }
        assertTrue(
            "the recorder must be able to observe a real warning on this method: got $warnings",
            warnings.any { it.contains("zero tool window ids") },
        )
        warnings.clear()

        manager.register("Project", visible = false)
        manager.register("Terminal", visible = false)

        LoggedErrorProcessor.executeWith<RuntimeException>(recorder) {
            controller.hideForReview()
        }

        assertEquals(
            "ids enumerated but none visible is a legitimate outcome and must not warn: got $warnings",
            emptyList<String>(),
            warnings,
        )
        assertEquals(
            "nothing was visible, so nothing should be recorded as hidden",
            emptyList<String>(),
            controller.state.hiddenByReview,
        )
    }

    /**
     * Finding D (fix round 2, adversarial review): the pre-hide log ([logSweepOutcome] in
     * `IdeLayoutController`) only restates what the filter judged visible **before** `hide()` ever
     * ran — it is byte-identical whether `hide()` took effect and something reopened the window
     * afterwards, or `hide()` silently no-oped. This proves the *post*-hide check catches the
     * second case: a window still reporting visible immediately after `hide()` returned.
     *
     * [RecordingToolWindow.hide] always flips `visible` on its own, so an ordinary registered
     * window cannot reproduce this. `ignoresHide` gives the fixture a window that still calls
     * through to `hide()` (so `hides` is counted, proving the sweep really invoked it) without the
     * call taking effect — modelling exactly the failure mode this check exists to surface.
     */
    fun testHideWarnsWhenAWindowIsStillVisibleImmediatelyAfterHideReturns() {
        val stubborn = manager.register("Stubborn", visible = true, ignoresHide = true)
        val controller = controllerWithRecord()

        val warnings = mutableListOf<String>()
        val recorder = object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return false
            }
        }

        LoggedErrorProcessor.executeWith<RuntimeException>(recorder) {
            controller.hideForReview()
        }

        assertEquals("hide() must still be called even though it does not take effect", 1, stubborn.hides)
        assertTrue(
            "the post-hide check must warn that Stubborn is still visible right after hide() " +
                "returned: got $warnings",
            warnings.any { it.contains("post-hide") && it.contains("Stubborn") },
        )
    }

    /**
     * Important 1 (pass-2 panel, fix round 2): the post-hide diagnostic re-queries
     * `isVisible()` on ids that were just passed to `hide()`, outside any try/catch of its own.
     * The sweep deliberately covers arbitrary third-party tool windows, and a plugin that disposes
     * its content on hide can make that re-query throw (e.g. `AlreadyDisposedException`) instead of
     * returning a value. A purely diagnostic check must never be able to turn a successful hide into
     * a broken `hideForReview()` — this proves the throw is caught, logged at `warn` naming the id
     * and the throwable, and swallowed, while `hideForReview()` still completes normally.
     */
    fun testHidePostHideCheckSwallowsAThrowingReQueryAndWarns() {
        val disposesOnHide = manager.register("Disposes", visible = true, throwsOnIsVisibleAfterHide = true)
        val controller = controllerWithRecord()

        val warnings = mutableListOf<String>()
        val recorder = object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return false
            }
        }

        LoggedErrorProcessor.executeWith<RuntimeException>(recorder) {
            controller.hideForReview()
        }

        assertEquals("hide() must still have been called on it", 1, disposesOnHide.hides)
        assertTrue(
            "the post-hide check must warn that it could not verify Disposes, naming the throwable: " +
                "got $warnings",
            warnings.any { it.contains("could not verify") && it.contains("Disposes") },
        )
    }

    fun testHideRecordsAndHidesEveryVisibleWindow() {
        val projectWindow = manager.register("Project", visible = true)
        val terminal = manager.register("Terminal", visible = true)
        val thirdParty = manager.register("SomePluginWindow", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "every visible window must be recorded, whichever plugin registered it",
            setOf("Project", "Terminal", "SomePluginWindow"),
            controller.state.hiddenByReview.toSet(),
        )
        assertFalse(projectWindow.isVisible)
        assertFalse(terminal.isVisible)
        assertFalse("a third-party window is hidden too; there is no allowlist", thirdParty.isVisible)
    }

    /**
     * The invisible half of the sweep. Without it, replacing the `isVisible == true` filter with a
     * bare "is registered" check would pass the whole suite while hiding — and then reopening —
     * windows the user had deliberately closed.
     */
    fun testHideSkipsWindowsThatAreAlreadyClosed() {
        val visible = manager.register("Project", visible = true)
        val closed = manager.register("Terminal", visible = false)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "an already-closed window must not be recorded, or restore reopens it at end of pass",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertEquals("it must not be hidden either — it already was", 0, closed.hides)
        assertFalse(visible.isVisible)
    }

    /**
     * A single already-closed window, alone, must be left alone — spec scenario "A window the user
     * had already closed is not reopened". Narrower than [testHideSkipsWindowsThatAreAlreadyClosed]:
     * there is no other visible window in play here, so this also guards against a regression where
     * the sweep hides *something* whenever `hideForReview` runs, whether or not anything is visible.
     */
    fun testHideLeavesAnAlreadyClosedWindowAloneWhenNothingElseIsVisible() {
        val projectWindow = manager.register("Project", visible = false)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "an already-closed window must not be recorded, or restore reopens something the user " +
                "closed on purpose",
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
     *
     * Under the every-visible-window sweep, reclaiming a leftover and then leaving it open would
     * defeat the point of KAN-6: the reviewer would be left with one extra window the pass forgot
     * about. So the reclaim's `show()` and the sweep's `hide()` both fire in the same call — the
     * window is genuinely visible for an instant, and the sweep — with no allowlist to exempt it —
     * hides it again like any other, this time with a record that will restore it correctly.
     */
    fun testHideReclaimsALeftoverRecordInsteadOfRefusingToHide() {
        val leftover = manager.register("Leftover", visible = false)
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord("Leftover")

        controller.hideForReview()

        assertEquals(
            "the reclaim must have reopened the leftover before the sweep ran",
            1,
            leftover.shows,
        )
        assertEquals(
            "the sweep records everything it hid, including a window the reclaim just reopened",
            setOf("Leftover", "Project"),
            controller.state.hiddenByReview.toSet(),
        )
        assertFalse(
            "the reclaimed window is visible again by the time the sweep runs, so it is hidden too",
            leftover.isVisible,
        )
        assertFalse("hiding must still happen; a leftover record may not veto it", projectWindow.isVisible)
    }

    /**
     * Nothing recorded how far the hide loop got if `hide()` itself threw
     * part-way through. The pre-hide sweep line lists every id judged visible, but says nothing about
     * which of them actually had `hide()` invoked before the throw.
     *
     * Fail-fast is kept (the hard constraint against swallowing exceptions from the hide loop rules
     * out catch-and-continue as a sound option here — see the KDoc on `hideForReview()`): the
     * exception must still propagate out of `hideForReview()`, unchanged from before this fix. What
     * changes is that the failing id is now named, at `warn`, before the throwable is rethrown — so
     * this test also proves the ids *before* the failure had `hide()` invoked and the ids *after* it
     * did not, which is the "how far did it get" question Minor 2 asks for.
     */
    fun testHideNamesTheIdWhereHideThrowsAndStillPropagatesTheThrow() {
        val before = manager.register("Before", visible = true)
        val blowsUp = manager.register("BlowsUp", visible = true, throwsOnHide = true)
        val after = manager.register("After", visible = true)
        val controller = controllerWithRecord()

        val warnings = mutableListOf<String>()
        val recorder = object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return false
            }
        }

        var thrown: Throwable? = null
        LoggedErrorProcessor.executeWith<RuntimeException>(recorder) {
            try {
                controller.hideForReview()
            } catch (t: Throwable) {
                thrown = t
            }
        }

        assertTrue(
            "hide() throwing must still propagate out of hideForReview() — fail-fast is preserved",
            thrown is IllegalStateException,
        )
        assertTrue(
            "the warning must name the id whose hide() threw: got $warnings",
            warnings.any { it.contains("BlowsUp") },
        )
        assertEquals("the id before the failure must have had hide() invoked", 1, before.hides)
        assertEquals("the failing id's hide() must have been invoked once", 1, blowsUp.hides)
        assertEquals(
            "the id after the failure must never have had hide() invoked — the loop is fail-fast",
            0,
            after.hides,
        )
        assertEquals(
            "the record-before-hide invariant must survive the throw: every id the sweep judged " +
                "visible must already be on the record, or a window is left hidden with nothing to " +
                "reopen it",
            setOf("Before", "BlowsUp", "After"),
            controller.state.hiddenByReview.toSet(),
        )
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
