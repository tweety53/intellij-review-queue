# Focus mode: sweep the session that drives the visible UI — Implementation Plan

> **Execution:** `/myflow-do` implements this plan. Mark each checkbox when its task passes spec +
> quality review.

**Goal:** Make a review pass hide the tool windows the reviewer can actually see under a split /
remote-dev IDE, by running the sweep and the restore under the right client `ClientId`.

**Architecture:** Two new files hold the session rule (pure) and the session enumeration (the
injectable seam). `IdeLayoutController.hideForReview()` runs its whole sweep inside one session's
`ClientId`; `restore()` runs inside every non-guest session in turn. A `ToolWindowManagerListener`
warns if a hidden window is shown again mid-pass. *(Corrected during implementation: that listener is
on the **project's** message bus, not the swept session's — the platform has no per-session bus. See
`design.md`, "The re-show watch", and task 6's notes below.)*

**Tech Stack:** Kotlin 2.4.10, JVM toolchain 21, IntelliJ Platform Gradle Plugin 2.18.1, target
IU-2026.2 (build 262.8665.258), JUnit 4 via `HeavyPlatformTestCase`.
<!-- measured: read from build.gradle.kts and gradle.properties, and from
     .intellijPlatform/layoutIndex/ for the build number @ b06a364 -->

## Global Constraints

- **No new Gradle dependency.** `build.gradle.kts` is not edited by this change. `ClientId`,
  `ClientKind`, `ClientType`, `ClientSessionsManager` and `ClientProjectSession` are core platform.
- **No `rdserver` or `cwm-plugin` symbol may be referenced** in `src/main` or `src/test`. Those
  classes are not on the compile classpath and are absent from a non-Ultimate/non-split install.
- **Services are declared with `@Service`, never in `plugin.xml`.** The plugin has no
  `<projectService>` entry today and this change adds none.
- **A plain local installation must behave exactly as it does today.** Where the only session is
  `LOCAL`, every existing test must pass unchanged.
- **Load-bearing behaviour that must not change:** the record is written before anything is hidden;
  the sweep enumerates rather than using a fixed id list; the hide loop names the failing id and
  rethrows; the post-hide re-query swallows its own throwable; `LEGACY_IDS` pruning on `loadState`;
  every KAN-6 diagnostic line, with the pre-hide line still emitted before the mutation.
- **Package for all new files:** `dev.tweety.reviewqueue.ui`.

## Reference: the platform behaviour this change works with

```kotlin verified:javap -c com.jetbrains.rdserver.toolWindow.BackendServerToolWindowManager, from plugins/cwm-plugin/lib/modules/intellij.platform.backend.split.jar of IU-2026.2 build 262.8665.258
// BackendServerToolWindowManager.getToolWindow — decompiled shape
val session = ClientSessionsManager.getProjectSession(project, ClientId.getCurrent()) ?: return null
if (session.isLocal()) return super.getToolWindow(id)
// …otherwise the per-client window, by extractor mode

// BackendServerToolWindowManager.hideToolWindow — decompiled shape
val session = ClientSessionsUtil.getCurrentSession(project)
if (session.isLocal()) super.hideToolWindow(id, hideSide, moveFocus, removeFromStripe, source)
updateBackendToolwindowState(id, session, { Hidden })   // ← the push that reaches a frontend
```

```kotlin verified:javap com.intellij.openapi.client.ClientType and ClientType.isOwner/isRemote @ IU-2026.2
enum class ClientType { LOCAL, FRONTEND, CONTROLLER, GUEST }
// isOwner  == isLocal || isController      → ClientKind.OWNER excludes FRONTEND
// isRemote == isController || isGuest      → ClientKind.REMOTE excludes FRONTEND and LOCAL
// No ClientKind means "non-guest": the filter is written by hand over ClientType.
```

Baseline before any task in this plan: 215 tests, 0 failures.
<!-- measured: ./gradlew test @ b06a364, counted from build/test-results/test/TEST-*.xml -->

---

## 1. The session rule

### Task 1: `ReviewSessionTargeting` — which session, decided by a pure function

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargeting.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargetingTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal data class SessionRef(val clientId: ClientId, val type: ClientType)`;
  `internal object ReviewSessionTargeting` with
  `fun hideTarget(sessions: List<SessionRef>): SessionRef?` and
  `fun restoreTargets(sessions: List<SessionRef>): List<SessionRef>`. Tasks 2–5 use both.

- [x] **Step 1.1: Write the failing test**

Create `src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargetingTest.kt`:

```kotlin unverified:confirm ClientId's constructor takes a single String and ClientType's entries are named exactly LOCAL/FRONTEND/CONTROLLER/GUEST
package dev.tweety.reviewqueue.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.client.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The whole rule, exercised without a platform. `IdeLayoutControllerTest` cannot cover the
 * priority order — it runs one fixture at a time — so the ordering lives here, where every
 * combination is cheap.
 */
class ReviewSessionTargetingTest {

    private fun ref(name: String, type: ClientType) = SessionRef(ClientId(name), type)

    @Test
    fun `frontend wins over controller and local`() {
        val sessions = listOf(
            ref("local", ClientType.LOCAL),
            ref("controller", ClientType.CONTROLLER),
            ref("frontend", ClientType.FRONTEND),
        )

        assertEquals(ClientId("frontend"), ReviewSessionTargeting.hideTarget(sessions)?.clientId)
    }

    @Test
    fun `controller wins over local when there is no frontend`() {
        val sessions = listOf(ref("local", ClientType.LOCAL), ref("controller", ClientType.CONTROLLER))

        assertEquals(ClientId("controller"), ReviewSessionTargeting.hideTarget(sessions)?.clientId)
    }

    @Test
    fun `a plain local installation targets the local session`() {
        val sessions = listOf(ref("local", ClientType.LOCAL))

        assertEquals(ClientId("local"), ReviewSessionTargeting.hideTarget(sessions)?.clientId)
    }

    @Test
    fun `a guest is never the hide target`() {
        val sessions = listOf(ref("guest", ClientType.GUEST))

        assertNull(
            "a guest is another person; their layout is not ours to hide",
            ReviewSessionTargeting.hideTarget(sessions),
        )
    }

    @Test
    fun `no sessions means no target`() {
        assertNull(ReviewSessionTargeting.hideTarget(emptyList()))
    }

    @Test
    fun `restore targets every non-guest session`() {
        val sessions = listOf(
            ref("local", ClientType.LOCAL),
            ref("guest", ClientType.GUEST),
            ref("frontend", ClientType.FRONTEND),
        )

        assertEquals(
            listOf(ClientId("local"), ClientId("frontend")),
            ReviewSessionTargeting.restoreTargets(sessions).map { it.clientId },
        )
    }

    @Test
    fun `restore targets nothing when only a guest is present`() {
        assertEquals(
            emptyList<SessionRef>(),
            ReviewSessionTargeting.restoreTargets(listOf(ref("guest", ClientType.GUEST))),
        )
    }
}
```

- [x] **Step 1.2: Run the test and confirm it fails**

```bash verified:the Gradle test-filter syntax is used identically by this repo's other suites
./gradlew test --tests "dev.tweety.reviewqueue.ui.ReviewSessionTargetingTest"
```

Expected: compilation failure — `Unresolved reference: SessionRef`,
`Unresolved reference: ReviewSessionTargeting`.

- [x] **Step 1.3: Write the minimal implementation**

Create `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargeting.kt`:

```kotlin unverified:confirm ClientType is importable from com.intellij.openapi.client and is a Kotlin enum, not a Java one with different member names
package dev.tweety.reviewqueue.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.client.ClientType

/** One client session of the project, reduced to the two facts this plugin needs about it. */
internal data class SessionRef(val clientId: ClientId, val type: ClientType)

/**
 * Which client session a review pass acts on.
 *
 * Under a split or remote-dev IDE the project has several sessions, and `ToolWindowManager`'s
 * `getToolWindow`/`hideToolWindow` both address whichever one the current `ClientId` names. Running
 * with no client id in scope addresses the local session — which has no screen — so the reviewer's
 * windows are never told to hide. That is KAN-18.
 *
 * No built-in `ClientKind` expresses either rule below: `ClientKind.OWNER` is `LOCAL ∪ CONTROLLER`
 * and excludes `FRONTEND`, and `ClientKind.REMOTE` is `CONTROLLER ∪ GUEST`. Hence the hand-written
 * filters.
 */
internal object ReviewSessionTargeting {

    /**
     * The one session whose layout the reviewer is looking at, or `null` when there is none.
     *
     * Exactly one, because visibility must be measured in the same layout that is mutated: taking
     * the union across sessions would hide a window that is visible in one and closed in another,
     * and a later restore would reopen it where the user had closed it.
     */
    fun hideTarget(sessions: List<SessionRef>): SessionRef? =
        sessions.firstOrNull { it.type == ClientType.FRONTEND }
            ?: sessions.firstOrNull { it.type == ClientType.CONTROLLER }
            ?: sessions.firstOrNull { it.type == ClientType.LOCAL }

    /**
     * Every session that could be showing the reviewer's layout.
     *
     * Broader than [hideTarget] on purpose. Restoring is bounded by the record — it only ever shows
     * ids a pass already hid — so breadth cannot open a window the sweep did not hide. It is what
     * makes the quit-mid-pass path work when `ReviewLayoutRestorer` runs before the frontend session
     * has attached.
     */
    fun restoreTargets(sessions: List<SessionRef>): List<SessionRef> =
        sessions.filter { it.type != ClientType.GUEST }
}
```

- [x] **Step 1.4: Run the test and confirm it passes**

```bash verified:same filter syntax as step 1.2
./gradlew test --tests "dev.tweety.reviewqueue.ui.ReviewSessionTargetingTest"
```

Expected: 7 tests, 0 failures.
<!-- predicted: ./gradlew test --tests "dev.tweety.reviewqueue.ui.ReviewSessionTargetingTest" after step 1.3 -->

- [x] **Step 1.5: Stage**

```bash verified:/myflow-do stages and does not commit before a PR exists — pipeline.md, Git boundaries
git add src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargeting.kt \
        src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargetingTest.kt
```

---

## 2. The seam

### Task 2: `ReviewClientSessions` — enumerate the real sessions, replaceable in tests

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewClientSessions.kt`
- Test: covered from `IdeLayoutControllerTest` in tasks 3–5; no test of its own, because its only
  behaviour is the platform call it wraps.

**Interfaces:**
- Consumes: `SessionRef` (task 1).
- Produces: `internal open class ReviewClientSessions(project: Project)` with
  `open fun sessions(): List<SessionRef>` and
  `open fun messageBus(session: SessionRef): MessageBus?`, plus
  `companion object { fun getInstance(project: Project): ReviewClientSessions }`. Tasks 3–5 call
  all three.

> **Implementation note (task 2, as built).** The `unverified:` block in Step 2.1 does not survive
> contact with IU-2026.2 and the shipped file differs from it in two ways. Tasks 3 and 6 depend on
> both.
>
> 1. **`@Service` moved off `ReviewClientSessions` onto a private final nested `Default` holder.**
>    `LightServiceInstanceSupportKt.isLightService` is `Modifier.isFinal(cls) &&
>    cls.isAnnotationPresent(Service)`, so a `@Service`-annotated **open** class is never registered
>    as a light service and `ComponentManagerImpl.doGetService` throws
>    `PluginException("Light service class … must be final")` on the first resolve. Reproduced in a
>    `HeavyPlatformTestCase`. `getInstance` is now
>    `project.getServiceIfCreated(ReviewClientSessions::class.java) ?: project.service<Default>().instance`.
>    **Task 3's `project.replaceService(ReviewClientSessions::class.java, fake, parent)` still works
>    unchanged** — verified against this exact shape. Nothing else in tasks 3–6 changes.
>
> 2. **`messageBus(session)` returns the *project's* bus, not a session bus.** There is no
>    per-session bus on this platform: `ClientSession.messageBus` is `@Deprecated(level = ERROR)`
>    ("sessions don't have their own message bus") and `ClientSessionImpl.getMessageBus()` is `final`
>    and throws `IllegalStateException("Not supported")` unconditionally
>    (`isMessageBusSupported()` returns `false`). The lookup still returns `null` when the session is
>    gone, so task 6's `?: return` guard keeps its meaning — but the `reshow-watch-listener` decision
>    in `design.md` claims the subscription is "scoped to the session that was swept", and that is
>    **not** what task 6 will get. Task 6's watch must filter events itself.

- [x] **Step 2.1: Write the implementation**

There is no failing test to write first: this class has no logic of its own — it is the boundary
that lets tasks 3–5 have tests at all. Its correctness is established by the platform call it makes,
which no unit test can observe.

Create `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewClientSessions.kt`:

```kotlin unverified:confirm ClientSessionsManager.getProjectSessions is reachable as a companion @JvmStatic from Kotlin, that ClientProjectSession exposes `clientId`, `type` and `messageBus` as properties, and that an `open` class carrying @Service does not trip a platform assertion
package dev.tweety.reviewqueue.ui

import com.intellij.openapi.client.ClientKind
import com.intellij.openapi.client.ClientSessionsManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus

/**
 * The project's client sessions, as [SessionRef]s.
 *
 * This exists to be replaced. `IdeLayoutControllerTest` runs against `RecordingToolWindowManager`,
 * a manager that always answers the way the controller expects — a fixture that cannot, by
 * construction, reproduce a `ClientId` defect. Swapping this service for one returning fabricated
 * sessions is what lets a test assert which session the sweep ran as, which is the defect KAN-18
 * describes stated as an assertion.
 *
 * `open` for that reason, and for that reason only.
 */
@Service(Service.Level.PROJECT)
internal open class ReviewClientSessions(private val project: Project) {

    /** Every session of this project, guests included; [ReviewSessionTargeting] does the filtering. */
    open fun sessions(): List<SessionRef> =
        ClientSessionsManager.getProjectSessions(project, ClientKind.ALL)
            .map { SessionRef(it.clientId, it.type) }

    /**
     * The message bus of [session], or `null` when it has gone away since [sessions] was read.
     *
     * Scoped to the session rather than to the project, so the re-show watch in
     * [IdeLayoutController] hears about the layout it actually swept.
     */
    open fun messageBus(session: SessionRef): MessageBus? =
        ClientSessionsManager.getProjectSession(project, session.clientId)?.messageBus

    companion object {
        fun getInstance(project: Project): ReviewClientSessions = project.service()
    }
}
```

- [x] **Step 2.2: Confirm it compiles**

```bash verified:this repo builds with the Gradle wrapper; compileKotlin is the standard task name for the kotlin-jvm plugin
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. If `messageBus` is not a property on `ClientProjectSession`, use
`getMessageBus()`; if `getProjectSessions` is not statically reachable, route through
`ClientSessionsUtil.sessions(project, ClientKind.ALL)`, which the same jar exposes.

- [x] **Step 2.3: Stage**

```bash verified:staged-only, per pipeline.md Git boundaries
git add src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewClientSessions.kt
```

---

## 3. The fixture learns to see `ClientId`

### Task 3: Record the calling `ClientId` in `RecordingToolWindows`

**Files:**
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindows.kt`

**Interfaces:**
- Consumes: `SessionRef` (task 1), `ReviewClientSessions` (task 2).
- Produces: on `RecordingToolWindow`, `val hideClientIds: List<ClientId>` and
  `val showClientIds: List<ClientId>`; on `RecordingToolWindowManager`,
  `val getToolWindowClientIds: List<ClientId>` and `val enumerationClientIds: List<ClientId>`; and
  a new file-level `class FakeClientSessions(...) : ReviewClientSessions(project)` with
  `companion object { fun install(project, parent, sessions, bus): FakeClientSessions }`. Tasks 4–6
  assert on all of these.

> **Implementation note (task 3, as built).** The load-bearing hypothesis in Step 3.3 is **false**,
> and the *stronger* of the two shapes is what is in the tree — tasks 4–6 assert on the recorded ids.
>
> 1. `ClientId.Companion.addFakeLocalId(id, parent)` exists, but it adds `id.value` to
>    `ClientId.fakeLocalIds`, and `ClientId.withClientIdImpl` substitutes `ClientId.localId` for any
>    value in that set. Registering a fabricated id therefore makes it enter the block **as
>    `ClientId("Host")`** — measured, not inferred: with the `addFakeLocalId` line present,
>    `testHideRecordsTheClientIdItWasCalledUnder` fails `expected <[ClientId(value=kan-18-frontend)]>
>    but was <[ClientId(value=Host)]>`. Every fabricated session would have collapsed onto one id and
>    every session-scoping assertion in tasks 4–6 would have held vacuously. **`install` does not call
>    `addFakeLocalId`.** Do not add it.
> 2. The concern that motivated it does not arise: a project service still resolves under an
>    unregistered fabricated id (`testAProjectServiceStillResolvesUnderAFabricatedClientId`).
> 3. `ClientId.getCurrent()` is a Kotlin property — the call site is **`ClientId.current`**.
> 4. New file `src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindowsTest.kt` pins all of the
>    above, so a later change that re-breaks the recording fails there rather than silently turning
>    tasks 4–6 green. The case that specifically guards point 1 is
>    `testASessionInstalledByTheFakeEntersTheSweepAsItsOwnId`: it goes **through**
>    `FakeClientSessions.install`, reads a session back out of the service, enters that id and hides
>    under it. Re-adding `addFakeLocalId` to `install` fails exactly that case — and, verified, only
>    that case. `testRecordedIdsTellTwoSessionsApart` enters raw ids and never calls `install`, so it
>    pins the weaker property "the fixture records distinct ids" and would **not** catch the
>    re-introduction.

- [x] **Step 3.1: Add the recording to `RecordingToolWindow`**

In `src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindows.kt`, add these members to
`RecordingToolWindow` alongside the existing `shows`/`hides` counters, and record in `show`/`hide`:

```kotlin unverified:confirm ClientId.getCurrent() is reachable as ClientId.current from Kotlin; if not, use ClientId.getCurrent()
/**
 * The `ClientId` in scope at each call. KAN-18: under a split IDE the platform's own
 * `ToolWindowManager` addresses whichever session the current id names, so "which id was this asked
 * under" is the property the fix turns on — and the one this fixture could not previously observe.
 */
val hideClientIds: MutableList<ClientId> = mutableListOf()
val showClientIds: MutableList<ClientId> = mutableListOf()

override fun show(runnable: Runnable?) {
    showClientIds += ClientId.getCurrent()
    shows++
    visible = true
}

override fun hide(runnable: Runnable?) {
    hideClientIds += ClientId.getCurrent()
    hides++
    if (throwsOnHide) throw IllegalStateException("hide() blew up for this tool window")
    if (!ignoresHide) visible = false
    if (throwsOnIsVisibleAfterHide) disposedAfterHide = true
}
```

The `hide` body is otherwise unchanged: `hides` is still incremented before `throwsOnHide` fires, so
existing cases that count an attempted-and-thrown hide keep passing.

- [x] **Step 3.2: Add the recording to `RecordingToolWindowManager`**

```kotlin unverified:confirm overriding the `toolWindowIds` property getter with a side effect is allowed here — it is declared `override val toolWindowIds: Array<String>` in the current file
val getToolWindowClientIds: MutableList<ClientId> = mutableListOf()
val enumerationClientIds: MutableList<ClientId> = mutableListOf()

override fun getToolWindow(id: String?): ToolWindow? {
    getToolWindowClientIds += ClientId.getCurrent()
    return windows[id]
}

override val toolWindowIds: Array<String>
    get() {
        enumerationClientIds += ClientId.getCurrent()
        return windows.keys.toTypedArray()
    }
```

- [x] **Step 3.3: Add the fake session service to the same file**

```kotlin unverified:confirm replaceService accepts a subclass instance for an @Service-annotated class, as it already does for ToolWindowManager in this file
/**
 * Fabricated client sessions, so a test can say which sessions exist and assert which one the sweep
 * ran as. The real [ReviewClientSessions] reads the platform, which in a test always reports one
 * local session — the configuration in which KAN-18 does not reproduce.
 */
internal class FakeClientSessions(
    project: Project,
    private val refs: List<SessionRef>,
    private val bus: MessageBus,
) : ReviewClientSessions(project) {

    override fun sessions(): List<SessionRef> = refs

    override fun messageBus(session: SessionRef): MessageBus? = bus

    companion object {
        /**
         * Installs sessions for [project], returning the fake, undone when [parent] is disposed.
         *
         * Every fabricated id is registered with [ClientId.Companion.addFakeLocalId] so platform
         * service resolution keeps working while it is in scope — without that, entering an id the
         * platform has no session for can make `getService` fail. `ClientId.getCurrent()` still
         * returns the fabricated id, so the assertions still bite.
         */
        fun install(
            project: Project,
            parent: Disposable,
            refs: List<SessionRef>,
            bus: MessageBus = project.messageBus,
        ): FakeClientSessions {
            refs.forEach { ClientId.addFakeLocalId(it.clientId, parent) }
            return FakeClientSessions(project, refs, bus).also {
                project.replaceService(ReviewClientSessions::class.java, it, parent)
            }
        }
    }
}
```

- [x] **Step 3.4: Run the existing suite and confirm nothing broke**

```bash verified:same filter syntax as step 1.2
./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"
```

Expected: PASS, unchanged — nothing calls the new members yet.

If `ClientId.addFakeLocalId` turns out not to exist or not to keep `getCurrent()` returning the
fabricated id, drop the `addFakeLocalId` line and, in tasks 4–6, assert on the `SessionRef` the
controller passed to `FakeClientSessions` rather than on the ids the fixture recorded. Record which
of the two was used in the task's commit message — the assertions are weaker in the fallback and a
later reader must not have to guess which shape is in the tree.

- [x] **Step 3.5: Stage**

```bash verified:staged-only, per pipeline.md Git boundaries
git add src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindows.kt
```

---

## 4. Scope the sweep

### Task 4: `hideForReview()` runs inside the target session's `ClientId`

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt:75-134`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`

**Interfaces:**
- Consumes: `ReviewSessionTargeting.hideTarget` (task 1), `ReviewClientSessions.getInstance`
  (task 2), `FakeClientSessions.install` and the fixture's `ClientId` lists (task 3).
- Produces: `hideForReview()` keeps its signature and its `Unit` return. A new private
  `sweep(target: SessionRef)` holds the body that tasks 5 and 6 extend.

- [x] **Step 4.1: Write the failing tests**

Add to `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`:

```kotlin unverified:confirm ClientType and ClientId import cleanly into this test file and that testRootDisposable is in scope, as it is for the existing setUp
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
```

- [x] **Step 4.2: Run them and confirm they fail**

```bash verified:same filter syntax as step 1.2
./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"
```

Expected: the three new cases FAIL — the recorded ids are the local/default id rather than
`frontend`, and the guest case hides `Project` instead of leaving it alone.

- [x] **Step 4.3: Restructure `hideForReview`**

In `IdeLayoutController.kt`, replace the body of `hideForReview()` (currently lines 75–134) with a
target lookup wrapping the existing sweep, moved verbatim into a private `sweep`:

```kotlin unverified:confirm ClientId.withExplicitClientId(ClientId) { … } resolves to the Function0 overload from Kotlin rather than the AccessToken one; if it does not, use `ClientId.withExplicitClientId(target.clientId).use { sweep(target) }`
fun hideForReview() {
    // Unchanged, and deliberately before the target lookup: a leftover record must be reclaimed
    // whatever session is available now.
    if (myState.hiddenByReview.isNotEmpty()) restore()

    val sessions = ReviewClientSessions.getInstance(project).sessions()
    val target = ReviewSessionTargeting.hideTarget(sessions)
    if (target == null) {
        // Not an error: a project whose only session is a guest's has no layout of ours to hide.
        // Warned rather than logged at info because it is abnormal for the reviewer's own IDE.
        thisLogger().warn(
            "Review Queue: hideForReview() found no session to sweep among " +
                "${sessions.map { it.type }} — the layout is left alone",
        )
        return
    }

    // The whole sweep, inside the target session's ClientId. KAN-18: `ToolWindowManager`'s
    // `getToolWindow` and `hideToolWindow` both address whichever session the current id names,
    // so a sweep run under no client id mutates a layout with no screen behind it.
    ClientId.withExplicitClientId(target.clientId) { sweep(target) }
}

private fun sweep(target: SessionRef) {
    // …the existing body from `val manager = ToolWindowManager.getInstance(project)` through
    // `logPostHideVerification(manager, hidden, managerClass)`, moved verbatim. Task 6 threads
    // `target` into the log lines; task 5 arms the re-show watch at its end.
}
```

Nothing inside `sweep` changes in this task. The record is still assigned before the hide loop, the
loop still catches per id and rethrows, and `logSweepOutcome` still runs before any mutation.

- [x] **Step 4.4: Run the whole layout suite**

```bash verified:same filter syntax as step 1.2
./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"
```

Expected: PASS, including every pre-existing case. Cases that install no `FakeClientSessions` fall
through to the real `ReviewClientSessions`, which in a test reports the local session — so they
keep taking exactly the path they took before.

- [x] **Step 4.5: Stage**

```bash verified:staged-only, per pipeline.md Git boundaries
git add src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt \
        src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt
```

**Implementation note (task 4, as built).** Both `unverified:` blocks above held:
`ClientId.withExplicitClientId(target.clientId) { sweep(target) }` resolves to the inline
`Function0` overload from Kotlin (no `.use` fallback needed), and `com.intellij.codeWithMe.ClientId`
/ `com.intellij.openapi.client.ClientType` import cleanly into the test file, where
`testRootDisposable` is in scope. Two things later tasks must know:

1. **`sweep`'s `target` parameter is unused as of task 4.** Nothing inside the moved body reads it
   yet — it is there because task 7 threads the session into `logSweepOutcome` /
   `logPostHideVerification`, and task 6 arms the re-show watch from it. Neither the Kotlin compiler
   nor this repo's tooling warns about it (there is no detekt/ktlint here), but a reviewer will
   notice it, and **task 7 is what makes it load-bearing**. Do not delete the parameter to silence
   the smell.
2. **The whole of `sweep` runs inside the target session's `ClientId`**, because the entry is at the
   call site in `hideForReview`. The watch task 6 arms at the end of `sweep` is therefore armed
   *inside* that scope — the shape this plan specified.

---

## 5. Scope the restore

### Task 5: `restore()` reaches every non-guest session

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt:281-289`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`

**Interfaces:**
- Consumes: `ReviewSessionTargeting.restoreTargets` (task 1), `ReviewClientSessions.getInstance`
  (task 2), `FakeClientSessions.install` (task 3).
- Produces: `restore()` keeps its signature and `Unit` return.

- [x] **Step 5.1: Write the failing tests**

```kotlin unverified:confirm RecordingToolWindowManager returns the same RecordingToolWindow instance for both sessions — it does, because the fixture keys windows by id alone and knows nothing about sessions
/**
 * The session available when a pass ends need not be the one that was swept when it began — most
 * sharply at post-startup, where `ReviewLayoutRestorer` may run before a frontend session has
 * attached. Restoring is bounded by the record, so reaching every non-guest session cannot open a
 * window the pass did not hide.
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
}
```

- [x] **Step 5.2: Run them and confirm they fail**

```bash verified:same filter syntax as step 1.2
./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"
```

Expected: the three new cases FAIL — `showClientIds` holds one default id rather than one per
session.

- [x] **Step 5.3: Rewrite `restore()`**

```kotlin unverified:confirm the same withExplicitClientId overload resolution as step 4.3, and that reading `myState.hiddenByReview` once before the loop is safe — show() must not be able to mutate the record
/**
 * Reopens whatever [hideForReview] hid, then forgets only what it actually reopened. Safe to call
 * when nothing was hidden.
 *
 * Runs in every non-guest session, not in one chosen session: the session present when a pass ends
 * need not be the one that was swept when it began — `ReviewLayoutRestorer` may run before a
 * frontend session has attached. This cannot widen what is reopened, because only ids already on
 * the record are shown.
 *
 * An id that resolved to a registered tool window in no session stays on the record. Tool-window
 * registration is not guaranteed complete when [ReviewLayoutRestorer] runs at post-startup, and
 * dropping an unresolved id would leave that window hidden with no record that it ever was.
 */
fun restore() {
    // Before anything is shown, so the plugin's own reopening can never look like a re-show.
    disarmReshowWatch()

    val recorded = myState.hiddenByReview.toList()
    if (recorded.isEmpty()) return

    val resolved = mutableSetOf<String>()
    ReviewSessionTargeting.restoreTargets(ReviewClientSessions.getInstance(project).sessions())
        .forEach { session ->
            ClientId.withExplicitClientId(session.clientId) {
                val manager = ToolWindowManager.getInstance(project)
                recorded.forEach { id ->
                    val window = manager.getToolWindow(id)
                    if (window != null) {
                        window.show(null)
                        resolved += id
                    }
                }
            }
        }

    myState.hiddenByReview = recorded.filterNot { it in resolved }.toMutableList()
}
```

`disarmReshowWatch()` does not exist until task 6. Until then, add it as a private no-op:

```kotlin unverified:this stub is replaced wholesale in task 6; it exists only so task 5 compiles on its own
private fun disarmReshowWatch() = Unit
```

- [x] **Step 5.4: Run the whole layout suite**

```bash verified:same filter syntax as step 1.2
./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"
```

Expected: PASS, including `testRestoreReopensAndThenForgetsTheWindowsItReopened` and
`testRestoreKeepsIdsThatDidNotResolveToARegisteredWindow`, which install no fake sessions and so
run against the single local session exactly as before.

- [x] **Step 5.5: Stage**

```bash verified:staged-only, per pipeline.md Git boundaries
git add src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt \
        src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt
```

---

## 5b. Close the fixture's session blind spot

### Task 5b: the mixed-resolution case becomes a test

**Added during implementation, not in the original plan.** Task 5's review raised this as an Important
finding and recommended closing it before the change ships. Recorded here before it was implemented,
so the plan never describes less than the tree contains.

**Why.** The record contract is *an id is forgotten once it resolved to a registered tool window in at
least one session, and kept when it resolved in none.* Task 5 covers resolved-**everywhere** and
resolved-**nowhere**. It cannot cover resolved-**in-A-but-not-B** — which is the case that actually
occurs at post-startup, when `ReviewLayoutRestorer` runs before a frontend session has attached —
because `RecordingToolWindowManager` keys its windows by id alone and knows nothing about sessions.
Production is correct by construction (`resolved` is a set unioned across sessions), but that is an
argument, not a test.

Accepting the argument would be the third time this change let a claim rest on a fixture that cannot
express the thing being claimed, and the plan's own constraints say the fix "has to close it rather
than work around it". Two bugs in this class have already hidden behind exactly this blind spot.

**Files:**
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindows.kt`
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`

**Interfaces:**
- Consumes: `SessionRef` (task 1), the fixture's `ClientId` recording (task 3), `restore()` (task 5).
- Produces: a per-session registration on `RecordingToolWindowManager`, **additive** — every existing
  `register(...)` call keeps resolving in every session, so no existing test changes.

- [x] **Step 5b.1: Write the failing test**

A case where one recorded id resolves only in the `local` session and another only in the `frontend`
session, asserting that **both** are dropped from the record — each resolved somewhere — and that a
third id registered in neither is kept. Under a "resolved in the last session" or "resolved in every
session" reading, this fails.

- [x] **Step 5b.2: Run it and confirm it fails for the right reason**

It must fail because the fixture cannot yet scope a window to a session, not because of an assertion
typo.

- [x] **Step 5b.3: Teach the fixture per-session registration**

Add an optional session restriction to `RecordingToolWindowManager`, with `getToolWindow` consulting
`ClientId.current`. **Default behaviour is unchanged:** a window registered without a restriction
resolves under every id, which is what every existing test relies on.

- [x] **Step 5b.4: Run the whole suite**

Every pre-existing case must still pass unedited.

- [x] **Step 5b.5: Stage**

---

## 6. The re-show watch

### Task 6: Warn when a hidden window is shown again mid-pass

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`

**Interfaces:**
- Consumes: `ReviewClientSessions.messageBus` (task 2), `sweep(target)` (task 4), the
  `disarmReshowWatch()` stub (task 5), which this task replaces.
- Produces: nothing later tasks depend on.

> **Implementation note (task 6, as built).** **The `unverified:` code blocks in Steps 6.1 and 6.3
> below are superseded on two points, and following them literally would build a watch that never
> fires.** They are left in place as the record of what was planned; this note is what was shipped.
>
> 1. **The subscription is on the *project's* message bus, not the swept session's.** There is no
>    per-session bus: `ClientSession.messageBus` is `@Deprecated(level = ERROR)` with the message
>    *"sessions don't have their own message bus"*, `ClientSessionImpl.getMessageBus()` is `final`
>    and throws unconditionally, and `isMessageBusSupported()` is a constant `false`. The plan's own
>    named fallback — `getMessageBus()` — **is** the throwing method. `ReviewClientSessions
>    .messageBus(session)` therefore returns the project bus, and the watch discriminates by watched
>    **id** alone. Step 6.3's KDoc line *"Subscribed on the swept session's own bus, not the
>    project's"* is exactly backwards and was not shipped. The warning still names the swept session,
>    because that is the session the pass acted on — not because the event came from there.
> 2. **The one-argument `toolWindowShown(ToolWindow)` is the overload to implement**, not the
>    two-argument `toolWindowShown(String, ToolWindow)` the Step 6.3 block shows. The two-arg form is
>    `@Deprecated(forRemoval = true)` with an empty body and exists only as the delegate target of the
>    one-arg default; nothing in the platform publishes it. `ToolWindowManagerImpl
>    .fireToolWindowShown` publishes the **one-arg** form from `doShowWindow`, which every show path
>    funnels through. Overriding the two-arg form as printed would compile, subscribe, and silently
>    never fire. `stateChanged`/`ShowToolWindow` is not needed: it is strictly narrower, fired only
>    from `showToolWindow(String)` *after* `doShowWindow` has already fired `toolWindowShown`.
>
> Both were read from the platform bytecode and independently re-derived by a reviewer. A third
> finding, not a plan defect but a limit of the approach, is recorded in `design.md` and in the delta
> spec: under a split IDE a frontend re-show reaches no `ToolWindowManagerListener` at all, so the
> watch degrades to **silence** — never to a wrong answer.

- [x] **Step 6.1: Write the failing tests**

```kotlin unverified:confirm LoggedErrorProcessor is used the same way as in testHideWarnsWhenAWindowIsStillVisibleImmediatelyAfterHideReturns, and that publishing on project.messageBus reaches a subscriber registered on the same bus synchronously
/**
 * The known limitation the KAN-6 post-hide check could not close: it proves `hide()` took effect at
 * the instant it returned, and says nothing about a window re-shown afterwards. This watch answers
 * that by being told, rather than by re-checking at a chosen moment.
 */
fun testAWindowShownAgainDuringThePassIsWarnedAbout() {
    val bus = project.messageBus
    FakeClientSessions.install(
        project,
        testRootDisposable,
        listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
        bus,
    )
    val projectWindow = manager.register("Project", visible = true)
    val controller = controllerWithRecord()
    controller.hideForReview()

    val warnings = mutableListOf<String>()
    LoggedErrorProcessor.executeWith<RuntimeException>(
        object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return false
            }
        },
    ) {
        bus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown("Project", projectWindow)
    }

    assertTrue(
        "a window reopened behind the reviewer's back must be reported, naming the window",
        warnings.any { it.contains("Project") && it.contains("shown again") },
    )
}

/**
 * The plugin's own restoring must never trip the watch: reopening the recorded windows is what
 * ending a pass means. `restore()` disconnects before it shows anything, which is what makes this
 * true by construction rather than by filtering.
 */
fun testRestoringDoesNotReportItselfAsAReShow() {
    val bus = project.messageBus
    FakeClientSessions.install(
        project,
        testRootDisposable,
        listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
        bus,
    )
    manager.register("Project", visible = true)
    val controller = controllerWithRecord()
    controller.hideForReview()

    val warnings = mutableListOf<String>()
    LoggedErrorProcessor.executeWith<RuntimeException>(
        object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return false
            }
        },
    ) {
        controller.restore()
    }

    assertTrue(
        "the plugin's own restore must not be reported as something reopening a window",
        warnings.none { it.contains("shown again") },
    )
}

/**
 * Spec scenario "A second pass reports independently". `hideForReview` reclaims a leftover record by
 * calling `restore()` first, which disarms; the sweep then re-arms over what the *new* pass hid. If
 * arming ever stacked instead of replacing, a window from the first pass would keep being reported
 * during the second, and one re-show would produce two warnings.
 */
fun testASecondPassWatchesWhatTheSecondPassHid() {
    val bus = project.messageBus
    FakeClientSessions.install(
        project,
        testRootDisposable,
        listOf(SessionRef(ClientId("local"), ClientType.LOCAL)),
        bus,
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

    val warnings = mutableListOf<String>()
    LoggedErrorProcessor.executeWith<RuntimeException>(
        object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return false
            }
        },
    ) {
        bus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown("Terminal", second)
        bus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown("Project", first)
    }

    assertEquals(
        "exactly one finding, for the window the second pass hid",
        1,
        warnings.count { it.contains("shown again") },
    )
    assertTrue(warnings.any { it.contains("Terminal") })
}
```

- [x] **Step 6.2: Run them and confirm they fail**

```bash verified:same filter syntax as step 1.2
./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"
```

Expected: `testAWindowShownAgainDuringThePassIsWarnedAbout` FAILS — no warning is produced.
`testRestoringDoesNotReportItselfAsAReShow` passes vacuously, and is the control that keeps it
passing once the watch exists.

- [x] **Step 6.3: Implement the watch**

Replace the `disarmReshowWatch()` stub from task 5, and call `armReshowWatch` at the end of `sweep`:

```kotlin unverified:confirm ToolWindowManagerListener.toolWindowShown(String, ToolWindow) is the signature to override (javap shows both a one-arg and a two-arg form), and that MessageBus.connect() returns a MessageBusConnection with disconnect()
/**
 * The connection carrying the re-show watch for the pass currently running, or `null` when no pass
 * has hidden anything.
 *
 * Armed at the end of a sweep, disarmed at the start of [restore] — *before* it shows anything, so
 * the plugin's own reopening can never be reported. [hideForReview]'s leftover-reclaim branch calls
 * [restore] first, so a second pass re-arms cleanly rather than stacking a second subscription.
 */
private var reshowWatch: MessageBusConnection? = null

private fun disarmReshowWatch() {
    reshowWatch?.disconnect()
    reshowWatch = null
}

/**
 * Reports the first time each of [hidden] becomes visible again while the pass is running.
 *
 * The synchronous post-hide check ([logPostHideVerification]) can only say whether `hide()` had
 * taken effect at the instant it returned; it cannot tell a window re-shown a frame later from one
 * that was never hidden. This closes that gap by being told rather than by re-checking, so a
 * re-show is caught whenever in the pass it happens and there is no interval to justify.
 *
 * Subscribed on the swept session's own bus, not the project's, so it hears about the layout that
 * was actually swept. A session that has gone away since the sweep leaves the watch unarmed — a
 * diagnostic that reports nothing, never a broken hide.
 */
private fun armReshowWatch(target: SessionRef, hidden: List<String>) {
    disarmReshowWatch()
    if (hidden.isEmpty()) return
    val bus = ReviewClientSessions.getInstance(project).messageBus(target) ?: return

    val watched = hidden.toSet()
    val reported = mutableSetOf<String>()
    val connection = bus.connect()
    connection.subscribe(
        ToolWindowManagerListener.TOPIC,
        object : ToolWindowManagerListener {
            override fun toolWindowShown(id: String, toolWindow: ToolWindow) {
                // `reported.add` is the once-per-id guard: a window shown, hidden and shown again
                // during one pass is one finding, not a stream of them.
                if (id in watched && reported.add(id)) {
                    thisLogger().warn(
                        "Review Queue: $id was hidden for this review pass but has been shown " +
                            "again on session ${target.clientId.value} (${target.type}) — " +
                            "something outside this plugin reopened it",
                    )
                }
            }
        },
    )
    reshowWatch = connection
}
```

At the end of `sweep`, immediately after `logPostHideVerification(...)`:

```kotlin unverified:confirm `hidden` is still in scope at that point — it is, it is the local the record was assigned from
armReshowWatch(target, hidden)
```

- [x] **Step 6.4: Run the whole layout suite**

```bash verified:same filter syntax as step 1.2
./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"
```

Expected: PASS, both new cases included.

- [x] **Step 6.5: Stage**

```bash verified:staged-only, per pipeline.md Git boundaries
git add src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt \
        src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt
```

---

## 7. The diagnostic names the session

### Task 7: Every KAN-6 log line says which session was swept

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt:176-271`

**Interfaces:**
- Consumes: `sweep(target)` (task 4).
- Produces: nothing.

- [x] **Step 7.1: Thread the session into both log helpers**

`logSweepOutcome` and `logPostHideVerification` each gain a `session: SessionRef` parameter, and
every message they emit gains the same suffix. Keep every existing branch, level and wording:
KAN-6's `warn`-vs-`info` split is deliberate and is pinned by
`testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible`.

```kotlin unverified:confirm each call site is updated — there are three branches in logSweepOutcome and three warn/info sites in logPostHideVerification
// The suffix, appended to every message in both helpers:
" [session ${session.clientId.value} (${session.type})]"
```

The concrete manager class stays in every message. It is still the fastest way to see which
`ToolWindowManager` answered; the session is the fastest way to see who it answered *for*. Both are
computed once in `sweep` and passed down, so no two lines from one call can disagree.

> **Scope extended during implementation: a third line, the hide loop's own `warn`.** As written,
> step 7.1 covered the two log helpers only, which would have left `sweep`'s per-id
> `"hide() threw for $id on $managerClass; ids earlier than $id in $hidden already had hide()
> invoked…"` as the single KAN-6 sweep diagnostic unable to say whose layout it was hiding — the
> inconsistency a later reader trips on, and on the line that fires when something has *actually* gone
> wrong, which is when the session matters most. This task's own heading is "Every KAN-6 log line says
> which session was swept", so the line is in scope by the task's own terms; it needed naming here
> explicitly only because it is emitted inline in `sweep` rather than by either helper. It now renders
> `target` through the same private `SessionRef.logSuffix()` the helpers use, so the three cannot
> drift apart.
>
> **Control flow is untouched:** the `catch` still names the id, still rethrows immediately, and still
> leaves the record and the "which ids had `hide()` invoked" behaviour exactly as they were. Only the
> message text changed. Being a `warn` it is assertable, and
> `IdeLayoutControllerTest.testTheHideThrewWarningNamesTheSweptSession` pins it — driving a throwing
> `hide()` under a fabricated frontend session and asserting both that the throwable still propagates
> and that the warning names the session. No existing case was edited; the fail-fast behaviour stays
> pinned by `testHideNamesTheIdWhereHideThrowsAndStillPropagatesTheThrow` as before.

- [x] **Step 7.2: Update the KDoc and the `ids.isEmpty()` message**

The KDoc on `logSweepOutcome` and the `ids.isEmpty()` warning both say an empty `ids` means
`ToolWindowManager.getInstance(project)` "is not resolving to the manager the IDE is actually
using". KAN-18 superseded that reading: the manager was right and the session was wrong. Replace the
warning text with:

```kotlin unverified:confirm this is the only branch whose wording asserts a cause, and that no test asserts on this exact string — testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible asserts only that this branch warns
ids.isEmpty() -> thisLogger().warn(
    "Review Queue: hideForReview() sweep found zero tool window ids on $managerClass for " +
        "session ${session.clientId.value} (${session.type}) — that session has no tool windows " +
        "registered, or it is not the session the reviewer is looking at",
)
```

And in the KDoc, replace the sentence beginning "an empty `ids` means" with: *an empty `ids` means
the swept session has no tool windows registered; the session named in the message is the first
thing to check, because KAN-18 was a wrong session rather than a wrong manager.*

> **The `unverified:` claim above was checked and is FALSE — corrected here so the plan does not
> outlive the implementation still saying it.** The block asserts *"no test asserts on this exact
> string — testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible asserts only that this branch
> warns"*. It does more than that: it asserts
> `warnings.any { it.contains("zero tool window ids") }` as its **positive control**, the proof that
> its `LoggedErrorProcessor` recorder can observe a warning on this method at all — without which its
> real assertion (that the `hidden.isEmpty()` branch stays *silent*) would hold vacuously.
>
> So the literal substring **`zero tool window ids`** is load-bearing. The replacement text above
> preserves it, which is why that case passes unedited — but anyone rewording this branch further
> must keep it, or the control stops controlling *while the suite still passes*. That is the one
> failure mode a positive control exists to prevent, and it is silent by construction. The constraint
> is also recorded in `logSweepOutcome`'s KDoc, at the line someone would actually be editing.
>
> The block's other claim — that this is the only branch whose *wording* asserts a cause — held for
> the code but not for the KDoc: two sentences there asserted the superseded manager reading (the one
> named in step 7.2 and the paragraph justifying the manager class). Both were corrected.

- [x] **Step 7.3: Run the whole suite**

```bash verified:the plugin's full test task, as used for the baseline at the top of this plan
./gradlew test
```

Expected: 0 failures, and 231 tests.
<!-- predicted: ./gradlew test after task 7 -->

That total is the baseline plus the cases this plan adds — seven in task 1, three each in tasks 4,
5 and 6. A different total is not a failure in itself; reconcile it against those cases before
moving on.

- [x] **Step 7.4: Stage**

```bash verified:staged-only, per pipeline.md Git boundaries
git add src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt
```

---

## 8. Whole-change verification

### Task 8: Build, full suite, and the sandbox check

**Files:**
- Modify: `README.md` if it describes focus mode's behaviour in a way this change contradicts;
  otherwise none.

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [x] **Step 8.1: Full build**

```bash verified:this repo builds with the Gradle wrapper and the intellijPlatform plugin; `build` runs compilation, tests and plugin verification wiring
./gradlew build
```

Expected: BUILD SUCCESSFUL, 0 test failures.
<!-- predicted: ./gradlew build after task 7 -->

There is no separate lint or formatter task in this project — `build.gradle.kts` declares neither a
detekt nor a ktlint plugin, so `build` is the whole automated gate.

- [x] **Step 8.2: Confirm no forbidden symbol leaked in**

```bash verified:plain grep over the source tree; the two package roots are the ones named in the global constraints
grep -rn "rdserver\|jetbrains\.rd\b" src/ && echo "FORBIDDEN SYMBOL PRESENT" || echo "clean"
```

**Expected: two hits, both KDoc prose in `IdeLayoutController.kt`, and neither a symbol reference.**
The original expectation of `clean` was wrong, and was corrected during implementation: those two
mentions are **pre-existing at the merge base**, they name `rdserver` in explanatory prose about which
manager answers, and the constraint they are being checked against is that no `rdserver` or
`cwm-plugin` **symbol** may be referenced. The grep is deliberately broader than the constraint, so a
non-empty result is not by itself a failure. Read each hit: a hit inside a comment or KDoc passes; an
`import`, a type name, or a call does not. What the grep is really guarding is that neither package
ever reaches the compile classpath — which the build enforces anyway, since those classes are absent
from the plugin's dependencies.

- [x] **Step 8.3: Check the README**

```bash verified:plain grep over the README this repo ships
grep -n -i "focus mode\|tool window" README.md
```

If any line describes focus mode as hiding only the Project panel, or as working everywhere, correct
it. If nothing there contradicts this change, make no edit and say so in the handoff.

- [ ] **Step 8.4: Sandbox run — the only check that can see the real defect**

```bash verified:runIde is the intellijPlatform plugin's sandbox task and this repo's sandbox lives at .intellijPlatform/sandbox/review-queue/IU-2026.2
./gradlew runIde
```

In the sandbox: open a project with a git repo, open Project and Terminal, run **Start Review**.
Both must disappear. Run **End Review**; both must come back.

Then read the log for the new session suffix:

```bash verified:the log path this repo's own KAN-6 investigation read
grep "hideForReview" .intellijPlatform/sandbox/review-queue/IU-2026.2/log/idea.log
```

The sweep line must name a session. Record in the handoff which `ClientType` it named — that is the
one fact this whole change turns on, and the manual test guide `/myflow-do` writes must ask for it
explicitly.

- [x] **Step 8.5: Stage**

```bash verified:staged-only, per pipeline.md Git boundaries
git add -A
```
