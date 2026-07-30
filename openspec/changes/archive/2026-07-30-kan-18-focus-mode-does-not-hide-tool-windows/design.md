## Context

`IdeLayoutController` is the only component that changes the user's IDE layout. `hideForReview()`
sweeps every visible tool window, records the set, and hides it; `restore()` reopens it. KAN-6
shipped diagnostics for a report that the hide did nothing visible, and explicitly did not fix it.

The diagnostic produced, over three sandbox runs on 2026-07-29, the same result each time: the
manager was `com.jetbrains.rdserver.toolWindow.BackendServerToolWindowManager`, the sweep enumerated
22 ids, judged `[Terminal, Project, Database]` visible, and the post-hide re-check confirmed all
three invisible the instant `hide()` returned. The screen disagreed.

### Root cause, from the bytecode

The `intellij.platform.backend.split` module, shipped inside `cwm-plugin`, overrides the
project-level `ToolWindowManager`:

```xml
<!-- verified: plugins/cwm-plugin/lib/modules/intellij.platform.backend.split.jar
     → intellij.platform.backend.split.xml, line 810, IU-2026.2 build 262.8665.258 -->
<projectService serviceInterface="com.intellij.openapi.wm.ToolWindowManager"
                serviceImplementation="com.jetbrains.rdserver.toolWindow.BackendServerToolWindowManager"
                headlessImplementation="com.intellij.toolWindow.ToolWindowHeadlessManagerImpl"
                preload="notHeadless" overrides="true"/>
```

Two of its overrides read the current `ClientId`. Both were read with `javap -c`:

- `getToolWindow(id)` resolves `ClientSessionsManager.getProjectSession(project,
  ClientId.getCurrent())` first, returning `null` when there is no such session. Under a **local**
  id it returns the host `ToolWindowImpl`; under a **client** id it returns either the host window
  or that client's `BackendToolWindow`, depending on the window's extractor mode.
- `hideToolWindow(id, …)` is, in effect:

  ```kotlin
  // verified: javap -c BackendServerToolWindowManager, method hideToolWindow
  val session = ClientSessionsUtil.getCurrentSession(project)
  if (session.isLocal()) super.hideToolWindow(id, …)
  updateBackendToolwindowState(id, session, { Hidden })
  ```

  The second statement is the push that reaches a frontend, and it is addressed to *that session*.

`hideForReview()` runs with no client `ClientId` in scope. It therefore fetched the host tool
windows, hid them — flipping the host `WindowInfo`, which is exactly why the post-hide check
reported "confirmed no longer visible" — and pushed `Hidden` to the local session, which has no
screen.

Every logged fact is consistent with this and with nothing else. The manager lookup, the sweep, the
visibility filter and `hide()` were all correct. Only the scope they ran in was wrong.

### Constraints

- The plugin must keep compiling and behaving identically on a plain, non-split IDE.
- No new Gradle dependency. `ClientId`, `ClientKind`, `ClientType`, `ClientSessionsManager` and
  `ClientProjectSession` are core platform (`intellij.platform.core.jar`, `util.jar`); no `rdserver`
  or `cwm-plugin` symbol may be referenced.
- `IdeLayoutControllerTest` runs entirely against `RecordingToolWindowManager`, which overrides
  `toolWindowIds` and `getToolWindow`. That stub cannot reproduce a `ClientId` defect by
  construction. This is the second bug in this class to hide behind that gap; the fix has to close
  it rather than work around it.

## Goals / Non-Goals

**Goals:**

- A pass hides the tool windows the reviewer can actually see, under a split / remote-dev IDE.
- Ending a pass — including after an IDE quit mid-pass — reopens them.
- A window re-shown during a pass is reported, closing the known limitation in the KAN-6 diagnostic.
- The scoping defect is expressible as a test assertion.

**Non-Goals:**

- Managing a Code With Me guest's layout. A guest is another person.
- Changing which windows a pass hides, how the visible set is determined, or the shape of the
  persisted record.
- Any change to `ReviewSessionService` or `ReviewLayoutRestorer`.
- Resolving whether `BackendToolWindow`'s per-client state and the host `ToolWindowImpl` can
  disagree about visibility for the same session and id — see *Open Questions*.

## Decisions

### Which session the hide sweep acts on

**ID:** `hide-single-target-session`
**Status:** active
**Chosen:** One target session, `FRONTEND` → `CONTROLLER` → `LOCAL` — visibility is measured in
exactly the layout that is mutated, so the flat record and every existing spec scenario stay correct.
**Considered:** *Hide in every non-guest session and record the union* — if a window is visible in
one session and closed in another, the union hides it and a later restore reopens it where the user
had closed it, violating *A window the user had already closed is not reopened*. *A per-session
record keyed by client id* — exact for multi-session setups, but client ids are not stable across an
IDE restart, so the persisted record could not be replayed by `ReviewLayoutRestorer`, the one case
the persistence exists for.

### How restore reaches the right layout

**ID:** `restore-every-non-guest-session`
**Status:** active
**Chosen:** Restore into every non-guest session — restoring is additive and bounded by the record,
so breadth cannot open a window the sweep did not hide, and it survives the frontend session not
having attached when `ReviewLayoutRestorer` runs at post-startup.
**Considered:** *Restore into the same single target session* — symmetric, but at post-startup it can
fall back to `LOCAL`, silently drop the ids from the record and leave the frontend windows hidden
with nothing left to reopen them: precisely the failure the persistence exists to prevent. *Same
target plus a retry when a session appears* — most precise, but adds a session listener and a retry
path to a class whose safety argument rests on being simple.

### How the fix is covered, given the stub cannot reproduce the defect

**ID:** `clientid-recording-fixture`
**Status:** active
**Chosen:** An injectable session-picker seam plus `ClientId` recording in the fixture — the exact
regression becomes an assertion, in the fixture that until now could not express it.
**Considered:** *A pure selection unit test only* — leaves the `withExplicitClientId` wiring to the
manual test guide, which would be the third bug shipped behind this gap. *A fake
`ClientProjectSession` registered with the platform* — closest to production, but
`ClientProjectSession` extends `ComponentManager`, so a faithful fake is large and brittle against
platform changes.

### Whether to close the known limitation in the post-hide diagnostic

**ID:** `reshow-watch-listener`
**Status:** active
**Chosen:** Add the deferred check as a `ToolWindowManagerListener` subscription, always on — the
operator accepted the scope increase after it was flagged. (Implementation established that the bus
must be the project's, not the session's; see *The re-show watch* under **Architecture**.)
Event-driven means no interval to justify, and a re-show is caught whenever in the pass it happens.
**Considered:** *One delayed re-query after a fixed interval* — simple and certain to run, but the
interval is an unsourced guess and it observes a single instant. *Both a listener and a delayed
sweep* — most coverage, largest diff, and two log sources that can disagree. *Leaving the diagnostic
as-is* — the root cause is established, so further instrumentation buys nothing for this fix.
*Gating it behind a Registry key* — the next report would arrive without the evidence, the exact
cycle KAN-6 shipped logging to end.

## Architecture

### Session targeting is a pure rule behind a seam

```kotlin
internal data class SessionRef(val clientId: ClientId, val type: ClientType)

internal object ReviewSessionTargeting {
    fun hideTarget(sessions: List<SessionRef>): SessionRef?
    fun restoreTargets(sessions: List<SessionRef>): List<SessionRef>
}
```

`ClientType` has exactly four values — `LOCAL`, `FRONTEND`, `CONTROLLER`, `GUEST`. No built-in
`ClientKind` expresses "non-guest": `ClientKind.OWNER` is `LOCAL ∪ CONTROLLER` and excludes
`FRONTEND`; `ClientKind.REMOTE` is `CONTROLLER ∪ GUEST`. Both were read from `ClientType.isOwner`
and `ClientType.isRemote` in the disassembly. The filter is therefore written by hand over
`ClientType`, and the enumeration asks for `ClientKind.ALL`.

`ReviewClientSessions`, a project service, maps
`ClientSessionsManager.getProjectSessions(project, ClientKind.ALL)` to `List<SessionRef>`. It is the
injectable seam; the rules above are pure and tested on their own.

### Hide and restore are deliberately asymmetric

**Hide** enters one session's `ClientId` and does the whole sweep inside it. **Restore** enters each
non-guest session in turn and shows the recorded ids, forgetting an id once it resolved in at least
one session.

The asymmetry is safe in one direction only, and that is why it is asymmetric. Hiding must never
touch a layout whose visibility it did not measure, or it would hide a window the user had closed
there. Restoring may be broad because it is bounded by the record.

### The re-show watch

After a sweep completes, subscribe to `ToolWindowManagerListener.TOPIC` on the **project's** message
bus. The first time a recorded id reports `toolWindowShown`, one `warn` names the id and the session
that was swept.

**Corrected during implementation: one event, and it is `toolWindowShown(ToolWindow)`.** This section
originally also listened for a `ShowToolWindow` state change. Read from `intellij.platform.ide.jar`
and `intellij.platform.ide.impl.jar` with `javap -c`: `ToolWindowManagerImpl.fireToolWindowShown`
publishes the **one-argument** `toolWindowShown(ToolWindow)` from `doShowWindow`, which every show
path funnels through, and that is the only overload the platform ever publishes — the two-argument
`toolWindowShown(String, ToolWindow)` is `@Deprecated(forRemoval = true)` and exists only as the
one-argument default's delegate. `fireStateChanged(ShowToolWindow, toolWindow)` is fired by
`showToolWindow(id)` alone, a strictly narrower path that has already reached `doShowWindow` — and so
already fired `toolWindowShown` — by the time it fires, so subscribing to it as well would add a
second source of the same finding and no coverage.

**Corrected during implementation: there is no per-session message bus.** This section originally
specified the target session's own bus, on the hypothesis that `ClientSession` extends
`ComponentManager` and so usefully exposes `getMessageBus()`. It does not:
`ClientSession.messageBus` is `@Deprecated(level = ERROR)` with the message *"sessions don't have
their own message bus"*, `ClientSessionImpl.getMessageBus()` is `final` and throws unconditionally,
and `isMessageBusSupported()` is a constant `false`. The plan's named fallback (`getMessageBus()`)
does not help, because that method *is* the thing that throws.

The consequence is bounded, and it changes the mechanism rather than the outcome. The watch is still
armed over exactly the ids the sweep hid, and still names the swept session in its warning, so every
scenario in the delta spec is still met. What it loses is session discrimination: a project-bus
subscription cannot say which session showed the window, so a watched id re-shown in some *other*
session would be reported against the swept one. That is a diagnostic naming the wrong session in a
multi-session configuration — accepted, because the alternative is no watch at all, and because the
id filter, which decides whether to warn, is unaffected.

`restore()` disconnects **before** it shows anything, so the plugin's own reopening cannot trigger
the warning. `hideForReview()`'s leftover-reclaim branch already calls `restore()` first, so a second
pass re-arms cleanly.

### Logging

Every KAN-6 line is kept and extended to name the chosen session's `ClientId` value and
`ClientType`. The new re-show `warn` is the only line added.

## Risks / Trade-offs

- **Entering a `ClientId` the platform does not know may affect service resolution inside tests** →
  the risk turned out not to exist, and the mitigation this section proposed would have caused a
  worse one. **Corrected during implementation.** A project service does still resolve under a
  fabricated, unregistered id — pinned by a test. And
  `ClientId.Companion.addFakeLocalId(clientId, disposable)`, proposed here on the belief that it
  keeps `ClientId.current` returning the fake id, does the opposite: it adds the id's *value* to
  `ClientId.fakeLocalIds`, after which `withClientIdImpl` substitutes `ClientId.localId` for that
  value on every `withExplicitClientId` entry. Registering a fabricated id is therefore exactly what
  destroys the recording. Measured, not inferred: with the call present, the scoping assertion failed
  `expected <[ClientId(value=kan-18-frontend)]> but was <[ClientId(value=Host)]>` — every
  session-scoping assertion would have held vacuously. The fixture does not call it, and the
  assertions stay on the recorded ids rather than moving to the seam boundary.
- **`ToolWindowManagerListener` fidelity across the split is unverified** → **now measured, and it is
  a real gap that the watch degrades into silence rather than into a wrong answer.**
  `BackendServerToolWindowManager.fireStateChanged` does call `super`, which publishes on the
  project's bus, so events *can* originate from it. But its `showToolWindow(String)` override calls
  `ToolWindowManagerImpl.showToolWindow` **only when the current session is local**. It pushes
  `updateBackendToolwindowState` on *both* paths — the local one does both — so what a non-local
  session loses is specifically the `super` call, and with it `doShowWindow`: neither
  `toolWindowShown` nor a `ShowToolWindow` state change is published for it. Since
  `ToolWindowImpl.show(Runnable)` is just `toolWindowManager.showToolWindow(id)`, a window re-shown in
  a *frontend* session under a split IDE emits nothing this watch — or any other
  `ToolWindowManagerListener` subscription — can see. Read with `javap -c` from
  `plugins/cwm-plugin/lib/modules/intellij.platform.backend.split.jar`. Accepted: no listener choice
  closes it, the watch is then silent rather than wrong, and the local case it does cover is where the
  KAN-6 limitation was actually reported.
- **Restoring into several sessions could reopen a window closed in a second frontend** → only ids
  already on the record are shown, and a second frontend for the same user is not a configuration
  this plugin has ever been reported in. Accepted over the startup failure the alternative causes.
- **The sweep now depends on session enumeration succeeding** → an empty session list is treated as
  "no target", which is logged and leaves the layout untouched rather than hiding in the wrong place.

## Migration Plan

None. No persisted state shape changes, no dependency changes, and a plain local installation takes
the same path it takes today.

## Open Questions

Whether `BackendToolWindow`'s per-client state and the host `ToolWindowImpl` can ever disagree about
visibility for the *same* session and id — the extractor-mode branch in `getToolWindow` returns the
host object for some modes and the per-client object for others. It does not affect this fix:
entering the session's `ClientId` makes both branches address the right session, because
`hideToolWindow` reads the session rather than the object. Recorded so a later round does not
re-derive it.

### Whether a restore that runs without the frontend present can lose that frontend's window

**Raised by the review panel, deliberately not resolved in this change, and the operator's call.**

Two reviewers independently derived the same sequence from the IU-2026.2 bytecode. Hiding as `FRONTEND`
never flips the host `WindowInfo` — `hideToolWindow` skips `super` for a non-local session — so only
that client's state records the window as hidden. If the IDE is then quit mid-pass and
`ReviewLayoutRestorer` runs at post-startup while only `LOCAL` exists, `getToolWindow` under `LOCAL`
returns the *host* `ToolWindowImpl` and `show()` on it succeeds trivially against state nothing hid.
`restore()` reads that no-op as "reopened" and forgets the id. A frontend reattaching afterwards would
still have the window hidden, with nothing left to retry it.

**Why it is recorded here rather than fixed.** It is not a regression introduced by this change: the
"forgotten once reopened anywhere" rule predates every fix round, and the last round's `failedAnywhere`
tracking made the bookkeeping strictly more conservative. And every available fix reopens a decision
this document already made deliberately — either a **per-session record** (rejected above: client ids
are not stable across a restart, so `ReviewLayoutRestorer` could not replay it, which is the one case
the persistence exists for) or a **session-attach listener plus retry** (rejected above: it adds a
listener and a retry path to a class whose safety argument rests on being simple). Remaking either of
those silently, inside a fix round, would be the wrong way to revisit them.

**The load-bearing premise is unverified.** The sequence requires the frontend's per-client hidden
state to *survive the backend restart* and still be set when that frontend reattaches. Neither reviewer
established this; both assumed it. If per-client state is created fresh per session, the scenario cannot
occur. `docs/manual-test/kan-18-focus-mode-does-not-hide-tool-windows.md` §4a asks for exactly this
observation on a real split setup — settle it there before designing anything.
