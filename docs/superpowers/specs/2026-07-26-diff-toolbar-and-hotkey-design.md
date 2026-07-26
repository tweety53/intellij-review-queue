# Diff toolbar controls, file list and hotkey — design

**Date:** 2026-07-26
**Status:** Approved
**Builds on:** `2026-07-26-guided-review-mode-design.md`

## Purpose

Guided review mode made the diff the product, but three rough edges remain once you are inside a
pass:

1. The **Mark Reviewed** shortcut does not fire. It is bound to `ctrl shift SPACE` in `$default`,
   which is `SmartTypeCompletion` in both `$default` and the macOS keymap — and on macOS the chord
   that gets pressed is Cmd+Shift+Space, which was never registered at all.
2. A session hides the Review Queue tool window, so **Start Review**, **Refresh** and **Reset All**
   become unreachable for the duration of the pass. The only session control on the diff toolbar is
   End Review.
3. There is no way to see the whole change from inside the diff — which files are in scope, which
   are already reviewed, where you are — without ending the pass.

## Scope

**In scope**

- A conflict-free Mark Reviewed shortcut on every platform.
- Start Review, End Review, Refresh and Reset All on the diff viewer's toolbar, right-aligned and
  confirming.
- A file-list popup opened from the diff toolbar, showing every file in scope with its reviewed
  state, and navigating to the picked file.

**Out of scope**

- Any change to how marks are stored or hashed.
- Adding files to a session already under way. The file list moves the cursor; it never grows the
  pass.
- Confirmation on the tool-window copies of these buttons. See *Confirmation belongs to the diff
  window*.
- Persisting the popup's grouping or sort preference.

## The shortcut

`ctrl shift SPACE` collides with `SmartTypeCompletion`, which is bound to exactly that chord in both
`keymaps/$default.xml` and `keymaps/Mac OS X 10.5+.xml`. Verified free in both keymaps:
`meta shift SPACE` and `control alt shift SPACE`.

```xml
<action id="ReviewQueue.MarkReviewed" ...>
    <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt shift SPACE"/>
    <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta shift SPACE" replace-all="true"/>
</action>
```

`replace-all` drops the inherited `$default` chord on macOS, so Cmd+Shift+Space is the only binding
there rather than one of two.

The `DiffDataKeys.DIFF_CONTEXT` gate in `MarkReviewedAction.update` **stays**. It was written to
survive the `SmartTypeCompletion` collision, and with the collision gone it still does useful work:
it keeps the chord inert in a normal editor mid-session, where marking "the file on screen" has no
meaning. The comment on that method gets rewritten to say so — as written it justifies the gate by a
collision that no longer exists, which would invite a later reader to delete it.

## Two groups on the diff toolbar

The diff toolbar splits by meaning:

| Left — per-file navigation | Right — session and queue controls |
| --- | --- |
| Show File List | Start Review |
| Previous File | End Review |
| Mark Reviewed | Refresh |
| Toggle Reviewed | Reset All |

**End Review moves** from the left group into the right one rather than appearing in both. Two End
buttons, one confirming and one not, is a trap: the muscle memory built on the unconfirmed one fires
the confirmed one and vice versa.

Right alignment comes from `com.intellij.openapi.actionSystem.RightAlignedToolbarAction`, a marker
interface the toolbar layout honours. It is present in 2026.2 (`lib/intellij.platform.editor.ui.jar`).

**Known risk.** Whether the *diff viewer's* toolbar honours the marker is unverified — it is an
`ActionToolbarImpl`, which does, but the diff framework builds it. The first implementation step is
to confirm this visually. If it does not hold, the fallback is a `Separator` ahead of the group:
visually grouped, not flush right.

### Confirmation belongs to the diff window

Only the diff-toolbar copies confirm. The tool-window toolbar keeps today's behaviour, where Reset
All confirms and nothing else does.

The asymmetry is deliberate and reflects where an accidental press is expensive. In the tool window
you are looking at a list and clicking a toolbar; in the diff you are reading code with your hands
on navigation keys and the buttons sit directly above the text you are reading. Refresh in
particular is one click in the tool window today, and putting a dialog in front of it there would
tax the common case to protect the rare one.

### Implementation

Four thin subclasses in `actions/diff/`, each of the form:

```kotlin
class DiffEndReviewAction : EndReviewAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Leave the guided review?", "End Review")) super.actionPerformed(e)
    }
}
```

Subclassing rather than wrapping inherits each action's `update()` unchanged. That is what makes
Start Review come out correctly disabled inside a session for free — `StartReviewAction.update`
already disables while `isActive`. Its confirmation is therefore unreachable in practice today,
since ending a pass closes the diff tab; it is written anyway so the group is uniform and the button
does not become a live unconfirmed press if that ever changes.

`DiffResetAllAction` adds only the marker interface. `ResetAllAction.actionPerformed` already
confirms, and overriding it would produce two dialogs.

The four existing action classes gain `open` on the class and on `actionPerformed`. The shared
`confirmed(project, message, title): Boolean` helper lands in `actions/Confirm.kt`, and
`ResetAllAction` is rewritten to use it so there is one confirmation shape in the plugin.

The confirming variants are **constructed directly** in `ReviewSessionService.diffActions` rather
than resolved through `ActionManager`. Registering them in `plugin.xml` would list them in Find
Action beside the originals — eight entries where there are four commands. Resolution by id stays
for the three navigation actions, because that is what makes the keyboard shortcut show in the
button tooltip; the four session controls have no shortcut to display.

## The file list

`ReviewQueue.ShowFileList` (`AllIcons.Actions.ListFiles`) heads the left group and opens a
`JBPopup` chooser over `ReviewQueueService.snapshot().items` — every file in scope, not just the
files in the pass — in the existing `ReviewOrdering` order. Each row carries the file's reviewed
state as an icon; the file currently on screen is preselected; the popup title carries
`N / M reviewed`.

The list is **flat**, not a tree. `ReviewOrdering` already sorts by git root and then by path, so
rows arrive root-grouped; a row's label carries its root name as a prefix only when the snapshot
spans more than one root. A tree inside a chooser popup would cost keyboard-navigable rows for
nothing on the single-root case, which is the normal one.

`ShowFileListAction` is registered in `plugin.xml` and resolved by id alongside the other three
navigation actions. Unlike the four session controls it is a single command with no confirming twin,
so listing it in Find Action duplicates nothing.

On pick:

- **The file is in the current pass** → `ReviewSession.jumpTo(key)`, then the service's existing
  `showCurrent()`. Tab title and progress count follow from the new index.
- **The file is not in the pass** — it was already reviewed when the pass started — → it opens as a
  browsing diff through the existing `ReviewDiffOpener`, leaving the session untouched. This is
  exactly what double-clicking a row in the tool-window tree does today.

The session's fixed-list invariant holds: `jumpTo` moves the cursor within `keys` and returns null
for anything outside it, so a pick can never grow the pass.

`showCurrent()` settles *forward* from the new index if the target has since left the queue. A jump
to a file that is gone therefore lands on the next live file after it rather than failing. That is
the same behaviour marking already has and needs no special case.

## Components

| Unit | Responsibility | Depends on |
| --- | --- | --- |
| `core/ReviewSession.jumpTo` | Cursor move to a named key, or null when off-list | nothing |
| `core/ReviewFileList` | Pure row model: label (root-prefixed when multi-root), reviewed flag, current flag | `ReviewItem` |
| `ui/ReviewFileListPopup` | Builds and shows the chooser; routes the pick | `ReviewFileList`, session service, `ReviewDiffOpener` |
| `actions/ShowFileListAction` | Toolbar entry point, diff-scoped | popup |
| `actions/Confirm.kt` | One `Messages.showYesNoDialog` shape | platform |
| `actions/diff/*` | Confirmation plus right alignment over the four existing actions | those actions |
| `queue/ReviewSessionService.jumpTo` | Applies a jump and re-shows | `ReviewSession` |

## Testing

New pure logic is unit-tested without an IDE, matching the existing suite:

- `ReviewSessionTest` — `jumpTo` to a key in the list moves the index; to a key not in the list
  returns null; to the current key is a no-op.
- `ReviewFileListTest` — row labels and reviewed flags follow the snapshot; the current key is
  marked exactly once; row order matches `ReviewOrdering`; the root prefix appears only when the
  snapshot spans more than one root.
- `ReviewSessionServiceTest` — `jumpTo` a session key shows that file through the fake presenter;
  `jumpTo` an unknown key leaves the session where it was.

The popup's Swing wiring, the right alignment and the confirmation dialogs are not unit tested —
they need a live IDE. They go into `docs/manual-verification.md` as checklist items, which is how
this repo already handles UI:

- Cmd+Shift+Space marks and advances on macOS; Ctrl+Alt+Shift+Space does on Windows/Linux; neither
  chord triggers completion.
- The four session controls sit flush right on the diff toolbar and each asks before acting.
- Start Review is visibly disabled during a pass.
- The file list opens from the diff toolbar, shows every file with its reviewed state, preselects
  the current file, and jumping to another file in the pass updates the tab title and progress.
- Picking an already-reviewed file outside the pass opens a browsing diff and leaves the pass where
  it was.

## Documentation

`README.md` gains the new shortcut, the two-group toolbar and the file list, and drops the
`Ctrl+Shift+Space` reference.
