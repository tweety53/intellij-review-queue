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
- **Mark Reviewed** and **Exit Review** in the diff viewer's own toolbar, plus a keyboard shortcut
  for Mark Reviewed that is active only during a session.
- Hiding the Project and Review Queue tool windows for the duration of a session, and restoring
  them afterwards.
- Automatic advance to the next unreviewed file on marking.

**Out of scope**

- Un-marking a single file (see *Removed*).
- Persisting an in-progress session across IDE restarts.
- Adding files to a session already under way.
- Any change to how marks are stored or hashed.

## Removed

`ToggleReviewedAction` and `ReviewQueueService.toggleReviewed` are deleted.

**Consequence, stated deliberately:** un-marking one file is no longer possible. A mis-click during
a session can only be undone with **Reset all**, which clears every mark in the project. This was
the owner's explicit call after the trade-off was raised; it is recorded here so the decision is
visible rather than rediscovered.

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

Builds one `ChangeDiffRequestChain` for the session's files, attaches **Mark Reviewed** and **Exit
Review** through `DiffUserDataKeys.CONTEXT_ACTIONS`, opens it as a single diff tab, advances with
`CacheDiffRequestChainProcessor.setCurrentRequest(index)`, and closes the tab when the session ends.
The tab title carries progress: `Review 3/12 — GitReviewSource.kt`.

`DiffChainPlanner` — the pure pairing/filter/index logic already extracted and unit-tested — is
reused unchanged.

**Fallback:** if obtaining the processor to call `setCurrentRequest` proves impractical, close the
current diff tab and open the next file's. That is fully controllable at the cost of a visible
flicker per file, and is a drop-in substitution behind `ReviewDiffPresenter`'s interface.

### Actions

| Action | Lives in | Enabled when |
|---|---|---|
| Scope selector | Panel toolbar | No session active |
| Start Review | Panel toolbar | No session, queue has ≥1 unreviewed file |
| End Review | Panel toolbar | Session active |
| Refresh | Panel toolbar | Always |
| Reset all | Panel toolbar | Always (confirmation dialog) |
| Mark Reviewed | Diff toolbar + shortcut | Session active |
| Exit Review | Diff toolbar | Session active |

**End Review and Exit Review are the same action**, registered once and surfaced in both places —
the panel (for when a session is running and you have reopened the panel) and the diff toolbar
(where you actually are). They are named differently only because the table lists them by location;
the implementation has one action class and one handler.

The shortcut for Mark Reviewed defaults to <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Enter</kbd>,
registered against the diff viewer so it cannot clash with normal editing bindings, and rebindable
under Settings → Keymap. If that binding is already taken in the default macOS keymap, the
implementer picks the nearest free equivalent and records the choice.

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
- The original spec's data-flow item 3 ("Toggling a reviewed file off removes its stored entry")
  no longer holds; see *Removed*.
- The original spec's "clicking any row opens that file's diff" remains true only outside a session.

## Testing

**Unit (pure, no IDE):** `ReviewSession` transitions — starting with a mix of marked and unmarked
files, advancing, skipping a file that vanished, finishing on the last file, exiting early.
`DiffChainPlanner` keeps its existing tests.

**Integration (existing heavy fixture):** drive a real repo through start → mark every file →
finish, asserting the marks are stored and the session ends inactive.

**Manual only** — added to `docs/manual-verification.md`: tool windows hiding and restoring
(including after an IDE quit mid-session), the diff toolbar actually showing both buttons, the
keyboard shortcut firing, and the tab title tracking progress.

## Success criteria

- A twelve-file review is completed without the mouse leaving the diff area, using one keystroke
  per file.
- Starting a review after a `/myflow-do-fix` round walks only the files that changed.
- Exiting early, or quitting the IDE mid-session, always returns the IDE layout to how it was.
