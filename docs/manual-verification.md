# Manual verification checklist — Review Queue

Everything below requires a human driving the IDE's GUI. Nothing in this checklist has been
performed by an automated agent; treat every box as unchecked until you have actually done it.

Prerequisite: `build/distributions/review-queue-0.1.0.zip` exists (`./gradlew buildPlugin`).

## 1. Install the plugin

1. `open -na "IntelliJ IDEA"` to launch a fresh instance (or use your normal one).
2. Settings → Plugins → gear icon (top right) → **Install Plugin from Disk…**
3. Select `build/distributions/review-queue-0.1.0.zip`.
4. Restart the IDE when prompted.
5. **Expected:** IDE restarts cleanly, no error notification about the plugin failing to load.

## 2. Find a worktree at Gate B

```bash
git -C /Users/tweety53/Projects/gymie worktree list
```

Pick a worktree that is an `openspec/<name>` apply worktree sitting at `awaiting-do-review`
with staged, uncommitted changes. Do not modify anything under `/Users/tweety53/Projects/gymie*`
— open it read-only in the IDE.

Open that worktree's directory as an IDE project.

## 3. Tool window appears and lists staged files grouped by root

1. Look for a **Review Queue** tab on the right-hand tool window bar. Click it open.
2. **Expected:** the tree lists every staged file. If the worktree has more than one attached git
   root (e.g. submodules), files are grouped under a node per root, not flattened together.
3. Confirm against `git -C <worktree> status --porcelain` (and the same for any submodule) that
   the file count and set matches what git itself reports as staged.

## 4. Progress label is correct

1. Look at the "`N / M reviewed`" label in the tool window.
2. **Expected:** `M` equals the total staged file count from step 3. `N` equals the number of
   files already marked reviewed in this project (0 on a fresh worktree/profile).

## 5. Mark Reviewed advances the queue — the core interaction

This is the plugin's central behavior and has not been observed running before this checklist.

1. Select the first unreviewed file in the tree.
2. Click **Mark Reviewed** (toolbar).
3. **Expected:**
   - The file just marked shows a "✓ reviewed" suffix in the tree.
   - The progress label's `N` increments by 1.
   - Selection moves to the next unreviewed file in queue order (root order, then path order) —
     not to a random file, not staying on the same file.
4. Repeat until every file is marked, confirming each step advances correctly and the last mark
   does not throw or freeze the UI.

## 6. Diff tab is single and reused

1. With several files still unreviewed, click on file A in the tree. Note the diff opens in an
   editor tab.
2. Click on file B. **Expected:** the same tab updates to show B's diff — no second tab is opened.
   Check the editor tab strip; there should be exactly one tab related to this queue (titled
   "Review Queue" or similar), not one per file clicked.
3. Click back to file A and confirm the tab shows file A again, still using that one tab.

## 7. Reviewed marks survive an IDE restart

1. Mark at least one file reviewed (if not already done in step 5).
2. Note which files are marked.
3. Close the IDE completely and reopen the same project.
4. Open the Review Queue tool window again.
5. **Expected:** the same files are still shown as reviewed, and the `N / M` count matches what
   it was before closing.

## 8. Editing and re-staging a reviewed file returns exactly that file

1. Pick a file already marked reviewed.
2. Make a small edit to it (in the worktree, on disk) and `git add` it again (re-stage).
3. Refresh the Review Queue (Refresh button, or change scope and back).
4. **Expected:** exactly that one file drops back to unreviewed (progress `N` decrements by 1).
   No other file's reviewed status changes.

## 9. The three scopes work, including commit-range input validation

1. Open the **Scope** dropdown in the toolbar. Confirm three options: **Staged**, **Branch vs
   Base…**, **Commit Range…**.
2. Select **Staged** — queue should match the staged diff (as in steps 3–4).
3. Select **Branch vs Base…** — leave the base ref blank to use the tracked branch, or type an
   explicit ref (e.g. `main`). **Expected:** queue repopulates with the branch-vs-base diff
   (three-dot / merge-base semantics), no error.
4. Select **Commit Range…** — a two-step dialog asks for a "From ref" then a "To ref".
   - Try entering a ref containing a space, e.g. `HEAD 1`. **Expected:** the dialog's OK button
     is disabled / input is rejected — you cannot close the dialog with this value.
   - Try entering a ref containing `;`, e.g. `HEAD;rm`. **Expected:** same rejection.
   - Enter valid refs (e.g. `HEAD~1` and `HEAD`). **Expected:** dialog accepts, queue repopulates
     with that commit range's diff.
5. Switch back to **Staged** to leave the queue in the state Gate B expects.

## 10. Completion balloon and clipboard action

1. Working in a worktree whose current branch is `openspec/<some-name>`, mark every remaining
   file in the queue as reviewed.
2. **Expected:** as soon as the last file is marked, a balloon notification appears once (not
   once per remaining refresh) saying all files are reviewed.
3. The notification should have an action button reading **Copy `/myflow-do-done <name>`** where
   `<name>` is the part of the branch name after `openspec/`.
4. Click that action, then paste (Cmd+V) into a scratch text field or terminal.
5. **Expected:** the pasted text is exactly `/myflow-do-done <name>` — the literal command, with
   the correct change name, nothing else appended.
6. Un-mark a file (e.g. via Reset All, or edit+re-stage as in step 8) and re-mark everything.
   **Expected:** the balloon fires again after going incomplete and complete again — it should
   not have fired a second time while still complete (e.g. from an unrelated Refresh click).

## 11. Known, accepted behaviour — confirm you're fine with it

**Renames show as delete+add.** Rename detection is deliberately disabled. Stage a rename of a
tracked file (`git mv old new && git add -A` in a scratch test repo, not in gymie) and open the
Staged scope.
**Expected:** the queue lists `old` as a deletion and `new` as an addition — two rows, not one
rename row. **Please confirm you find this acceptable** for Gate B review purposes (it is a
known, intentional simplification, not a bug) — or note if it should be revisited.

## 12. Known rough edge — judgment call requested

**Cursor relocation on rebuild.** When the queue is refreshed and the file that was selected is no
longer in the list (e.g. its content changed since being marked, or the scope changed), the
selection falls to whichever file now sits at the same *position* in the list — which may already
be marked reviewed, rather than jumping to the nearest unreviewed file.

To observe this: mark file at position 3 as reviewed, then change the scope (e.g. Staged →
Branch vs Base and back, or edit+unstage the file at position 3) so the queue rebuilds with a
different file occupying position 3.
**Expected reaction:** the selection moves to whatever is now at position 3, even if already
reviewed. This is intended behavior (keeps cursor movement predictable/local rather than jumping
around), not a bug. **Please judge whether this feels right in practice** — flag it if it's
disorienting enough to warrant a follow-up change.

---

## Sign-off

Record here once each section above has actually been performed (do not check off from reading
the code — only from having done it in the IDE):

- [ ] 1. Install
- [ ] 2. Found a Gate B worktree
- [ ] 3. Tool window lists staged files, grouped by root
- [ ] 4. Progress label correct
- [ ] 5. Mark Reviewed advances the queue
- [ ] 6. Single reused diff tab
- [ ] 7. Marks survive IDE restart
- [ ] 8. Re-staging returns exactly the edited file
- [ ] 9. Three scopes work; commit-range rejects space/`;`
- [ ] 10. Completion balloon + clipboard copy verified
- [ ] 11. Rename-as-delete+add — acceptable? (yes/no + comment)
- [ ] 12. Cursor relocation — feels right? (yes/no + comment)
