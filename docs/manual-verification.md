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

**Superseded — see section 20.** Mark Reviewed no longer lives in the tool window toolbar and does
not act on a tree selection. It lives on the diff viewer's own toolbar (and on
<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd>) and only appears once a guided pass is running.
Run section 20 for the current mechanism; this section is kept only so the numbering below does not
shift.

## 6. Diff tab opens on click, outside a session

This covers **browsing** a file from the tool window when no guided review is running — distinct
from the guided flow in section 20, where advancing happens inside the diff viewer itself. The two
are not in tension: clicking a row still opens that file's diff at any time, session or not.

1. With several files still unreviewed and no review in progress, click on file A in the tree.
   Note the diff opens in an editor tab.
2. Click on file B. **Expected:** the same tab updates to show B's diff — no second tab is opened.
   Check the editor tab strip; there should be exactly one tab related to this queue (titled
   "Review Queue" or similar), not one per file clicked.
3. Click back to file A and confirm the tab shows file A again, still using that one tab.

## 7. Reviewed marks survive an IDE restart

1. Mark at least one file reviewed (if not already done in section 20).
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

## 20. Guided review mode

The tool window is now a scope picker and a launcher; marking, un-marking and navigation all
happen in the diff viewer once a review is running. This section checks that flow end to end.

1. **Start Review hides both tool windows; End Review restores them.**
   - With the queue showing at least two unreviewed files, click **Start Review**.
   - **Expected:** the Project tool window and the Review Queue tool window both disappear, and the
     first unreviewed file opens as a diff with **Previous File**, **Mark Reviewed**, **Toggle
     Reviewed** and **End Review** on the diff viewer's own toolbar.
   - Click **End Review**. **Expected:** both tool windows reappear, the diff tab closes, and every
     mark made during the pass is kept.

2. **The persisted layout snapshot survives quitting mid-session.**
   - Start Review again, mark one file, then quit the IDE entirely (not just close the project)
     while the tool windows are still hidden.
   - Reopen the same project.
   - **Expected:** the Project and Review Queue tool windows come back on their own, without you
     doing anything else. (The session itself is not resumed — this only checks that the layout
     does not stay stuck hidden with no explanation.)

3. **Closing the review diff tab by hand ends the session.**
   - Start Review, then close the diff tab yourself (the "x" on the tab, or Ctrl+F4/Cmd+W).
   - **Expected:** both tool windows restore immediately, the same as clicking End Review. The
     session does not linger in a state where the tool windows are hidden but no diff is open.

4. **Mis-mark recovery: Previous File + Toggle Reviewed.**
   - Start Review. Click **Mark Reviewed** on the first file (this is the "wrong" mark).
   - Click **Previous File**. **Expected:** the diff steps back to the file just marked, its mark
     is untouched, and the tab title's `Review N/M` reflects the earlier position again.
   - Click **Toggle Reviewed**. **Expected:** that file's mark is removed and the diff stays on the
     same file (Toggle Reviewed does not advance).
   - Click **Mark Reviewed**. **Expected:** the file is marked again and the pass continues forward
     from there, in order — the round trip did not skip or duplicate a file.

5. **The Mark Reviewed shortcut marks and advances in the review diff.**
   - During a review, place focus in the diff viewer. Press Cmd+Shift+Enter on macOS, or
     Ctrl+Shift+Enter on Windows/Linux. **Expected:** it marks the file reviewed and advances, same
     as clicking Mark Reviewed.
   - Now the case that matters: **leave the review running.** Do not click End Review. With the
     session still active, open a normal source file in another editor tab (Cmd+Shift+N / double
     click a file in the Recent Files popup — the Project tool window is hidden), click into that
     editor so it has focus, and press the same chord.
   - **Expected:** it does **nothing at all** — no "Choose action" popup, no silent advance through
     the review. That is the `DIFF_CONTEXT` gate doing its job. On Windows/Linux, Complete Current
     Statement should fire there instead, normally: it shares Ctrl+Shift+Enter, and the gate is what
     keeps the two from competing.
   - **To verify the binding registered**, open Settings → Keymap, search for "Mark Reviewed", and
     confirm it shows the chord for your platform. On Windows/Linux a conflict warning against
     Complete Current Statement is expected and accepted; on macOS there should be none.

   **If the shortcut does nothing but the toolbar button works**, the action and its gate are fine
   and the chord itself is being eaten before the IDE sees it. Do not start by suspecting the plugin.
   Diagnose it this way, which takes a minute and no rebuild: in Settings → Keymap, add a second,
   unrelated shortcut to Mark Reviewed and press that instead. If the second chord works, the
   original is being consumed below the IDE and the fix is to choose a different one.

   Two chords have already failed this way, which is why the binding is what it is:
   - `Ctrl+Shift+Space` is Smart Type Completion in both bundled keymaps.
   - `Cmd+Shift+Space` never reaches the IDE on macOS once a second input source is installed — the
     OS claims the Cmd+Space family for input-source switching. Note that `defaults read
     com.apple.symbolichotkeys` does **not** reliably list this: a chord's absence from that plist is
     not evidence the system leaves it alone.

6. **Start Review after a fix round walks only the changed files.**
   - Finish or end a review with every file marked. Edit and re-stage exactly one of the
     previously-reviewed files, the same content-addressed drop-back that section 8 checks outside
     a session — re-staging one file returns exactly that file to unreviewed, whether or not a
     review happens to be running when it happens.
   - Click **Start Review** again. **Expected:** the pass covers only that one file — Start Review
     is disabled with nothing unreviewed, and once something is unreviewed it opens only that.

7. **Re-test carried over: Toggle Reviewed now enables.**
   In the previous release, Toggle Reviewed looked permanently disabled. The likely cause was
   `refresh()` dying under a read action and leaving the queue empty, which fed nothing for the
   action to act on. That code path is fixed. Confirm directly: during a review (or with a file
   selected in the tool window before Start Review), Toggle Reviewed is enabled and works as
   described in step 4/section 15, not greyed out.

8. **The tab title tracks progress forward, not just on the first file and one step back.**
   - Start Review in a scope with at least four unreviewed files. Note the diff tab title —
     **Expected:** `Review 1/M - <first file's name>`.
   - Click **Mark Reviewed**. **Expected:** the title updates to `Review 2/M - <second file's
     name>` — both the count and the filename change together.
   - Click **Mark Reviewed** twice more. **Expected:** the title reads `Review 3/M - <third file's
     name>` after the first of those two clicks, then `Review 4/M - <fourth file's name>` after the
     second — the count advances by exactly one per mark and the filename always matches the file
     actually on screen, not the previous one.

9. **The completion balloon fires from the diff toolbar, not the tool window.**
   Section 10 above was written for marking from the tool window, which no longer exists as a way
   to mark a file — this is the equivalent check for the current mechanism.
   - Working in a worktree whose current branch is `openspec/<some-name>`, start a review that
     covers every remaining unreviewed file.
   - Mark every file except the last one however you like, then mark the **last** one either by
     clicking **Mark Reviewed** on the diff toolbar or by pressing
     <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd>.
   - **Expected, all together:** the completion balloon fires exactly once, with an action button
     reading **Copy `/myflow-do-done <name>`**; both tool windows come back; and the diff tab
     closes. This is the session ending because the queue is complete, not because you clicked End
     Review.

10. **The queue changing mid-session does not silently swallow a mark.**
    Use a **scratch repo in a temp directory** for this one — it requires staging files. Do not run
    it against anything under `/Users/tweety53/Projects/gymie*`.

    ```bash
    R=$(mktemp -d)/scratch && mkdir -p "$R" && cd "$R" && git init
    git config user.email t@example.com && git config user.name T
    for f in one two three four; do echo v1 > $f.txt; done
    git add . && git commit -m base
    for f in one two three four; do echo v2 >> $f.txt; done
    git add .
    ```

    - Open `$R` as an IDE project, confirm the Staged queue lists four files, and click
      **Start Review**. Mark the first file so you are sitting on **file 2** (`Review 2/4`).
    - Leave the IDE alone. In a terminal, change **file 3** so it leaves the queue as currently
      keyed — committing it removes it from the staged diff:

      ```bash
      cd "$R" && git commit -m "fix round" three.txt
      ```

    - Wait a couple of seconds for the IDE to notice the VCS change, then click **Mark Reviewed**
      on file 2 and keep marking forward to the end of the pass.
    - **Expected:** file 3 either still marks normally, or is visibly skipped — the diff jumps
      straight from file 2 to file 4 and the tab title reflects it. What must **not** happen is a
      mark on file 3 that appears to work while the progress count does not move: pressing Mark
      Reviewed on a file that has left the queue must never store nothing and advance anyway.
    - Cross-check at the end: the number of files the progress label counts reviewed must equal the
      number of files you actually saw and marked. If it is short by one, that is the defect —
      report it, and check `idea.log` for the plugin's warning about a file leaving the queue
      before it could be marked (section 19 covers reading the log).

11. **Enablement during a session: Previous File at the first file, and Scope.**
    - Start a review over at least two files. Before clicking anything else, look at **Previous
      File** on the diff toolbar while sitting on `Review 1/M`.
    - **Expected:** it is **disabled** (greyed out). If it is clickable, click it and confirm it at
      least does nothing — but a clickable Previous File on the first file is a defect; report it.
    - Click **Mark Reviewed** once so you are on `Review 2/M`. **Expected:** Previous File is now
      enabled, and clicking it steps back to file 1, where it must go back to being disabled.
    - Still mid-session, reopen the **Review Queue** tool window (View → Tool Windows → Review
      Queue) and look at the **Scope** dropdown. **Expected:** it is disabled while the review is
      running, and enabled again after End Review. Changing the scope mid-pass would rebuild the
      queue underneath the session's fixed file list.

12. **Closing the project mid-session restores the layout too.**
    A different code path from step 2: closing a project runs the services' `dispose()` and writes
    the workspace file on a different schedule than a full IDE shutdown, so both routes need
    checking.
    - Start a review so both tool windows are hidden, mark one file, then **File → Close Project**
      (do not quit — you should land on the Welcome screen with the IDE still running).
    - Reopen the same project from the Welcome screen.
    - **Expected:** the Project and Review Queue tool windows are both back on their own, with no
      "IDE internal error" balloon and no error in `idea.log` attributed to `reviewqueue`. The
      session is not resumed; only the layout is.
    - Then repeat from the same state but **quit the IDE entirely** (step 2's route), to confirm
      both shutdown paths end with the layout restored.

13. **Start Review from Find Action, with the tool window never opened.**
    The diff toolbar's buttons are resolved from the action registry precisely so this path works;
    nothing else in this checklist exercises it.
    - Start from a **freshly launched IDE** and open a project with unreviewed staged files.
      **Do not open the Review Queue tool window at all** in this IDE session — not once. (If you
      already have, restart the IDE before doing this step.)
    - Press <kbd>Shift</kbd> twice, or <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>A</kbd>, for Find
      Action; type **Start Review** and run it.
    - **Expected:** the first unreviewed file opens as a diff and its toolbar carries all four
      buttons — **Previous File**, **Mark Reviewed**, **Toggle Reviewed**, **End Review**. A diff
      with no toolbar buttons here would strand the user with only the keyboard shortcut and a tab
      close as the way out; report it if any button is missing.
    - Click **End Review**. **Expected:** the Project tool window comes back, and the **Review Queue
      tool window does not pop open** — you never had it open, so ending the review must not open a
      panel you did not ask for.

---

## Sign-off

Record here once each section above has actually been performed (do not check off from reading
the code — only from having done it in the IDE):

- [ ] 1. Install
- [ ] 2. Found a Gate B worktree
- [ ] 3. Tool window lists staged files, grouped by root
- [ ] 4. Progress label correct
- [ ] 5. Superseded by section 20 — no separate sign-off needed
- [ ] 6. Diff opens on click outside a session (single reused tab)
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
- [ ] 20a. Start Review hides both tool windows; End Review restores them
- [ ] 20b. Hidden layout survives quitting and reopening the IDE mid-session
- [ ] 20c. Closing the review diff tab by hand ends the session and restores the layout
- [ ] 20d. Mis-mark recovery: Mark Reviewed → Previous File → Toggle Reviewed → Mark Reviewed
- [ ] 20e. Cmd+Shift+Enter (Ctrl+Shift+Enter on Windows/Linux) marks and advances in the review
      diff, and **while the session is still running** does nothing at all in a normal editor.
      Confirm in Settings → Keymap that Mark Reviewed shows the chord for your platform.
      If the chord does nothing while the toolbar button works, follow the diagnosis note in
      section 5 — the chord is being eaten below the IDE, not by the plugin.
- [ ] 20f. Start Review after a fix round walks only the changed file(s)
- [ ] 20g. Toggle Reviewed enables (re-test: previously looked permanently disabled)
- [ ] 20h. Tab title tracks progress forward through at least three consecutive marks
- [ ] 20i. Completion balloon fires from marking the last file via the diff toolbar/shortcut
- [ ] 20j. A file leaving the queue mid-session is marked or visibly skipped, never silently passed
- [ ] 20k. Previous File is disabled on the first file; Scope is disabled during a session
- [ ] 20l. Closing the project mid-session restores the layout (and so does quitting the IDE)
- [ ] 20m. Start Review from Find Action, tool window never opened: all four diff buttons present,
      and End Review does not pop the Review Queue panel open
- [ ] 21a. The four session controls (Start Review, End Review, Refresh, Reset All) sit after a
      separator on the review diff toolbar, visually grouped apart from the navigation buttons (not
      flush right — confirmed in 2026.2 that the diff toolbar's layout does not honour
      `RightAlignedToolbarAction`; see the design doc's *Known risk*)
- [ ] 21b. Each of the four asks before acting, and answering No leaves the pass exactly as it was
- [ ] 21c. Reset All from the diff toolbar shows **one** dialog, not two
- [ ] 21d. Start Review is visibly disabled on the diff toolbar during a pass
- [ ] 21e. Refresh in the *tool window* still acts on one click with no dialog
- [ ] 21f. Find Action lists each of Start Review / End Review / Refresh / Reset All exactly once
- [ ] 22a. The file list opens from the diff toolbar and lists every file in scope, reviewed ones
      marked, with the file on screen preselected and the title showing `N / M reviewed`
- [ ] 22b. Picking another file in the pass swaps the diff and moves the tab title's `Review N/M`;
      marking then continues from there
- [ ] 22c. Picking a file that was already reviewed when the pass started opens a browsing diff and
      leaves the pass and its progress untouched
- [ ] 22d. Speed-search filtering in the popup selects the right file
- [ ] 22e. Jumping to a file that has left the queue lands on the next live file, never on a blank
      diff
