package dev.tweety.reviewqueue.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.util.messages.MessageBus

/**
 * The injectable seam for everything `IdeLayoutController` does.
 *
 * `ScopeSwitchTest` used to record that there was no such seam and fall back to counting presenter
 * closes as a proxy for "no tool window is hidden or restored". That was false: swapping the project's
 * [ToolWindowManager] through `replaceService` is all it takes, and the proxy could not catch a stray
 * `hideForReview()` on a path that is supposed to leave the layout alone.
 *
 * The platform's own headless tool window hard-codes `isVisible = false` and no-ops `show`/`hide`, so
 * asserting against it would only test the mock. [RecordingToolWindow] actually tracks visibility,
 * which is what lets a test say whether a window was really reopened rather than merely dropped from
 * the record.
 */
class RecordingToolWindow(
    /**
     * The id this window is registered under. [ToolWindowHeadlessManagerImpl.MockToolWindow.getId]
     * answers `""` for every window, so a listener handed one of them could not tell them apart —
     * and `ToolWindowManagerListener.toolWindowShown(ToolWindow)`, the event the platform actually
     * fires, carries nothing else to identify the window by.
     */
    private val windowId: String,
    private val project: Project,
    private var visible: Boolean,
    /**
     * Models `hide()` being called and returning normally without the window actually going
     * invisible — the failure mode fix round 2's post-hide check (finding D) exists to catch.
     * `hides` still counts the call; only the visibility flip is skipped.
     */
    private val ignoresHide: Boolean = false,
    /**
     * Models a plugin that disposes its content on `hide()`, so any *later* `isVisible()` query
     * throws (e.g. `AlreadyDisposedException`) instead of returning a value — pass-2 round 2's
     * Important 1: the post-hide diagnostic re-queries ids that were just passed to `hide()`, and
     * must never let that throw escape `hideForReview()`. `isVisible()` still answers normally
     * before `hide()` is called, so the sweep's own pre-hide filter is unaffected — only the
     * post-hide re-query can observe the throw, matching the real failure this models.
     */
    private val throwsOnIsVisibleAfterHide: Boolean = false,
    /**
     * Models `hide()` itself throwing (pass-3 panel Minor 2) — a third-party tool window whose
     * `hide()` blows up mid-sweep, e.g. because it eagerly tears down content. `hides` still counts
     * the call before throwing, so a test can tell "reached and attempted" apart from "never reached"
     * for the ids after it in the sweep.
     */
    private val throwsOnHide: Boolean = false,
    /**
     * Models `show()` itself throwing — the restore-side twin of [throwsOnHide], and the same real
     * failure the hide loop and the post-hide check already defend against: a third-party tool window
     * whose content its owning plugin disposed while the pass was running, so reopening it blows up
     * (e.g. `AlreadyDisposedException`). `shows` still counts the call before throwing, so a test can
     * tell "reached and attempted" apart from "never reached" for the ids after it in the record.
     */
    private val throwsOnShow: Boolean = false,
    /**
     * The session-scoped form of [throwsOnShow]: `show()` throws only while this `ClientId` is the
     * current one, and returns normally in every other session.
     *
     * Models what `design.md` found in `getToolWindow`'s extractor-mode branch — one id resolves to a
     * *different* window object per session, so one of them can have had its content disposed while
     * the others are healthy. Without this the fixture could only state "throws in every session" or
     * "throws in none", and the mixed outcome — reopened in one session, failed in another — could not
     * be written down at all.
     *
     * Scoped exactly as [RecordingToolWindowManager.register]'s `resolvesOnlyIn` is, and opt-in the
     * same way: omitting it leaves [throwsOnShow] the sole decider, so every registration that does
     * not name a session behaves as it always did.
     */
    private val throwsOnShowIn: ClientId? = null,
) : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {

    /**
     * The `ClientId` in scope at each call, in call order. KAN-18: under a split IDE the platform's
     * own `ToolWindowManager` addresses whichever session the current id names, so "which id was this
     * asked under" is the property the fix turns on — and the one this fixture could not previously
     * observe.
     */
    val hideClientIds: List<ClientId> get() = recordedHideClientIds

    /** The `ClientId` in scope at each [show], in call order. See [hideClientIds]. */
    val showClientIds: List<ClientId> get() = recordedShowClientIds

    private val recordedHideClientIds = mutableListOf<ClientId>()

    private val recordedShowClientIds = mutableListOf<ClientId>()

    var shows = 0
        private set

    var hides = 0
        private set

    private var disposedAfterHide = false

    override fun getId(): String = windowId

    override fun isVisible(): Boolean {
        if (disposedAfterHide) throw IllegalStateException("tool window content was disposed on hide")
        return visible
    }

    /**
     * Shows the window **and announces it**, the way the platform does.
     *
     * `ToolWindowImpl.show(Runnable)` calls `ToolWindowManagerImpl.showToolWindow(id)`, which reaches
     * `doShowWindow` and there publishes `toolWindowShown(ToolWindow)` on the **project's** message bus
     * (`ToolWindowManagerImpl.fireToolWindowShown`, read with `javap -c` from
     * `intellij.platform.ide.impl.jar`). `MockToolWindow.show` announces nothing, so without this a
     * test could not distinguish `IdeLayoutController.restore()` being exempt from the re-show watch
     * from the fixture simply never producing an event to be exempt from — the whole content of
     * `IdeLayoutControllerTest.testRestoringDoesNotReportItselfAsAReShow`.
     *
     * Only the show event is modelled, because it is the only one the watch listens for. Hiding also
     * fires (`stateChanged` with `HideToolWindow`), and adding it would be unused machinery.
     */
    override fun show(runnable: Runnable?) {
        val clientId = ClientId.current
        recordedShowClientIds += clientId
        shows++
        if (throwsOnShow || throwsOnShowIn == clientId) {
            throw IllegalStateException("show() blew up for this tool window")
        }
        visible = true
        project.messageBus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown(this)
    }

    override fun hide(runnable: Runnable?) {
        recordedHideClientIds += ClientId.current
        hides++
        if (throwsOnHide) throw IllegalStateException("hide() blew up for this tool window")
        if (!ignoresHide) visible = false
        if (throwsOnIsVisibleAfterHide) disposedAfterHide = true
    }
}

/**
 * Resolves only the windows a test [register]s, so an id the plugin should not be touching resolves to
 * null exactly as an unregistered window would.
 *
 * A registration may additionally be restricted to one session (`register(..., resolvesOnlyIn = id)`),
 * which is what lets a test state the case `restore()` actually meets at post-startup: an id that
 * resolves in one session and not in another. Without it the fixture keyed its windows by id alone, so
 * "resolved in at least one session" could only ever be exercised as "resolved in all of them" or "in
 * none" — and the union across the session loop rested on an argument instead of a test.
 *
 * **The restriction is opt-in and nothing else changes.** A window registered without one resolves
 * under every id, which is what every case that installs no fake sessions — and so runs under the
 * default local id — relies on.
 */
class RecordingToolWindowManager(private val project: Project) : ToolWindowHeadlessManagerImpl(project) {

    /**
     * The `ClientId` in scope at each [getToolWindow], in call order.
     * See [RecordingToolWindow.hideClientIds].
     */
    val getToolWindowClientIds: List<ClientId> get() = recordedGetToolWindowClientIds

    /** The `ClientId` in scope at each read of [toolWindowIds], in call order. */
    val enumerationClientIds: List<ClientId> get() = recordedEnumerationClientIds

    private val recordedGetToolWindowClientIds = mutableListOf<ClientId>()

    private val recordedEnumerationClientIds = mutableListOf<ClientId>()

    private val windows = mutableMapOf<String, Registration>()

    /**
     * Registers a tool window under [id].
     *
     * [resolvesOnlyIn] names the one session the window belongs to; omitting it — as every case that
     * does not care about sessions does — makes the window resolve in all of them. One session rather
     * than a set of them because that is what the case being modelled is: a window registered on one
     * side of a split IDE and not yet on the other.
     *
     * [throwsOnShowIn] is the same idea applied to failure rather than to resolution: the window
     * resolves everywhere but its `show()` blows up in the one named session. See
     * [RecordingToolWindow.throwsOnShowIn] for why that case cannot be written with [throwsOnShow].
     */
    fun register(
        id: String,
        visible: Boolean,
        ignoresHide: Boolean = false,
        throwsOnIsVisibleAfterHide: Boolean = false,
        throwsOnHide: Boolean = false,
        throwsOnShow: Boolean = false,
        resolvesOnlyIn: ClientId? = null,
        throwsOnShowIn: ClientId? = null,
    ): RecordingToolWindow =
        RecordingToolWindow(
            id,
            project,
            visible,
            ignoresHide,
            throwsOnIsVisibleAfterHide,
            throwsOnHide,
            throwsOnShow,
            throwsOnShowIn,
        ).also { windows[id] = Registration(it, resolvesOnlyIn) }

    override fun getToolWindow(id: String?): ToolWindow? {
        val clientId = ClientId.current
        recordedGetToolWindowClientIds += clientId
        return windows[id]?.takeIf { it.resolvesUnder(clientId) }?.window
    }

    /**
     * The sweep in `hideForReview` enumerates ids rather than consulting a fixed list, so the
     * fixture has to answer this as well as [getToolWindow]. Returning only the ids registered *for
     * the current session* keeps the two in agreement: every id this returns resolves, and nothing
     * else does. Letting them disagree would model a manager that lists windows it then refuses to
     * resolve, and the sweep's enumerate-then-filter pass would read a window that is not in this
     * session's layout at all as one it had judged invisible.
     */
    override val toolWindowIds: Array<String>
        get() {
            val clientId = ClientId.current
            recordedEnumerationClientIds += clientId
            return windows.filterValues { it.resolvesUnder(clientId) }.keys.toTypedArray()
        }

    /**
     * One registration: the window, and the session it is restricted to if a test asked for one. Held
     * together in a single entry so that what resolves and where it resolves cannot drift apart.
     */
    private class Registration(val window: RecordingToolWindow, private val resolvesOnlyIn: ClientId?) {

        /** An unrestricted registration answers to every session — see [register]'s KDoc. */
        fun resolvesUnder(clientId: ClientId): Boolean =
            resolvesOnlyIn == null || resolvesOnlyIn == clientId
    }

    companion object {
        /** Installs a manager for [project] and returns it, undone when [parent] is disposed. */
        fun install(project: Project, parent: Disposable): RecordingToolWindowManager =
            RecordingToolWindowManager(project).also {
                project.replaceService(ToolWindowManager::class.java, it, parent)
            }
    }
}

/**
 * Fabricated client sessions, so a test can say which sessions exist and assert which one the sweep
 * ran as. The real [ReviewClientSessions] reads the platform, which in a test always reports one
 * local session — the configuration in which KAN-18 does not reproduce.
 */
internal class FakeClientSessions(
    project: Project,
    private val refs: List<SessionRef>,
    /**
     * `null` models the one failure the real seam has: a session named by [sessions] that has gone
     * away before anything asked for its bus. See [ReviewClientSessions.messageBus].
     */
    private val bus: MessageBus?,
    /**
     * Models the seam *throwing* rather than answering, which `null` cannot express.
     * [ReviewClientSessions.messageBus] resolves the session's `ClientProjectSession` and then its
     * project's bus with no guard, so a session or project torn down between the enumeration at the
     * top of `hideForReview()` and the arming at the end of the sweep makes that lookup throw
     * (e.g. `AlreadyDisposedException`) instead of answering `null`.
     */
    private val throwsOnMessageBus: Boolean = false,
) : ReviewClientSessions(project) {

    override fun sessions(): List<SessionRef> = refs

    override fun messageBus(session: SessionRef): MessageBus? {
        if (throwsOnMessageBus) {
            throw IllegalStateException("client session was already disposed")
        }
        return bus
    }

    companion object {
        /**
         * Installs sessions for [project], returning the fake, undone when [parent] is disposed.
         *
         * The fabricated ids are deliberately **not** registered with `ClientId.addFakeLocalId`. That
         * call adds the id's value to `ClientId.fakeLocalIds`, and `withClientIdImpl` then substitutes
         * `ClientId.localId` for any value in that set — so every fabricated session would enter the
         * sweep as the same local id and every "which session was this hidden in" assertion would
         * hold vacuously. Entering an unregistered id is safe here: project services still resolve
         * under one.
         *
         * Both facts are pinned by `RecordingToolWindowsTest` — re-adding the call fails
         * `testASessionInstalledByTheFakeEntersTheSweepAsItsOwnId`, which enters a session read back
         * out of this service and asserts on the id the sweep was recorded under.
         */
        fun install(
            project: Project,
            parent: Disposable,
            refs: List<SessionRef>,
            bus: MessageBus? = project.messageBus,
            throwsOnMessageBus: Boolean = false,
        ): FakeClientSessions =
            FakeClientSessions(project, refs, bus, throwsOnMessageBus).also {
                project.replaceService(ReviewClientSessions::class.java, it, parent)
            }
    }
}
