# Remove the Review Queue tool window

**Date:** 2026-07-26
**Ticket:** [KAN-5](https://tweety53.atlassian.net/browse/KAN-5) — *Intellij plugin remove right panel completely*
**Status:** approved

## Why

The plugin ships two surfaces for the same job. The Review Queue tool window on the right lists the
scope and carries Scope / Start Review / End Review / Refresh / Reset All; the diff viewer's toolbar
carries per-file navigation plus a confirming copy of those same four session controls. A guided
pass hides the tool window on the way in, so for the whole of the activity the plugin exists for,
the right panel is not on screen. It earns its space only in the moments before a pass starts and
after one ends.

KAN-5 asks for the panel to go, with everything it offered reachable from the diff window —
including "choose the review queue lists", the `ScopeSelector`.

## What changes

The tool window and its whole component tree are deleted. Everything it offered moves to one of two
places: a **Tools → Review Queue** menu group for the out-of-session commands, and the **diff
toolbar** for everything usable during a pass — now including scope selection.

### Deleted

- `ui/ReviewQueuePanel.kt`
- `ui/ReviewQueueToolWindowFactory.kt`
- `ui/ReviewQueueTree.kt`
- `ui/ScopeSelector.kt` — its three responsibilities split across the new files below: the combo box
  becomes `DiffScopeAction`, the ref dialogs become `ScopePrompts`, the inner scope-setting classes
  become `ScopeActions`. Nothing is left to keep.
- the `<toolWindow id="Review Queue" …>` extension in `plugin.xml`
- the `ReviewQueue.Toolbar` action group, superseded by the menu group

Nothing outside `IdeLayoutController.MANAGED_IDS` references the `"Review Queue"` tool window id.

`ReviewQueueToolWindowFactory` is the sole source of the 4 deprecated-API and 6 experimental-API
usages that the README's *Verification status* section documents and explains away. Deleting it
removes all ten, and that paragraph goes with them.

### The menu group

```
Tools
└── Review Queue
    ├── Scope            ▸ Staged / Branch vs Base… / Commit Range…
    ├── Start Review      Alt+Shift+R
    ├── Show File List
    ├── ─────────────
    ├── Refresh
    └── Reset All
```

Registered in `plugin.xml` as `ReviewQueue.Menu` with `<add-to-group group-id="ToolsMenu"
anchor="last"/>`. `ToolsMenu` is the right group id: `idea/PlatformActions.xml:802` declares it, and
`:1575` shows this exact `add-to-group` idiom. Scope is a plain nested `DefaultActionGroup`, not a
`ComboBoxAction` — the latter is a `CustomComponentAction` and renders a button, not a menu item.

Four registration details the platform will not supply by default:

- **`popup="true"` and `text=` are required on both new groups.** Without `popup`, children are
  inlined flat into the parent menu; without `text`, the submenu renders unnamed.
- **The nested group takes a fresh id, `ReviewQueue.ScopeMenu`.** `ReviewQueue.Scope` currently exists
  as an `<action>` pointing at `ScopeSelector` (`plugin.xml:25-28`). Reusing that id for a `<group>`
  would work, but any user keymap or menu customisation referring to it would degrade silently.
- **The three scope actions are registered rather than constructed per surface**, inside
  `ReviewQueue.ScopeMenu`, and `DiffScopeAction.createPopupActionGroup` returns
  `ActionManager.getAction("ReviewQueue.ScopeMenu") as DefaultActionGroup`. One group, one rule, one
  registry entry each — the cast-a-registered-group pattern that `ReviewQueuePanel.kt:35` already
  uses. The cost is three new Find Action entries ("Staged", "Branch vs Base…", "Commit Range…"),
  which is why the group needs `text="Scope"` to disambiguate them.
- **`ReviewQueue.EndReview` must be re-declared top-level**, with no `add-to-group`, exactly as
  `ReviewQueue.ShowFileList` and `ReviewQueue.MarkReviewed` already are. It currently lives inside the
  deleted `ReviewQueue.Toolbar` group (`:29-36`); dropping it silently would remove the id from the
  registry, leaving the toolbar button and closing the tab as the only ways out of a pass, and
  removing it from Find Action entirely.

No existing test asserts over the action *registry* in a way this breaks —
`testTheFileListActionIsRegistered` (`ReviewSessionServiceTest.kt:156`) is the only registry
assertion and it survives. The two shortcut tests read `plugin.xml` as text but scope their regexes
to a single `<action id=…>` block (`MarkReviewedShortcutTest.kt:26-33`,
`NavigationShortcutTest.kt:28-34`), so adding groups is invisible to them — **provided** Start
Review's declaration stays a single `<action>` block, which it must anyway to carry a
`<keyboard-shortcut>`.

The menu copies of Refresh and Reset All are the existing unconfirming actions (`ResetAllAction`
confirms on its own account, as it already does). The confirming `Diff*` variants stay exclusive to
the diff toolbar, where a button sits directly above the code being read. That split is unchanged
from today.

Menu **Refresh** needs redefining, because with no panel there is no list for it to visibly update.
It runs `resolveNow(current)` rather than the asynchronous `refresh()`: the modal progress is then
itself the feedback that something happened, and a root that has newly broken balloons on the way
out. An async `refresh()` from the menu would be a gesture with no observable result at all.

That redefinition **cannot be scoped to the menu**. `DiffRefreshQueueAction` extends
`RefreshQueueAction` and calls `super.actionPerformed` after confirming (`:24-29`), so changing the
parent changes the diff-toolbar copy too. Refresh therefore means `resolveNow` in *both* places —
one semantic behind one label. In-pass refresh becomes immediate rather than eventual, which is
arguably an improvement: that action's confirmation exists precisely because its effect currently
only surfaces later, at `markCurrent()`'s "left the queue" branch, far from the click that caused it.
The KDoc at `DiffRefreshQueueAction.kt:12-18` is rewritten accordingly, and a mid-pass cancel is a
supported path.

The alternatives were both worse: overriding in the subclass puts two semantics behind one label and
stops it delegating to its parent, and a separate menu-only action would list two "Refresh" entries in
Find Action — the exact duplication the `Diff*` subclass pattern was created to avoid
(`ReviewSessionService.kt:58-62`).

### New files

| File | Role |
| --- | --- |
| `ui/ScopePrompts.kt` | The Branch-vs-Base and Commit-Range ref dialogs, lifted out of `ScopeSelector`'s inner classes. Each returns `ReviewScope?`, `null` on cancel. Keeps `CommitRangeValidator` wiring in one place. |
| `actions/ScopeActions.kt` | `SetStagedAction`, `SetBranchVsBaseAction`, `SetCommitRangeAction` as top-level classes over a shared base that owns the confirm-and-switch rule. Both surfaces use these same three instances. |
| `actions/diff/DiffScopeAction.kt` | `ComboBoxAction` for the diff toolbar, presentation text showing the current scope name. **Enabled during a pass** — the behaviour change this design turns on. Implements `RightAlignedToolbarAction` and sits first in the second toolbar group, consistent with all four existing `Diff*` siblings and the shared rationale in `DiffStartReviewAction`'s KDoc (`:20-31`). Without the marker it would fall into the *navigation* partition, breaking both assertions in `ReviewSessionServiceTest`'s toolbar test and landing in the group whose KDoc says every member is resolved by id for its shortcut tooltip. |
| `notify/ScopeErrorNotifier.kt` | Balloon for git roots that failed to resolve. Sibling of `CompletionNotifier`. |
| `notify/QueueNotices.kt` | `nothingUnreviewed(project, scope)` — the "Nothing unreviewed in \<scope\>" balloon, called by the actions after a resolve that found nothing to act on. A function rather than a class: unlike the other two notifiers it holds no arming state. |
| `git/GitRoots.kt` | `exist(project): Boolean` — the enablement predicate, top-level so `update()` does not construct a project service. |

### Modified files

| File | Change |
| --- | --- |
| `queue/ReviewQueueService.kt` | Add `resolveNow(scope)`, extract `resolveAndAssemble(scope)` shared with `refresh()`, add the `internal` progress-runner seam. Wire `ScopeErrorNotifier` into `fireChanged()`. Amend the `refreshExecutor` KDoc (`:95-97`) to cover both paths. |
| `queue/ReviewSessionService.kt` | Add `switchScope(scope)`. Extract the "unreviewed and showable keys" computation shared by `start()` and `switchScope()`. Add `DiffScopeAction()` to `diffActions`, first in the second group. |
| `actions/StartReviewAction.kt` | Enablement no longer reads queue contents; `actionPerformed` resolves on demand first, and reports an empty result. |
| `actions/ShowFileListAction.kt` | Drop the `isActive` and `DIFF_CONTEXT` gates; resolve on demand when no pass is running. |
| `actions/RefreshQueueAction.kt` | `actionPerformed` becomes `resolveNow(current)`, which necessarily changes `DiffRefreshQueueAction` too. |
| `actions/diff/DiffRefreshQueueAction.kt` | KDoc at `:12-18` rewritten: the effect is now immediate, not eventual. |
| `ui/ReviewFileListPopup.kt` | Popup title gains the scope name, replacing the deleted progress label's `N / M reviewed • scope`. |
| `ui/IdeLayoutController.kt` | `MANAGED_IDS` becomes `["Project"]`; `loadState` prunes unmanaged ids. |
| `README.md` | *Use* section rewritten around the menu; the `ToolWindowFactory` API-warning paragraph removed. |
| `docs/manual-verification.md` | Tool-window checklist items removed; menu-entry and mid-pass re-scope items added. |

## Scope selection during a pass

`ScopeSelector` is disabled while a pass runs today, with good reason: changing the scope rebuilds
the queue underneath the session's fixed key list. And the only diff tab that carries a toolbar is
the session's own — `ReviewDiffOpener`'s browsing diff is built without
`DiffUserDataKeys.CONTEXT_ACTIONS`. A scope control on the diff toolbar under today's rules would
therefore be visible *only* in the state where it is always greyed out.

Rather than ship a dead control or leave scope out of the diff window against the ticket, the
restriction is lifted: **choosing a scope during a pass ends that pass and starts a fresh one in the
new scope, after confirming.** Every reviewed mark is kept — marks are content-addressed and stored
per file, independent of any session.

This also collapses what would otherwise be two behaviours into one. There is a single
scope-setting operation, `ReviewSessionService.switchScope`, shared by both surfaces:

```
switchScope(scope):
    if no session:  queue.setScope(scope)          // records it and kicks the existing async
                                                   // refresh; harmless, and Start Review's
                                                   // resolveNow is what guarantees freshness
    else:           queue.resolveNow(scope)        // synchronous rebuild
                    rebuild the session from the new queue
                    showCurrent()  — or end() if the new scope has nothing unreviewed
```

The confirmation lives in the action layer, not in `switchScope`: the shared `SetScopeAction` base
asks before calling it when a session is active, and asks nothing when one is not. That placement is
forced, not merely tidy — `ReviewSessionServiceTest` drives the real service headlessly (`:47-56`), so
a `Messages` call inside `switchScope` would hang or fail the suite.

**Prompt first, then confirm.** `SetScopeAction`'s `actionPerformed` is `final`: it resolves the scope
via an abstract `promptScope(project): ReviewScope?`, then confirms only if a session is active, then
calls `switchScope`. Ordering it this way lets the confirmation name the resolved scope — *"Switch the
review scope to Branch vs Base (origin/main)? The current pass restarts."* — and cancelling the ref
prompt costs no confirmation dialog at all.

One ordering constraint inside `switchScope`: `resolveNow` must run **before** `session` is
reassigned, so that a cancelled progress leaves the pass exactly where it was.

### Why it resettles rather than `end()` then `start()`

`end()` restores the layout and `start()` hides it again, which would flash the Project tool window
open and shut mid-pass. `switchScope` instead rebuilds the session and calls the existing
`showCurrent()`, which replaces the diff tab in place. The layout is already hidden and stays
hidden. If the new scope holds nothing unreviewed, `end()` runs and the layout comes back — the
correct outcome, reached by the ordinary path.

`EditorTabDiffPresenter.close()` already clears `openFile` before closing the tab, so the swap does
not trip `ReviewSessionService`'s `fileClosed` listener into treating it as the user abandoning the
review. That existing guard is what makes the in-place swap safe.

## Resolving the queue on demand

`ReviewQueuePanel.init` is currently the only caller of the initial `ReviewQueueService.refresh()`.
The service's own `init` merely subscribes to VCS events. Delete the panel and the queue starts
empty, so `StartReviewAction.update` — which requires at least one unreviewed item — would sit
permanently disabled until an unrelated git event happened to fire.

Nothing replaces that startup refresh. Instead the queue is resolved at the point of use:

```
Start Review          → resolveNow(current) → session.start()
Show File List (idle)  → resolveNow(current) → popup
Scope, during a pass   → confirm → resolveNow(scope) → resettle in place
```

`resolveNow(scope)` records the scope, bumps `refreshGeneration` so any in-flight async rebuild is
discarded rather than applied on top, and resolves the scope inside
`ProgressManager.runProcessWithProgressSynchronously`. A cancelled dialog aborts the calling action
and leaves the queue exactly as it was.

This deliberately does not reuse `refresh()`. `refresh()` exists to absorb refresh storms from
background VCS events and must stay asynchronous and coalescing; `resolveNow` exists to give a user
gesture a queue it can act on immediately. They share a common `resolveAndAssemble(scope)` body and
differ only in scheduling.

### The git work runs on `refreshExecutor`, not the progress task's thread

`runProcessWithProgressSynchronously` runs its body on a **platform pooled thread**, which is not
`refreshExecutor`. Resolving there directly would break the invariant recorded at
`ReviewQueueService.kt:95-97` — *"Serialises rebuilds so two refreshes never resolve git concurrently
for the same project"* — because bumping `refreshGeneration` does **not** cancel an executor task
already inside `source.resolve`: there is no cancellation check between submission and `assemble`.
A VCS-triggered refresh and a user's `resolveNow` could then be in `git status` on the same root at
once, colliding on `index.lock` and surfacing as a `RootResult.error` — an empty queue and an error
balloon at the exact moment the user pressed Start Review.

So the progress body **submits to `refreshExecutor` and awaits the future** under the indicator
(`ProgressIndicatorUtils.awaitWithCheckCanceled`), keeping every git resolve for a project on the one
thread that was always meant to own them. The await is cancellable, and it cannot deadlock: executor
tasks only run git and `invokeLater`, never block on the EDT. `dispose()` calls
`shutdownNow()`, so `RejectedExecutionException` is caught and treated as "no queue".

The KDoc at `ReviewQueueService.kt:95-97` is amended to say the executor serialises **both** paths.

### Preconditions, asserted rather than assumed

Applying the rebuild directly on return is only legal under three conditions, none of which the
compiler enforces:

1. **Called on the EDT.** Otherwise the apply mutates `items`/`errors` off the EDT.
2. **The caller holds no read or write access.** `ApplicationImpl.runProcessWithProgressSynchronously`
   short-circuits to running the body **inline on the calling thread** when
   `isDispatchThread() && isWriteAccessAllowed()`, or `!isDispatchThread() && isReadAccessAllowed()`.
   In that branch git runs under a read action — reviving precisely the
   `OSProcessHandler.checkEdtAndReadAction` failure the KDoc at `ReviewQueueService.kt:130-141` was
   written for. The constraint therefore holds for the intended callers (`actionPerformed`, on the
   EDT, outside any read action) and would break silently for a future caller from a background
   `update()` or inside a `ReadAction`.
3. **No modal dialog already up**, or the apply lands under one — the thing
   `ModalityState.nonModal()` forbids for the async path. The ref-prompt flows are safe because
   `Messages.showInputDialog` has already closed by then.

`resolveNow` opens with `ThreadingAssertions.assertEventDispatchThread()` and carries a KDoc naming
all three, stating that it is the one deliberate exception to the nonModal rule: the gesture that
opened the progress is what is waiting for the result.

Two further details the code must not omit: a `generation == refreshGeneration.get()` re-check before
the direct apply (today no bump can happen during the modal, since every `refresh()` entry is on the
EDT — but that is a non-obvious invariant a future direct background call would break silently), and
an explicit `ProgressManager.checkCanceled()` **after** the resolve, because the
`ThrowableComputable` overload only rethrows if the body actually observes cancellation. Without it,
a cancel landing between the last git call and the apply would apply anyway.

For the record, there is **no lost update** in either direction. `resolveNow` bumps before working,
so an in-flight `refresh()`'s `nonModal` apply is deferred past the dialog and then discarded by the
generation check; `applyRebuild`'s `rebuild.scope != scope` guard passes because `resolveNow` records
the scope first. The one artefact is a *redundant* rebuild — a VCS event arriving during the modal
fires a second identical `fireChanged()` just after `resolveNow` applied. Harmless: the session does
not subscribe, and `ScopeErrorNotifier` deduplicates.

### A scheduling seam, so the tests can reach this

`HeavyPlatformTestCase` runs test bodies on the EDT, and `runProcessWithProgressSynchronously`
headless is not the modal-dialog path, so a test that drives `switchScope` would risk running git on
the test EDT and tripping the internal-mode process assertion — which `TestLoggerFactory` turns into
a failure. The existing fake-`presenter` seam covers the presenter, not the progress.

`ReviewQueueService` therefore carries an `internal` progress-runner seam defaulting to the modal
implementation, exactly mirroring `ReviewSessionService.presenter` (`:37`). Tests substitute a direct
call. This is why `resolveAndAssemble(scope)` is factored out as a pure function shared with
`refresh()`: the seam wraps scheduling only, so the tests exercise the real resolve.

### Enablement

Enablement stops depending on queue contents, since the queue is now cold until someone asks for it:

- `StartReviewAction` — `GitRoots.exist(project) && !session.isActive`
- `ShowFileListAction` — `GitRoots.exist(project)`

`GitRoots.exist(project)` is a new top-level function in the `git` package reading
`GitRepositoryManager.repositories` — cached and EDT-safe. Deliberately **not** a method on
`ReviewQueueService`: that would construct a project service from inside `update()`, wiring
`ChangeListListener` and `GIT_REPO_CHANGE` as a side effect of hovering the Tools menu. A top-level
function keeps `git4idea` out of the action classes just as well, and leaves the service genuinely
uncreated until a real gesture — which is what this design says it wants.

`GitRepositoryManager.repositories` is empty until VCS mappings initialise, so Start Review is
briefly disabled just after project open and self-corrects on the next `update()` poll. No event is
needed.

### Empty results must say so

Enablement no longer reads queue contents, so all three gestures become clickable with an empty
queue — and all three then do nothing visible. `ReviewFileListPopup.show` returns on
`rows.isEmpty()` (`:37`); `start()` returns on `ReviewSession.start(keys) ?: return` (`:110`); and
`switchScope` into an empty scope closes the tab and restores the layout with no statement of why.
The worst case is a project whose staged files are all already reviewed: Start Review shows a
progress dialog and then absolutely nothing happens, because `CompletionNotifier` deliberately does
not balloon in that state (`armed == null && complete → armed = false`, `CompletionNotifier.kt:44-49`).

Under the old design the tool window's empty list *was* that feedback. Removing the panel removes it,
so silence here would be a regression rather than the status quo. A `Nothing unreviewed in <scope>`
balloon fires in the existing `Review Queue` notification group when a post-resolve queue has nothing
to act on.

`ReviewFileListPopup`'s KDoc at `:34-37` justifies its `isEmpty` guard as covering *a race*. That is
no longer true — it is now the routine "nothing staged" case — so that comment is rewritten rather
than left to mislead.

Dropping `ShowFileListAction`'s `isActive` and `DIFF_CONTEXT` gates is what makes it the replacement
for the deleted tree: it already lists the full scope with reviewed marks, already shows
`N / M reviewed` in its title, and already opens an out-of-pass file as a browsing diff through
`ReviewDiffOpener`. `ReviewFileList.rows` already accepts a nullable `current`, so no pass means no
current row and nothing else changes.

### Consequence: the service is now lazy

`ReviewQueueService` used to be constructed by the panel at project open, and its `init` is what
subscribes to `ChangeListListener` and `GitRepository.GIT_REPO_CHANGE`. It is now constructed on
first use — the first `update()` of one of its actions, i.e. opening the Tools menu or Find Action.
Until then no background rebuilds happen and the plugin is genuinely inert.

This is harmless for the review flow: the service is always alive by the time a pass starts, so
content-hash invalidation during a fix round and the completion balloon behave as before. It is
recorded here because it is a real change in when the plugin does work, not because it needs fixing.

## Error handling

Per-root resolve failures render in the deleted panel's `errorLabel` today. They move to a balloon
in the existing `Review Queue` notification group, fired from `fireChanged()` beside
`CompletionNotifier`.

It cannot simply fire whenever the error map is non-empty: `changeListUpdateDone` lands on every VCS
event, so that would balloon on every rebuild. `ScopeErrorNotifier` remembers the last map it
reported and fires only when the current map differs from it. A persistently broken root balloons
once; a root that recovers and breaks again balloons again. `CompletionNotifier.armed` is the
precedent for this shape.

A cancelled `resolveNow` is not an error and produces no balloon.

## Migration of persisted layout state

`IdeLayoutController` persists `hiddenByReview` in workspace storage, and an existing install may
have `"Review Queue"` in it. Once that tool window is unregistered, `restore()` can never resolve
the id, so it stays on the `unresolved` record forever — which permanently latches
`hideForReview()`'s "a leftover record means restore first" branch.

`loadState` therefore prunes `LEGACY_IDS = setOf("Review Queue")`, with a KDoc line recording that
the id was managed until KAN-5 and is unrestorable by construction.

**Pruning by `MANAGED_IDS` instead — the obvious move, and this design's first answer — is wrong.**
Every test in `IdeLayoutControllerTest` seeds its state *through* `loadState`, via the
`controllerWithRecord` helper (`:43-49`). With `MANAGED_IDS = ["Project"]` and pruning keyed on it,
the seeded record is emptied before the test body runs, breaking four tests:
`testRestoreKeepsIdsThatDidNotResolveToARegisteredWindow` (`:92`, which seeds `"NotRegisteredYet"`),
`testRestoreReopensAndThenForgetsTheWindowsItReopened` (`:76`, `"Restorable"`),
`testHideReclaimsALeftoverRecordInsteadOfRefusingToHide` (`:117`, `"Leftover"`), and
`testHideRecordsAndHidesOnlyTheVisibleManagedWindows` (`:59`, which uses `"Review Queue"` as a
managed-but-invisible window it no longer is). Worse, two of those cannot be fixed by swapping ids:
with a single managed id you cannot express "a managed leftover *and* another managed window" at all,
and `MANAGED_IDS` is private so the tests could not key off it either way.

The named legacy list fixes the actual cause — a specific id that a specific past version wrote —
and leaves the general contract and three of the four tests untouched. A future removal appending to
that list is the correct maintenance burden, because this *is* a migration.

The two rules remain distinct, and both survive:

- `loadState` drops ids the plugin **no longer manages** — unrestorable by construction, so keeping
  them only latches.
- `restore` keeps ids it **does** manage but which are **not yet registered** — a startup-timing
  problem that resolves itself on the next call.

## Progress display

The deleted panel's `N / M reviewed • <scope>` label had two replacements already in place: the diff
tab title (`Review N/M - filename`) and the file-list popup title (`N / M reviewed`). The popup
title gains the scope name so nothing the label showed is lost, and `DiffScopeAction`'s presentation
text names the current scope on the toolbar.

## The keyboard shortcut

**`Cmd+Option+Shift+R`**, joining the plugin's existing macOS `Cmd+Option+Shift` navigation cluster.

An earlier draft of this design chose `Alt+Shift+R`. That is **taken**: `keymaps/$default.xml` and
`keymaps/Mac OS X 10.5+.xml` both bind `shift alt R` to `RerunTests`, which is enabled globally. It
was withdrawn.

### The known collision, accepted deliberately

`Mac OS X 10.5+.xml` binds `meta alt shift R` to `ForceRefresh`. This was raised and the binding was
chosen anyway; the risk is recorded here rather than argued away.

- `ForceRefresh` is declared `class="…EmptyAction"` in `idea/PlatformActions.xml` — a shortcut-holder
  id, not a command. It does nothing itself.
- Exactly one bundled action borrows it, `DatabaseView.ForceRefresh` via
  `use-shortcut-of="ForceRefresh"`, live only with the Database view focused.
- **It cannot be unbound.** `remove="true"` inside our own `<action>` block removes a shortcut from
  *our* action only, never from someone else's. If the holder presents as enabled,
  `IdeKeyEventDispatcher` shows the "Choose action" chooser wherever both are enabled — and Start
  Review is enabled globally, not only in a read-only diff viewer. The
  "tolerable because the bundled action is disabled exactly where ours is live" argument that
  justifies Mark Reviewed's two knowing collisions therefore does **not** transfer here.

Whether the chooser actually appears is an empirical question, not one to settle by argument, and it
cannot be settled headlessly. **Gate C must check it by hand**: in `runIde`, with no Database view
focused, press the chord — either Start Review fires, or a chooser appears. This is a checklist item
in `docs/manual-verification.md`, and if the chooser appears the binding has to change.

For the record, `Cmd+Option+Shift+S`, `+G` and `+V` were all verified free across both shipped
keymaps and all 377 bundled plugin jars, as were plain `Alt+Shift+{C,E,F,H,K,N,O,T,U,W,X,Y}`. The
whole `meta+alt+shift` family in the macOS keymap holds only `R`, `` ` ``, `[` and `]`.

### Consequence: no Windows or Linux binding

The `Cmd+Option+Shift` cluster is macOS-only by design — `meta` is the Windows key on `$default` —
and that is pinned by `NavigationShortcutTest`'s *"the cluster is macOS-only and binds nothing on the
default keymap"*. So **Start Review ships shortcutless on Windows and Linux**, reachable there from
Tools → Review Queue and Find Action only.

This is defensible now that the menu is the out-of-session home, but it drops the earlier rationale
for a platform-neutral chord, so the README's *Use* section and `docs/manual-verification.md` must
both say it plainly rather than leaving non-macOS users to discover it.

## Testing

| Test | What it pins |
| --- | --- |
| `IdeLayoutControllerTest` (edit) | Two cases change: `testLoadedStateIsReturnedByGetState` (`:51`) and `testHideRecordsAndHidesOnlyTheVisibleManagedWindows` (`:59`), both of which use `"Review Queue"`. New case: `loadState` prunes a `LEGACY_IDS` entry. The other three cases stay untouched — that is the point of the legacy-list mechanism. |
| `ReviewSessionServiceTest` (edit) | `testTheDiffToolbarSplitsNavigationFromRightAlignedSessionControls` (`:119-154`) partitions `diffActions` on `RightAlignedToolbarAction`, asserts the non-marked list by **exact identity** against five `ActionManager` lookups, and pins `navigation.size == indexOfFirst { it is Separator }`. Adding `DiffScopeAction` forces an edit here either way. |
| `ScopeSwitchTest` (new) | Switching mid-pass resettles in place with no `hideForReview`/`restore` churn; switching into a scope with nothing unreviewed ends the pass and restores the layout; switching while idle records the scope and resolves it (`setScope` fires `refresh()`); a cancelled progress leaves the pass untouched. Uses the fake-presenter seam **and** the new progress seam. |
| `ScopeErrorNotifierTest` (new) | One balloon per distinct error map; silent on an unchanged map; silent when empty. |
| `QueueNoticesTest` (new) | "Nothing unreviewed" fires when a post-resolve queue has nothing to act on, and not otherwise. |
| `StartReviewEnablementTest` (new) | Enabled with a git root and no session; disabled during a session; independent of queue contents. |
| `StartReviewShortcutTest` (new) | Queries `KeymapManagerEx.getInstanceEx().getKeymap(…)?.getActionIds(keystroke)` in a `HeavyPlatformTestCase` and pins the binding **together with its known overlap** — asserting the id set is exactly ours plus `ForceRefresh`, so that a platform change in either direction fails the test instead of passing silently. Falls back to the resource-only form of `MarkReviewedShortcutTest` if the keymap is unavailable headlessly. |

The shortcut test cannot simply copy `MarkReviewedShortcutTest`: that test deliberately reads only
the shipped `plugin.xml` resource and "needs no IDE" (`:8-19`), which is exactly why it could not have
caught the `Alt+Shift+R` collision. Given two prior shortcut failures in this plugin and a third
caught during this design, the keymap-querying form is worth the heavier test base class.

`./gradlew test` and `./gradlew verifyPlugin` must both stay green. `verifyPlugin` should now report
**zero** deprecated- and experimental-API usages, since the only source of them is deleted — a
result worth asserting in the README rather than leaving to chance.

`runIde` cannot verify the UI in a headless sandbox, as the README's verification section records.
The menu group rendering, the diff-toolbar scope combo, and the mid-pass re-scope flow all need a
human with a display; they belong in `docs/manual-verification.md`, not in an automated claim.

## Deliberately out of scope

- Any change to `MarkReviewedAction`'s four chords or the macOS navigation cluster.
- Rename detection, still disabled.
- The cursor-fallback behaviour on refresh, still as documented in the README's *Known, deliberate
  limitations*.
- Restoring an out-of-session **tree**. The flat file-list popup replaces it; a tree inside a
  chooser popup was already rejected in `ReviewFileList`'s KDoc and that reasoning stands.
