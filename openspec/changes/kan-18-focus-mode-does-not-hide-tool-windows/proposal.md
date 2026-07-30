## Why

Starting a review pass leaves Project, Terminal and every other tool window on screen. The pass runs
and the diff opens; only the hide fails. KAN-6 shipped the diagnostic that identified this and
deliberately did not fix it.

The cause is now established from the IU-2026.2 bytecode rather than inferred: under the split /
remote-dev backend, `ToolWindowManager`'s `getToolWindow` and `hideToolWindow` both resolve the
current `ClientId` and address that session. `IdeLayoutController` runs with no client `ClientId` in
scope, so it hid the *local* session's layout — which has no screen — while the frontend session the
reviewer is looking at was never told.

## What Changes

- `IdeLayoutController.hideForReview()` runs its whole sweep inside the `ClientId` of the one
  session driving the visible UI, chosen `FRONTEND` → `CONTROLLER` → `LOCAL`. Code With Me guests
  are never touched.
- `IdeLayoutController.restore()` runs inside **every** non-guest session in turn. An id is
  forgotten once it resolved in at least one of them, and kept otherwise.
- A new project service enumerates the project's client sessions; a new pure object holds the two
  selection rules. The service is the seam that makes the scoping testable.
- After a sweep, a `ToolWindowManagerListener` subscription on the project's message bus (the
  platform has no per-session bus — see `design.md`, *The re-show watch*)
  warns the first time a hidden window is shown again during the pass — closing the known limitation
  that the synchronous post-hide check cannot distinguish a re-show from a no-op. `restore()`
  disconnects before it shows anything, so the plugin's own reopening never triggers it.
- Every KAN-6 diagnostic line is kept and extended to name the chosen session's `ClientId` and
  `ClientType`.
- The test fixture records `ClientId.getCurrent()` at each `getToolWindow`, `hide` and `show`, so the
  regression that has now hidden twice behind a cooperating stub becomes an assertion.

No public behaviour changes in a plain, non-split IDE: the only session there is `LOCAL`, which is
what the code already ran under.

## Capabilities

### New Capabilities

*(none — this changes how an existing capability is fulfilled and adds requirements to it)*

### Modified Capabilities

- `review-layout-management`: adds requirements fixing which client session a pass hides in and
  restores into, and requiring a re-shown window to be reported. The existing requirements
  *A pass hides every visible tool window* and *Persisted state naming an unmanaged window is pruned
  on load* are unchanged.

## Impact

- `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt` — scoping, restore breadth,
  re-show watch, log lines.
- `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargeting.kt` — new, pure selection rules.
- `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewClientSessions.kt` — new project service, the seam.
- `src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindows.kt` — records the calling `ClientId`.
- `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt` — scoping, breadth and
  re-show cases.
- `src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewSessionTargetingTest.kt` — new.
- `docs/manual-test/kan-18-focus-mode-does-not-hide-tool-windows.md` — new, written by `/myflow-do`.

**No dependency changes.** `ClientId`, `ClientKind`, `ClientType`, `ClientSessionsManager` and
`ClientProjectSession` are core platform (`intellij.platform.core.jar`, `util.jar`). No `rdserver`
or `cwm-plugin` symbol is referenced and `build.gradle.kts` is untouched.

`ReviewSessionService` and `ReviewLayoutRestorer` call `hideForReview()`/`restore()` and are
unchanged — the new behaviour lives entirely inside those two methods.
