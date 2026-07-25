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

Before you touch anything else, capture the repository baseline that section 16 diffs against:

```bash
W=<worktree>            # and repeat for every submodule root
{ git -C "$W" rev-parse HEAD; git -C "$W" status --porcelain; \
  git -C "$W" stash list; git -C "$W" reflog | head; } > /tmp/review-queue-before.txt
```

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

**Note on ordering:** the cursor advances in *queue* order — git roots in the order the repository
manager reports them, then path order within each root. Under directory grouping the tree draws
rows in a different visual order, so the next selection may not be the row directly below the one
you just marked. That is expected; judge correctness against root-then-path order, not against
what the tree looks like.

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
7. **Reopen an already-complete project.** With every file in the queue marked reviewed, close the
   project (File → Close Project) and reopen it, then open the Review Queue tool window.
   **Expected:** **no** balloon appears. The queue was already complete before you arrived; there
   is nothing to announce. A balloon here is a defect — report it.

## 11. Reviewed marks are never lost

The stored marks are a user's review work. Nothing short of Reset All may destroy them. Each part
below has previously been a real defect, so run all three.

1. **Scope round trip — refresh while you are away.** With the Staged scope, mark several files
   reviewed and note exactly which. Switch the scope to **Commit Range…** (`HEAD~1` → `HEAD`).
   **Now click Refresh at least twice while still in the Commit Range scope**, and give the IDE a
   few seconds so background VCS events land too. Then switch back to **Staged**.
   The extra refreshes are the point: the defect this catches only appeared from the *second*
   rebuild in the foreign scope onward, so a round trip without them proves nothing.
   **Expected:** every mark you noted is still there and the progress `N` is unchanged. Marks are
   keyed by root/path/hash, so they carry across scopes.
2. **Project close and reopen.** Leave the Review Queue tool window open, close the project
   (File → Close Project), reopen it, and open the Review Queue.
   **Expected:** the marks are present *before* you click Refresh, change scope, or touch anything
   else. Check the label the moment the tree first populates.
3. **A failing root.** Switch to **Branch vs Base…** and enter a ref that does not exist
   (e.g. `no-such-ref-xyz`). The queue will show an error line.
   **Expected:** the error is reported and the marks survive it. Switch back to **Staged**:
   `N` is what it was before the bad ref.

## 12. Error line: one failing root does not take down the others

Needs a project with two git roots (a repo with a submodule, or two directory mappings).

1. Point the scope at something one root can resolve and the other cannot — e.g. **Branch vs
   Base…** with a ref that exists in only one root, or **Commit Range…** with a `from` ref that
   only one root knows.
2. **Expected:** the healthy root still lists its files normally in the tree, and the error line
   under the progress label names *the failing root's path* alongside git's own message (not a
   generic "Could not diff …"). The whole queue must not go blank.

## 13. Staged deletion and binary file return to unreviewed

This checks that marks are addressed to content and not to a constant.

1. **Deletion.** In a scratch test repo, `git rm somefile.txt`. Refresh the queue; the deletion
   appears as a row. Mark it reviewed. **Expected:** the row shows "✓ reviewed".
   Now `git reset somefile.txt && git rm --cached otherfile.txt` (i.e. stage a *different*
   deletion) and refresh. **Expected:** the new deletion is unreviewed — it did not inherit the
   previous deletion's "reviewed" state.
2. **Binary file.** Stage a PNG (`cp a.png img.png && git add img.png`). Mark it reviewed.
   Replace it with a *different* PNG (`cp b.png img.png && git add img.png`) and refresh.
   **Expected:** `img.png` returns to unreviewed. If it stays reviewed, the hash is not tracking
   the file's bytes — report it.

## 13b. An unrenderable file does not break diff opening for the rest

1. With the PNG from step 13 still staged, click its row in the tree.
2. **Expected:** either its diff opens or nothing happens — no error dialog, no "IDE internal
   error" balloon, no frozen tool window.
3. Now click a plain text file's row.
4. **Expected:** that file's diff opens normally. One file the diff framework cannot render must
   not disable diff opening for every other file in the queue.

## 14. Nested root / submodule marks the file you selected

Needs a project with a submodule (a git root nested inside another git root).

1. Stage a change inside the submodule and a change to the submodule gitlink in the outer repo.
2. Click the row for a file inside the submodule.
3. Press **Mark Reviewed**.
4. **Expected:** the "✓ reviewed" marker appears on **the row you selected**, and the progress
   label increments by exactly 1. If the marker lands on a different file — or on no visible row
   at all — stop and report it.

### 14b. The submodule gitlink itself — open question, please answer it

Staging a submodule pointer bump produces a *gitlink* change in the **outer** repo (a row for the
submodule directory itself, not for a file inside it). Its content revision is
`GitSubmoduleContentRevision`, which is **not** a `ByteBackedContentRevision` — so if such a row
reaches the queue, the plugin cannot read bytes for it and hashes it as unresolved on every
refresh. The consequence would be a row that can never stay marked, and therefore a queue that can
never reach "all files reviewed".

1. In the outer repo, `git add <submodule-dir>` after committing something inside the submodule.
2. Refresh the queue and answer: **does a row for the submodule directory appear at all?**
   (It may legitimately be filtered out before reaching the queue.)
3. If it does appear: select it, press **Mark Reviewed**, then click **Refresh**.
   **Expected if healthy:** it stays marked. **If it reverts to unreviewed on every refresh**,
   record that — it means the completion balloon can never fire in a repo with submodule bumps.
4. Record the answer either way; this has not been determined.

## 15. Toggle Reviewed un-marks a single file

1. Mark two or three files reviewed.
2. Select one of them and click **Toggle Reviewed** in the toolbar.
3. **Expected:** that file's "✓ reviewed" marker disappears, `N` decrements by 1, the other marks
   are untouched, and the selection/cursor does **not** move.
4. Click **Toggle Reviewed** again on the same file. **Expected:** it is marked again, cursor still
   unmoved.
5. With nothing selected (click empty space below the rows), **Expected:** the toolbar button is
   disabled.

## 16. No repository mutation

The plugin issues read-only git queries only; nothing it does may change a repository.

1. After finishing every section above, capture the same state you captured in section 2, for the
   worktree and for every submodule root:

```bash
{ git -C "$W" rev-parse HEAD; git -C "$W" status --porcelain; \
  git -C "$W" stash list; git -C "$W" reflog | head; } > /tmp/review-queue-after.txt
diff /tmp/review-queue-before.txt /tmp/review-queue-after.txt
```

2. **Expected:** `diff` reports no differences at all. Any change to HEAD, to the index/worktree
   status, to the stash list, or a new reflog entry means something mutated the repo — stop and
   report it immediately.

## 17. Known, accepted behaviour — confirm you're fine with it

**Renames show as delete+add.** Rename detection is deliberately disabled. Stage a rename of a
tracked file (`git mv old new && git add -A` in a scratch test repo, not in gymie) and open the
Staged scope.
**Expected:** the queue lists `old` as a deletion and `new` as an addition — two rows, not one
rename row. **Please confirm you find this acceptable** for Gate B review purposes (it is a
known, intentional simplification, not a bug) — or note if it should be revisited.

## 18. Known rough edge — judgment call requested

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

## 19. Read the IDE log — do this last

Threading and re-entrancy defects often show up only as logged errors, with no visible symptom.
Do not sign off without this step.

**First, make sure the slow-operations assertion is actually on.** It is registry-gated, and a
clean log proves nothing while it is off. Open **Help → Find Action → Registry…**, find
`ide.slow.operations.assertion`, and confirm it is **enabled**. If it was off, enable it, restart
the IDE, and redo sections 3–16 before reading the log.

1. **Help → Show Log in Finder** and open `idea.log` (use the log from the instance you just drove).
2. Search the log for each of:
   - `StackOverflowError` — a selection/refresh feedback cycle.
   - `Slow operations are prohibited on EDT` — git subprocesses running on the UI thread.
   - `com.intellij.diagnostic` entries mentioning `reviewqueue` — freeze reports attributed to
     this plugin.
   - `dev.tweety.reviewqueue` at `WARN`/`ERROR` — e.g. "no tree node for cursor key …" or
     "duplicate entry for …", both of which indicate a real inconsistency.
3. **Expected:** none of the above appear. Paste any hit verbatim into the sign-off notes.
4. Note in the sign-off whether `ide.slow.operations.assertion` was on for the run you are
   reporting. If it was off, say so — the log result is inconclusive.

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
- [ ] 10. Completion balloon + clipboard copy verified, and silent on reopening a complete project
- [ ] 11. Reviewed marks survive a refreshed scope round trip, project reopen and a failing root
- [ ] 12. One failing root reports its own error; other roots still list
- [ ] 13. Staged deletion and binary file return to unreviewed on change
- [ ] 13b. An unrenderable file does not break diff opening for other files
- [ ] 14. Nested root/submodule — the file marked is the file selected
- [ ] 14b. Submodule gitlink — does a row appear, and can it stay marked? (record the answer)
- [ ] 15. Toggle Reviewed un-marks one file without moving the cursor
- [ ] 16. `git` before/after diff is empty — no repository mutation
- [ ] 17. Rename-as-delete+add — acceptable? (yes/no + comment)
- [ ] 18. Cursor relocation — feels right? (yes/no + comment)
- [ ] 19. idea.log read; no StackOverflowError / slow-EDT / reviewqueue diagnostics
      (state whether `ide.slow.operations.assertion` was enabled)
