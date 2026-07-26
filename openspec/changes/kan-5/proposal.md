## Why

The plugin ships two surfaces for one job. The Review Queue tool window on the right lists the scope
and carries Scope / Start Review / End Review / Refresh / Reset All; the diff viewer's toolbar carries
per-file navigation plus a confirming copy of those same four session controls. A guided pass **hides
the tool window on the way in** — so for the entire activity the plugin exists for, the right panel is
not on screen. It earns its space only in the moments before a pass starts and after one ends.

[KAN-5](https://tweety53.atlassian.net/browse/KAN-5) asks for the panel to go, with everything it
offered reachable from the diff window — including "choose the review queue lists", the
`ScopeSelector`.

## What Changes

- **BREAKING (UI):** the `Review Queue` tool window is removed. `ReviewQueuePanel`,
  `ReviewQueueToolWindowFactory`, `ReviewQueueTree`, `ScopeSelector` and the `<toolWindow>` extension
  are deleted, along with the `ReviewQueue.Toolbar` action group.
- A **Tools → Review Queue** menu group becomes the out-of-session home: Scope, Start Review,
  Show File List, Refresh, Reset All.
- **Start Review gains a shortcut**, `Cmd+Option+Shift+R`, joining the plugin's existing macOS
  `Cmd+Option+Shift` cluster. That cluster is macOS-only by design, so **Start Review ships
  shortcutless on Windows and Linux**, reachable there from the menu and Find Action.
- **Scope selection becomes available during a pass.** Today it is disabled mid-pass and the only
  toolbar-bearing diff tab is the session's own, so a scope control on the toolbar would be visible
  only where it is always greyed out. The restriction is lifted instead: choosing a scope mid-pass
  confirms, then restarts the pass in the new scope, in place. Every reviewed mark is kept.
- **The queue is resolved on demand** rather than at project open. `ReviewQueuePanel.init` was the
  only caller of the initial refresh; a new `ReviewQueueService.resolveNow(scope)` resolves under a
  modal progress at the point of use. `ReviewQueueService` therefore becomes lazily constructed.
- **Refresh becomes synchronous everywhere**, not only in the menu — `DiffRefreshQueueAction`
  inherits from `RefreshQueueAction`, so the two cannot be given different semantics without
  duplicating the action.
- **`ShowFileListAction` loses its `isActive` and `DIFF_CONTEXT` gates**, which is what makes its
  popup the replacement for the deleted tree.
- **Two things the panel displayed need new homes:** per-root git errors move to a balloon, and an
  empty post-resolve queue now says so instead of silently doing nothing.
- **A one-time migration** prunes the stale `"Review Queue"` id from persisted layout state, which
  would otherwise latch the layout controller permanently.

## Capabilities

### New Capabilities

- `review-entry-points`: how a review is reached with no tool window — the menu group, the Start
  Review shortcut, action enablement based on git-root existence rather than queue contents, and
  `EndReview` retaining a registry home.
- `review-scope-selection`: choosing the review scope from either surface, including switching
  mid-pass, the prompt-then-confirm order, and the in-place session resettle.
- `review-queue-resolution`: on-demand synchronous resolution, its concurrency and threading
  contract, and the feedback it owes the user (per-root errors, empty results, cancellation).
- `review-layout-management`: which tool windows a pass hides, and the migration of persisted state
  that names a window the plugin no longer manages.

### Modified Capabilities

None. `openspec/specs/` is empty — OpenSpec was initialised in this repo as part of this change, so
every capability above is newly captured rather than modified.

## Impact

**Deleted:** `ui/ReviewQueuePanel.kt`, `ui/ReviewQueueToolWindowFactory.kt`, `ui/ReviewQueueTree.kt`,
`ui/ScopeSelector.kt`, the `<toolWindow>` extension and the `ReviewQueue.Toolbar` group.

**New:** `ui/ScopePrompts.kt`, `actions/ScopeActions.kt`, `actions/diff/DiffScopeAction.kt`,
`notify/ScopeErrorNotifier.kt`, `notify/QueueNotices.kt`, `git/GitRoots.kt`.

**Modified:** `queue/ReviewQueueService.kt`, `queue/ReviewSessionService.kt`,
`actions/StartReviewAction.kt`, `actions/ShowFileListAction.kt`, `actions/RefreshQueueAction.kt`,
`actions/diff/DiffRefreshQueueAction.kt`, `ui/ReviewFileListPopup.kt`, `ui/IdeLayoutController.kt`,
`META-INF/plugin.xml`, `README.md`, `docs/manual-verification.md`.

**Tests:** `IdeLayoutControllerTest` and `ReviewSessionServiceTest` need edits; five new test classes.

**Dependencies:** none added or removed.

**Incidental benefit:** `ReviewQueueToolWindowFactory` is the sole source of the 4 deprecated-API and
6 experimental-API usages the README documents and explains away. Deleting it removes all ten, and
`verifyPlugin` should report zero.

**Carried risk (revised during implementation, then re-confirmed by the maintainer):**
`Cmd+Option+Shift+R` is shared with **three** platform actions, not the one this was accepted on, and
cannot be unbound from our side — `remove="true"` strips a shortcut only from our own action.

| id | How it holds the chord | Enabled when |
| --- | --- | --- |
| `ForceRefresh` | declared in `Mac OS X 10.5+.xml` | never — inert `EmptyAction` holder |
| `DatabaseView.ForceRefresh` | `use-shortcut-of="ForceRefresh"` | Database view focused |
| `ReloadScriptConfiguration` | declares `ctrl alt shift R` on `$default`; `MacOSDefaultKeymap` translates Ctrl→Meta | editing a Kotlin script — **`*.gradle.kts`** |

The third was missed initially because scanning keymap *files* cannot see a binding inherited through
that translation. It matters because it is live in exactly the kind of project this plugin is developed
in. Alternatives were then re-checked **with** the translation applied: the `Cmd+Option+Shift` cluster
turns out to be effectively exhausted — only `Y` and `Z` are free, and `Z` collides visually with Mark
Reviewed's `Cmd+Shift+Z` — while the plain `Alt+Shift` family has `C E F H K N O T U W X Y` free and is
cross-platform. The maintainer weighed those and chose to keep `Cmd+Option+Shift+R`.

Whether an action-chooser popup actually appears is empirical and needs a display, so it stays a Gate C
checklist item — checked with no Database view and no `.gradle.kts` editor focused, and **then again
with a `.gradle.kts` open**, which is the case most likely to surface it. If the chooser appears, the
binding changes. `StartReviewShortcutTest` pins the exact four-id set, so a platform change in either
direction fails loudly rather than quietly altering this bet.

## Manual Test Fixes

### Fix — 2026-07-27

`/myflow-review`'s coverage check found three `review-queue-resolution` scenarios with no automated
test. All three are cheap, sit in one file, and change no behaviour — they close gaps the panel had
already surfaced as "covered by neither a test nor a Gate C item".

- **"Called off the EDT"** — `resolveNow` opens with a threading assertion that nothing exercises.
  Deleting the assertion leaves the suite green.
- **"Resolution after disposal is not an error"** — the `RejectedExecutionException` arm exists because
  `dispose()` calls `shutdownNow()`, but no test drives a resolve against a disposed project.
- **"A cancelled resolution is not a failure"** — the spec requires a cancel to produce no
  notification; nothing asserts the bus stays silent.

**Not attempted, recorded rather than silently skipped.** The *resolved base ref* guard in
`GitReviewSource` remains unpinned: reaching it needs `findTrackedBranch` to return a hostile name, and
two fixture attempts — a hand-written `branch.*.merge` config, then a real bare remote with an
upstream — both fell back to the `origin/HEAD` literal and passed with the guard deleted. A test that
cannot fail is worse than none, so the gap stays documented in that file's KDoc instead. Also left
alone: the four scenarios that live inside the real `progressRunner` (the executor race, a cancellable
wait, stale-result rejection, late cancellation), which every test replaces via the seam.

**Gate C was skipped for this change**, which means the seven scenarios that were Gate C-only are now
verified by nothing. That is recorded here because it is a property of this change as shipped, not a
defect introduced by this fix round.
