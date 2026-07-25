# Guided review mode — design

**Date:** 2026-07-26
**Status:** Approved
**Supersedes:** parts of `2026-07-25-intellij-review-queue-design.md` — see *Changes to the original design*.

## Purpose

The first release made the tool window the product: a list you click through, with **Mark reviewed**
in the panel toolbar. Reviewing a file therefore means looking at the diff on the left, moving the
mouse to the panel on the right, clicking, and looking back. On a twelve-file change that is
twenty-four context switches, and it is why the plugin does not feel convenient to use.

This redesign makes the *diff* the product. You choose a scope, press **Start Review**, and the IDE
gets out of the way: side panels hide, the first file opens, and one button — directly above the
code — marks it reviewed and brings up the next. You never leave the diff until the pass is done.

## Scope

**In scope**

- A review *session*: an explicit mode with a start, a position, and an end.
- **Start Review** / **End Review**, grouped with Refresh and Reset all in the panel toolbar.
- **Previous File**, **Mark Reviewed**, **Toggle Reviewed** and **Exit Review** in the diff viewer's
  own toolbar, plus a keyboard shortcut for Mark Reviewed that is active only during a session.
- Hiding the Project and Review Queue tool windows for the duration of a session, and restoring
  them afterwards.
- Automatic advance to the next unreviewed file on marking.
- Recovering from a mis-mark without leaving the diff.

**Out of scope**

- Persisting an in-progress session across IDE restarts.
- Adding files to a session already under way.
- Any change to how marks are stored or hashed.
- A dedicated "next file without marking" action — **Mark Reviewed** on an already-marked file
  re-stores the same hash and advances, which covers moving forward after stepping back.

## Recovering from a mis-mark

Marking advances immediately, so a mis-marked file is behind you by the time you notice. The
recovery is two actions in the diff toolbar, both of which stay within the session:

1. **Previous File** steps back one position and changes no marks. Disabled at the first file.
2. **Toggle Reviewed** removes (or re-adds) the mark on the file currently displayed.

**Toggle Reviewed moves out of the panel and into the diff toolbar**, where it acts on the current
file rather than a tree selection. In the panel it would be unreachable during a session — which is
precisely when marking, and mis-marking, happens.

## Components

### `ReviewSession` — pure, no platform types

The state a guided pass needs:

- `keys: List<ReviewKey>` — the files this session walks, fixed at Start
- `index: Int` — current position
- `active: Boolean`

Every transition (start, advance, skip, finish, exit) is a pure function over that state. This is
the single most important boundary in the design: the flow's logic is unit-testable without an IDE,
in a plugin whose hardest defects have all lived in code no test could reach.

### `ReviewSessionService` — project-level `@Service`

Orchestrates a session: capture layout → hide windows → open the diff → mark and advance → finish
or exit → restore layout. It composes `ReviewQueueService` (what files exist), `ReviewStateService`
(marks), `IdeLayoutController`, and `ReviewDiffPresenter`. It re-implements none of them.

### `IdeLayoutController`

Captures the visibility of the **Project** and **Review Queue** tool windows, hides them, and
restores them. The only component that mutates the user's IDE layout, kept in one small file with
an explicit contract.

The captured snapshot is persisted in project workspace state. A session is not persisted, but the
layout must be: quitting the IDE mid-session would otherwise leave the Project window hidden with
no indication why. On project open with no active session, a stored snapshot is restored and
cleared.

### `ReviewDiffPresenter` — replaces `ReviewDiffOpener`

Owns the diff tab for the session's lifetime. It attaches the four session actions through
`DiffUserDataKeys.CONTEXT_ACTIONS`, shows the file at the session's current index, and closes the
tab when the session ends. The tab title carries progress: `Review 3/12 — GitReviewSource.kt`.

`DiffChainPlanner` — the pure pairing/filter/index logic already extracted and unit-tested — is
reused unchanged.

**Mechanism: one diff file per step, replacing the previous one.** Moving to another file closes
the current diff editor tab and opens the next.

This is a deliberate reversal of the approach originally chosen. The first plan was to open one
`ChangeDiffRequestChain` for the whole session and advance inside it with
`CacheDiffRequestChainProcessor.setCurrentRequest(index)`. That method is public, but **the
processor is not reachable from an action through public API**: `DiffDataKeys` (in
`com.intellij.diff.tools.util`) exposes only `DIFF_CONTEXT` and `DIFF_VIEWER`, and
`DiffRequestProcessor` implements just `DiffEditorViewer` and `CheckedDisposable` — it publishes no
data key and no accessor. Reaching it would require reflection or casting into `impl` internals,
which is precisely the kind of coupling that breaks on a platform upgrade.

The cost is a visible tab swap per file instead of an in-place change. The benefit is that every
transition is fully under our control and depends on nothing internal. If a supported way to drive
a single tab is found later, it substitutes behind `ReviewDiffPresenter` without touching
`ReviewSession` or the actions.

### Actions

| Action | Lives in | Enabled when |
|---|---|---|
| Scope selector | Panel toolbar | No session active |
| Start Review | Panel toolbar | No session, queue has ≥1 unreviewed file |
| End Review | Panel toolbar | Session active |
| Refresh | Panel toolbar | Always |
| Reset all | Panel toolbar | Always (confirmation dialog) |
| Previous File | Diff toolbar | Session active, not at the first file |
| Mark Reviewed | Diff toolbar + shortcut | Session active |
| Toggle Reviewed | Diff toolbar | Session active |
| Exit Review | Diff toolbar | Session active |

**End Review and Exit Review are the same action**, registered once and surfaced in both places —
the panel (for when a session is running and you have reopened the panel) and the diff toolbar
(where you actually are). They are named differently only because the table lists them by location;
the implementation has one action class and one handler.

The shortcut for Mark Reviewed is <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd>, registered
against the diff viewer and rebindable under Settings → Keymap.

That chord is Smart Type Completion in the default keymap. Because the binding is scoped to the
diff viewer, where code completion has no meaning in a read-only diff, there is no practical
conflict — but the implementer must verify the scoping actually holds and that the chord does not
leak into normal editors.

### Panel

The tree remains as a read-only overview of what is pending and what is done. Clicking a row opens
that file's diff when no session is running. During a session the panel is hidden.

## Flow

1. **Start Review** captures the currently *unreviewed* items in queue order as the session's fixed
   list, saves which of Project / Review Queue are visible, hides both, and opens the chain at
   position 0.
2. **Mark Reviewed** stores the current file's hash, advances the index, moves the diff, and updates
   the tab title.
3. On the last file, the session **finishes**: the diff tab closes, the layout is restored, the
   panel reopens, and the existing completion balloon fires with *Copy `/myflow-do-done <name>`*.
4. **Exit Review** ends the session the same way, minus the balloon. Every mark made so far is kept.

## Failure cases

| Case | Behaviour |
|---|---|
| Diff tab closed by hand | A `FileEditorManagerListener` treats it as Exit: layout restores, session ends. Without this the IDE is left with both windows hidden and no obvious way back. |
| IDE quits mid-session | Session is not persisted; pressing Start again resumes at the first unreviewed file. The persisted layout snapshot is restored on next project open. |
| A fix round lands mid-session | The session's list stays fixed. A file that has vanished from the queue is skipped when reached. A file whose content changed still marks correctly, because marking stores the *current* hash. Newly added files join the next session, not the running one. |
| A file the diff viewer cannot render | Excluded from the session list up front by `DiffChainPlanner`. Otherwise the session could never reach its end — the trap the submodule-gitlink case would have sprung. |
| Empty queue | Start Review is disabled. |
| Per-root git error during a session | Does not disturb the running session, whose list is already fixed. The error surfaces in the panel as today. |

## Changes to the original design

- `ReviewQueueService` loses its **cursor**. Its only consumers were the panel's Mark Reviewed and
  Toggle Reviewed, both of which are leaving the panel; position now belongs to `ReviewSession`.
  `QueueSnapshot` drops its `cursor` field and `ReviewCursor.relocate` loses its last caller.
  The service is left with one job: *what files are in scope right now*.
- `ReviewQueueService.toggleReviewed` **stays** — its caller moves from the panel to the diff
  toolbar and passes the current session file's key instead of the tree selection.
- `ReviewQueueDataKeys.SELECTED_KEY` and the panel's `uiDataSnapshot` are deleted. Toggle Reviewed
  was their only consumer, so the panel ends up publishing no data keys at all — which strengthens
  the standing read-only guarantee: nothing this tool window exposes can feed a VCS action.
- The original spec's "clicking any row opens that file's diff" remains true only outside a session.

## Testing

**Unit (pure, no IDE):** `ReviewSession` transitions — starting with a mix of marked and unmarked
files, advancing, stepping back, stepping back at the first file (a no-op), skipping a file that
vanished, finishing on the last file, exiting early. `DiffChainPlanner` keeps its existing tests.

**Integration (existing heavy fixture):** drive a real repo through start → mark every file →
finish, asserting the marks are stored and the session ends inactive.

**Manual only** — added to `docs/manual-verification.md`: tool windows hiding and restoring
(including after an IDE quit mid-session), the diff toolbar actually showing all four actions, the
keyboard shortcut firing in the diff *and not leaking into normal editors*, the tab title tracking
progress, and the full mis-mark recovery (mark the wrong file → Previous File → Toggle Reviewed →
Mark Reviewed to continue).

**Re-test carried over from the previous release:** Toggle Reviewed appeared permanently disabled
during the owner's first hands-on test. The wiring was correct; the cause was almost certainly that
`refresh()` was dying with `Synchronous execution under ReadAction`, leaving the queue empty and
every row-dependent action disabled. That refresh bug is fixed, so this must be re-confirmed rather
than assumed.

## Success criteria

- A twelve-file review is completed without the mouse leaving the diff area, using one keystroke
  per file.
- Starting a review after a `/myflow-do-fix` round walks only the files that changed.
- Exiting early, or quitting the IDE mid-session, always returns the IDE layout to how it was.
