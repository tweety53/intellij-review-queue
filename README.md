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

Everything the plugin offers outside a review pass lives under **Tools → Review Queue**: a **Scope**
submenu, **Start Review**, **Show File List**, then a separator, **Refresh** and **Reset All**. There
is no tool window — the right-hand panel this plugin used to ship is gone, and nothing runs at
project open any more: the queue is resolved when a command asks for it, under a progress dialog you
can cancel.

Every entry in the group is enabled whenever the project has at least one git root, and disabled in a
project with none. Enablement deliberately does not look at the queue's contents, because nothing has
resolved the queue until you ask it to.

**Scope** is a nested submenu: **Staged** (the default), **Branch vs Base…**, **Commit Range…**.
Branch vs Base asks for a base ref and uses the tracked branch when you leave it empty. Commit Range
asks for a From ref, then a To ref.

Every ref is rejected if it contains whitespace or a shell metacharacter (`;`, `&`, `|`, a backtick,
`$`), **or if it begins with `-`**. That last rule is the one that matters: git4idea resolves a ref with
`git rev-list --timestamp --max-count=1 <ref>` and no `--` separator, so a ref starting with a dash is
read as an option — and `--output=<file>` truncates that file before git rejects the missing commit.
A ref is checked wherever it comes from, including refs this plugin never asked you for: the resolved
base and **the current branch name**. Branch names may legally begin with `-`, and `git clone` checks
out whatever the remote's HEAD points at, so cloning a hostile repository and pressing Start Review was
otherwise enough to zero `.git/index`. This plugin only ever reads a repository, and that rule is part
of how it keeps that promise.

**The scope can be changed during a pass**, which it could not before. It asks first — *"Switch the
review scope to …? The current pass restarts and every mark made so far is kept."* — and on Yes it
resolves the new scope and restarts the pass in place, keeping every mark. Cancelling the ref prompt
or the progress dialog leaves the pass exactly where it was. Outside a pass, choosing a scope records
it and re-reads it in the background; the next Start Review or Show File List resolves it for real,
which is the point at which you see a progress dialog.

**Start Review** begins a guided pass over everything still unreviewed in the current scope. It hides
the Project tool window and opens the first file as a diff.

On **macOS** it is <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>R</kbd>, joining the plugin's
<kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd> cluster. **On Windows and Linux it ships with no shortcut at
all.** That cluster is macOS-only because `meta` is the Windows key in the cross-platform keymap, so
there the way in is the Tools menu or Find Action — or bind your own chord in Settings → Keymap.

**Show File List** is now the only way to browse the queue, in a pass or out of one: the old panel's
queue tree is gone with the panel. It lists every file in the current scope with its reviewed state,
and its title reads `N / M files reviewed  •  <scope>` — that title is what replaced the panel's progress
label. Picking a file that is part of a running pass jumps the diff to it; picking one that was
already reviewed when the pass started opens it as a separate browsing diff and leaves the pass
alone. Outside a pass, every pick is a browsing diff.

**Refresh** re-reads the scope **immediately**, under a progress dialog, from the Tools menu and from
the diff toolbar alike — there is no list left for a background refresh to update, so the progress
dialog is itself the sign that anything happened. **Reset All** clears every reviewed mark in the
project, after confirming.

Failed repositories and an empty scope are reported as **notifications** now, not as labels in a
panel. A root that cannot be read balloons with its path and git's own message; an unchanged failure
on the next rebuild stays quiet, and a root that recovers and breaks again is announced again. A
resolve that leaves nothing to do says *which* nothing it found rather than appearing to do nothing:
*"Nothing unreviewed in Staged"* only when that is what happened, and otherwise that the scope could
not be read, that the project has no git repository, or that no unreviewed file could be displayed.
Switching scope mid-pass into a scope with nothing unreviewed says so as well, and does not claim you
finished a pass you never ran.

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

A separator, then the session and queue controls on the same toolbar, led by a **Scope** combo box
that names the current scope and opens the same three choices as the menu — **Start Review**, **End
Review**, **Refresh** and **Reset All** follow it. This is how you reach any of them without ending
the pass. Start Review appears here too, for uniformity, but shows up greyed out during a pass
because a pass is already running. Each one **asks before acting**: they sit directly above the code
you are reading, where an accidental press is expensive. In the Tools menu only Reset All confirms.

**End Review** leaves the guided pass early. Every mark made so far is kept and the Project tool
window is restored. Closing the review diff tab by hand does the same thing. End Review is not in the
Tools menu — it belongs to a running pass, so it lives on the diff toolbar and in Find Action.

The diff tab's title tracks progress as `Review N/M - filename`. Marking the last file restores the
Project tool window automatically and fires the completion balloon.

Files are ordered by git root (repository), then by path within each root, so a queue spanning
submodules stays grouped by the repository each file belongs to.

Reviewed marks are content-addressed: editing a file drops its mark automatically, so a fix round
returns exactly the rewritten file(s) to the queue, and starting a new review after a fix round
walks only those files. Marks are stored in per-project workspace state
(`.idea/workspace.xml`-equivalent storage) — never in the repository, and never shared between
machines. They survive an IDE restart.

## Develop

```bash
./gradlew test          # unit + integration tests
./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # JetBrains Plugin Verifier against the configured IDE build(s)
```

## Requirements

IntelliJ IDEA Ultimate 2026.2 or newer (build 262+), with the bundled Git4Idea plugin enabled.

## Verification status

- `./gradlew test` — 215 tests, all green.
- `./gradlew verifyPlugin` — **Compatible** with IU-262.9437.22, zero compatibility problems, and
  **zero deprecated-API and zero experimental-API usages**.
  Earlier releases reported 4 deprecated and 6 experimental usages, all Kotlin-generated bridge
  overrides of `ToolWindowFactory`'s default methods. That class was deleted with the tool window, so
  the count is now zero and there is nothing left to explain away.
- `./gradlew runIde` was launched in a headless sandbox with no display attached (`screencapture`
  fails there with "could not create image from display"), so it could only confirm that the
  plugin loads cleanly — the sandbox log records `Loaded custom plugins: Review Queue (0.4.0)`, and
  `dev.tweety.reviewqueue` appears nowhere in the platform's "Problems found loading plugins" block.
  (That block is non-empty, but names only bundled Ultimate plugins missing
  `com.intellij.modules.ultimate` in the sandbox. One consequence worth knowing before the shortcut
  check: if the Database plugin does not load there, `DatabaseView.ForceRefresh` is absent, so a clean
  chord press proves less than it appears to — the `.gradle.kts` case is the informative one.)
  No UI interaction
  was possible in that environment: whether the diff toolbar's two groups
  (`DiffUserDataKeys.CONTEXT_ACTIONS` on the chain, see `EditorTabDiffPresenter`) actually render,
  whether **Tools → Review Queue** renders as a named submenu, whether
  <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>R</kbd> reaches Start Review or raises an action-chooser
  popup, and every other guided-review interaction — all remain **unverified by a human**. One part
  of that question is already settled without a display, though: whether the right-hand group renders
  flush right does not require a human to check, because reading the 2026.2 platform's toolbar-layout
  bytecode is enough to show it cannot, regardless of what a screenshot would show. A `Separator`
  ships instead, grouping the session controls rather than flushing them right — see the design doc's
  *Known risk* for the bytecode read.
- **The progress banner, the focus-mode tool-window sweep, and the right-click context menu remain
  unverified by a human**, same as the rest of the guided-review flow above — none of the three has
  been driven through a real display. Staged-rename detection is exercised end to end by
  `GitReviewSourceIntegrationTest` against a real `git mv`, so it does not carry the same caveat, but
  the human pass in `docs/manual-verification.md` section 27 still asks for a look because Branch vs
  Base and Commit Range are worth confirming unchanged, not just Staged.
- **One more honest limitation, specific to the context menu.** `ReviewDiffExtensionTest` cannot
  construct a live `CacheDiffRequestChainProcessor` — building one aborts headlessly with
  `no ComponentUI class for DiffHeaderToolbarPanel`, because no look-and-feel is registered for that
  Swing component in a headless test run. The test instead subclasses `DiffContextOnDataHolders`
  directly (the real base class the platform's private `DiffRequestProcessor$MyDiffContext` extends)
  and drives it with a real `ChangeDiffRequestChain` and `ChangeDiffRequestProducer`, which exercises
  the actual marker-propagation logic without building the Swing toolbar around it. That means the
  claim "the right-click menu appears at all in a real IDE" rests on bytecode disassembly of
  `intellij.platform.diff.impl` and `intellij.platform.vcs.impl` (see the KDoc on
  `ReviewDiffExtension` and on `ReviewDiffExtensionTest`) plus this structurally-faithful headless
  substitute — **not** on a live UI test, because none could be built in this environment. Section 28
  of the manual checklist is where a human closes that gap.

See `docs/manual-verification.md` for the checklist a human with a real display must run before
relying on this plugin — section 20 covers the guided review flow, section 24 the entry points KAN-5
changed (including the Start Review shortcut, which must be tested twice), and sections 25–28 the four
features new in this release: the progress banner, focus mode, rename detection and the context menu.
