## Why

KAN-6 collects four complaints about the guided review pass. There is no reviewed count on screen —
the tab title reads `Review 3/12 - file.kt`, which is where the cursor is, not how much of the diff
has been read. A pass hides only the Project panel, leaving the Terminal, Git, Run and every
third-party window in the reader's way. A staged rename still surfaces as a delete plus an add.
And right-clicking the diff offers only *Compare with Clipboard*, while every action a reviewer
wants sits in the toolbar, out of reach of the pointer already over the code.

The ticket also asks to fix a cursor limitation from the README. That one is already fixed:
`ReviewCursor` has no callers in `src/main`, and the live path is `ReviewSession.settleOn`, which
settles forward and can never land on an arbitrary index. KAN-5 replaced the cursor and left the
object, its tests and the README paragraph behind — so that half is a deletion, not a fix.

## What Changes

- **A progress banner above the diff**, reading `N / M files reviewed  •  <scope>` with a progress
  bar. Delivered through `DiffUserDataKeys.NOTIFICATION_PROVIDERS`, stamped on the chain beside the
  `CONTEXT_ACTIONS` the presenter already sets. The banner subscribes to the queue rather than
  rendering once, so Toggle Reviewed and a background rebuild move the number too.
- **A pass hides every visible tool window**, on every side, including windows registered by other
  plugins — not the hardcoded `listOf("Project")`. Record and restore are unchanged.
- **Rename detection in all three scopes**, so a rename is one queue entry keyed to the new path.
  Staged is re-resolved as a HEAD-vs-index diff, because `git status --porcelain=v2` has no
  similarity pass. **BREAKING** for stored marks: a mark recorded against the old delete+add pair
  goes stale once, so a renamed file may be re-offered on the first pass after upgrade.
- **The review actions on right-click**, Mark Reviewed first, then the session controls, then the
  platform's diff menu with `CompareClipboardWithSelection` filtered out — inside review diffs only.
  Installed by a `DiffExtension` that refuses any request without this plugin's marker key.
- **Deletion of `core/ReviewCursor.kt`** and its test, the two *Known, deliberate limitations*
  bullets in `README.md`, and the matching entries in `docs/manual-verification.md`.

## Capabilities

### New Capabilities
- `review-progress-display`: how much of the current scope has been reviewed, shown on the diff
  itself and kept live as marks change.

### Modified Capabilities
- `review-layout-management`: the hidden set becomes every visible tool window rather than the
  Project window alone.
- `review-queue-resolution`: renames resolve to a single queue entry in every scope.
- `review-entry-points`: the review actions gain a third entry point, the diff's context menu, and
  one platform entry is removed from it.

## Impact

- `src/main/kotlin/dev/tweety/reviewqueue/ui/` — `EditorTabDiffPresenter` stamps two more user-data
  keys; `IdeLayoutController.MANAGED_IDS` becomes a sweep; a new banner component.
- `src/main/kotlin/dev/tweety/reviewqueue/git/GitReviewSource.kt` — all three scope resolutions;
  `core/StagedFilter.kt` loses its only caller if the staged status call goes.
- `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/` — a new popup group class and a new
  `DiffExtension`; `src/main/resources/META-INF/plugin.xml` registers both.
- `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewCursor.kt` — deleted.
- Platform APIs relied on, all verified present in IU-2026.2:
  `DiffUserDataKeys.NOTIFICATION_PROVIDERS`, `DiffNotificationProvider`, `DiffExtension.EP_NAME`,
  `EditorEx.installPopupHandler` + `ContextMenuPopupHandler.Simple` + `DiffViewerBase.addListener`
  (see **Fix round 1** — `EditorEx.setContextMenuGroupId` was the original choice and does not
  work), and the `Diff.EditorPopupMenu` / `CompareClipboardWithSelection` ids from
  `PlatformActions.xml`.
- No dependency changes. No lint tooling is configured in `build.gradle.kts`.

## Fix round 1 — the context menu never appeared

Found at the human gate: right-clicking a review diff showed only *Compare with Clipboard* — the
stock platform menu. None of the review actions appeared, and `idea.log` carried none of
`ReviewDiffPopupGroup`'s warnings, so `getChildren` was never called at all.

**Root cause — the wrong API, installed at a time that could not have won anyway.**
`ReviewDiffExtension` called `EditorEx.setContextMenuGroupId(...)`. Disassembling IU-2026.2 shows
that method only writes a string into `EditorState`, which is consulted by the *default* popup
handler at index 0 of `EditorImpl.myPopupHandlers`. The platform's own diff menu does not go through
that field at all:

- `TwosideTextDiffViewer.installEditorListeners()` builds a `TextDiffViewerUtil.EditorActionsPopup`
  from `createEditorPopupActions()` (which resolves `Diff.EditorPopupMenu`) and calls
  `EditorEx.installPopupHandler(ContextMenuPopupHandler.Simple(...))`, **appending** to
  `myPopupHandlers`.
- `EditorImpl.getPopupActionGroup` scans `myPopupHandlers` from `size() - 1` **downwards**, so the
  last handler installed wins.
- `DiffRequestProcessor.createState()` calls `DiffExtension.onViewerCreated` immediately after
  `createComponent()`, and `DiffViewerBase.init()` runs `onInit()` — hence
  `installEditorListeners()` — *afterwards*.

So the platform's handler was always installed last and always won, and the group id the extension
set was never consulted. Neither ordering nor API was survivable on its own.

**Fix.** Keep the guard and the group; change how the menu is attached. `ReviewDiffExtension`
registers a `DiffViewerListener` on the viewer during `onViewerCreated`, and installs
`ContextMenuPopupHandler.Simple(ReviewQueue.DiffPopup)` from that listener's `onInit()`.
`DiffViewerBase.init()` fires `EventType.INIT` to its listeners *after* calling `onInit()`
(verified at offsets 60 and 107 of `init()`), so this plugin's handler is appended after the
platform's and wins the downward scan.

**No spec change.** Every requirement under `review-entry-points` still describes the intended
behaviour exactly; only the mechanism named in *Impact* was wrong.

**Why the tests passed.** `ReviewDiffExtensionTest` asserted that the marker is readable off a
`DiffContextOnDataHolders` built the way the presenter builds it. That was true and is still true —
it tested the guard, never the attachment, so no test could see that the menu was not installed.
Fix round 1 adds the missing coverage.

## Fix round 2 — two reports from the gate, one of them not a defect

### Report A: "Branch vs Base shows only the design doc" — **working as specified**

Entering `main`, `origin/main`, or leaving the base empty all list exactly one file,
`docs/superpowers/specs/2026-07-29-kan-6-plugin-updates-design.md`. That is git's correct answer, not
a plugin fault:

```
$ git log --oneline origin/main..HEAD
c515c42 docs(kan-6): design for the plugin updates
$ git diff --name-only origin/main...HEAD
docs/superpowers/specs/2026-07-29-kan-6-plugin-updates-design.md
$ git diff --cached --name-only | wc -l
31
```

The branch carries **one commit**. The other 31 files are staged and uncommitted, because
`/myflow-do` stages and leaves committing to `/myflow-finish`. `BranchVsBase` and `CommitRange`
compare *commits* — `getThreeDotDiffOrThrow(base, head)` and `getDiff(from, to)` — so uncommitted
work is invisible to them by construction. All three base inputs resolve to the same merge base
(`c7d85bd`), which is why all three give the same one file.

**No code change.** The defect is in the *test guide*, which sent the reviewer at these two scopes on
a branch where they structurally cannot show the work under review. The guide now says to exercise
them against a branch with real commits, and says why.

This is a general property of running this plugin under myflow, not a quirk of this branch: at the
`IN_PROGRESS` gate, **Staged is the only scope that can see the change being reviewed.**

### Report B: focus mode does not hide the tool windows — **root cause not yet established**

Start Review opens the diff and the pass runs, but Project, Terminal and the rest stay on screen.
The pass genuinely starts, so `ReviewSessionService.start()` reached its last statement:

```kotlin
session = ReviewSession.start(unreviewedShowableKeys()) ?: return false
showCurrent()
if (session == null) return false
layout.hideForReview()          // reached — the diff opened, so session is non-null
```

Ruled out by evidence rather than assumption, from the sandbox log
(`.intellijPlatform/sandbox/review-queue/IU-2026.2/log/idea.log`, IDE runs 11:10 and 12:36):

- no exception from `dev.tweety.*`, so nothing threw part-way through the sweep;
- no EDT/threading assertion, so `hide(null)` was not called off the EDT;
- no `ToolWindow`-related error of any kind.

So `hideForReview()` ran and hid nothing, or something reopened the windows immediately after.
**Which of those it is cannot be determined from the code or the log**, because this is the one path
in the plugin that reports nothing at all — every other failure branch logs.

**Why no test caught it, and why the existing tests cannot.** `IdeLayoutControllerTest` has ten
cases, including `testASecondHideDoesNotStrandTheWindowsTheFirstOneHid`. All of them run against
`RecordingToolWindowManager`, which **overrides `toolWindowIds` and `getToolWindow` itself** — so the
suite proves the sweep is correct *given* a manager that answers the way the code expects. It cannot
observe the real `ToolWindowManager` disagreeing. That is structurally the same failure as fix round
1: verified against a stub, wrong against the platform.

**This round therefore ships instrumentation, not a speculative fix.** Guessing a cause and
"fixing" it is what the debugging discipline forbids, and a wrong guess here is expensive — the
sweep is the one component that mutates the user's IDE layout. `hideForReview()` now records what
it enumerated, what it judged visible, and what it hid, and warns when it finds nothing to hide,
which is precisely the reported symptom. The next gate run produces the evidence that identifies
the cause.

**Also unresolved and deliberately not assumed:** whether this reproduces in the **Staged** scope.
`start()` is scope-agnostic and `hideForReview()` takes no scope argument, so a scope-dependent
failure would be surprising. Focus mode has never been ticked off in the guide in any scope, so
"broken everywhere, noticed in two scopes" is at least as likely as "broken in two scopes". The
guide now asks for all three explicitly.
