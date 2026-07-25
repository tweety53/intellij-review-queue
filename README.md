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

Open the **Review Queue** tool window on the right.

- **Scope** — choose Staged (the default), Branch vs Base, or an explicit Commit Range. The
  Commit Range dialog rejects refs containing a space or `;`.
- **Mark Reviewed** — marks the selected file and moves to the next unreviewed one.
- Click any row to open its diff. All files in the queue share a single reused "Review Queue"
  diff tab (a chain viewer), rather than accumulating one tab per file. Reviewed files stay
  listed so they can be revisited.
- **Refresh** re-reads the current scope. **Reset All** clears every reviewed mark in the project.
- Files are grouped by git root (repository), then sorted by path within each root, using the
  platform's own changes-tree grouping.

Reviewed marks are content-addressed: editing a file drops its mark automatically, so a fix round
returns exactly the rewritten file(s) to the queue. Marks are stored in per-project workspace state
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

- `./gradlew test` — 50 tests, all green.
- `./gradlew verifyPlugin` — **Compatible** with IU-262.9437.22, zero compatibility problems.
  It reports 4 deprecated-API and 6 experimental-API usages, all on `ToolWindowFactory`
  (`isDoNotActivateOnStart`, `isApplicable`, `getIcon`, `getAnchor`, `manage`). These are Kotlin
  compiler-generated bridge overrides for that interface's default methods, not calls this plugin
  makes — `ReviewQueueToolWindowFactory` only implements `createToolWindowContent`. Every Kotlin
  plugin implementing `ToolWindowFactory` reports the same usages; there is nothing to fix here.
- Manual UI verification (installing the built zip and running it against a real Gate B worktree)
  has **not** been performed by automation. See `docs/manual-verification.md` for the checklist a
  human should run before relying on this plugin.
