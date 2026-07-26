# Review Queue

An IntelliJ IDEA plugin for working through a diff file by file, marking each one reviewed.

Built for the myflow Gate B manual review, where a change sits staged and uncommitted in a worktree
and needs a human read-through before `/myflow-do-done`.

## Install

Download or build the plugin zip, then Settings → Plugins → gear → Install Plugin from Disk.

```bash
./gradlew buildPlugin   # build/distributions/review-queue-<version>.zip
```

## Use

Open the **Review Queue** tool window on the right and pick a **Scope**: Staged (the default),
Branch vs Base, or an explicit Commit Range. The Commit Range dialog rejects refs containing a
space or `;`.

Press **Start Review** to begin a guided pass over everything still unreviewed in that scope. This
hides the Project and Review Queue tool windows and opens the first file as a diff.

The diff viewer's own toolbar carries two groups. On the left, the actions for the file on screen:

- **Show File List** — every file in the scope with its reviewed state, without leaving the pass.
  Picking a file in the pass jumps the diff to it; picking one that was already reviewed when the
  pass started opens it as a separate browsing diff and leaves the pass alone.
- **Previous File** / **Next File** — step between files without changing any mark. Use Previous File
  together with **Toggle Reviewed** to fix a mis-mark: step back, toggle the wrong mark off, then
  Mark Reviewed to continue from there. Next File is for reading ahead, or for coming back forward
  afterwards without recording a judgement on the file you leave. Each is disabled at its end of the
  pass — Next File at the last file does nothing rather than finishing the pass, which is what
  marking the last file is for.
- **Previous Change** / **Next Change** — move between the changed regions *within* the file on
  screen. These forward to the diff viewer's own Previous/Next Difference, so <kbd>Shift</kbd>+<kbd>F7</kbd>
  and <kbd>F7</kbd> keep working as they always did; the plugin only adds a second way in.
- **Mark Reviewed** — marks the file on screen reviewed and opens the next unreviewed one. Advancing
  **replaces** the diff tab rather than opening a new one, so there is only ever one review tab.

  Four chords are bound, so it is reachable wherever your hand happens to be resting:

  | | macOS | Windows / Linux |
  | --- | --- | --- |
  | | <kbd>⌥</kbd><kbd>⇧</kbd><kbd>Z</kbd> | <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>Z</kbd> |
  | | <kbd>⌘</kbd><kbd>⇧</kbd><kbd>Z</kbd> | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Z</kbd> |
  | | <kbd>⌥</kbd><kbd>⇧</kbd><kbd>Space</kbd> | <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd> |
  | | <kbd>⌥</kbd><kbd>⇧</kbd><kbd>Enter</kbd> | <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>Enter</kbd> |
  | | <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>Space</kbd> | — |
  | | <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>Enter</kbd> | — |

  Two of these are deliberately shared with bundled actions: **Ctrl+Shift+Z is Redo** (Windows and
  Linux only — macOS gets Cmd+Shift+Z instead and drops the Ctrl form), and **Alt+Shift+Enter is
  Split Chooser** and a Database/Grid binding. That is safe here because Mark Reviewed is enabled only
  inside the review diff viewer, whose editors are read-only, so the bundled actions are disabled
  exactly where this one is live. If an ambiguity popup ever appears, these are the two to suspect.

  Navigation has its own macOS cluster, <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd> plus an arrow — left and
  right move **between files**, up and down move **between changes within** the current file:

  | | |
  | --- | --- |
  | <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>←</kbd> | Previous File |
  | <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>→</kbd> | Next File |
  | <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>↑</kbd> | Previous Change |
  | <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>↓</kbd> | Next Change |

  That cluster is macOS-only on purpose: Cmd is a Mac key, and the Windows analogue
  Win+Alt+Shift+arrow is OS window snapping. On Windows and Linux these four are reachable from the
  diff toolbar, or bind your own chords in Settings → Keymap.

  **If you rebind this, avoid the space bar except as Alt+Shift+Space.** Two earlier attempts failed:
  <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd> is Smart Type Completion in the bundled keymaps,
  and on macOS the entire Cmd+Space family is swallowed by input-source switching as soon as a second
  input source is installed — the OS takes the key before the IDE ever sees it.
- **Toggle Reviewed** — adds or removes the reviewed mark on the file currently on screen without
  moving to another file.

A separator, then the session and queue controls on the same toolbar — **Start Review**, **End
Review**, **Refresh** and **Reset All**, the same four as in the tool window. Refresh and Reset All
are how you reach those two without ending the pass, since a pass hides the tool window. Start
Review appears here too, for symmetry with the tool-window group, but shows up greyed out during a
pass — the same as the tool window's own copy — because a pass is already running. Each one **asks
before acting**: they sit directly above the code you are reading, where an accidental press is
expensive. The tool-window copies are unchanged — only Reset All confirms there.

**End Review** leaves the guided pass early. Every mark made so far is kept, and both tool windows
are restored. Closing the review diff tab by hand does the same thing.

The diff tab's title tracks progress as `Review N/M - filename`. Marking the last file restores
both tool windows automatically and fires the completion balloon.

Outside a review session, the tool window itself still lists every file in the current scope with
its reviewed state, and **Refresh** re-reads the scope. **Reset All** clears every reviewed mark in
the project. Files are grouped by git root (repository), then sorted by path within each root,
using the platform's own changes-tree grouping.

Reviewed marks are content-addressed: editing a file drops its mark automatically, so a fix round
returns exactly the rewritten file(s) to the queue, and starting a new review after a fix round
walks only those files. Marks are stored in per-project workspace state
(`.idea/workspace.xml`-equivalent storage) — never in the repository, and never shared between
machines. They survive an IDE restart.

### Known, deliberate limitations

- Rename detection is disabled: a staged rename appears as a delete of the old path plus an add of
  the new path, not a single rename entry.
- When the scope is refreshed and the file that was selected drops out of the queue (e.g. it was
  reviewed and content changed upstream), the cursor falls back to whichever item now occupies the
  old position in the list — which may already be reviewed. This is intentional (it keeps the
  cursor from jumping unpredictably) but can feel surprising; see
  `docs/manual-verification.md` for the checklist item that asks a human to judge whether it feels
  right in practice.

## Develop

```bash
./gradlew test          # unit + integration tests
./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # JetBrains Plugin Verifier against the configured IDE build(s)
```

## Requirements

IntelliJ IDEA Ultimate 2026.2 or newer (build 262+), with the bundled Git4Idea plugin enabled.

## Verification status

- `./gradlew test` — 79 tests, all green.
- `./gradlew verifyPlugin` — **Compatible** with IU-262.9437.22, zero compatibility problems.
  It reports 4 deprecated-API and 6 experimental-API usages, all on `ToolWindowFactory`
  (`isDoNotActivateOnStart`, `isApplicable`, `getIcon`, `getAnchor`, `manage`). These are Kotlin
  compiler-generated bridge overrides for that interface's default methods, not calls this plugin
  makes — `ReviewQueueToolWindowFactory` only implements `createToolWindowContent`. Every Kotlin
  plugin implementing `ToolWindowFactory` reports the same usages; there is nothing to fix here.
- `./gradlew runIde` was launched in a headless sandbox with no display attached (`screencapture`
  fails there with "could not create image from display"), so it could only confirm that the
  plugin loads cleanly — `dev.tweety.reviewqueue` does not appear in the platform's "Problems
  found loading plugins" block and nothing else in the log mentions the plugin. No UI interaction
  was possible in that environment: whether the diff toolbar's two groups
  (`DiffUserDataKeys.CONTEXT_ACTIONS` on the chain, see `EditorTabDiffPresenter`) actually render —
  and every other guided-review interaction — remains **unverified by a human**. One part of that
  question is already settled without a display, though: whether the right-hand group renders flush
  right does not require a human to check, because reading the 2026.2 platform's toolbar-layout
  bytecode is enough to show it cannot, regardless of what a screenshot would show. A `Separator`
  ships instead, grouping the four controls rather than flushing them right — see the design doc's
  *Known risk* for the bytecode read. See
  `docs/manual-verification.md` for the checklist a human with a real display must run before
  relying on this plugin — section 20 covers the guided review flow specifically.
