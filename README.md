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
- **Mark Reviewed** (also <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd> on macOS,
  <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd> on Windows and Linux) — marks the
  file on screen reviewed and opens the next unreviewed one. Advancing **replaces** the diff tab
  rather than opening a new one, so there is only ever one review tab.
- **Previous File** — steps back to the file shown before this one, without changing any mark. Use
  it together with **Toggle Reviewed** to fix a mis-mark: step back, toggle the wrong mark off, then
  Mark Reviewed to continue from there.
- **Toggle Reviewed** — adds or removes the reviewed mark on the file currently on screen without
  moving to another file.

Right-aligned on the same toolbar, the session and queue controls — **Start Review**, **End
Review**, **Refresh** and **Reset All**, the same four as in the tool window. A pass hides the tool
window, so these are how you reach them without ending it. Each one **asks before acting**: they sit
directly above the code you are reading, where an accidental press is expensive. The tool-window
copies are unchanged — only Reset All confirms there.

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
  (`DiffUserDataKeys.CONTEXT_ACTIONS` on the chain, see `EditorTabDiffPresenter`) actually render as
  designed — including whether the right-hand group renders flush right — and every other
  guided-review interaction, remains **unverified by a human**. See
  `docs/manual-verification.md` for the checklist a human with a real display must run before
  relying on this plugin — section 20 covers the guided review flow specifically.
