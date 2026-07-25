# Review Queue — IntelliJ IDEA plugin design

**Date:** 2026-07-25
**Status:** Approved

## Purpose

myflow's Gate B (`awaiting-do-review` / `do-review-started`) asks a human to read a staged,
uncommitted diff in an apply worktree opened in IntelliJ IDEA, then run `/myflow-do-done` or
`/myflow-do-fix`. IntelliJ offers no way to track progress through that diff: its "Mark as Viewed"
concept is wired to code-review and commit views (GitHub PRs, Log), not to local staged changes.
On a change touching a dozen files across three repos, there is nothing that records which files
have already been read.

This plugin fills that gap: a tool window listing the files under review, a per-file reviewed mark
that survives IDE restarts, and a one-key flow that marks the current file and moves to the next
unreviewed one.

## Scope

**In scope**

- Three review scopes: staged changes, branch-vs-base, and an explicit commit range.
- Per-file reviewed marks, content-addressed so a later edit invalidates them.
- All git roots attached to the project, grouped by root.
- Completion notification with a clipboard shortcut for `/myflow-do-done <name>`.

**Out of scope**

- Per-file or per-line review comments.
- Sharing review state between machines or users.
- Any write-back into myflow's state file, or any git command that mutates the repo.
- Review scopes beyond the three above.

## Stack

Kotlin, Gradle with the IntelliJ Platform Gradle Plugin 2.x, JDK 21, `platformVersion` 2026.2 (IU),
depending on the bundled `Git4Idea` plugin. Repository root: `/Users/tweety53/Projects/intellij-review-queue`.

## Components

### `ReviewScope`

A sealed type with three cases:

- `Staged` — the git index against HEAD.
- `BranchVsBase(baseRef)` — the working branch against a base ref. The base defaults to the
  merge-base with the repository's tracked (or default) branch; for a myflow apply worktree that
  resolves to `openspec/<name>` vs `develop`.
- `CommitRange(from, to)` — an explicit range.

Each case resolves itself against a `GitRepository` into a list of `Change`. This is the only
component that talks to git.

### `ReviewItem`

One row in the queue: git root, repo-relative path, the underlying `Change`, and a `contentHash`.
The hash is SHA-1 over the after-revision bytes. A deletion (no after-revision) hashes a fixed
sentinel. A file whose content cannot be read — binary, oversized, deleted on disk — hashes its
revision number instead, so every item is always hashable.

### `ReviewStateService`

Project-level `@Service` implementing `PersistentStateComponent`, `roamingType = DISABLED`, so state
lives in the IDE's per-project workspace storage and never touches the repository.

Its entire state is `Map<String, String>`: the key is `"<rootPath>|<relPath>"`, the value is the
content hash that was reviewed. A file counts as **reviewed** if and only if its stored hash equals
its current hash. Staleness therefore needs no invalidation logic: a `/myflow-do-fix` round that
rewrites a file changes its hash, and the file silently returns to the unreviewed set. Reverting a
file to its previously reviewed bytes restores the mark.

Because state is keyed by root, path, and hash — not by scope — a file reviewed under `Staged`
still reads reviewed under `BranchVsBase` when the content is identical.

Entries whose paths appear in no current queue are pruned on each queue rebuild, bounding state
growth.

### `ReviewQueueService`

Project-level `@Service` owning the active scope, the ordered queue, and the cursor.

Ordering is grouped by git root in `GitRepositoryManager` order, path-sorted within each root. The
order is stable across refreshes, so the cursor behaves predictably.

The service subscribes to `ChangeListListener` and `GitRepositoryChangeListener`, rebuilding the
queue and re-hashing on any VCS change.

### `ReviewQueuePanel`

A `ChangesTree` subclass hosted in a right-docked tool window. Subclassing the platform tree
inherits repo grouping, path shortening, file icons, and the standard context menu; the plugin adds
a reviewed decorator (checkmark, dimmed label) and a `3 / 12 reviewed` progress label.

Toolbar: scope selector, Mark reviewed, Refresh, Reset all. **Refresh** re-resolves and re-hashes
the queue on demand, for the case where a change arrives without a VCS event. **Reset all** clears
every stored mark for the current project after a confirmation prompt. The scope selector opens an
input for the base ref or commit range when either of those scopes is chosen.

There are no Next/Previous actions. Marking a file advances the cursor, and clicking any row opens
that file's diff — which is also how an already-reviewed file is revisited.

### `ReviewDiffOpener`

Opens the current item through `DiffManager` using a diff chain, reusing a single editor tab rather
than accumulating one tab per file.

### `CompletionNotifier`

Fires when the queue reaches fully-reviewed: a balloon reading `All N files reviewed`, carrying a
*Copy `/myflow-do-done <name>`* action. `<name>` is parsed from the current branch when it matches
`openspec/<name>`; when it does not, the balloon shows without the copy action.

The notifier re-arms only after the queue returns to a not-fully-reviewed state, so refreshes
cannot repeat the balloon.

## Data flow

1. The tool window opens. `ReviewQueueService` resolves the active scope across all git roots,
   builds and hashes `ReviewItem`s, and `ReviewStateService` classifies each as reviewed or
   unreviewed. The tree renders and the cursor lands on the first unreviewed item.
2. Clicking a row moves the cursor and opens that file's diff via `ReviewDiffOpener`.
3. **Mark reviewed** stores the current item's hash, then advances to the next unreviewed item,
   wrapping to the start of the queue once so that files above the cursor are not stranded. If no
   unreviewed item remains, the cursor stays put and the completion notification fires. Toggling a
   reviewed file off removes its stored entry and does not move the cursor.
4. A VCS change event rebuilds and re-hashes the queue. Files whose hash moved lose their reviewed
   state. The cursor stays on the same path if it still exists; otherwise it falls to the nearest
   following item.
5. Changing the scope rebuilds the queue. Reviewed marks carry over wherever content matches.

## Error handling

Failures are per-root and non-fatal. A root that fails to resolve — detached HEAD, an unknown ref in
a commit range, a git binary error — is reported on an error line beneath the tree, naming the root
and the message, while every other root lists normally. The queue never fails as a whole because one
root could not be read.

Commit-range input is validated when entered, not at resolution time.

The plugin never writes to the repository and never runs a mutating git command.

## Testing

Automated tests cover logic, not Swing:

- **Scope resolution** — a real temporary git repository per test (`git init`, commit, stage,
  branch), asserting each scope yields the expected paths. This covers the merge-base resolution in
  `BranchVsBase`, the most error-prone piece.
- **Hash and staleness** — mark a file reviewed, rewrite it, re-resolve, assert unreviewed; restore
  the original bytes, assert reviewed again.
- **Queue ordering and cursor** — multi-root fixtures asserting group order, mark-advance
  wrap-around, and cursor recovery when the current file leaves the queue.
- **Persistence round-trip** — serialize and deserialize `ReviewStateService`, including pruning.
- **Notification arming** — fires once at completion and re-arms only after the queue goes
  incomplete.

UI is verified manually via `./gradlew runIde` against an `openspec/*` apply worktree.

## Success criteria

- Opening an apply worktree and the tool window lists every staged file across all attached git
  roots, grouped by root.
- Marking files advances through the queue; progress survives an IDE restart.
- A `/myflow-do-fix` round returns exactly the rewritten files to the unreviewed set.
- Completing the queue produces a notification that puts `/myflow-do-done <name>` on the clipboard.
