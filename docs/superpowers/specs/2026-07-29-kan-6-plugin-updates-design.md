# Plugin updates: progress banner, focus mode, renames, review popup

**Date:** 2026-07-29
**Ticket:** [KAN-6](https://tweety53.atlassian.net/browse/KAN-6) — *Plugin Updates*
**Status:** approved

## Why

KAN-6 collects four unrelated complaints about the guided review pass, all of them about the pass
itself rather than about the queue behind it.

1. **There is no reviewed count on screen.** The diff tab title reads `Review 3/12 - file.kt`, which
   is where the *cursor* is, not how much of the diff has been read. A reviewer part-way through a
   pass cannot tell how much is left without opening the file list.
2. **Two limitations in the README ask to be fixed** — rename detection, and a cursor that falls back
   to an arbitrary queue position after a refresh.
3. **A pass hides only the Project panel.** The Terminal, Git, Run, Problems and every third-party
   tool window stay on screen, so the reader is not left alone with the diff.
4. **Right-clicking the diff offers nothing useful.** The one entry the platform puts there,
   *Compare with Clipboard*, is not something a reviewer wants; the actions they do want are all in
   the toolbar, out of reach of the pointer that is already over the code.

Only item 2's second half turns out not to be a defect — see *Already fixed* below.

## Already fixed: the cursor limitation

The README's second known limitation describes `ReviewCursor.relocate`: after a rebuild, a cursor
whose file left the queue falls back to whichever item now occupies the old index, which may already
be reviewed.

**Nothing in `src/main` calls `ReviewCursor` at all** — not `relocate`, not `firstUnreviewed`, not
`nextUnreviewed`. The only references are in `ReviewCursorTest`. The live path is
`ReviewSession.settleOn`, which settles *forward* to the next file still in the queue and can never
land on an arbitrary index. KAN-5 replaced the tool window's queue-tree cursor and left the object,
its tests, and the README paragraph behind.

So item 2's cursor half is a documentation cleanup and a dead-code deletion, not a fix.

## What changes

Four independent changes plus a cleanup, touching disjoint areas. No decomposition is warranted: they
share no state and can be implemented and verified in any order.

### 1. Progress banner above the diff

A strip across the top of the review diff, above the toolbar:

```
▓▓▓▓▓▓▓▓░░░░░░░  5 / 12 files reviewed  •  Staged
```

**Mechanism.** `DiffUserDataKeys.NOTIFICATION_PROVIDERS: Key<List<DiffNotificationProvider>>`,
verified present in `lib/intellij.platform.diff.jar` for IU-2026.2. `EditorTabDiffPresenter` already
stamps `DiffUserDataKeys.CONTEXT_ACTIONS` on the chain it builds per file; the provider list is one
more `putUserData` beside it. No new extension point, and nothing about the presenter's
one-tab-per-file design changes.

**The banner must subscribe, not just render.** `DiffNotificationProvider.createNotification(viewer)`
is called once, when the viewer is created. Mark Reviewed replaces the tab, so it would refresh the
number by accident — but **Toggle Reviewed does not replace the tab**, and neither does a background
rebuild from a fix round. A render-once banner would sit there showing a stale count during exactly
the gestures a reviewer uses to correct a mis-mark. The component therefore registers a
`ReviewQueueService.addListener` callback against the viewer's disposable and repaints itself.

**No new counting.** `QueueSnapshot` already carries `reviewedCount`, computed by
`ReviewStateService.reviewedCount(items)` on every `snapshot()`. The banner reads `reviewedCount`,
`items.size` and `scope` from the snapshot it is handed. A second count computed anywhere else could
disagree with the file-list popup's `N / M reviewed` title, which reads the same snapshot.

### 2. Focus mode: hide every visible tool window

`IdeLayoutController` hides `MANAGED_IDS = listOf("Project")`. That constant is replaced by a sweep
over `ToolWindowManager.getInstance(project).toolWindowIds`, filtered on `isVisible` — every side,
every window, including ones registered by plugins this one has never heard of.

**Record and restore are unchanged, and that is the point.** The existing controller already:

- records the ids **before** hiding, so a throw part-way through cannot orphan a hidden window;
- keeps an id on the record when it cannot be resolved at restore time, because tool-window
  registration is not complete when `ReviewLayoutRestorer` runs at post-startup;
- restores only what it hid, so a pass started with the Project panel already closed does not pop one
  open on the way out.

All three properties are what a dynamic set needs, and none of them assumed a fixed list.

`LEGACY_IDS` pruning in `loadState` stays. It exists to drop the deleted `Review Queue` tool-window
id from a persisted record — an id that can never resolve, and that would otherwise latch
`hideForReview()`'s leftover branch forever. A dynamic managed set weakens the *name* "ids this
plugin no longer manages" but not the mechanism.

Hiding the Terminal does not stop what is running in it; a tool window is a view.

### 3. Rename detection, all three scopes

A staged rename currently surfaces as a delete of the old path plus an add of the new one. All three
scopes change:

| Scope | Today | Change |
|-------|-------|--------|
| Commit Range | `GitChangeUtils.getDiff(project, root, from, to, null)` | switch to the rename-detecting overload |
| Branch vs Base | `GitChangeUtils.getThreeDotDiffOrThrow(repository, base, head)` | renames must arrive as single entries; mechanism resolved at implementation |
| Staged | `git4idea.index.getStatus(project, root, …, false, false, false)` | re-resolve as a HEAD-vs-index diff |

The requirement for every scope is the same and is what a test asserts: **a rename produces one queue
entry, keyed to the new path**. `getThreeDotDiffOrThrow` exposes no rename flag at the call site, so
whether it already detects renames — and if not, which call replaces it — is settled during
implementation against a real repository fixture, not guessed here.

**Staged is the real work.** `getStatus` reads `git status --porcelain=v2`, which has no
similarity-detection pass; no combination of its three booleans produces rename entries. (The
original plan, `docs/superpowers/plans/2026-07-25-review-queue.md:29`, records the empirical session
that established this and settled on all-three-false as the unambiguous choice.) Getting single
rename entries means resolving Staged through a diff call rather than a status call.

`StagedFilter` exists solely to interpret `getStatus`'s index column — `GitReviewSource.kt:80` is its
only use in `src/main`. If the status call goes, `StagedFilter` and `StagedFilterTest` go with it.

**No model change.** `ChangeMapper.toItem` already prefers `afterRevision`, so a rename keys to the
**new** path and hashes the new content. `ReviewKey` and `ReviewItem` are untouched.

**Accepted cost, stated plainly.** Marks recorded against the old delete+add pair go stale on the
first review after this ships: the delete entry's key disappears from the queue, and the add entry's
key survives with its mark intact. In practice a renamed file may be re-offered once. That is the
correct behaviour for content-addressed marks, and it is a one-time cost at the upgrade boundary.

### 4. Review actions on right-click

Right-clicking the diff content opens:

```
Mark Reviewed              ⌥⇧Z
Toggle Reviewed
Show File List
Previous File              ⌘⌥⇧←
Next File                  ⌘⌥⇧→
Previous Change            ⌘⌥⇧↑
Next Change                ⌘⌥⇧↓
──────────────────────────
Scope                    ▸
Start Review
End Review
Refresh
Reset All
──────────────────────────
<the platform's diff menu, minus Compare with Clipboard>
```

**The platform group is `Diff.EditorPopupMenu`** and the entry to remove is
**`CompareClipboardWithSelection`** — both read out of `PlatformActions.xml:452` for IU-2026.2.

**Why the platform tail is composed rather than enumerated.** `Diff.EditorPopupMenu` is contributed
to by several plugins — Annotate with Git Blame from `VcsActions.xml`, review comments from
`intellij.platform.collaborationTools`, more from the Ultimate customization layer. A hand-written
replacement list would silently drop all of them, and would keep dropping whatever a future IDE adds.

So `ReviewQueue.DiffPopup` is a code-backed `DefaultActionGroup` whose `getChildren` returns our
actions, a separator, the session controls, a separator, then `Diff.EditorPopupMenu`'s **live**
children with `CompareClipboardWithSelection` filtered out by id. Contributors keep working; one
entry is gone.

**Scoping.** The group is installed with `EditorEx.setContextMenuGroupId("ReviewQueue.DiffPopup")`
(present on `EditorEx` in `lib/intellij.platform.ide.impl.jar`) from a `ReviewDiffExtension :
DiffExtension`. `DiffExtension.EP_NAME` is the only hook that reaches a diff viewer's editors, and it
is **global** — it fires for Git log diffs, local history, Compare Files, every diff in the IDE. The
extension therefore returns immediately unless it finds a private marker `Key<Boolean>` that
`EditorTabDiffPresenter` stamps on the chain it builds.

Consequences of that scoping, both intended: Compare with Clipboard is removed **only** inside review
diffs, and no diff this plugin did not open is ever modified.

The session controls in the second section are the confirming variants already constructed in
`ReviewSessionService.diffActions` — the same instances the toolbar uses, so a popup press and a
toolbar press ask the same question. They are deliberately below a separator and below the per-file
actions: `Reset All` clears every mark in the project, and it should not sit where a slip lands.

### 5. Dead code and stale docs

- Delete `core/ReviewCursor.kt` and `ReviewCursorTest.kt` in full.
- Delete the two *Known, deliberate limitations* bullets from `README.md`.
- Delete the rename paragraph at `docs/manual-verification.md:278` and checklist item 17
  (`docs/manual-verification.md:691`), which asks a human to accept the rename-as-delete+add
  behaviour that no longer exists.

## Decisions

Recorded from the brainstorming dialogue; each was a live choice with a rejected alternative.

- **Banner, not toolbar label or tab title.** A toolbar label competes for width with ten buttons and
  gets squeezed on a narrow window; a tab title truncates once several tabs are open and would cost
  the position/total distinction the title carries today.
- **Every visible tool window, no keep list.** A keep list would need maintaining, and the windows
  most worth exempting (Terminal, Run) are the ones most likely to be distracting. Distraction Free
  mode was rejected as well: it changes a global IDE setting that outlives the project.
- **Our actions plus the session controls in the popup, then the platform tail.** A popup of only our
  actions would lose Annotate and the rest inside the review diff, which is the one place a reviewer
  wants blame.
- **Marker key on the request, not an `isActive` check.** Asking `ReviewSessionService` whether a
  pass is running would claim any diff opened mid-pass — open Git log while reviewing and that diff
  would get the review menu too.
- **Renames in all three scopes, rewriting Staged if required.** Staged is the scope myflow Gate B
  actually uses; renames working only in Branch-vs-Base would fix the case nobody hits.

## Testing

The existing suite is 194 tests, all green. No lint tooling is configured — `build.gradle.kts`
declares no detekt, ktlint or spotless.

New automated coverage:

- Banner text formatting, as a pure function of `(reviewedCount, total, scope)`.
- The tool-window sweep, against the existing `RecordingToolWindows` fixture in
  `src/test/kotlin/dev/tweety/reviewqueue/ui/` — hide records exactly the visible ids, restore
  reopens exactly those, an invisible window is never recorded.
- `ReviewQueue.DiffPopup` child composition: our actions lead, `CompareClipboardWithSelection` is
  absent, and a sibling contributor to `Diff.EditorPopupMenu` survives.
- Rename mapping in `GitReviewSourceIntegrationTest`: a staged rename yields one item keyed to the
  new path, not two.

## Risks

**Rename detection is the only item that can grow.** Re-resolving Staged replaces the code path that
every myflow Gate B review depends on. If it destabilises, its blast radius exceeds the other three
items combined. The other three are additive and independently verifiable, so a problem in the rename
work should not be allowed to hold them back.

**Three of the four items are UI that cannot be verified without a display.** This repository's
sandbox has none — `runIde` there could only confirm the plugin loads. Whether the banner renders
above the toolbar, whether the sweep leaves a clean screen, and whether the popup opens with Mark
Reviewed first are all Gate B checks for a human at a real IDE. `docs/manual-verification.md` gains a
section per item.
