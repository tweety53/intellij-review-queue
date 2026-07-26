# Manual test — kan-5: remove the Review Queue tool window

**Change:** `kan-5` — delete the right-hand Review Queue tool window and rehome everything it carried:
out-of-session commands to **Tools → Review Queue**, scope selection onto the **diff toolbar** with
mid-pass switching newly allowed, and queue resolution from a startup refresh to an on-demand
synchronous resolve.

**Branch / worktree:** `openspec/kan-5` at
`/Users/tweety53/Projects/intellij-review-queue/.claude/worktrees/openspec-kan-5`

**Next after sign-off:** `/myflow-manual-test-done kan-5`, then `/myflow-review kan-5`

**Manual test status:** SKIPPED — 2026-07-27 (Gate C intentionally bypassed)

---

## Involved apps

Only one: **the Review Queue plugin itself**, in a sandbox IDE. This repo is an IntelliJ Platform
plugin — there is no backend, no KMP frontend and no admin panel in scope, so those run sections are
omitted rather than left as placeholders.

**Requires a real display.** Every item below is a UI observation. A headless sandbox can confirm the
plugin loads and nothing else; that much is already automated and recorded in the README's
*Verification status*.

## How to run

```bash
cd /Users/tweety53/Projects/intellij-review-queue/.claude/worktrees/openspec-kan-5
./gradlew runIde
```

That launches a sandbox IntelliJ IDEA Ultimate 2026.2 with the plugin loaded from this worktree. Do
**not** run from the main checkout at `/Users/tweety53/Projects/intellij-review-queue` — it is on
`main` and does not contain this change.

Inside the sandbox, open any git repository with staged changes. This worktree itself works well and
has the advantage of containing `.gradle.kts` files, which matter for item 1 below.

Useful while testing:

```bash
cd /Users/tweety53/Projects/intellij-review-queue/.claude/worktrees/openspec-kan-5
git diff --cached --stat            # what is under test: 57 files
git diff --cached -- src/main       # production only, ~+993/−416
```

The exhaustive per-feature checklist for the whole plugin lives in
[`docs/manual-verification.md`](../manual-verification.md) — this file covers only what **kan-5**
changed, and points there for the rest.

---

## Functionality checklist

### 1. The shortcut — the one carried risk, and it must be run twice

`Cmd+Option+Shift+R` is knowingly shared with three platform actions. Two are inert or
Database-scoped; the third, `ReloadScriptConfiguration`, declares `ctrl alt shift R` on `$default`
and `MacOSDefaultKeymap` translates Ctrl→Meta, so it holds the same chord and is **live while editing
a Kotlin script**. Checking only the clean case would look fine and prove nothing.

- [ ] With **no** Database view and **no** `.gradle.kts` editor focused, press <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>R</kbd> — Start Review runs, no chooser popup
- [ ] With a **`.gradle.kts` file open and focused**, press the same chord — Start Review still runs, no chooser popup
- [ ] If a chooser appears in **either** case, stop: the binding must change (`Alt+Shift+{C,E,F,H,K,N,O,T,U,W,X,Y}` are verified free, and are cross-platform)

### 2. The menu is the entry point

- [ ] **Tools → Review Queue** exists and renders as a *named* submenu
- [ ] It lists: Scope, Start Review, Show File List, a separator, Refresh, Reset All
- [ ] **Scope** is a nested submenu (Staged / Branch vs Base… / Commit Range…), not three flat entries
- [ ] There is **no** Review Queue tool window anywhere — no right-hand panel, nothing in View → Tool Windows
- [ ] In a project with **no** git repository, every entry in the group is disabled

### 3. On-demand resolution

- [ ] Opening a project runs nothing — no progress dialog, no git activity from the plugin
- [ ] Start Review resolves the scope first (a progress dialog may flash on a large repo) and then opens the first unreviewed file
- [ ] Cancelling that progress dialog leaves everything as it was — no pass starts, no scope recorded
- [ ] **Refresh** from the menu re-reads the scope immediately under a progress dialog
- [ ] **Refresh** on the diff toolbar confirms first, then does the same

### 4. Show File List replaces the deleted tree

- [ ] Works **outside** a pass, and lists every file in the scope with its reviewed state
- [ ] Works **during** a pass
- [ ] Title reads `N / M reviewed  •  <scope>` — this replaced the deleted panel's progress label
- [ ] Picking a file that is part of the running pass jumps the diff to it
- [ ] Picking one already reviewed when the pass started opens a separate browsing diff and leaves the pass alone

### 5. Mid-pass re-scoping — new behaviour

- [ ] The diff toolbar's Scope combo renders, names the current scope, and is **enabled** mid-pass
- [ ] Choosing a scope mid-pass asks first, naming the scope being switched to
- [ ] Declining leaves the pass on the same file with the same scope
- [ ] Accepting restarts the pass in the new scope, and marks made before the switch survive
- [ ] **The Project panel does not flash open and shut during the switch** — the automated test only proxies this through presenter-close counts, so it is human-only
- [ ] Switching into a scope with nothing unreviewed ends the pass **and says so** — it must not congratulate you for a pass you never ran

### 6. Empty and failed results say which

The four messages are distinct on purpose; a single "nothing unreviewed" was wrong in three of the
four cases, and the error balloon dedupes so the false claim would have been the only message on a
second press.

- [ ] Nothing staged → Start Review reports *"Nothing unreviewed in Staged"* rather than doing nothing visibly
- [ ] Bad ref via Branch vs Base → the failed-root balloon names the path and git's own message
- [ ] Press Start Review **again** with the same bad ref → the message must still be about the scope failing to read, **not** "Nothing unreviewed"
- [ ] A repository whose files are all already reviewed → says nothing is unreviewed, and does not open a diff or hide the Project panel

### 7. Refs that git would misread

- [ ] Enter `--output=/tmp/probe` as a Commit Range From ref → the dialog refuses it and explains that git would read it as an option
- [ ] Same in the Branch vs Base base-ref prompt → also refused (this prompt had no validation at all before this change)
- [ ] Confirm no file named in such a ref was created or truncated

### 8. Layout and regressions

- [ ] Start Review hides the Project panel; ending the pass restores it
- [ ] A panel you had already closed yourself is not reopened at end of pass
- [ ] Quit the IDE mid-pass, reopen the project → the Project panel comes back
- [ ] Mark Reviewed's four chords still work (see [`docs/manual-verification.md`](../manual-verification.md) section 20)
- [ ] Previous/Next File at the ends of a pass do nothing rather than ending it
- [ ] Editing a reviewed file returns it to the unreviewed set (content-addressed marks — the plugin's headline guarantee)

---

## Sign-off

- [ ] Section 1 — shortcut, both cases
- [ ] Section 2 — menu entry point
- [ ] Section 3 — on-demand resolution
- [ ] Section 4 — Show File List
- [ ] Section 5 — mid-pass re-scoping
- [ ] Section 6 — empty and failed results
- [ ] Section 7 — ref validation
- [ ] Section 8 — layout and regressions
- [ ] Ready for `/myflow-review kan-5`

---

## Known limitations of this guide

- **Everything above needs a display.** `runIde` in a headless sandbox confirms the plugin loads and
  nothing more.
- **The popup title and the popup itself have no automated coverage** — `showInBestPositionFor` needs
  a live component — so item 4's title check is the only verification that exists for it.
- **One guard is untested by design, not by oversight.** `GitReviewSource`'s validation of the
  *resolved base ref* could not be pinned by any test: reaching it needs `findTrackedBranch` to
  return a hostile name, and two fixture attempts both fell back to `origin/HEAD`. The guard is
  correct and its KDoc explains why it must stay. Item 7 exercises the paths that *are* reachable.
