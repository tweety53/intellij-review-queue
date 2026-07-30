package dev.tweety.reviewqueue.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.client.ClientType
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.testFramework.HeavyPlatformTestCase

/**
 * Tests for the fixture itself.
 *
 * `IdeLayoutControllerTest` proves the sweep correct *given* a cooperating `ToolWindowManager`, and
 * KAN-18 is a defect in which session that manager is asked under — a property the fixture could not
 * observe at all. These cases exist because the fix's tests will assert on ids this file records: a
 * recording that silently stored the wrong id would make every one of those assertions pass while
 * proving nothing, which is the same shape of green-but-wrong the change exists to eliminate.
 */
class RecordingToolWindowsTest : HeavyPlatformTestCase() {

    private lateinit var manager: RecordingToolWindowManager

    override fun setUp() {
        super.setUp()
        manager = RecordingToolWindowManager.install(project, testRootDisposable)
    }

    fun testHideRecordsTheClientIdItWasCalledUnder() {
        val window = manager.register("Project", visible = true)
        val frontend = ClientId("kan-18-frontend")

        ClientId.withExplicitClientId(frontend) { window.hide(null) }

        assertEquals(listOf(frontend), window.hideClientIds)
    }

    fun testShowRecordsTheClientIdItWasCalledUnder() {
        val window = manager.register("Project", visible = false)
        val frontend = ClientId("kan-18-frontend")

        ClientId.withExplicitClientId(frontend) { window.show(null) }

        assertEquals(listOf(frontend), window.showClientIds)
    }

    fun testGetToolWindowRecordsTheClientIdItWasCalledUnder() {
        manager.register("Project", visible = true)
        val frontend = ClientId("kan-18-frontend")

        ClientId.withExplicitClientId(frontend) { manager.getToolWindow("Project") }

        assertEquals(listOf(frontend), manager.getToolWindowClientIds)
    }

    fun testEnumeratingToolWindowIdsRecordsTheClientIdItWasCalledUnder() {
        manager.register("Project", visible = true)
        val frontend = ClientId("kan-18-frontend")

        ClientId.withExplicitClientId(frontend) { manager.toolWindowIds }

        assertEquals(listOf(frontend), manager.enumerationClientIds)
    }

    /**
     * The one property everything else here rests on: two sessions must stay *distinguishable*. A
     * recording that collapsed every fabricated id onto the local one would keep each assertion above
     * green — they would just all be asserting about the same id — and the fix's "hid in the wrong
     * session" case could never fail.
     *
     * This pins the fixture's *recording*: ids entered directly stay distinct. It says nothing about
     * what [FakeClientSessions.install] does to the ids handed to it —
     * [testASessionInstalledByTheFakeEntersTheSweepAsItsOwnId] is the case that covers that.
     */
    fun testRecordedIdsTellTwoSessionsApart() {
        val window = manager.register("Project", visible = true)
        val frontend = ClientId("kan-18-frontend")
        val controller = ClientId("kan-18-controller")

        ClientId.withExplicitClientId(frontend) { window.hide(null) }
        ClientId.withExplicitClientId(controller) { window.show(null) }

        assertEquals(listOf(frontend), window.hideClientIds)
        assertEquals(listOf(controller), window.showClientIds)
    }

    /**
     * The additive default every case in `IdeLayoutControllerTest` rests on: a window registered with
     * no session restriction resolves under **any** id, fabricated or default. Those cases install no
     * fake sessions and run under the default local id, so a restriction that became mandatory — or a
     * default that resolved only under the id in scope at registration time — would break the whole
     * suite at once. Pinned here so it breaks *here* first, with a message that says what the rule is.
     */
    fun testAWindowRegisteredWithoutARestrictionResolvesUnderAnyClientId() {
        val window = manager.register("Project", visible = true)

        assertSame(
            "an unrestricted window must resolve in a fabricated session",
            window,
            ClientId.withExplicitClientId(ClientId("kan-18-frontend")) { manager.getToolWindow("Project") },
        )
        assertSame(
            "and in any other one, including the default local id these tests otherwise run under",
            window,
            manager.getToolWindow("Project"),
        )
    }

    /**
     * The blind spot task 5b closes: `restore()` walks every non-guest session and forgets an id once
     * it resolved in *at least one*, and a fixture keying its windows by id alone cannot express an id
     * that resolves in one session and not another — the state that actually occurs at post-startup,
     * before a frontend session has attached its tool windows.
     */
    fun testAWindowRestrictedToOneSessionResolvesOnlyUnderThatSessionsId() {
        val frontend = ClientId("kan-18-frontend")
        val window = manager.register("FrontendOnly", visible = true, resolvesOnlyIn = frontend)

        assertSame(
            window,
            ClientId.withExplicitClientId(frontend) { manager.getToolWindow("FrontendOnly") },
        )
        assertNull(
            "in any other session it must be indistinguishable from a window that was never registered",
            ClientId.withExplicitClientId(ClientId("kan-18-host")) { manager.getToolWindow("FrontendOnly") },
        )
    }

    /**
     * The failure-side twin of the case above, and the blind spot fix wave 3 closes: `restore()` walks
     * every non-guest session, and an id can be *reopened* in one and fail in another — one id resolves
     * to a different window object per session (`design.md`, `getToolWindow`'s extractor-mode branch),
     * so the owning plugin can have disposed one of them and not the others. A single `throwsOnShow`
     * flag shared across sessions can only say "throws everywhere" or "throws nowhere", and the mixed
     * outcome the record write actually gets wrong could not be written down at all.
     *
     * Both halves are asserted because both are load-bearing: the scoped session must throw, and every
     * other session must succeed. A scoping that quietly threw everywhere would make the controller
     * test that depends on this pass for the wrong reason.
     */
    fun testAWindowWhoseShowThrowsInOneSessionStillShowsInEveryOther() {
        val frontend = ClientId("kan-18-frontend")
        val host = ClientId("kan-18-host")
        val window = manager.register("Database", visible = false, throwsOnShowIn = frontend)

        ClientId.withExplicitClientId(host) { window.show(null) }

        assertTrue(
            "show() must succeed in a session the window was not scoped to throw in",
            window.isVisible,
        )

        try {
            ClientId.withExplicitClientId(frontend) { window.show(null) }
            fail("show() must throw in the session the window was scoped to throw in")
        } catch (expected: IllegalStateException) {
            assertEquals("show() blew up for this tool window", expected.message)
        }

        assertEquals(
            "both attempts must be recorded, so a test can tell attempted-and-failed apart from " +
                "never-reached",
            listOf(host, frontend),
            window.showClientIds,
        )
    }

    /**
     * Enumeration and resolution have to agree, or the fixture models a manager that lists ids it then
     * refuses to resolve — a state no `ToolWindowManager` is in, and one that would make the sweep's
     * enumerate-then-filter pass look like it had judged a window invisible when the window simply was
     * not there. So a restriction narrows both queries, and an unrestricted window is listed in every
     * session.
     */
    fun testARestrictedWindowIsEnumeratedOnlyInTheSessionItResolvesIn() {
        val frontend = ClientId("kan-18-frontend")
        val host = ClientId("kan-18-host")
        manager.register("Shared", visible = true)
        manager.register("FrontendOnly", visible = true, resolvesOnlyIn = frontend)

        assertEquals(
            setOf("Shared", "FrontendOnly"),
            ClientId.withExplicitClientId(frontend) { manager.toolWindowIds.toSet() },
        )
        assertEquals(
            "the frontend-only window is not part of another session's layout",
            setOf("Shared"),
            ClientId.withExplicitClientId(host) { manager.toolWindowIds.toSet() },
        )
    }

    /**
     * A fabricated id names no session the platform knows about, so entering one has to leave service
     * resolution working — otherwise the fix's tests could not run the sweep under a fabricated id at
     * all, and the fixture's own manager would not be the one answering.
     */
    fun testAProjectServiceStillResolvesUnderAFabricatedClientId() {
        val resolved = ClientId.withExplicitClientId(ClientId("kan-18-frontend")) {
            ToolWindowManager.getInstance(project)
        }

        assertSame(manager, resolved)
    }

    /**
     * `MockToolWindow.getId` answers `""` for every window it is asked about, so without this override
     * two registered windows would be indistinguishable to anything handed the [ToolWindow] itself —
     * which is all `ToolWindowManagerListener.toolWindowShown(ToolWindow)` provides.
     */
    fun testARegisteredWindowReportsTheIdItWasRegisteredUnder() {
        val window = manager.register("Terminal", visible = false)

        assertEquals("Terminal", window.id)
    }

    /**
     * The fixture's model of the one platform behaviour the re-show watch is built on: showing a tool
     * window publishes `toolWindowShown` on the **project's** message bus
     * (`ToolWindowManagerImpl.fireToolWindowShown`, reached from `showToolWindow` via `doShowWindow`).
     *
     * `IdeLayoutControllerTest`'s re-show cases drive `show(null)` rather than publishing by hand, so
     * that the plugin's own restoring is exempted by the code under test rather than by the test not
     * producing an event at all. That only means anything if showing really does announce itself —
     * pinned here, on the same bus and the same id the watch filters on.
     */
    fun testShowingAWindowAnnouncesItOnTheProjectMessageBus() {
        val window = manager.register("Terminal", visible = false)
        val shown = mutableListOf<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    shown += toolWindow.id
                }
            },
        )

        window.show(null)

        assertEquals(
            "showing must announce the window, naming it, on the bus the watch subscribes to",
            listOf("Terminal"),
            shown,
        )
    }

    fun testInstalledFakeSessionsAreWhatTheServiceReturns() {
        val refs = listOf(
            SessionRef(ClientId("kan-18-frontend"), ClientType.FRONTEND),
            SessionRef(ClientId("kan-18-host"), ClientType.LOCAL),
        )

        FakeClientSessions.install(project, testRootDisposable, refs)

        assertEquals(refs, ReviewClientSessions.getInstance(project).sessions())
    }

    fun testInstalledFakeSessionsAnswerTheBusTheyWereGiven() {
        val ref = SessionRef(ClientId("kan-18-frontend"), ClientType.FRONTEND)

        FakeClientSessions.install(project, testRootDisposable, listOf(ref))

        assertSame(project.messageBus, ReviewClientSessions.getInstance(project).messageBus(ref))
    }

    /**
     * End-to-end through [FakeClientSessions.install], because that method is where a fabricated id
     * can be quietly disarmed: `ClientId.addFakeLocalId` adds the id's *value* to
     * `ClientId.fakeLocalIds`, and `withClientIdImpl` then substitutes `ClientId.localId` for any
     * value in that set — so a session installed that way would enter the sweep as `ClientId("Host")`
     * and every "which session was this hidden in" assertion in the fix's tests would compare `Host`
     * to `Host` and hold vacuously.
     *
     * The body below is what the fix will do for real: read a session off the service, enter its id,
     * hide under it. Re-adding `addFakeLocalId` to [FakeClientSessions.install] fails **here**, which
     * is what [testRecordedIdsTellTwoSessionsApart] — entering raw ids, never touching `install` —
     * cannot do.
     */
    fun testASessionInstalledByTheFakeEntersTheSweepAsItsOwnId() {
        val window = manager.register("Project", visible = true)
        val ref = SessionRef(ClientId("kan-18-frontend"), ClientType.FRONTEND)
        FakeClientSessions.install(project, testRootDisposable, listOf(ref))

        val target = ReviewClientSessions.getInstance(project).sessions().single()
        ClientId.withExplicitClientId(target.clientId) { window.hide(null) }

        assertEquals(listOf(ref.clientId), window.hideClientIds)
    }
}
