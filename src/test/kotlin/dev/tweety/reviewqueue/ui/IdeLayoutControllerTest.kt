package dev.tweety.reviewqueue.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.client.ClientType
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

    /**
     * KAN-6 named the concrete `ToolWindowManager` in every sweep diagnostic on the theory that a
     * wrong manager was why nothing appeared to be hidden. KAN-18 found the manager was right and the
     * *session* was wrong — so the zero-ids branch may no longer attribute the cause to the manager,
     * and every sweep line has to say which session it is talking about or the next log read cannot
     * tell "swept the wrong layout" from "there was nothing there to hide".
     *
     * This branch is the one KAN-6 keeps at `warn` (see
     * [testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible]), and therefore the only branch of
     * `logSweepOutcome` whose text is observable at all: `LoggedErrorProcessor` intercepts `warn` and
     * `error` only, and the two `info` branches write straight past it.
     *
     * A fabricated **frontend** session is what gives the assertion content: under the default local
     * session the id and type would be whatever the test platform reports, so a line that named no
     * session at all could still coincidentally match.
     */
    fun testTheZeroIdsWarningNamesTheSweptSessionRatherThanBlamingTheManager() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(
                SessionRef(ClientId("local"), ClientType.LOCAL),
                SessionRef(ClientId("frontend"), ClientType.FRONTEND),
            ),
        )
        val controller = controllerWithRecord()

        val warnings = warningsWhile { controller.hideForReview() }

        val line = warnings.singleOrNull { it.contains("zero tool window ids") }
        assertNotNull("the zero-ids branch must still warn: got $warnings", line)
        assertTrue(
            "it must name the session it swept, or a split IDE's log cannot say which layout was " +
                "enumerated: got $line",
            line!!.contains("session frontend (FRONTEND)"),
        )
        assertTrue(
            "and it must attribute zero ids to that session, not to the manager — KAN-18 disproved " +
                "the manager reading: got $line",
            line.contains("that session has no tool windows registered"),
        )
    }

    /**
     * The post-hide warning, for the same reason: it is the loudest line the sweep can emit, and on
     * its own it said which manager answered but not which session's layout it answered for. Two
     * sessions' worth of these lines in one log are indistinguishable without it.
     */
    fun testThePostHideStillVisibleWarningNamesTheSweptSession() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("frontend"), ClientType.FRONTEND)),
        )
        manager.register("Stubborn", visible = true, ignoresHide = true)
        val controller = controllerWithRecord()

        val warnings = warningsWhile { controller.hideForReview() }

        assertTrue(
            "the still-visible warning must name the swept session: got $warnings",
            warnings.any { it.contains("Stubborn") && it.contains("[session frontend (FRONTEND)]") },
        )
    }

    /**
     * The third and last observable line of the two sweep diagnostics: the re-query that could not
     * answer at all. It already named the id and the throwable; which session it failed to verify in
     * is the fact KAN-18 adds.
     */
    fun testThePostHideUnverifiableWarningNamesTheSweptSession() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("frontend"), ClientType.FRONTEND)),
        )
        manager.register("Disposes", visible = true, throwsOnIsVisibleAfterHide = true)
        val controller = controllerWithRecord()

        val warnings = warningsWhile { controller.hideForReview() }

        assertTrue(
            "the could-not-verify warning must name the swept session: got $warnings",
            warnings.any { it.contains("could not verify Disposes") && it.contains("[session frontend (FRONTEND)]") },
        )
    }

    /**
     * The fourth and loudest sweep line: `hide()` itself threw. It is the line that fires when
     * something has actually gone wrong, so it is the line where "whose layout was this?" matters
     * most — and it would otherwise have been the one sweep diagnostic unable to answer. It is
     * emitted by the hide loop rather than by either log helper, which is why it needs a case of its
     * own.
     *
     * Distinct from [testHideNamesTheIdWhereHideThrowsAndStillPropagatesTheThrow], which pins the
     * fail-fast behaviour itself — which ids had `hide()` invoked, and that the record survives the
     * throw. This case pins what the message says. It re-asserts the propagation for one reason: the
     * throwable has to be caught here for the warning to be readable at all, and a catch that said
     * nothing about what it caught would leave this case passing if the suffix had been added by
     * swallowing the throw.
     */
    fun testTheHideThrewWarningNamesTheSweptSession() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("frontend"), ClientType.FRONTEND)),
        )
        manager.register("BlowsUp", visible = true, throwsOnHide = true)
        val controller = controllerWithRecord()

        var thrown: Throwable? = null
        val warnings = warningsWhile {
            try {
                controller.hideForReview()
            } catch (t: Throwable) {
                thrown = t
            }
        }

        assertTrue(
            "naming the session must not have cost the fail-fast behaviour: the throwable must still " +
                "propagate out of hideForReview()",
            thrown is IllegalStateException,
        )
        assertTrue(
            "the hide-threw warning must name the session whose layout was being swept: got $warnings",
            warnings.any {
                it.contains("hide() threw for BlowsUp") && it.contains("[session frontend (FRONTEND)]")
            },
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
     * KAN-18's other half. The session available when a pass *ends* need not be the one that was
     * swept when it began — most sharply at post-startup, where [ReviewLayoutRestorer] may run before
     * a frontend session has attached. Restoring is bounded by the record, so reaching every
     * non-guest session cannot open a window the pass did not hide.
     *
     * Asserts on the whole of [RecordingToolWindow.showClientIds] rather than its last entry: the
     * fixture's recording lists are cumulative, so "shown once per session, in session order" is
     * only a claim about the whole list.
     */
    fun testRestoreShowsInEveryNonGuestSession() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(
                SessionRef(ClientId("local"), ClientType.LOCAL),
                SessionRef(ClientId("frontend"), ClientType.FRONTEND),
            ),
        )
        val window = manager.register("Restorable", visible = false)
        val controller = controllerWithRecord("Restorable")

        controller.restore()

        assertEquals(
            "each non-guest session must be shown in, in order",
            listOf(ClientId("local"), ClientId("frontend")),
            window.showClientIds,
        )
        assertTrue(controller.state.hiddenByReview.isEmpty())
    }

    fun testRestoreNeverShowsInAGuestSession() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(
                SessionRef(ClientId("local"), ClientType.LOCAL),
                SessionRef(ClientId("guest"), ClientType.GUEST),
            ),
        )
        val window = manager.register("Restorable", visible = false)
        val controller = controllerWithRecord("Restorable")

        controller.restore()

        assertEquals(
            "a guest's layout is not ours to change, on the way out any more than on the way in",
            listOf(ClientId("local")),
            window.showClientIds,
        )
    }

    /**
     * The existing "an unresolved id stays on the record" contract, now across sessions: an id is
     * forgotten once it resolved in at least one, and kept when it resolved in none.
     *
     * The record assertion alone cannot tell a cross-session restore from a single-session one —
     * [RecordingToolWindowManager] keys its windows by id and knows nothing about sessions, so both
     * would keep `NotRegisteredYet` and forget `Resolves`. The lookup counts are what make "resolved
     * in *no* session" a claim with content: every recorded id has to be *asked for* in every
     * non-guest session before it can be judged unresolvable. Counted per session rather than
     * asserted as an ordered list, so the test does not also pin which of the two loops is the outer
     * one.
     */
    fun testRestoreKeepsAnIdThatResolvedInNoSession() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(
                SessionRef(ClientId("local"), ClientType.LOCAL),
                SessionRef(ClientId("frontend"), ClientType.FRONTEND),
            ),
        )
        manager.register("Resolves", visible = false)
        val controller = controllerWithRecord("Resolves", "NotRegisteredYet")

        controller.restore()

        assertEquals(
            "an id that resolved nowhere must stay on the record; one that resolved anywhere must not",
            listOf("NotRegisteredYet"),
            controller.state.hiddenByReview,
        )
        assertEquals(
            "both recorded ids must be looked up in both non-guest sessions — that is what " +
                "\"resolved in no session\" quantifies over",
            mapOf(ClientId("local") to 2, ClientId("frontend") to 2),
            manager.getToolWindowClientIds.groupingBy { it }.eachCount(),
        )
    }

    /**
     * The case that actually happens in production, and the one neither
     * [testRestoreShowsInEveryNonGuestSession] (resolved everywhere) nor
     * [testRestoreKeepsAnIdThatResolvedInNoSession] (resolved nowhere) can express: a recorded id
     * resolves in one session and not in another. At post-startup [ReviewLayoutRestorer] may run
     * before a frontend session has attached its tool windows, so an id resolving only in `local` —
     * or, after the frontend attaches but before the backend re-registers, only in `frontend` — is
     * the normal state, not an edge case.
     *
     * The contract is a **union**: forgotten once it resolved anywhere, kept when it resolved
     * nowhere. Both of the scoped ids below therefore have to be dropped even though neither
     * resolved in both sessions. Under a "resolved in the last session" reading `LocalOnly` would be
     * kept; under a "resolved in every session" reading both would be — so this case is what makes
     * `resolved` being a set unioned across the session loop a tested claim rather than an argument.
     *
     * The [RecordingToolWindow.showClientIds] assertions are what stop that from holding vacuously:
     * were the fixture to ignore the restriction, each window would be shown in *both* sessions and
     * the record assertion would still pass while proving nothing.
     */
    fun testRestoreForgetsAnIdThatResolvedInOnlyOneOfTwoSessions() {
        val local = ClientId("local")
        val frontend = ClientId("frontend")
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(local, ClientType.LOCAL), SessionRef(frontend, ClientType.FRONTEND)),
        )
        val localOnly = manager.register("LocalOnly", visible = false, resolvesOnlyIn = local)
        val frontendOnly = manager.register("FrontendOnly", visible = false, resolvesOnlyIn = frontend)
        val controller = controllerWithRecord("LocalOnly", "FrontendOnly", "NeitherSession")

        controller.restore()

        assertEquals(
            "a window registered only in the local session must be shown there and nowhere else",
            listOf(local),
            localOnly.showClientIds,
        )
        assertEquals(
            "a window registered only in the frontend session must be shown there and nowhere else",
            listOf(frontend),
            frontendOnly.showClientIds,
        )
        assertEquals(
            "an id is forgotten once it resolved in at least one session, so both scoped ids must " +
                "go and only the id that resolved in neither may stay",
            listOf("NeitherSession"),
            controller.state.hiddenByReview,
        )
    }

    /**
     * One window whose `show()` throws must not cost the reviewer every other window on the record,
     * in this session or in any later one.
     *
     * The failure is the restore-side twin of the one the hide loop and the post-hide check already
     * defend against: the sweep covers arbitrary third-party tool windows, and a plugin that disposes
     * its content while the pass is running makes reopening it throw. Since `restore()` became a loop
     * over *every* non-guest session, an unguarded throw in an earlier session means no later session
     * is ever entered — so on a split IDE the frontend the reviewer is actually looking at is the one
     * layout left un-restored.
     *
     * `Database` is deliberately first on the record, so an unguarded `show()` takes `Terminal` down
     * with it: asserting `Terminal` was shown in **both** sessions is what makes this a claim about
     * isolation rather than about ordering. The record assertion states the decision the KDoc on
     * [IdeLayoutController.restore] justifies — an id whose `show()` threw was not reopened, so it
     * stays on the record and a later restore retries it.
     */
    fun testRestoreKeepsGoingWhenOneWindowsShowThrows() {
        val local = ClientId("local")
        val frontend = ClientId("frontend")
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(local, ClientType.LOCAL), SessionRef(frontend, ClientType.FRONTEND)),
        )
        val throwing = manager.register("Database", visible = false, throwsOnShow = true)
        val healthy = manager.register("Terminal", visible = false)
        val controller = controllerWithRecord("Database", "Terminal")

        val warnings = warningsWhile { controller.restore() }

        assertEquals(
            "the window after the throwing one must still be reopened, in every non-guest session",
            listOf(local, frontend),
            healthy.showClientIds,
        )
        assertEquals(
            "and the throwing window must have been attempted in every session, not abandoned after " +
                "the first failure",
            listOf(local, frontend),
            throwing.showClientIds,
        )
        assertEquals(
            "an id that was never actually reopened stays on the record, so a later restore retries " +
                "it; one that was reopened is forgotten",
            listOf("Database"),
            controller.state.hiddenByReview,
        )
        assertTrue(
            "and the failure must be reported, naming the id and the session: got $warnings",
            warnings.any { it.contains("Database") && it.contains("local") },
        )
    }

    /**
     * The mixed outcome neither [testRestoreForgetsAnIdThatResolvedInOnlyOneOfTwoSessions] (resolved
     * in one, absent in the other) nor [testRestoreKeepsGoingWhenOneWindowsShowThrows] (throws in
     * every session) can express: one id **reopened in one session and failed in another**.
     *
     * It is the configuration this whole change exists for. Under a split IDE `getToolWindow` resolves
     * one id to a *different* window object per session (`design.md`, "Root cause, from the bytecode"),
     * so the host's copy can show cleanly while the frontend's copy — the one the reviewer is actually
     * looking at — blows up because its owning plugin disposed the content mid-pass.
     *
     * A record write that forgets an id as soon as it succeeded *somewhere* drops `Database` while it
     * is still hidden in `frontend`, and nothing can recover it: the sweep's union only preserves ids
     * that are still on the record when the next reclaim reads them back. So the rule is narrower than
     * "reopened anywhere" — an id is forgotten only when it was reopened somewhere **and** failed
     * nowhere, which is what the spec means by both causes of "not reopened" getting the same answer.
     *
     * `Terminal` is here to keep the claim about the failing id rather than about the loop stopping,
     * and its [RecordingToolWindow.showClientIds] — together with `Database`'s — is what stops the
     * record assertion holding vacuously: were the fixture to ignore the session scoping, `Database`
     * would fail in both sessions and this would degenerate into
     * [testRestoreKeepsGoingWhenOneWindowsShowThrows].
     */
    fun testRestoreKeepsAnIdThatWasReopenedInOneSessionButFailedInAnother() {
        val local = ClientId("local")
        val frontend = ClientId("frontend")
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(local, ClientType.LOCAL), SessionRef(frontend, ClientType.FRONTEND)),
        )
        val mixed = manager.register("Database", visible = false, throwsOnShowIn = frontend)
        val healthy = manager.register("Terminal", visible = false)
        val controller = controllerWithRecord("Database", "Terminal")

        val warnings = warningsWhile { controller.restore() }

        assertEquals(
            "the id must have been attempted in both sessions — succeeding in one is what makes this " +
                "the mixed case",
            listOf(local, frontend),
            mixed.showClientIds,
        )
        assertEquals(
            "and the healthy window must still be reopened in every non-guest session",
            listOf(local, frontend),
            healthy.showClientIds,
        )
        assertEquals(
            "an id that failed to reopen in any session is still hidden there, so it stays on the " +
                "record however many other sessions reopened it; one that failed nowhere is forgotten",
            listOf("Database"),
            controller.state.hiddenByReview,
        )
        assertTrue(
            "and the failure must be reported, naming the id and the session it failed in: got $warnings",
            warnings.any { it.contains("Database") && it.contains("frontend") },
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
     * The sweep's record write must not erase what the reclaim `restore()` immediately before it
     * deliberately kept.
     *
     * `restore()` keeps an id whose `show()` threw, because the window is still hidden and this
     * plugin hid it — [testRestoreKeepsGoingWhenOneWindowsShowThrows] pins that. But the sweep that
     * `hideForReview()` runs straight afterwards computes its record from what is *currently
     * visible*, and a window whose `show()` threw is by construction not visible. Overwriting the
     * record with that list drops the id entirely: the window stays hidden on screen, this plugin
     * hid it, and nothing is left owing it a restore — neither a later `restore()` nor
     * [ReviewLayoutRestorer]'s replay at startup would ever try it again.
     *
     * This is the same failure class `LEGACY_IDS` pruning guards against, inverted: instead of
     * latching a stuck id forever, the next pass launders it out. Both halves of the pass are
     * asserted, because the fix must not trade one for the other — `Stuck` survives on the record
     * **and** `Project` is still hidden. An unreopenable id may never veto hiding.
     */
    fun testASweepKeepsAnIdWhoseReclaimShowThrew() {
        val stuck = manager.register("Stuck", visible = false, throwsOnShow = true)
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord("Stuck")

        controller.hideForReview()

        assertEquals("the reclaim must have attempted to reopen the stuck window", 1, stuck.shows)
        assertEquals(
            "an id the reclaim could not reopen must survive the sweep's record write, or the " +
                "window is left hidden with nothing owing it a restore",
            setOf("Stuck", "Project"),
            controller.state.hiddenByReview.toSet(),
        )
        assertFalse(
            "and hiding must still happen — a permanently stuck id may never veto the sweep",
            projectWindow.isVisible,
        )
    }

    /**
     * Carrying an id forward must be visible in `idea.log`, not silent.
     *
     * Silence is what made this defect invisible for two review rounds: an operator reading the log
     * of a pass that carried a window forward saw a sweep line naming only what it hid. The id is
     * deliberately one that resolves to no registered window at all — the post-startup cause — so
     * `restore()` itself emits no warning for it and this assertion cannot be satisfied by
     * `restore()`'s own `show() threw` line. That also makes the case a second cause of the same
     * defect: `NeverRegistered` is dropped by an overwriting record write exactly as a throwing
     * `show()` is.
     */
    fun testCarryingAnUnreopenedIdForwardIsWarnedAbout() {
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord("NeverRegistered")

        val warnings = warningsWhile { controller.hideForReview() }

        assertEquals(
            "an id that resolved nowhere must survive the sweep's record write too",
            setOf("NeverRegistered", "Project"),
            controller.state.hiddenByReview.toSet(),
        )
        assertTrue(
            "carrying it forward must be reported, naming the id: got $warnings",
            warnings.any { it.contains("NeverRegistered") },
        )
        assertFalse(projectWindow.isVisible)
    }

    /**
     * The other half of the carry-forward diagnostic, and the half that was protected only by
     * coincidence: an ordinary pass must say **nothing** about carrying anything forward.
     *
     * Silence is the operationally important half here. The diagnostic is a `warn` about an abnormal
     * state — a window this plugin is still hiding after a reclaim that ran with the IDE fully up — and
     * a warning that fires on every ordinary pass stops being read, which would cost the signal that
     * [testCarryingAnUnreopenedIdForwardIsWarnedAbout] exists to produce. `hidden` and `carriedForward`
     * are both `List<String>` computed two lines apart in the sweep, so passing the wrong one is an
     * easy edit — and one that the rest of this suite cannot catch, because every other case either
     * ignores log output or matches with `any`, which cannot see an extra warning alongside a
     * legitimate one.
     *
     * The first half is a positive control, on the same recorder and the same method: a pass that
     * really does carry an id forward, proving the substring below is one this recorder can observe.
     * Without it, a reworded message would leave the negative assertion holding vacuously — the one
     * failure mode a `none` assertion has. Registering the windows again between the two halves is how
     * the second pass gets something visible to hide: [RecordingToolWindowManager.register] replaces
     * the entry under that id.
     */
    fun testAnOrdinaryPassSaysNothingAboutCarryingAnythingForward() {
        manager.register("Project", visible = true)
        manager.register("Terminal", visible = true)
        val controller = controllerWithRecord("NeverRegistered")

        val control = warningsWhile { controller.hideForReview() }

        assertTrue(
            "the recorder must be able to observe the carry-forward line on this method before its " +
                "absence below is allowed to mean anything: got $control",
            control.any { it.contains("is carrying") },
        )

        val projectWindow = manager.register("Project", visible = true)
        val terminal = manager.register("Terminal", visible = true)
        controllerWithRecord()

        val warnings = warningsWhile { controller.hideForReview() }

        assertEquals(
            "the ordinary pass must really have swept and recorded, or its silence proves nothing",
            setOf("Project", "Terminal"),
            controller.state.hiddenByReview.toSet(),
        )
        assertFalse("and really have hidden what it recorded", projectWindow.isVisible)
        assertFalse("and really have hidden what it recorded", terminal.isVisible)
        assertTrue(
            "a pass with nothing outstanding carries nothing forward and must stay silent about it: " +
                "got $warnings",
            warnings.none { it.contains("is carrying") },
        )
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

    /**
     * KAN-18, stated as an assertion. Under a split IDE the platform's `ToolWindowManager` addresses
     * whichever session the current `ClientId` names; sweeping under no client id addresses the local
     * session, which has no screen. Every fixture call the sweep makes must therefore carry the target
     * session's id.
     */
    fun testHideSweepsAsTheFrontendSessionWhenThereIsOne() {
        val frontend = SessionRef(ClientId("frontend"), ClientType.FRONTEND)
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("local"), ClientType.LOCAL), frontend),
        )
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "the sweep must enumerate as the session the reviewer is looking at",
            listOf(ClientId("frontend")),
            manager.enumerationClientIds,
        )
        assertTrue(
            "every getToolWindow must be asked as the frontend session",
            manager.getToolWindowClientIds.all { it == ClientId("frontend") },
        )
        assertEquals(
            "hide() must be called as the frontend session, or the frontend is never told",
            listOf(ClientId("frontend")),
            projectWindow.hideClientIds,
        )
    }

    /**
     * The plain, non-split installation. Nothing about this path may change: it is what every other
     * case in this suite exercises, and what every user without a split IDE runs.
     */
    fun testHideSweepsAsTheLocalSessionWhenItIsTheOnlyOne() {
        val local = SessionRef(ClientId("local"), ClientType.LOCAL)
        FakeClientSessions.install(project, testRootDisposable, listOf(local))
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(listOf("Project"), controller.state.hiddenByReview)
        assertFalse(projectWindow.isVisible)
        assertEquals(listOf(ClientId("local")), projectWindow.hideClientIds)
    }

    /**
     * A guest is another person in a Code With Me session. Their layout is not this plugin's to touch,
     * and with no other session there is nothing to sweep at all.
     */
    fun testHideDoesNothingWhenOnlyAGuestSessionIsPresent() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("guest"), ClientType.GUEST)),
        )
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertTrue(
            "a guest's layout must be left alone, and nothing recorded against it",
            controller.state.hiddenByReview.isEmpty(),
        )
        assertEquals(0, projectWindow.hides)
        assertTrue("the guest's layout must not even be enumerated", manager.enumerationClientIds.isEmpty())
    }

    /**
     * The known limitation the KAN-6 post-hide check could not close: it proves `hide()` took effect at
     * the instant it returned, and says nothing about a window re-shown afterwards. The watch answers
     * that by being told, rather than by re-checking at a chosen moment.
     *
     * Driven through [RecordingToolWindow.show], which announces the window on the project bus exactly
     * as `ToolWindowManagerImpl.fireToolWindowShown` does, rather than by publishing the event by hand:
     * that is what lets [testRestoringDoesNotReportItselfAsAReShow] be a real exemption rather than a
     * test that produces no event to be exempt from.
     */
    fun testAWindowShownAgainDuringThePassIsWarnedAbout() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
        )
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()
        controller.hideForReview()

        val warnings = warningsWhile { projectWindow.show(null) }

        assertTrue(
            "a window reopened behind the reviewer's back must be reported, naming the window: " +
                "got $warnings",
            warnings.any { it.contains("Project") && it.contains("shown again") },
        )
        assertTrue(
            "and naming the session the pass swept, so a split IDE's log says which layout was acted " +
                "on: got $warnings",
            warnings.any { it.contains("local") },
        )
    }

    /**
     * Once per id per pass. A window shown, hidden and shown again during one pass is one finding for
     * the next log read, not a stream of them that buries everything else.
     */
    fun testAWindowShownAgainSeveralTimesIsReportedOnce() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
        )
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()
        controller.hideForReview()

        val warnings = warningsWhile {
            projectWindow.show(null)
            projectWindow.hide(null)
            projectWindow.show(null)
        }

        assertEquals(
            "three shows of one window are one finding: got $warnings",
            1,
            warnings.count { it.contains("shown again") },
        )
    }

    /**
     * The plugin's own restoring must never trip the watch: reopening the recorded windows is what
     * ending a pass means. `restore()` disconnects before it shows anything, which is what makes this
     * true by construction rather than by filtering.
     *
     * Not a vacuous control. [RecordingToolWindow.show] publishes the same event the watch is armed
     * over, so moving or dropping the `disarmReshowWatch()` call at the top of
     * [IdeLayoutController.restore] fails this case — which is why the window really being reopened is
     * asserted too: were `restore()` to show nothing, there would be nothing to report and the silence
     * below would mean nothing.
     */
    fun testRestoringDoesNotReportItselfAsAReShow() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
        )
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()
        controller.hideForReview()

        val warnings = warningsWhile { controller.restore() }

        assertTrue(
            "restore must actually have reopened the window, or its silence proves nothing",
            projectWindow.isVisible,
        )
        assertTrue(
            "the plugin's own restore must not be reported as something reopening a window: " +
                "got $warnings",
            warnings.none { it.contains("shown again") },
        )
    }

    /**
     * Spec scenario "A second pass reports independently": the watch covers what the *second* pass hid,
     * not what the first one did. `hideForReview` reclaims a leftover record by calling `restore()`
     * first, which disarms, and the sweep then re-arms over the ids it has just hidden — so an id the
     * first pass hid and the second one did not is no longer watched.
     *
     * Asserts both directions, because "one warning" alone would also hold if the watch were reporting
     * the wrong window.
     */
    fun testASecondPassWatchesWhatTheSecondPassHid() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
        )
        val first = manager.register("Project", visible = true)
        val second = manager.register("Terminal", visible = false)
        val controller = controllerWithRecord()
        controller.hideForReview()
        controller.restore()

        // Only Terminal is visible for the second pass, so only Terminal is watched.
        second.show(null)
        first.hide(null)
        controller.hideForReview()

        val warnings = warningsWhile {
            second.show(null)
            first.show(null)
        }

        assertEquals(
            "exactly one finding, for the window the second pass hid: got $warnings",
            1,
            warnings.count { it.contains("shown again") },
        )
        assertTrue("and it must name Terminal: got $warnings", warnings.any { it.contains("Terminal") })
        assertTrue(
            "the window only the first pass hid is no longer watched: got $warnings",
            warnings.none { it.contains("Project") },
        )
    }

    /**
     * A session named by the enumeration can be gone by the time the sweep ends, and
     * [ReviewClientSessions.messageBus] answers `null` for it. Nothing may be armed then — a diagnostic
     * that reports nothing, never a broken hide.
     *
     * This is also what makes the watch's use of that seam a tested claim: subscribing to
     * `project.messageBus` directly would arm here, and the re-show below would be reported for a
     * session that no longer exists.
     */
    fun testNoWatchIsArmedWhenTheSweptSessionHasGoneAway() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
            bus = null,
        )
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "the hide itself must be unaffected — only the diagnostic depends on the bus",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertFalse(projectWindow.isVisible)

        val warnings = warningsWhile { projectWindow.show(null) }

        assertTrue(
            "with no bus for the swept session there is nothing to watch on: got $warnings",
            warnings.none { it.contains("shown again") },
        )
    }

    /**
     * The same session going away, but *throwing* on the way out rather than answering `null`.
     * [ReviewClientSessions.messageBus] resolves the session's project and its bus with no guard, so a
     * session torn down between the enumeration at the top of `hideForReview()` and the arming at the
     * very end of the sweep makes that lookup throw rather than return nothing.
     *
     * By then the windows are hidden, the record is written and the post-hide check has passed: the
     * pass has already succeeded, and a purely diagnostic subscription must not be able to turn that
     * into a failure the user sees — the rule
     * [testHidePostHideCheckSwallowsAThrowingReQueryAndWarns] already holds the post-hide check to.
     * An unarmed watch reports nothing, which the design accepts; a throw out of `hideForReview()`
     * fails a pass that worked.
     *
     * The record and visibility assertions are what make the silence meaningful: they say the sweep
     * really did complete, rather than the throw having been avoided by never getting that far.
     */
    fun testHideStillSucceedsWhenArmingTheReShowWatchThrows() {
        FakeClientSessions.install(
            project,
            testRootDisposable,
            listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
            throwsOnMessageBus = true,
        )
        val projectWindow = manager.register("Project", visible = true)
        val controller = controllerWithRecord()

        val warnings = warningsWhile { controller.hideForReview() }

        assertEquals(
            "the hide itself must be unaffected — only the diagnostic depends on the seam that threw",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertFalse("and the window must really have been hidden", projectWindow.isVisible)
        assertTrue(
            "the failure to arm must be reported rather than swallowed silently: got $warnings",
            warnings.any { it.contains("re-show watch") },
        )
    }

    /**
     * Runs [block] and returns every message logged at `warn` while it ran.
     *
     * `LoggedErrorProcessor` intercepts `warn` and `error` only — `info` writes straight past it, the
     * limitation [testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible] documents. The cases above
     * inline this same recorder instead of calling it: they predate the re-show watch and are left
     * exactly as they were, so that a regression in them is unambiguously a regression in the
     * behaviour they cover.
     */
    private fun warningsWhile(block: () -> Unit): List<String> {
        val warnings = mutableListOf<String>()
        LoggedErrorProcessor.executeWith<RuntimeException>(
            object : LoggedErrorProcessor() {
                override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                    warnings += message
                    return false
                }
            },
        ) {
            block()
        }
        return warnings
    }
}
