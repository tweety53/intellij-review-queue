# Focus mode: sweep the session that drives the visible UI

**Date:** 2026-07-30
**Ticket:** [KAN-18](https://tweety53.atlassian.net/browse/KAN-18) — *Focus mode does not hide tool
windows: the plugin resolves the remote-dev backend ToolWindowManager*
**Status:** approved

## Why

Starting a review pass leaves Project, Terminal and every other tool window on screen. The pass
itself starts, the diff opens; only the hide fails. KAN-6 shipped the diagnostic that made this
observable and explicitly did not fix it.

The KAN-6 diagnostic established, over three sandbox runs, that
`ToolWindowManager.getInstance(project)` resolves to
`com.jetbrains.rdserver.toolWindow.BackendServerToolWindowManager`, that the sweep enumerated 22 ids
and judged `[Terminal, Project, Database]` visible, and that all three reported invisible the
instant `hide()` returned. What it could not say was *why the screen disagreed*.

## Root cause

Established by disassembling IU-2026.2 (build 262.8665.258), not inferred.

The `intellij.platform.backend.split` module, shipped inside `cwm-plugin`, overrides the
project-level `ToolWindowManager`:

```xml
<!-- verified: plugins/cwm-plugin/lib/modules/intellij.platform.backend.split.jar
     → intellij.platform.backend.split.xml, line 810 -->
<projectService serviceInterface="com.intellij.openapi.wm.ToolWindowManager"
                serviceImplementation="com.jetbrains.rdserver.toolWindow.BackendServerToolWindowManager"
                headlessImplementation="com.intellij.toolWindow.ToolWindowHeadlessManagerImpl"
                preload="notHeadless" overrides="true"/>
```

Two of that class's overrides read the **current `ClientId`**, and both were disassembled with
`javap -c`:

- `getToolWindow(id)` resolves
  `ClientSessionsManager.getProjectSession(project, ClientId.getCurrent())` first. Under a **local**
  id it returns the host `ToolWindowImpl`. Under a **client** id it returns either the host window
  or that client's `BackendToolWindow`, depending on the window's extractor mode.
- `hideToolWindow(id, …)` is, in effect:

  ```kotlin
  // verified: javap -c BackendServerToolWindowManager, method hideToolWindow
  val session = ClientSessionsUtil.getCurrentSession(project)
  if (session.isLocal()) super.hideToolWindow(id, …)
  updateBackendToolwindowState(id, session, { Hidden })
  ```

  The second statement is the push that reaches a frontend. It is addressed to **that session**.

`IdeLayoutController` runs with no client `ClientId` in scope. So it fetched the host tool windows,
hid them — which flips the host `WindowInfo` and is exactly why the post-hide check reported
"confirmed no longer visible" — and pushed `Hidden` to the *local* session, which has no screen. The
frontend session was never told.

Every logged fact is consistent with this and with nothing else. The manager, the sweep, the
visibility filter and `hide()` were all correct. Only the scope they ran in was wrong.

This also settles the ticket's second open question: the defect is specific to a split /
remote-dev IDE. In a plain monolith the only session is `LOCAL`, and the code already ran under it.

## What changes

Run the sweep, and the restore, under the `ClientId` of the session that drives the visible UI.

`ClientId`, `ClientKind`, `ClientType`, `ClientSessionsManager` and `ClientProjectSession` all live
in `intellij.platform.core.jar` and `util.jar` — core platform. No `rdserver` or `cwm-plugin`
symbol is referenced, and `build.gradle.kts` is untouched.

### Session targeting is a pure rule behind a seam

```kotlin
internal data class SessionRef(val clientId: ClientId, val type: ClientType)

internal object ReviewSessionTargeting {
    /** The one session whose layout the reviewer is looking at. */
    fun hideTarget(sessions: List<SessionRef>): SessionRef?          // FRONTEND > CONTROLLER > LOCAL
    /** Every session that could be showing the reviewer's layout. */
    fun restoreTargets(sessions: List<SessionRef>): List<SessionRef> // every non-GUEST
}
```

`ClientType` has exactly four values — `LOCAL`, `FRONTEND`, `CONTROLLER`, `GUEST`. A `GUEST` is
another person in a Code With Me session and is never touched by either rule.

No built-in `ClientKind` expresses "non-guest": `ClientKind.OWNER` is `LOCAL ∪ CONTROLLER` and
excludes `FRONTEND`, and `ClientKind.REMOTE` is `CONTROLLER ∪ GUEST`. Both were read from
`ClientType.isOwner`/`isRemote` in the disassembly. The filter is therefore written by hand over
`ClientType`, and `ClientKind.ALL` is what the enumeration asks for.

A thin project-level service maps
`ClientSessionsManager.getProjectSessions(project, ClientKind.ALL)` to `List<SessionRef>`. That
service is the injectable seam; the rule above is pure and tested on its own.

### Hide and restore are deliberately asymmetric

**Hide** enters exactly one session's `ClientId` and does the whole sweep inside it — enumerate,
filter by visibility, record, hide. Visibility is measured in the one layout that is mutated, so
`hiddenByReview` stays a flat `List<String>` and every existing scenario in
`openspec/specs/review-layout-management/spec.md` holds unchanged.

**Restore** enters each non-guest session in turn and shows the recorded ids. An id is forgotten
once it resolved in **at least one** session, and kept otherwise — which preserves the existing
"an id that did not resolve stays on the record" contract.

The asymmetry is the point, and it is safe in one direction only. Hiding must never touch a layout
whose visibility it did not measure, or it would hide a window the user had closed there. Restoring
can be broad because it only ever reopens ids already on the record: it cannot open a window the
sweep did not hide. The breadth is what keeps the quit-mid-pass path working when
`ReviewLayoutRestorer` runs at post-startup before the frontend session has attached.

### A re-show watch replaces guessing about asynchrony

The ticket records a known limitation: the post-hide check is synchronous, so it cannot tell a
window re-shown a frame later from a genuine no-op.

After a sweep completes, `IdeLayoutController` subscribes to `ToolWindowManagerListener.TOPIC` on
the **target session's** message bus — `ClientSession` extends `ComponentManager` and exposes
`getMessageBus()`, so the subscription is scoped to the session that was swept rather than to the
project at large. The first time a recorded id reports `toolWindowShown` / a `ShowToolWindow` state
change, one `warn` names the id and the session.

`restore()` disconnects **before** it shows anything, so the plugin's own reopening can never
trigger the warning. `hideForReview()`'s leftover-reclaim branch already calls `restore()` first, so
a second pass re-arms cleanly.

There is no timer and no interval to justify. A re-show is caught whenever in the pass it happens,
which a single delayed re-query could not do.

This is scope beyond what KAN-18 asks for, taken deliberately — see *Decisions*.

### Logging

Every KAN-6 log line is kept, and each is extended to name the chosen session's `ClientId` value and
`ClientType`. The next log read then confirms the scoping in one line instead of another
patch-and-rerun cycle — the same reason KAN-6 added the concrete manager class.

The new `warn` for a re-shown window is the only line added.

## Components

| File | Change |
|------|--------|
| `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargeting.kt` | New. `SessionRef`, the two pure rules. |
| `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewClientSessions.kt` | New. Project service; enumerates real sessions as `SessionRef`. The seam. |
| `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt` | `hideForReview()` and `restore()` scoped by `ClientId`; re-show watch; log lines name the session. |
| `src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindows.kt` | Records `ClientId.getCurrent()` at each `getToolWindow`, `hide`, `show`. |
| `src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargetingTest.kt` | New. The pure rules across every `ClientType` combination. |
| `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt` | Scoping assertions and re-show-watch cases added; existing cases unchanged. |

`ReviewLayoutRestorer` is unchanged — it already calls `restore()` on the EDT, and the breadth rule
lives inside `restore()`.

## Testing

`IdeLayoutControllerTest` has run entirely against `RecordingToolWindowManager`, which overrides
`toolWindowIds` and `getToolWindow` itself. That stub cannot reproduce a `ClientId` defect by
construction, which the ticket names as the gap two bugs have now hidden behind.

The gap is closed by making the fixture record what it was asked under, rather than by simulating a
split IDE:

1. **Pure rule tests.** `hideTarget` and `restoreTargets` over every combination of `LOCAL`,
   `FRONTEND`, `CONTROLLER` and `GUEST`, including the empty list and guest-only.
2. **Scoping tests.** With the seam returning a fake frontend session, assert that every
   `getToolWindow`, `hide` and `show` the sweep performed was made under that session's `ClientId`
   — the defect, stated as an assertion. With the seam returning only a local session, assert the
   behaviour is byte-for-byte what it is today.
3. **Restore breadth tests.** With two non-guest sessions, assert `show` reached both and that an
   id resolving in one is forgotten while an id resolving in none is kept. Assert a guest session
   is never entered.
4. **Re-show watch tests.** Assert one `warn` when a recorded id is shown during the pass, and
   **no** warning when `restore()` is what shows it.

Entering a `ClientId` the platform does not know can affect service resolution inside a test.
`ClientId.Companion.addFakeLocalId(clientId, disposable)` exists for exactly this and keeps
`ClientId.getCurrent()` returning the fake id, so the assertions still bite. Implementation verifies
this before relying on it; if it does not hold, the scoping assertions move to the seam boundary
instead.

## Must not regress

Each of these is load-bearing and named in the ticket or in the current KDoc:

- The record is written **before** anything is hidden, so a part-way failure still leaves a
  restorable record.
- The sweep enumerates every visible window rather than consulting a fixed id list.
- The hide loop names the id `hide()` threw on and rethrows — fail-fast is unchanged.
- The post-hide re-query still swallows its own throwable and never breaks a successful hide.
- `LEGACY_IDS` pruning on `loadState` stays.
- Every KAN-6 diagnostic line stays, and the pre-hide line is still emitted before the mutation.

## Decisions

### Which session the hide sweep acts on

**ID:** `hide-single-target-session`
**Status:** active
**Chosen:** One target session, `FRONTEND` → `CONTROLLER` → `LOCAL` — visibility is measured in
exactly the layout that is mutated, so the flat record and every existing spec scenario stay
correct.
**Considered:** *Hide in every non-guest session and record the union* — if a window is visible in
one session and closed in another, the union hides it and a later restore reopens it where the user
had closed it, violating "a window the user had already closed is not reopened". *A per-session
record keyed by client id* — exact for multi-session setups, but client ids are not stable across an
IDE restart, so the persisted record could not be replayed by `ReviewLayoutRestorer`, the one case
the persistence exists for.

### How restore reaches the right layout

**ID:** `restore-every-non-guest-session`
**Status:** active
**Chosen:** Restore into every non-guest session — restore is additive and bounded by the record, so
breadth costs nothing, and it survives the frontend session not having attached when
`ReviewLayoutRestorer` runs at post-startup.
**Considered:** *Restore into the same single target session* — symmetric, but at post-startup it
can fall back to `LOCAL`, silently drop the ids from the record and leave the frontend windows
hidden with nothing left to reopen them: precisely the failure the persistence exists to prevent.
*Same target plus a retry when a session appears* — most precise, but adds a session listener and a
retry path to a class whose safety argument rests on being simple.

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
**Chosen:** Add the deferred check as a `ToolWindowManagerListener` subscription on the target
session, always on — the operator accepted the scope increase after it was flagged. Event-driven
means no interval to justify and a re-show is caught whenever it happens.
**Considered:** *One delayed re-query after a fixed interval* — simple, but the interval is an
unsourced guess and it observes a single instant. *Both a listener and a delayed sweep* — most
coverage, largest diff, and two log sources that can disagree. *Leaving the diagnostic as-is* — the
root cause is established, so further instrumentation buys nothing for this fix; rejected in favour
of closing the gap now. *Gating it behind a Registry key* — the next report would arrive without the
evidence, the exact cycle KAN-6 shipped logging to end.

## Open question deliberately left open

Whether `BackendToolWindow`'s per-client state and the host `ToolWindowImpl` can ever disagree about
visibility for the *same* session and id — the extractor-mode branch in `getToolWindow` returns the
host object for some modes and the per-client object for others. It does not affect this fix:
entering the session's `ClientId` makes both branches address the right session, because
`hideToolWindow` reads the session rather than the object. It is recorded here so a later round does
not re-derive it.
