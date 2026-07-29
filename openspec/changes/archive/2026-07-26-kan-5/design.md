## Context

Full design narrative: `docs/superpowers/specs/2026-07-26-remove-review-tool-window-design.md`. This
file records the decisions and their alternatives; that one explains the mechanics.

The plugin currently has two UIs for one job. `ReviewQueuePanel` (a right-anchored tool window) owns
scope selection, the queue tree, a progress label, an error label, and Scope / Start Review / End
Review / Refresh / Reset All. The review diff viewer's toolbar owns per-file navigation plus
confirming copies of those four session controls, injected via
`DiffUserDataKeys.CONTEXT_ACTIONS`. `IdeLayoutController.hideForReview()` hides both the Project and
Review Queue windows when a pass starts — so the panel is off screen for the whole of the activity it
exists to support.

Three existing constraints shape every decision below, all recorded in KDoc written from prior
failures:

- `ReviewQueueService.refreshExecutor` is single-threaded specifically so that two rebuilds never
  resolve git concurrently for one project (`:95-97`).
- Resolving a scope must never happen under a read action — an earlier version did, and
  `OSProcessHandler.checkEdtAndReadAction` killed the refresh (`:130-141`).
- The async apply uses `ModalityState.nonModal()` so a rebuild cannot land underneath a dialog the
  user is deciding in (`:158-165`).

An architect pass over the approved design found two of its claims false; both corrections are
recorded as decisions below (`start-review-shortcut`, `layout-state-migration`).

## Goals / Non-Goals

**Goals:**

- Remove the tool window and everything that only exists to serve it.
- Give every command it carried a home that works without it.
- Put scope selection in the diff window, as the ticket asks, in a form that is actually usable there.
- Keep the plugin read-only with respect to the repository, as it is today.

**Non-Goals:**

- Changing `MarkReviewedAction`'s four chords or the macOS navigation cluster.
- Enabling rename detection.
- Changing the cursor-fallback behaviour on refresh.
- Restoring an out-of-session tree. The flat file-list popup replaces it; a tree inside a chooser
  popup was already rejected in `ReviewFileList`'s KDoc and that reasoning stands.

## Decisions

### Out-of-session commands live in a Tools menu group

**ID:** `out-of-session-surface`
**Status:** active
**Chosen:** A `Review Queue` group under `ToolsMenu` holding Scope, Start Review, Show File List,
Refresh, Reset All — discoverable, and every command keeps a home once the panel is gone.
**Considered:** Shortcut and Find Action only — nothing new to lay out, but undiscoverable for anyone
who has not read the README. A single Start Review action with everything else confined to the diff
toolbar — smallest surface, but Reset All becomes unreachable with no pass running.

### The queue is resolved on demand, not at project open

**ID:** `queue-warm-up`
**Status:** active
**Chosen:** A new synchronous `resolveNow(scope)` run at the point of use under a modal progress —
zero cost at project open, and "Start Review" honestly means "read the scope now and walk it".
**Considered:** A `postStartupActivity` refresh — keeps every action's precise enablement, at the cost
of git subprocesses on every project open for every user. Lazy first-touch — no modal, but the first
press can act on a queue that has not landed.

### Per-root errors move to a balloon

**ID:** `error-surface`
**Status:** active
**Chosen:** A `ScopeErrorNotifier` in the existing `Review Queue` notification group, firing only when
the error map differs from the last reported one — `changeListUpdateDone` lands on every VCS event, so
firing on non-empty would balloon on every rebuild. `CompletionNotifier.armed` is the precedent.
**Considered:** A footer in the file-list popup — next to the list it truncated, but silent for anyone
who never opens the popup. A dialog on the resolve — loudest, most interruptive, and says nothing
about failures from background rebuilds mid-pass.

### A pass still hides the Project tool window

**ID:** `layout-management`
**Status:** active
**Chosen:** `MANAGED_IDS` becomes `["Project"]`; `IdeLayoutController`, `ReviewLayoutRestorer` and the
persistence all stay. The ticket asks to remove the right panel, not to stop giving the reviewer a
full-width diff.
**Considered:** Dropping layout management entirely — about 100 lines and a whole failure mode gone,
but the reviewer keeps the Project tree beside the diff, and stale persisted state needs cleanup
either way.

### Scope selection is allowed mid-pass

**ID:** `mid-pass-scope-change`
**Status:** active
**Chosen:** Lift the restriction — choosing a scope mid-pass confirms, then rebuilds the queue and
restarts the pass, keeping all marks. The only toolbar-bearing diff tab is the session's own, so under
the old rules a toolbar scope control would be visible *only* where it is always greyed out. This also
collapses two behaviours into one shared `switchScope`.
**Considered:** Menu-only, noting the deviation from the ticket — simplest, but leaves the ticket's
explicit parenthetical unimplemented. A permanently-greyed toolbar copy for symmetry with
`DiffStartReviewAction` — literal compliance, dead control.

### The pass resettles in place rather than ending and restarting

**ID:** `resettle-in-place`
**Status:** active
**Chosen:** `switchScope` rebuilds the session and calls the existing private `showCurrent()`, which
replaces the diff tab in place. `end()` then `start()` would restore the layout and re-hide it,
flashing the Project window open and shut. Needs no new seam in `core/`: `ReviewSession` is a pure
value type, `showCurrent()` already does `settleOn(live)` and ends the pass when nothing is showable,
and `switchScope` lives in the same class. `EditorTabDiffPresenter.close()` already nulls `openFile`
before closing, so the swap does not trip the `fileClosed` listener.
**Considered:** `end()` then `start()` — reuses public API only, at the cost of visible layout churn
mid-pass.
**Constraint:** `resolveNow` must run before `session` is reassigned, so a cancelled progress leaves
the pass untouched.
**Corrected during implementation — the idle branch does more than record.** With no pass running,
`switchScope` calls `ReviewQueueService.setScope`, which fires `refresh()`, so the scope is resolved
immediately rather than merely noted for the next gesture. Two consequences, both accepted for now and
recorded here so neither is rediscovered as a bug: git is resolved **twice per user intent**, because
the following Start Review / Show File List / Refresh resolves the same scope again and discards this
result; and `setScope` records the scope **before** its rebuild lands, so `snapshot()` briefly reports a
scope that does not describe `items` — the same scope/items mismatch `resolve-now-scheduling` removed
from `resolveNow`, and now the last path that can produce it. Deleting the `refresh()` alone would make
that mismatch permanent instead of brief, so any change here is a behaviour change with its own test.

### Synchronous resolution runs its git work on `refreshExecutor`

**ID:** `resolve-now-scheduling`
**Status:** active
**Chosen:** The progress body submits to `refreshExecutor` and awaits the future under the indicator.
`runProcessWithProgressSynchronously` otherwise runs on a platform pooled thread, and bumping
`refreshGeneration` does **not** cancel an executor task already inside `source.resolve` — so a
VCS-triggered refresh and a user's resolve could be in `git status` on one root simultaneously,
colliding on `index.lock` and surfacing as an error balloon at the moment Start Review was pressed.
The await is cancellable and cannot deadlock, since executor tasks only run git and `invokeLater`.
`RejectedExecutionException` after `dispose()` is caught and treated as "no queue".
**Considered:** A lock or semaphore around the resolve body — same serialisation, second mechanism to
reason about, invisible in the executor's KDoc. Accepting the race — cheapest, and relies on per-root
error reporting to render a failure this design would have introduced.

### Synchronous resolution asserts its threading preconditions

**ID:** `resolve-now-threading`
**Status:** active
**Chosen:** `ThreadingAssertions.assertEventDispatchThread()` at entry, plus KDoc naming all three
preconditions — EDT, no write access, no modal already up — and stating that this is the one
deliberate exception to the nonModal rule. `ApplicationImpl.runProcessWithProgressSynchronously`
short-circuits to running the body **inline on the calling thread** when
`isDispatchThread() && isWriteAccessAllowed()`, which would run git under a write action and revive a
documented past failure. Also: a generation re-check before the direct apply, and an explicit
`checkCanceled()`, because the `ThrowableComputable` overload discards the cancelled flag and returns
normally when a cancel lands after the body's last git call.
**Considered:** Applying via `invokeLater(nonModal)` like `refresh()` — uniform, but destroys the
point, since Start Review would begin before the queue was populated. Returning the rebuild and
letting callers apply it — pushes the threading question into three action classes.

**Amended during apply, from a review finding.** Two details of this decision were wrong as first
written, and both were caught by the per-task reviewer reading the 2026.2 bytecode:

1. `checkCanceled()` must sit **inside** the progress body, not after it returns. On return, control is
   back on the bare EDT, which has no progress indicator — so a `checkCanceled()` there can never
   observe anything. It was a no-op whose comment claimed protection it did not provide. Inside the
   body it is effective, and it narrows the cancel window rather than closing it: a cancel arriving
   after that line is invisible either way, and the KDoc now says so.
2. The scope must **not** be recorded before the work. Assigning it at entry left every early return
   with the requested scope recorded and no queue matching it: `snapshot()` would name a scope that
   does not describe `items`, and the next `changeListUpdateDone` would resolve and apply the scope the
   user had just cancelled — switching the queue underneath a running pass seconds after they declined.
   `scope` is now assigned together with the apply, which also satisfies `applyRebuild`'s existing
   `rebuild.scope != scope` guard. `ResolveNowTest` pins this on both the cancel and the failure path,
   using a non-default scope, because asserting against `Staged` cannot distinguish "left alone" from
   "assigned" and so could not see the bug at all.

**Amended again, from the final review panel.** Two further details of this decision were wrong.

3. **Precondition 2's stated reason was false, and it is now checked rather than documented.** Git
   never runs under the write action — `resolve-now-scheduling` hands it to `refreshExecutor` — so the
   documented past failure is not the hazard. The real one is that the inline short-circuit makes
   `awaitWithCheckCanceled` block the EDT while the write lock is held, with no dialog and no cancel
   button, and no platform assertion firing because no read action is involved: a **deadlock, not a
   failure**. It is enforced with a plain `check(!isWriteAccessAllowed)`, because
   `ThreadingAssertions` has no write-access-not-allowed assertion in 2026.2 — the available
   `assertNoReadAccess` is a different and stricter precondition. `ResolveNowTest` drives `resolveNow`
   inside a `WriteAction` and pins that it refuses before the progress is raised.
4. **The generation is bumped with the apply, not at entry**, which retires the first "accepted cost"
   below as a defect rather than a trade-off. Bumping at entry meant a cancelled or failed resolve
   invalidated an in-flight `refresh()` result that nothing reschedules, and the claim that this was
   *self-correcting* is false mid-pass: `markCurrent`, `toggleCurrent`, `previous`, `nextFile` and
   `jumpTo` all read the queue without resolving it, and `ShowFileListAction` skips the resolve during
   a pass on purpose. A file rewritten by a fix round would therefore keep its mark against stale
   content and the pass would skip it — the guarantee content-addressed marks exist to provide.
   Neither bug this decision previously fixed comes back: the scope is still recorded only with the
   apply, and a stale rebuild still cannot be applied, because `refresh()` re-checks the generation
   before its work *and* before its apply, and `applyRebuild`'s scope guard rejects a rebuild resolved
   for a scope no longer in effect. The generation re-check that used to precede the direct apply is
   gone with the entry bump it was checking against.

**Accepted cost, documented rather than fixed:** cancelling the modal abandons the future without
cancelling it, so the git resolve completes in the background and the next call queues behind it:
latency, not incorrectness.

### A progress-scheduling seam, so the behaviour is testable

**ID:** `progress-seam`
**Status:** active
**Chosen:** An `internal` progress-runner property on `ReviewQueueService` defaulting to the modal
implementation, mirroring `ReviewSessionService.presenter` (`:37`), plus a pure
`resolveAndAssemble(scope)` shared with `refresh()`. `HeavyPlatformTestCase` runs bodies on the EDT and
the synchronous-progress API headless is not the modal path, so without a seam a test driving
`switchScope` risks running git on the test EDT and tripping an assertion `TestLoggerFactory` turns
into a failure.
**Considered:** Only extracting the pure body and testing that plus `applyRebuild` — then the tests
never exercise the wrapper, which is the part with the threading rules. Spiking first and adding a
seam only if it fails — defers a known problem.

### One shared scope action base, registered once

**ID:** `scope-action-sharing`
**Status:** active
**Chosen:** An `abstract class SetScopeAction` with a `final actionPerformed` (prompt → confirm if a
pass is active → `switchScope`) and an abstract `promptScope`; three subclasses registered inside
`ReviewQueue.ScopeMenu`; `DiffScopeAction.createPopupActionGroup` returns that registered group via
`ActionManager` — the cast-a-registered-group pattern `ReviewQueuePanel.kt:35` already uses. One rule,
one group. Costs three new Find Action entries, which is why the group needs `text="Scope"`.
**Considered:** A free `applyScope(project, scope)` function mirroring `Confirm.kt` — matches an
existing idiom and keeps the registry clean, but re-opens the door to two divergent child sets.
Confirming inside `switchScope` — rejected outright: `ReviewSessionServiceTest` drives the real service
headlessly, so a `Messages` call there would hang or fail the suite.

### Prompt first, then confirm

**ID:** `prompt-confirm-order`
**Status:** active
**Chosen:** Resolve the scope from its prompt, then confirm — so the confirmation can name the
resolved scope, and cancelling the ref prompt costs no confirmation at all.
**Considered:** Confirm then prompt — one fewer dialog when the user declines, but the confirmation
cannot say what it is switching to.

### `DiffScopeAction` implements `RightAlignedToolbarAction`

**ID:** `toolbar-marker`
**Status:** active
**Chosen:** Implement the marker and place it first in the second toolbar group, consistent with all
four existing `Diff*` siblings and the shared rationale in `DiffStartReviewAction`'s KDoc (`:20-31`).
Without it the action falls into the *navigation* partition, breaking both assertions in
`ReviewSessionServiceTest`'s toolbar test and landing in the group whose KDoc says every member is
resolved by id so the tooltip carries its shortcut.
**Considered:** No marker — fewer moving parts, but breaks the toolbar test in a way that would have to
be papered over rather than fixed.

### Refresh means synchronous resolution everywhere

**ID:** `refresh-semantics`
**Status:** active
**Chosen:** One semantic behind one label. `DiffRefreshQueueAction` extends `RefreshQueueAction` and
calls `super.actionPerformed`, so the two cannot diverge without duplicating the action. In-pass
refresh becomes immediate rather than eventual — arguably better, since that action's confirmation
exists precisely because its effect currently only surfaces later, at `markCurrent()`'s "left the
queue" branch, far from the click that caused it.
**Considered:** Overriding in the subclass to keep async — two semantics behind one label, and the
subclass stops delegating. A separate menu-only Refresh — two "Refresh" entries in Find Action, the
exact duplication the `Diff*` pattern was created to avoid.

### Enablement uses a top-level `GitRoots.exist`, not a service method

**ID:** `enablement-predicate`
**Status:** active
**Chosen:** `GitRoots.exist(project)` in the `git` package. A method on `ReviewQueueService` would
construct a project service from inside `update()`, wiring `ChangeListListener` and `GIT_REPO_CHANGE`
as a side effect of hovering the Tools menu. A top-level function keeps `git4idea` out of the actions
just as well and leaves the service uncreated until a real gesture.
**Considered:** `hasGitRoot()` on the service — one fewer file, at the cost of a side effect in a method
that should have none. `serviceIfCreated` — enablement that depends on invisible history.

### Start Review is bound to `Cmd+Option+Shift+R`, with a known accepted risk

**ID:** `start-review-shortcut`
**Status:** active
**Chosen:** `Cmd+Option+Shift+R`, joining the existing macOS cluster, chosen by the maintainer after
the collision below was raised.
**Risk accepted:** `Mac OS X 10.5+.xml` binds `meta alt shift R` to `ForceRefresh`, an `EmptyAction`
shortcut-holder. It **cannot be unbound**: `remove="true"` strips a shortcut from our own action only.
If a holder presents as enabled, the "Choose action" chooser appears wherever both are enabled — and
Start Review is enabled globally, so the argument that justifies Mark Reviewed's two knowing collisions
does not transfer. Whether the chooser actually appears cannot be settled headlessly and is a **Gate C
manual check**; if it appears, the binding must change.

**Risk revised upward during implementation — the maintainer should re-confirm.** The keymap *file*
names only `ForceRefresh`, and that is what this decision was taken on. Querying the live keymap found
**four** ids on the chord:

| id | Why it holds the chord | When it is enabled |
| --- | --- | --- |
| `ReviewQueue.StartReview` | ours | globally, outside a pass |
| `ForceRefresh` | declared in `Mac OS X 10.5+.xml` | never — it is an `EmptyAction` holder |
| `DatabaseView.ForceRefresh` | `use-shortcut-of="ForceRefresh"` | Database view focused |
| `ReloadScriptConfiguration` | declares `ctrl alt shift R` on `$default`; `MacOSDefaultKeymap` translates Ctrl→Meta | editing a Kotlin script — **`*.gradle.kts`, i.e. this repo's own build files** |

The last row is the material change. The decision was accepted believing the only live contender was
Database-view-scoped; `ReloadScriptConfiguration` is a Kotlin-scripting action, so it is live in exactly
the kind of project this plugin is developed in. A chooser popup is therefore meaningfully likelier than
"unlikely". This does not overturn the decision — the binding stands unless the maintainer changes it —
but it is a different bet than the one that was agreed, so it is recorded here rather than absorbed
silently. `Cmd+Option+Shift+S`, `+G` and `+V` remain verified free.
**Consequence:** the cluster is macOS-only by design (`meta` is the Windows key on `$default`, pinned by
`NavigationShortcutTest`), so Start Review ships **shortcutless on Windows and Linux**, reachable from
the menu and Find Action. The README and manual-verification guide must say so.
**Considered:** `Alt+Shift+R` — this design's original choice, **superseded**: both shipped keymaps bind
`shift alt R` to `RerunTests`, which is enabled globally. `Cmd+Option+Shift+S` — verified free in both
keymaps and all 377 bundled plugin jars, and mnemonic for Start, but not the maintainer's preference.
Plain `Alt+Shift+{C,E,F,H,K,N,O,T,U,W,X,Y}` — all verified free, and would keep a cross-platform
binding.

### The shortcut test queries the real keymap

**ID:** `shortcut-test-shape`
**Status:** active
**Chosen:** A `HeavyPlatformTestCase` querying `KeymapManagerEx` for the actions bound to the chord,
asserting the id set is exactly ours plus the platform actions that already hold it — so a platform
change in either direction fails rather than passing silently. Falls back to the resource-only form if
the keymap is unavailable headlessly.
**Considered:** A `plugin.xml`-text test like `MarkReviewedShortcutTest` — cheap and needs no IDE, but
that shape is exactly why the `Alt+Shift+R` collision went unnoticed until the architect pass.

Corrected during implementation: this decision originally said "exactly ours plus `ForceRefresh`",
which is what the keymap *file* declares. Querying the live keymap found **four** ids —
`DatabaseView.ForceRefresh` borrows the chord via `use-shortcut-of`, and `ReloadScriptConfiguration`
declares `ctrl alt shift R` on `$default`, which `MacOSDefaultKeymap` translates to `meta alt shift R`.
The original wording would have failed against a correct implementation. `StartReviewShortcutTest` and
the `review-entry-points` scenario both name all four.

### Layout state migration prunes a named legacy list

**ID:** `layout-state-migration`
**Status:** active
**Chosen:** `loadState` prunes `LEGACY_IDS = setOf("Review Queue")`, with a KDoc line recording that the
id was managed until KAN-5 and is unrestorable by construction. The stale id must go: once the tool
window is unregistered, `restore()` can never resolve it, so it stays on the `unresolved` record
forever and permanently latches `hideForReview()`'s "leftover means restore first" branch.
**Considered:** Pruning by `MANAGED_IDS` — this design's original choice, **superseded**: every test in
`IdeLayoutControllerTest` seeds state *through* `loadState` via `controllerWithRecord` (`:43-49`), so
that keying empties the seeded record before four test bodies run — including the very test the design
claimed was untouched — and two of them cannot be repaired by swapping ids, because a single managed id
cannot express "a managed leftover *and* another managed window". Pruning in `restore()` instead —
breaks the latch at the same place and keeps `loadState` a dumb setter, but forces the same test
rewrites.

## Risks / Trade-offs

- **The shortcut may pop an action chooser** → Gate C manual check, run **twice**: once with neither a
  Database view nor a `.gradle.kts` editor focused, and again **with a `.gradle.kts` open**, which is the
  case most likely to surface it because `ReloadScriptConfiguration` is live there. Checking only the
  first way would look clean and prove nothing. The shortcut test pins the exact four-id set so a
  platform change is caught; if the chooser appears, the binding changes.
- **No Start Review shortcut on Windows or Linux** → the menu group is the documented home there;
  README and manual-verification guide state it explicitly.
- **The plugin is inert until its menu is opened** → accepted: the service is always alive before a
  pass starts, so content-hash invalidation and the completion balloon behave as before.
- **A modal progress on every Start Review** → accepted as the honest cost of not resolving at project
  open; it doubles as the feedback that Refresh otherwise lacks with no panel to update.
- **Losing the panel loses the only always-visible progress display** → the diff tab title and the
  file-list popup title already carry `N / M`; the popup title gains the scope name.
- **Three new Find Action entries for the scope children** → mitigated by the group's `text="Scope"`.
- **A redundant rebuild right after a synchronous resolve**, when a VCS event arrives during the modal
  → harmless: the session does not subscribe, and both notifiers deduplicate.

## Migration Plan

1. Ship the `LEGACY_IDS` prune in `loadState`, so the first launch after upgrading clears any stale
   `"Review Queue"` entry from workspace storage.
2. No user action is required. Reviewed marks are untouched — they are keyed by root and path with a
   content hash, independent of any UI.
3. Rollback is a plugin downgrade: the older build re-registers the tool window, and the pruned id is
   simply re-recorded the next time a pass hides it.

## Open Questions

None blocking. One item is deliberately deferred to Gate C rather than decided here: whether
`Cmd+Option+Shift+R` raises an action chooser in practice. It cannot be answered without a display,
and the answer changes only the binding, not the design.
