# Manual verification checklist — Review Queue

Everything below requires a human driving the IDE's GUI. Nothing in this checklist has been
performed by an automated agent; treat every box as unchecked until you have actually done it.

Prerequisite: `build/distributions/review-queue-0.4.0.zip` exists (`./gradlew buildPlugin`).

**There is no tool window any more.** KAN-5 deleted it. Every command that used to live in the right
panel is now under **Tools → Review Queue**, and browsing the queue is **Show File List**. Section 24
covers the new entry points specifically, and it contains the one risk this change knowingly carried
into manual test — the Start Review shortcut. Run section 24 even if you run nothing else.

**Four features new in this release, sections 25–28.** The progress banner (25), the focus-mode
tool-window sweep (26), staged-rename detection (27) and the review diff's right-click context menu
(28) are all new since the last manual pass and none of them has been driven by a human yet. Section
28 in particular carries two checks nothing automated can reach: that an unrelated diff opened during
a pass keeps the platform's stock menu untouched, and that a binary file's missing context menu is
deliberate rather than a defect.

## 1. Install the plugin

1. `open -na "IntelliJ IDEA"` to launch a fresh instance (or use your normal one).
2. Settings → Plugins → gear icon (top right) → **Install Plugin from Disk…**
3. Select the built zip from `build/distributions/`.
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

## 3. The queue lists the staged files, ordered by root

1. **Tools → Review Queue → Show File List.**
2. **Expected:** a progress dialog appears briefly while the scope is resolved — nothing resolves the
   queue at project open any more — then a popup lists every staged file.
3. **Expected:** the list is flat and path-ordered within each git root, with the roots in the order
   the repository manager reports them. If the worktree has more than one attached git root (e.g.
   submodules), each row from a foreign root is prefixed with that root's directory name; with a
   single root there is no prefix. A flat list is deliberate — a tree inside a chooser popup costs
   keyboard-navigable rows.
4. Confirm against `git -C <worktree> status --porcelain` (and the same for any submodule) that
   the file count and set matches what git itself reports as staged.

## 4. The popup title carries the progress and the scope

**This has no automated coverage.** The title is assembled inside `JBPopupFactory` plumbing that is
not reachable headlessly, and it is the design's stated replacement for the deleted panel's progress
label. It is a human check or it is unchecked.

1. Open **Show File List** and read its title.
2. **Expected:** it reads `N / M files reviewed  •  <scope>` — e.g. `0 / 7 files reviewed  •  Staged`. `M` equals
   the total file count from section 3; `N` equals the number already marked reviewed in this project
   (0 on a fresh worktree/profile).
3. Change the scope (section 9) and reopen the popup. **Expected:** the scope in the title changed
   with it — `Staged`, `Branch vs base` or `Commit range`.

## 5. Mark Reviewed advances the queue — the core interaction

**Superseded — see section 20.** Mark Reviewed lives on the diff viewer's own toolbar (and on its
four chords, see 20.5) and only appears once a guided pass is running. There has never been a way to
mark a file from a list selection since the tool window went, and now there is no list selection to
mark from. Run section 20 for the current mechanism; this section is kept only so the numbering below
does not shift.

## 6. Diff tab opens on pick, outside a session

This covers **browsing** a file when no guided review is running — distinct from the guided flow in
section 20, where advancing happens inside the diff viewer itself. The two are not in tension:
picking a row still opens that file's diff at any time, session or not.

1. With several files still unreviewed and no review in progress, **Tools → Review Queue → Show File
   List**, and pick file A. Note the diff opens in an editor tab.
2. Reopen Show File List and pick file B. **Expected:** the same tab updates to show B's diff — no
   second tab is opened. Check the editor tab strip; there should be exactly one tab related to this
   queue, not one per file picked.
3. Pick file A again and confirm the tab shows file A again, still using that one tab.

## 7. Reviewed marks survive an IDE restart

1. Mark at least one file reviewed (if not already done in section 20).
2. Note which files are marked.
3. Close the IDE completely and reopen the same project.
4. **Tools → Review Queue → Show File List.**
5. **Expected:** the same files are still shown with the reviewed checkmark, and the `N / M` in the
   popup title matches what it was before closing.

## 8. Editing and re-staging a reviewed file returns exactly that file

1. Pick a file already marked reviewed.
2. Make a small edit to it (in the worktree, on disk) and `git add` it again (re-stage).
3. **Tools → Review Queue → Refresh.** **Expected:** a progress dialog appears and closes — the
   resolve is synchronous now, so the dialog is the feedback that anything happened.
4. Open Show File List. **Expected:** exactly that one file lost its checkmark (`N` in the title
   decrements by 1). No other file's reviewed status changes.

## 9. The three scopes work, including commit-range input validation

1. **Tools → Review Queue → Scope.** Confirm it is a **nested submenu** with three options:
   **Staged**, **Branch vs Base…**, **Commit Range…** — not three flat entries in the parent menu.
2. Select **Staged** — the queue should match the staged diff (as in sections 3–4).
3. Select **Branch vs Base…** — leave the base ref blank to use the tracked branch, or type an
   explicit ref (e.g. `main`). **Expected:** the next Show File List / Start Review shows the
   branch-vs-base diff (three-dot / merge-base semantics), no error.
4. Select **Commit Range…** — a two-step dialog asks for a "From ref" then a "To ref".
   - Try entering a ref containing a space, e.g. `HEAD 1`. **Expected:** the dialog's OK button
     is disabled / input is rejected — you cannot close the dialog with this value.
   - Try entering a ref containing `;`, e.g. `HEAD;rm`. **Expected:** same rejection. The same goes
     for `&`, `|`, a backtick and `$`.
   - Try a ref **beginning with a dash**, e.g. `--output=.git/index`. **Expected:** rejected, with a
     message naming the real reason — git would read it as an option, not a ref. This is the
     load-bearing rule of the three: no shell is involved anywhere on this path, so the metacharacters
     above are inert as characters, while a leading dash reaches git's own option parser and
     `git rev-list --output=<file>` truncates that file *before* it rejects the missing commit. Same
     check in the **Branch vs Base…** base-ref prompt, which had no validation at all before.
   - Enter valid refs (e.g. `HEAD~1` and `HEAD`). **Expected:** dialog accepts, and the queue for
     that commit range is what the next Show File List / Start Review reads.
   - Cancel out of either prompt. **Expected:** the scope is unchanged — cancelling means "change
     nothing", never "an empty ref".
5. Switch back to **Staged** to leave the queue in the state Gate B expects.

## 10. Completion balloon and clipboard action

1. Working in a worktree whose current branch is `openspec/<some-name>`, mark every remaining
   file in the queue as reviewed (see section 20 for the mechanism).
2. **Expected:** as soon as the last file is marked, a balloon notification appears once (not
   once per remaining refresh) saying all files are reviewed.
3. The notification should have an action button reading **Copy `/myflow-do-done <name>`** where
   `<name>` is the part of the branch name after `openspec/`.
4. Click that action, then paste (Cmd+V) into a scratch text field or terminal.
5. **Expected:** the pasted text is exactly `/myflow-do-done <name>` — the literal command, with
   the correct change name, nothing else appended.
6. Un-mark a file (e.g. via Reset All, or edit+re-stage as in section 8) and re-mark everything.
   **Expected:** the balloon fires again after going incomplete and complete again — it should
   not have fired a second time while still complete (e.g. from an unrelated Refresh).
7. **Reopen an already-complete project.** With every file in the queue marked reviewed, close the
   project (File → Close Project) and reopen it, then run **Show File List**.
   **Expected:** **no** balloon appears. The queue was already complete before you arrived; there
   is nothing to announce. A balloon here is a defect — report it.

## 11. Reviewed marks are never lost

The stored marks are a user's review work. Nothing short of Reset All may destroy them. Each part
below has previously been a real defect, so run all three.

1. **Scope round trip — refresh while you are away.** With the Staged scope, mark several files
   reviewed and note exactly which. Switch the scope to **Commit Range…** (`HEAD~1` → `HEAD`).
   **Now run Tools → Review Queue → Refresh at least twice while still in the Commit Range scope**,
   and give the IDE a few seconds so background VCS events land too. Then switch back to **Staged**.
   The extra refreshes are the point: the defect this catches only appeared from the *second*
   rebuild in the foreign scope onward, so a round trip without them proves nothing.
   **Expected:** every mark you noted is still there and the popup title's `N` is unchanged. Marks
   are keyed by root/path/hash, so they carry across scopes.
2. **Project close and reopen.** Close the project (File → Close Project), reopen it, and run
   **Show File List**.
   **Expected:** the marks are present in the very first popup, before you Refresh, change scope, or
   touch anything else.
3. **A failing root.** Switch to **Branch vs Base…** and enter a ref that does not exist
   (e.g. `no-such-ref-xyz`). A warning **notification** reports it (section 12).
   **Expected:** the failure is reported and the marks survive it. Switch back to **Staged**:
   `N` is what it was before the bad ref.

## 12. Failed roots are reported as a notification, and one failure does not take down the others

The panel's error label is gone; per-root failures are now a balloon in the **Review Queue**
notification group. Needs a project with two git roots (a repo with a submodule, or two directory
mappings) for the second half.

1. Point the scope at something one root can resolve and the other cannot — e.g. **Branch vs
   Base…** with a ref that exists in only one root, or **Commit Range…** with a `from` ref that
   only one root knows.
2. **Expected:** a warning balloon appears titled "A repository could not be read" (or
   "*N* repositories could not be read"), whose body names *the failing root's path* alongside git's
   own message — not a generic "Could not diff …".
3. **Expected:** the healthy root still lists its files normally in Show File List. The whole queue
   must not go blank.
4. **The balloon must not repeat itself.** Leave the broken scope in place and let the IDE sit for a
   minute, touching nothing (`changeListUpdateDone` lands on every VCS event, and each one rebuilds).
   **Expected:** one balloon for that failure, not a stream of them.
5. **A recurrence is news again.** Switch to **Staged** (healthy), then back to the broken scope.
   **Expected:** the balloon appears again — a root that recovers and breaks again is reported again.

## 13. Staged deletion and binary file return to unreviewed

This checks that marks are addressed to content and not to a constant.

1. **Deletion.** In a scratch test repo, `git rm somefile.txt`. Refresh the queue; the deletion
   appears as a row in Show File List. Mark it reviewed (start a pass over it, section 20).
   **Expected:** the row shows the reviewed checkmark.
   Now `git reset somefile.txt && git rm --cached otherfile.txt` (i.e. stage a *different*
   deletion) and refresh. **Expected:** the new deletion is unreviewed — it did not inherit the
   previous deletion's "reviewed" state.
2. **Binary file.** Stage a PNG (`cp a.png img.png && git add img.png`). Mark it reviewed.
   Replace it with a *different* PNG (`cp b.png img.png && git add img.png`) and refresh.
   **Expected:** `img.png` returns to unreviewed. If it stays reviewed, the hash is not tracking
   the file's bytes — report it.

## 13b. An unrenderable file does not break diff opening for the rest

1. With the PNG from section 13 still staged, pick its row in Show File List.
2. **Expected:** either its diff opens or nothing happens — no error dialog, no "IDE internal
   error" balloon, no frozen popup.
3. Now pick a plain text file's row.
4. **Expected:** that file's diff opens normally. One file the diff framework cannot render must
   not disable diff opening for every other file in the queue.

## 14. Nested root / submodule marks the file you were on

Needs a project with a submodule (a git root nested inside another git root).

1. Stage a change inside the submodule and a change to the submodule gitlink in the outer repo.
2. Start a review (section 20) and use **Show File List** to jump to a file inside the submodule.
   Confirm the diff on screen is that file.
3. Press **Mark Reviewed**.
4. **Expected:** reopening Show File List shows the checkmark on **the file you were on**, and the
   title's `N` incremented by exactly 1. If the mark landed on a different file — or on no visible
   row at all — stop and report it.

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
3. If it does appear: get onto it in a pass, press **Mark Reviewed**, then **Refresh**.
   **Expected if healthy:** it stays marked. **If it reverts to unreviewed on every refresh**,
   record that — it means the completion balloon can never fire in a repo with submodule bumps.
4. Record the answer either way; this has not been determined.

## 15. Toggle Reviewed un-marks a single file

Toggle Reviewed acts on the file on screen in a pass; there is no list selection to act on.

1. Start a pass and mark two or three files reviewed.
2. Step back onto one of them with **Previous File** and click **Toggle Reviewed** on the diff
   toolbar.
3. **Expected:** that file's mark is removed (`N` in the popup title decrements by 1), the other
   marks are untouched, and the diff does **not** move to another file.
4. Click **Toggle Reviewed** again on the same file. **Expected:** it is marked again, still without
   moving.
5. Outside a pass, with no file on screen, **Expected:** Toggle Reviewed does nothing (it has no
   current file); it is not offered on any menu.

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
   - `Slow operations are prohibited on EDT` — git subprocesses running on the UI thread. Section 24
     makes this more likely to matter than before: Start Review, Show File List, Refresh and a
     mid-pass scope switch all resolve git synchronously behind a modal progress now.
   - `Synchronous execution under ReadAction` — the resolve running under a read action, which
     killed an earlier version of `refresh()` outright.
   - `com.intellij.diagnostic` entries mentioning `reviewqueue` — freeze reports attributed to
     this plugin.
   - `dev.tweety.reviewqueue` at `WARN`/`ERROR` — e.g. "left the queue before it could be marked",
     which indicates a real inconsistency.
3. **Expected:** none of the above appear. Paste any hit verbatim into the sign-off notes.
4. Note in the sign-off whether `ide.slow.operations.assertion` was on for the run you are
   reporting. If it was off, say so — the log result is inconclusive.

## 20. Guided review mode

Marking, un-marking and navigation all happen in the diff viewer once a review is running. This
section checks that flow end to end.

1. **Start Review hides the Project tool window; End Review restores it.**
   - With at least two unreviewed files in scope, run **Tools → Review Queue → Start Review**.
   - **Expected:** a progress dialog resolves the scope, the Project tool window disappears, and the
     first unreviewed file opens as a diff with **Show File List**, **Previous File**, **Next
     File**, **Mark Reviewed**, **Toggle Reviewed** on the left of the diff viewer's own toolbar and,
     after a separator, **Scope**, **Start Review**, **End Review**, **Refresh**, **Reset All**.
   - Click **End Review**. **Expected:** the Project tool window reappears, the diff tab closes, and
     every mark made during the pass is kept.
   - **Now machine-checked too.** The hide and the restore themselves are asserted in `ScopeSwitchTest`
     against a recording `ToolWindowManager` — the `review-layout-management` scenarios "The Project
     window is hidden for a pass" and "Ending a pass restores what it hid" are no longer manual-only.
     Both were untested until fix round 2, and deleting either call left the whole suite green. Run this
     item anyway: the automated check proves a hide and a show were *requested*, not that the window
     visibly moved, and nothing automated can see the layout on screen.

2. **The persisted layout snapshot survives quitting mid-session.**
   - Start Review again, mark one file, then quit the IDE entirely (not just close the project)
     while the Project tool window is still hidden.
   - Reopen the same project.
   - **Expected:** the Project tool window comes back on its own, without you doing anything else.
     (The session itself is not resumed — this only checks that the layout does not stay stuck
     hidden with no explanation.) **Expected also:** nothing tries to restore a "Review Queue"
     window — that id is pruned from persisted layout state on load, and a stale one left in place
     would permanently stop the plugin from hiding anything ever again. If the Project window ever
     stops hiding on Start Review after an upgrade, this is the first thing to suspect.

3. **Closing the review diff tab by hand ends the session.**
   - Start Review, then close the diff tab yourself (the "x" on the tab, or Ctrl+F4/Cmd+W).
   - **Expected:** the Project tool window restores immediately, the same as clicking End Review. The
     session does not linger in a state where the panel is hidden but no diff is open.

4. **Mis-mark recovery: Previous File + Toggle Reviewed.**
   - Start Review. Click **Mark Reviewed** on the first file (this is the "wrong" mark).
   - Click **Previous File**. **Expected:** the diff steps back to the file just marked, its mark
     is untouched, and the tab title's `Review N/M` reflects the earlier position again.
   - Click **Toggle Reviewed**. **Expected:** that file's mark is removed and the diff stays on the
     same file (Toggle Reviewed does not advance).
   - Click **Mark Reviewed**. **Expected:** the file is marked again and the pass continues forward
     from there, in order — the round trip did not skip or duplicate a file.

5. **The Mark Reviewed shortcut marks and advances in the review diff.**
   - During a review, place focus in the diff viewer. Test **each** bound chord in turn:
     Alt+Shift+Z, Alt+Shift+Space, Alt+Shift+Enter, Cmd+Shift+Z (Ctrl+Shift+Z on Windows/Linux),
     and on macOS also Cmd+Option+Shift+Space and Cmd+Option+Shift+Enter.
     **Expected:** every one of them marks the file reviewed and advances, same as clicking Mark
     Reviewed. All are bound deliberately; a chord that silently does nothing is the bug.
   - Now the case that matters: **leave the review running.** Do not click End Review. With the
     session still active, open a normal source file in another editor tab (Cmd+Shift+N / double
     click a file in the Recent Files popup — the Project tool window is hidden), click into that
     editor so it has focus, and press the same chord.
   - **Expected:** it does **nothing at all** — no "Choose action" popup, no silent advance through
     the review. That is the `DIFF_CONTEXT` gate doing its job, and it matters more now that two
     chords are shared: in that normal editor, **Alt+Shift+Enter must still open Split Chooser**, and
     on Windows/Linux **Ctrl+Shift+Z must still Redo**. Verify both explicitly — an editor where Redo
     has stopped working is the worst outcome of this binding set.
   - **To verify the bindings registered**, open Settings → Keymap, search for "Mark Reviewed", and
     confirm all four chords are listed for your platform. On macOS, confirm Ctrl+Shift+Z is **not**
     among them — it is removed there so it stays Redo. Conflict warnings against Split Chooser
     (everywhere) and Redo (Windows/Linux) are expected and accepted.

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
   - Run **Start Review** again. **Expected:** the pass covers only that one file.
   - Now mark it, so nothing is unreviewed, and press **Start Review** once more. **Expected:** it
     is still *enabled* (enablement follows git-root existence, not queue contents), and pressing it
     produces a notification reading **"Nothing unreviewed in Staged"** — not a silent no-op and not
     an empty diff tab.

7. **Re-test carried over: Toggle Reviewed now enables.**
   In an earlier release, Toggle Reviewed looked permanently disabled. The likely cause was
   `refresh()` dying under a read action and leaving the queue empty, which fed nothing for the
   action to act on. That code path is fixed. Confirm directly: during a review, Toggle Reviewed is
   enabled and works as described in 20.4 / section 15, not greyed out.

8. **The tab title tracks progress forward, not just on the first file and one step back.**
   - Start Review in a scope with at least four unreviewed files. Note the diff tab title —
     **Expected:** `Review 1/M - <first file's name>`.
   - Click **Mark Reviewed**. **Expected:** the title updates to `Review 2/M - <second file's
     name>` — both the count and the filename change together.
   - Click **Mark Reviewed** twice more. **Expected:** the title reads `Review 3/M - <third file's
     name>` after the first of those two clicks, then `Review 4/M - <fourth file's name>` after the
     second — the count advances by exactly one per mark and the filename always matches the file
     actually on screen, not the previous one.

9. **The completion balloon fires from the diff toolbar.**
   Section 10 above describes the balloon; this is the check that it fires from the current
   mechanism.
   - Working in a worktree whose current branch is `openspec/<some-name>`, start a review that
     covers every remaining unreviewed file.
   - Mark every file except the last one however you like, then mark the **last** one either by
     clicking **Mark Reviewed** on the diff toolbar or with one of its chords.
   - **Expected, all together:** the completion balloon fires exactly once, with an action button
     reading **Copy `/myflow-do-done <name>`**; the Project tool window comes back; and the diff tab
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

    - Open `$R` as an IDE project, confirm Show File List lists four files, and run
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
    - Cross-check at the end: the number of files the popup title counts reviewed must equal the
      number of files you actually saw and marked. If it is short by one, that is the defect —
      report it, and check `idea.log` for the plugin's warning about a file leaving the queue
      before it could be marked (section 19 covers reading the log).

11. **Enablement during a session: Previous File at the first file, Next File at the last, Scope
    enabled.**
    - Start a review over at least two files. Before clicking anything else, look at **Previous
      File** on the diff toolbar while sitting on `Review 1/M`.
    - **Expected:** it is **disabled** (greyed out). If it is clickable, click it and confirm it at
      least does nothing — but a clickable Previous File on the first file is a defect; report it.
    - Click **Mark Reviewed** once so you are on `Review 2/M`. **Expected:** Previous File is now
      enabled, and clicking it steps back to file 1, where it must go back to being disabled.
    - Look at the **Scope** combo on the diff toolbar. **Expected:** it is **enabled** mid-pass and
      names the current scope. This is the reverse of the old rule — the tool window's scope picker
      was disabled during a pass, and section 24 covers what changing it mid-pass must do.

12. **Closing the project mid-session restores the layout too.**
    A different code path from 20.2: closing a project runs the services' `dispose()` and writes
    the workspace file on a different schedule than a full IDE shutdown, so both routes need
    checking.
    - Start a review so the Project tool window is hidden, mark one file, then **File → Close
      Project** (do not quit — you should land on the Welcome screen with the IDE still running).
    - Reopen the same project from the Welcome screen.
    - **Expected:** the Project tool window is back on its own, with no "IDE internal error" balloon
      and no error in `idea.log` attributed to `reviewqueue`. The session is not resumed; only the
      layout is.
    - Then repeat from the same state but **quit the IDE entirely** (20.2's route), to confirm
      both shutdown paths end with the layout restored.

13. **Start Review from Find Action.**
    The diff toolbar's navigation buttons are resolved from the action registry precisely so this
    path works; nothing else in this checklist exercises it.
    - Start from a **freshly launched IDE** and open a project with unreviewed staged files.
    - Press <kbd>Shift</kbd> twice, or <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>A</kbd>, for Find
      Action; type **Start Review** and run it.
    - **Expected:** the first unreviewed file opens as a diff and its toolbar carries every button
      listed in 20.1. A diff with no toolbar buttons here would strand the user with only the
      keyboard shortcut and a tab close as the way out; report it if any button is missing.
    - **Expected also:** Find Action lists **Start Review**, **End Review**, **Refresh**, **Reset
      All**, **Show File List**, **Toggle Reviewed** and the three Scope entries (**Staged**,
      **Branch vs Base…**, **Commit Range…**) exactly once each — the diff toolbar's confirming
      copies and its Scope combo are deliberately unregistered, so they must not appear as duplicates.

## 24. Entry points after the tool window removal (KAN-5)

Everything in this section is new behaviour with no predecessor to compare against. Item 1 is the
single most important check in this document.

1. **The Start Review shortcut — run this twice.** On macOS, press
   <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>R</kbd>:

   - **(a)** with neither a Database view nor a `.gradle.kts` editor focused — e.g. focus a `.kt`
     source file, or the empty editor area.
   - **(b)** **with a `.gradle.kts` file open and focused** — e.g. this repo's own
     `build.gradle.kts`.

   **Expected in both cases:** Start Review runs (a progress dialog, then the first file's diff).
   **What must not happen:** a "Choose action" chooser popup listing Start Review beside something
   else.

   Case (b) is the one most likely to surface the problem, and it is why checking only (a) is
   worthless. `ReloadScriptConfiguration` declares `ctrl alt shift R` on the `$default` keymap, and
   `MacOSDefaultKeymap` translates Ctrl→Meta, so on macOS that action answers to exactly this chord
   — and it is live while editing any Kotlin script, including this plugin's own build files.
   (`ForceRefresh` also holds the chord but is an inert `EmptyAction` holder;
   `DatabaseView.ForceRefresh` borrows it and is live only with the Database view focused.) None of
   the three can be unbound from the plugin's side: `remove="true"` strips a shortcut from our own
   action only.

   **If the chooser appears in either case, the binding must change.** This is the one risk KAN-5
   knowingly carried into manual test; the decision to keep the chord was taken on the understanding
   that this check would be run, both ways. `StartReviewShortcutTest` pins the exact set of ids on
   the chord, so a platform change is not silent — but whether a popup appears cannot be answered
   without a display.

2. **Menu rendering.** Open the **Tools** menu.
   - **Expected:** a **Review Queue** entry that is a *named* submenu — not unnamed, and not its
     children inlined flat into Tools.
   - **Expected:** inside it, **Scope** is itself a *nested* submenu holding Staged / Branch vs
     Base… / Commit Range…, not three flat entries.
   - **Expected:** the order is Scope, Start Review, Show File List, **a separator**, Refresh, Reset
     All — with the separator between Show File List and Refresh, so the two queue-wide commands are
     visibly grouped apart from the two review commands.

3. **The diff toolbar's Scope combo.** Start a pass and look at the second toolbar group.
   - **Expected:** a combo box, first after the separator, whose label **names the current scope**
     (`Staged`, `Branch vs base` or `Commit range`) rather than reading a generic "Scope".
   - **Expected:** it is **enabled** mid-pass, and opening it lists the same three scope entries as
     the Tools menu.

4. **Mid-pass re-scope — and no layout flash.**
   - Start a pass over several files and mark one or two, noting which.
   - From the diff toolbar's Scope combo (or the Tools menu), pick a different scope. **Expected:** a
     confirmation reading *"Switch the review scope to …? The current pass restarts and every mark
     made so far is kept."*
   - Answer **No**. **Expected:** nothing changes — same file on screen, same scope named on the
     combo.
   - Do it again and answer **Yes**. **Expected:** a progress dialog, then the pass restarts in the
     new scope with the first unreviewed file of that scope on screen, the tab title back to
     `Review 1/M'`, and the combo naming the new scope.
   - **Watch the left edge of the window while it happens.** **Expected:** the Project tool window
     does **not** flash open and shut. The pass is rebuilt in place for exactly this reason; going
     out through End Review and back in through Start Review would restore the layout and re-hide
     it. `ScopeSwitchTest` now asserts this directly — no hide and no show may be requested across a
     successful switch — which replaced a presenter-close proxy that was blind to a stray re-hide. The
     **flash** is still a human check: the counters cannot see the screen, and this is the only
     end-to-end observation of it.
   - Reopen **Show File List** and confirm the marks you noted before the switch are still there.
   - Repeat once more but **cancel the progress dialog**. **Expected:** the pass is exactly where it
     was — same file, same scope, marks intact.

5. **An empty result says which nothing it is.** An empty queue has four different causes and the
   plugin now names each one; the *choice* between them is machine-checked in `EmptyResolveNoticeTest`,
   so what you are confirming here is the wording a user actually reads. Start Review is *enabled* in
   all four cases on purpose — enablement follows git-root existence, not queue contents — so without a
   notification the press would look like a no-op.

   - **Nothing left to review.** A git root, `git status` clean or everything already marked.
     **Expected:** **"Nothing unreviewed in Staged"** (an information balloon). Same check for **Show
     File List**: the notification, not an empty popup.
   - **A scope that could not be read.** Switch to **Branch vs Base…** with a ref that does not exist
     (e.g. `no-such-ref-xyz`), then press Start Review. **Expected:** **"The review scope could not be
     read — one repository failed to resolve"** (a warning), *not* "Nothing unreviewed". Press Start
     Review a **second** time and confirm you get it again: section 12's own balloon deduplicates by
     failure set, so from the second press on this message is the only one left, and a confident
     "nothing unreviewed" there is the worst output this plugin can produce.
   - **No git repository at all.** Only reachable by racing the enablement gate (the `repositories`
     cache can empty between the menu poll and the click), so treat it as best-effort: if you ever see
     a review notification in a non-git project, it must read **"No git repository in this project"**.
   - **Unreviewed but unrenderable.** With a file the diff framework refuses (the PNG from section 13
     is the usual candidate) as the *only* thing left unreviewed, press Start Review.
     **Expected:** **"No unreviewed file in Staged could be displayed"**, and the Project tool window
     does **not** flash shut and open again — the pass never began, so nothing may be hidden.

6. **A scope switch never congratulates you.** Marks are content-addressed, so files carried into a new
   scope arrive already marked — which used to make a mid-pass switch fire **"All N files reviewed"**,
   complete with its `Copy /myflow-do-done <name>` button, for a pass nobody ran.
   - Set up a pass with unreviewed work, mark everything except one file, then arrange for that last
     file to leave the scope (`git rm --cached` it) and switch the scope from the diff toolbar.
   - **Expected:** one balloon, reading **"Nothing unreviewed in Staged"**. **What must not appear:**
     any balloon containing *"files reviewed"* or a `/myflow-do-done` copy action.
   - **Expected also:** the pass ends — the new scope has nothing to walk — and the Project tool window
     comes back. A switch that silently leaves a dead diff tab is the defect this replaced.
   - **The second way a switch can end the pass — check this one too.** The case above ends the pass
     because the new scope holds nothing *unreviewed*. A scope can also hold unreviewed files that the
     diff framework refuses to render, and that route ends the pass through a different branch, which for
     a while reported nothing at all. Stage the PNG from section 13 **as the only unreviewed file** in
     some scope, then switch into that scope from a running pass.
     **Expected:** the pass ends, the Project tool window comes back, and a balloon reads
     **"No unreviewed file in Staged could be displayed"** — *some* balloon, not silence. That is the
     whole point of this item; `ScopeSwitchTest` now covers both routes, but this is the only place the
     wording of the second one is read by a human.
   - Also check that the balloon **still names a completion for real work**: finish an ordinary pass
     after doing the switch above, and confirm **"All N files reviewed"** does appear then, with its
     `Copy /myflow-do-done <name>` button. The suppression must be a suppression, not a mute.

7. **The tool window is gone, and stays gone.** This is the change's headline requirement.
   - Open **View → Tool Windows** and search the tool-window stripes on all four edges.
     **Expected:** no **Review Queue** entry anywhere.
   - Open **Settings → Appearance & Behavior → Menus and Toolbars** and, separately, use Find Action to
     search `Review Queue`. **Expected:** the commands are all there; a tool window is not.
   - If you have an `idea.log` or a `workspace.xml` from an older build of this plugin in the project you
     are testing, confirm the IDE does not log a failure to restore a `Review Queue` window — persisted
     layout state naming it is pruned as it loads.
   - `PluginRegistrationTest` now asserts both the descriptor and the registered tool-window list, so a
     re-registration is no longer silent; what you are confirming here is that nothing *else* in the IDE
     still advertises the window.

8. **Non-git project.** Open a directory that is not under git (and has no VCS mapping) as a project.
   - **Expected:** every entry under **Tools → Review Queue** is disabled — Scope and its three
     children, Start Review, Show File List, Refresh, Reset All. Nothing should open a progress
     dialog that resolves nothing.
   - Note that the entries may be briefly disabled in a *git* project immediately after opening it,
     until VCS mappings initialise, and must enable themselves within a second or two without any
     event. If they stay disabled in a git project, report it.

9. **Windows / Linux: no Start Review shortcut.** On a Windows or Linux IDE:
   - Settings → Keymap, search "Start Review". **Expected:** no shortcut is bound. This is by
     design — the <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd> cluster is macOS-only because `meta` is the
     Windows key on the cross-platform keymap.
   - **Expected:** **Tools → Review Queue → Start Review** works, and so does Find Action.
   - Bind your own chord there and confirm it runs the action, so the documented workaround is real.

10. **The plugin is inert until asked.** Launch a fresh IDE, open a git project, and **do not touch
   the Tools menu**. Read `idea.log`.
   - **Expected:** no git subprocess attributable to this plugin ran on project open, and nothing
     from `dev.tweety.reviewqueue` appears beyond plugin loading. The queue is resolved when a
     command asks for it; there is no startup refresh any more.
   - Then hover **Tools → Review Queue** without clicking anything. **Expected:** still nothing —
     enablement is a git-root check that must not construct the queue service or start background
     work.

## 25. Progress banner

`ReviewProgressBanner` (`src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewProgressBanner.kt`) puts a
`N / M files reviewed  •  <scope>` strip with a progress bar above the review diff, delivered through
`DiffUserDataKeys.NOTIFICATION_PROVIDERS` so it lives above the diff content rather than being a
component the presenter builds and forgets. The banner subscribes to the same queue listener the
file-list popup's title reads, so the two numbers can never disagree — but that also means the one
property worth checking by hand is whether the *subscription* actually fires on every gesture that
changes the count, not just the ones that replace the diff tab.

1. **Fresh scope reads `0 / N`.** With an unreviewed scope, run **Start Review**.
   **Expected:** the banner appears above the diff, reading `0 / N files reviewed  •  <scope>`
   (e.g. `0 / 7 files reviewed  •  Staged`), and the bar is empty.
2. **Mark Reviewed advances the number.** Click **Mark Reviewed**.
   **Expected:** the banner now reads `1 / N`, and the bar has moved. This much a render-once banner
   would also get right, because the diff tab is replaced anyway.
3. **The subtle one — Toggle Reviewed moves the number without the tab being replaced.** Step back
   with **Previous File** onto the file just marked, then click **Toggle Reviewed**.
   **Expected:** the banner's count decrements by exactly 1 (back to the value before step 2), even
   though the diff viewer stayed on the same file and the tab was never recreated. **This is the check
   that actually distinguishes a live subscription from a snapshot rendered once**: `Toggle Reviewed`
   is deliberately excluded from `DiffNotificationProvider.createNotification`'s per-viewer callback —
   see the KDoc on `ReviewProgressBanner.provider` — so a banner that only re-rendered when the tab
   changed would pass every other check in this section and silently show a stale count here. If the
   number does not move without a visible tab swap, that is the defect this item exists to catch.
4. **A background rebuild also moves it.** With a pass running, re-stage a change to a file outside
   the current file (as in section 20.10) so the queue rebuilds behind the scenes.
   **Expected:** the banner's `M` (and `N` if the rebuild affected a reviewed file) updates without you
   touching Mark Reviewed or Toggle Reviewed.
5. **Completing the pass reads `N / N`.** Mark every remaining file, ending with the last one.
   **Expected:** immediately before the pass ends (the last frame the diff is on screen), the banner
   reads `N / N files reviewed  •  <scope>` and the bar is full.
6. **The scope name in the banner matches the popup title's.** Compare the banner's `<scope>` segment
   against **Show File List**'s title (section 4) at the same moment.
   **Expected:** they read the same scope name — `Staged`, `Branch vs base` or `Commit range` — because
   both read `QueueSnapshot.scope` off the same service.

## 26. Focus mode — every visible tool window, not a fixed list

`IdeLayoutController` (`src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt`) now hides
**every tool window that is visible when a pass starts**, on any side of the IDE, whoever registered
it — not a hard-coded list of ids. That is deliberately broader than "hide the Project window"
(section 20.1's older behaviour): a fixed id list would silently miss the next plugin the user
installs. `restore()` reopens exactly what `hideForReview()` recorded, so a window the user had
already closed before the pass started must never come back.

1. **Everything visible goes, including third-party.** Open the **Project** panel, the **Terminal**,
   the **Git** tool window (or **Commit**, depending on your IDE's naming), and one third-party tool
   window if you have one installed (a language plugin's panel, a database view, anything not shipped
   by this plugin). Confirm all four are visible, then run **Start Review**.
   **Expected:** all four disappear — not just the Project panel. If any third-party window survives,
   that is the defect this section exists to catch; the whole point of reading `toolWindowIds` and
   filtering on `isVisible` instead of naming ids is that nothing on this list should ever miss one.
2. **Ending the pass restores exactly those four.** Click **End Review** (or let the pass complete).
   **Expected:** all four windows you opened in step 1 reopen, in a state you'd recognize as "back to
   how it was" — nothing extra appears.
3. **A window already closed before the pass is not opened by ending it.** Close one of the four (say,
   the Terminal) before starting a new pass. Leave the other three open. Start a pass, then end it.
   **Expected:** the Terminal is **still closed** afterward. `hideForReview()`'s visibility filter is
   the whole safety property here — only a window that was open when the pass started goes on the
   hidden list, so `restore()` can never open one you had already closed. If the Terminal reappears,
   the filter has regressed to "reopen a fixed list" and this is the item that catches it.
4. **Repeat once with everything closed.** Close every tool window you can find, then start and end a
   pass. **Expected:** nothing opens at the end — an empty "hidden" record restores to nothing, not to
   some default layout.

Sections 20.2, 20.3, 20.12 and 20.4's "no layout flash" check exercise the same controller from other
angles (quitting mid-session, closing the review tab, closing the project, a mid-pass scope switch) —
run those too if you have not already; this section is specifically about the *set* of windows hidden,
not the timing of the hide/restore.

## 27. Rename detection — one queue entry, keyed to the new path

`GitReviewSource.resolveStaged` (`src/main/kotlin/dev/tweety/reviewqueue/git/GitReviewSource.kt`)
switched the **Staged** scope from `git status --porcelain=v2` (no similarity pass) to
`git diff --cached -M`, so a staged rename now resolves as one `'R'`-status change instead of a delete
of the old path plus an add of the new one. **Commit Range** and **Branch vs Base** already had rename
detection before this change — `GitChangeUtils.getDiff` and `getThreeDotDiffOrThrow` already ran with
similarity detection — so this section is really only exercising something new in the **Staged**
scope; the other two are here to confirm they still behave the same as before, not because anything in
them changed.

1. **Staged.** In a scratch repo, stage a plain rename: `git mv old.txt new.txt && git add -A`.
   **Tools → Review Queue → Refresh**, then **Show File List**.
   **Expected:** exactly **one** row, for `new.txt` — not a `old.txt` deletion row plus a `new.txt`
   addition row. Start a pass, mark it reviewed, confirm the checkmark lands on `new.txt`.
2. **Staged, with content changes alongside the rename.** `git mv` a file and also edit its content
   before staging (`git mv a.txt b.txt && echo more >> b.txt && git add b.txt`).
   **Expected:** still one row for `b.txt`, and its diff shows both the rename and the content change
   in the same view — the same `'R'`-plus-`origPath` combination the code comment describes, where a
   same-path edit and a genuine rename are handled by the identical code path.
3. **Branch vs Base.** Commit a rename on a branch (`git mv old.txt new.txt && git commit -m rename`),
   switch scope to **Branch vs Base…**, and confirm the queue still shows one row for the rename, keyed
   to the new path — this scope should look unchanged from before this task.
4. **Commit Range.** Point **Commit Range…** at the commit range spanning the same rename commit from
   step 3, and confirm the same: one row, new path — again, this scope should look unchanged.
5. **A rename always drops the reviewed mark, even with byte-identical content.** Mark a file
   reviewed, then rename it (`git mv`, no content change) and re-stage. **Expected:** the row under the
   new path is **unreviewed** — this is deterministic, not a possible outcome among several. Marks are
   keyed by `ReviewKey.storageKey()` (`rootPath|relPath`, see `ReviewKey.kt`), and
   `ReviewStateService.isReviewed` requires an exact match on that string
   (`ReviewStateService.kt:44-45`); a rename changes `relPath`, so the new item's key can never equal
   the old one's regardless of whether the bytes are identical. The old mark is simply orphaned in
   storage (marks are never pruned — see the KDoc on `ReviewStateService`) and the new path's lookup
   always misses. **If the mark appears to carry over, that is a regression — report it, do not record
   it as an acceptable variant.**

## 28. Right-click context menu on the review diff

`ReviewDiffPopupGroup` (`src/main/kotlin/dev/tweety/reviewqueue/actions/diff/ReviewDiffPopupGroup.kt`)
and `ReviewDiffExtension` (`src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffExtension.kt`) install
a context menu on the review diff's editors: **Mark Reviewed** first, then the rest of the seven
per-file actions, then a separator, then the five session controls, then a separator, then whatever the
platform's own `Diff.EditorPopupMenu` contributes — with **Compare with Clipboard** filtered out.

1. **Right-click inside the review diff, during a pass.** Start a review and right-click inside the
   diff editor.
   **Expected, in order top to bottom:**
   - **Mark Reviewed** first.
   - The rest of the per-file actions — **Toggle Reviewed**, **Show File List**, **Previous File**,
     **Next File**, **Previous Change**, **Next Change** — seven per-file entries in total including
     Mark Reviewed.
   - A separator, then the five session controls — **Scope** (or however the platform renders it in a
     popup), **Start Review**, **End Review**, **Refresh**, **Reset All**.
   - A separator, then the platform's own diff menu.
   **Expected also:** **Compare with Clipboard is absent**, and **Annotate with Git Blame is still
   there** — it comes from `VcsActions.xml`, not from this plugin, and is exactly the kind of
   platform-contributed entry a hand-written replacement list would have dropped by accident. Its
   presence is what confirms the platform tail is read live rather than enumerated.
2. **An unrelated diff during a pass gets the platform's untouched menu.** **This is the guard against
   a global extension point misfiring, and it is the single most important check in this section** —
   `DiffExtension.onViewerCreated` fires for *every* diff the IDE opens, not only this plugin's, so the
   whole safety property is the `shouldDecorate` marker check. With a pass still running (do not end
   it), open a diff the plugin did not open:
   - From the **Git log** (VCS → Git → Show History, or right-click a file → Git → Show History, then
     open a revision's diff), or
   - **local history** (right-click a file → Local History → Show History), or
   - **Compare Files** (select two files → right-click → Compare Files).
   **Expected:** that diff's right-click menu is the **platform's stock menu, completely unchanged** —
   Compare with Clipboard is **present**, and none of this plugin's per-file or session actions appear
   anywhere on it. If any Review Queue action leaks into an unrelated diff's menu, the marker check in
   `ReviewDiffExtension.shouldDecorate` has regressed to something broader than "did this plugin open
   this specific chain."
3. **A binary file gets no context menu at all — known and deliberate, please confirm it's
   acceptable.** Stage a binary file change (a PNG works, as in section 13) as part of the scope, and
   get onto it during a pass.
   **Expected:** right-clicking inside the binary diff viewer shows **no Review Queue entries at all**
   — not a partial menu, not a menu missing just the platform tail, nothing. This is **inherent, not a
   bug to chase**: binary diff viewers (`TwosideBinaryDiffViewer` and friends) wrap a platform
   `FileEditor` — an image viewer or `DumbFileEditor` — never an `EditorEx`, and
   `installPopupHandler` has no `EditorEx` to attach a handler to without one. See the KDoc on
   `ReviewDiffExtension.editorsOf` for the full disassembly-backed argument that no viewer-agnostic
   alternative exists. **The diff toolbar's Mark Reviewed button still works on a binary file** — the
   toolbar is built independently in `EditorTabDiffPresenter` and does not depend on there being a text
   editor — so marking a binary file reviewed remains possible, just not from a right-click.
   **Please explicitly confirm this is acceptable** as a permanent limitation (toolbar yes,
   context-menu no, for binaries only) rather than something to fix later; record your answer in the
   sign-off either way.

---

## Sign-off

Record here once each section above has actually been performed (do not check off from reading
the code — only from having done it in the IDE):

- [ ] 1. Install
- [ ] 2. Found a Gate B worktree
- [ ] 3. Show File List lists the staged files, ordered by root then path
- [ ] 4. Popup title reads `N / M files reviewed  •  <scope>` and follows the scope (no automated coverage)
- [ ] 5. Superseded by section 20 — no separate sign-off needed
- [ ] 6. Diff opens on pick outside a session (single reused tab)
- [ ] 7. Marks survive IDE restart
- [ ] 8. Re-staging returns exactly the edited file, and Refresh shows a progress dialog
- [ ] 9. Three scopes work as a nested submenu; commit-range rejects space/`;`; cancelling changes
      nothing
- [ ] 10. Completion balloon + clipboard copy verified, and silent on reopening a complete project
- [ ] 11. Reviewed marks survive a refreshed scope round trip, project reopen and a failing root
- [ ] 12. A failing root balloons once, names its own path, does not repeat, and re-announces after
      recovering — and the healthy root still lists
- [ ] 13. Staged deletion and binary file return to unreviewed on change
- [ ] 13b. An unrenderable file does not break diff opening for other files
- [ ] 14. Nested root/submodule — the file marked is the file that was on screen
- [ ] 14b. Submodule gitlink — does a row appear, and can it stay marked? (record the answer)
- [ ] 15. Toggle Reviewed un-marks the file on screen without moving the diff
- [ ] 16. `git` before/after diff is empty — no repository mutation
- [ ] 19. idea.log read; no StackOverflowError / slow-EDT / ReadAction / reviewqueue diagnostics
      (state whether `ide.slow.operations.assertion` was enabled)
- [ ] 20a. Start Review hides the Project tool window; End Review restores it (the calls themselves
      are machine-checked now; this confirms the window really moves)
- [ ] 20b. Hidden layout survives quitting and reopening the IDE mid-session, and nothing tries to
      restore a "Review Queue" window
- [ ] 20c. Closing the review diff tab by hand ends the session and restores the layout
- [ ] 20d. Mis-mark recovery: Mark Reviewed → Previous File → Toggle Reviewed → Mark Reviewed
- [ ] 20e. All four chords mark and advance in the review diff — Alt+Shift+Z, Alt+Shift+Space,
      Alt+Shift+Enter, Cmd+Shift+Z (Ctrl+Shift+Z on Windows/Linux) — and **while the session is
      still running** none of them does anything in a normal editor, where Split Chooser and Redo
      must still work. On macOS confirm Ctrl+Shift+Z is not bound to Mark Reviewed.
      If a chord does nothing while the toolbar button works, follow the diagnosis note in
      section 20.5 — that chord is being eaten below the IDE, not by the plugin.
- [ ] 23a. Cmd+Option+Shift+Left / Right step between files without changing any mark, and each is
      disabled at its end of the pass. **Next File at the last file must do nothing** — it must not
      end the pass the way marking the last file does.
- [ ] 23b. Cmd+Option+Shift+Up / Down move between changed regions *within* the file on screen, and
      match what the diff viewer's own Previous/Next Difference buttons do
- [ ] 23c. Shift+F7 and F7 still work unchanged in the review diff, and in ordinary diffs outside a
      review pass — the plugin adds a second way in, it must not take the platform's away
- [ ] 23d. All four arrow chords do nothing in a normal editor while a pass is still running
- [ ] 23e. Next File appears on the diff toolbar beside Previous File
- [ ] 20f. Start Review after a fix round walks only the changed file(s), and says "Nothing
      unreviewed in Staged" when there is nothing left
- [ ] 20g. Toggle Reviewed enables (re-test: previously looked permanently disabled)
- [ ] 20h. Tab title tracks progress forward through at least three consecutive marks
- [ ] 20i. Completion balloon fires from marking the last file via the diff toolbar/shortcut
- [ ] 20j. A file leaving the queue mid-session is marked or visibly skipped, never silently passed
- [ ] 20k. Previous File is disabled on the first file; the Scope combo is **enabled** during a
      session and names the current scope
- [ ] 20l. Closing the project mid-session restores the layout (and so does quitting the IDE)
- [ ] 20m. Start Review from Find Action: every diff toolbar button present, and Find Action lists
      each registered command exactly once (no duplicate confirming copies, no Scope combo)
- [ ] 21a. The session controls (Scope, Start Review, End Review, Refresh, Reset All) sit after a
      separator on the review diff toolbar, visually grouped apart from the navigation buttons (not
      flush right — confirmed in 2026.2 that the diff toolbar's layout does not honour
      `RightAlignedToolbarAction`; see the design doc's *Known risk*)
- [ ] 21b. Each of Start Review / End Review / Refresh / Reset All asks before acting, and so does a
      mid-pass Scope change; answering No leaves the pass exactly as it was
- [ ] 21c. Reset All from the diff toolbar shows **one** dialog, not two
- [ ] 21d. Start Review is visibly disabled on the diff toolbar during a pass
- [ ] 21e. Refresh from **Tools → Review Queue** acts on one click with no confirmation, showing a
      progress dialog; Refresh on the diff toolbar confirms first, and cancelling the progress is a
      supported way out
- [ ] 21f. Find Action lists each of Start Review / End Review / Refresh / Reset All exactly once
- [ ] 22a. The file list opens from the diff toolbar and from Tools → Review Queue, lists every file
      in scope, reviewed ones marked, with the file on screen preselected
- [ ] 22b. Picking another file in the pass swaps the diff and moves the tab title's `Review N/M`;
      marking then continues from there
- [ ] 22c. Picking a file that was already reviewed when the pass started opens a browsing diff and
      leaves the pass and its progress untouched
- [ ] 22d. Speed-search filtering in the popup selects the right file
- [ ] 22e. Jumping to a file that has left the queue lands on the next live file, never on a blank
      diff
- [ ] 24a. **Cmd+Option+Shift+R run twice** — without a `.gradle.kts` focused, **and with one
      focused**. Start Review ran both times; no action-chooser popup appeared. (If it did, say so —
      the binding must change.)
- [ ] 24b. Tools → Review Queue renders as a named submenu, Scope nested inside it, separator
      between Show File List and Refresh
- [ ] 24c. The diff toolbar's Scope combo renders, names the current scope, and is enabled mid-pass
- [ ] 24d. Mid-pass re-scope: confirms, restarts the pass in the new scope, keeps every mark, the
      Project panel does **not** flash open and shut, and cancelling leaves the pass untouched
- [ ] 24e. Each of the four empty-result messages reads as documented — "Nothing unreviewed in
      Staged", "The review scope could not be read — one repository failed to resolve" (**including on
      the second press**), "No git repository in this project", "No unreviewed file in Staged could be
      displayed" — from Start Review and, where applicable, Show File List
- [ ] 24f. In a non-git project every Tools → Review Queue entry is disabled (and they do enable
      themselves shortly after opening a git project)
- [ ] 24g. On Windows/Linux Start Review has no shortcut; the menu and Find Action are the way in,
      and a self-bound chord works
- [ ] 24h. Nothing from the plugin runs at project open, and hovering the menu starts no background
      work
- [ ] 24i. A mid-pass scope switch into an all-reviewed scope says "Nothing unreviewed in …" and fires
      **no** "All N files reviewed" balloon and no `/myflow-do-done` copy action
- [ ] 24j. A mid-pass scope switch into a scope whose only unreviewed file cannot be rendered also ends
      the pass, restores the Project panel, and says **"No unreviewed file in Staged could be
      displayed"** — the second pass-ending route, which reported nothing at all for a while
- [ ] 24k. After that suppressed switch, finishing an ordinary pass **does** still fire "All N files
      reviewed" with its `Copy /myflow-do-done <name>` button — the suppression is not a mute
- [ ] 24l. **No Review Queue tool window exists** — not on any stripe, not in View → Tool Windows, and
      the IDE logs no failure to restore one from older persisted state
- [ ] 25a. Fresh scope banner reads `0 / N files reviewed  •  <scope>`, empty bar
- [ ] 25b. Mark Reviewed advances the banner's count and bar
- [ ] 25c. **Toggle Reviewed moves the count without the diff tab being replaced** — the subtle check
      a render-once banner would fail
- [ ] 25d. A background queue rebuild (re-staging another file mid-pass) also moves the banner
- [ ] 25e. Completing the pass leaves the banner reading `N / N` on the last frame
- [ ] 25f. The banner's scope name matches Show File List's popup-title scope name
- [ ] 26a. Starting a pass hides **every** visible tool window, including a third-party one — not
      only the Project panel
- [ ] 26b. Ending the pass reopens exactly the windows that were visible when it started
- [ ] 26c. **A window already closed before the pass is not reopened by ending it**
- [ ] 26d. With everything closed beforehand, nothing opens at the end of a pass
- [ ] 27a. Staged: a plain `git mv` produces one queue row keyed to the new path, not a delete+add pair
- [ ] 27b. Staged: a rename with a simultaneous content edit still resolves to one row
- [ ] 27c. Branch vs Base: a committed rename still resolves to one row keyed to the new path
      (unchanged behaviour, confirmed not regressed)
- [ ] 27d. Commit Range: same check as 27c (unchanged behaviour, confirmed not regressed)
- [ ] 27e. Renaming a previously-reviewed file (even with unchanged content) and re-staging **always**
      resets it to unreviewed — marks are keyed by path, a rename changes the path, so carry-over is
      impossible by construction; the mark appearing to carry over is a defect, report it
- [ ] 28a. Right-click in the review diff: Mark Reviewed first, all seven per-file actions, a
      separator, all five session controls, a separator, the platform tail with Compare with Clipboard
      **absent** and Annotate with Git Blame **present**
- [ ] 28b. **An unrelated diff opened during a pass (Git log / local history / Compare Files) shows
      the platform's stock menu, unmodified, with Compare with Clipboard still present** — the guard
      against the global extension point misfiring
- [ ] 28c. A binary file in a review pass shows **no** context menu at all, while the diff toolbar's
      Mark Reviewed still works there — confirm this is an acceptable permanent limitation (yes/no +
      comment)
