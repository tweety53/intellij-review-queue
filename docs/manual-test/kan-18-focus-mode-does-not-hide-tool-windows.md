# Manual test guide — kan-18-focus-mode-does-not-hide-tool-windows

**Change:** kan-18-focus-mode-does-not-hide-tool-windows · [KAN-18](https://tweety53.atlassian.net/browse/KAN-18) — Focus mode does not hide tool windows
**Worktree:** `/Users/tweety53/Projects/intellij-review-queue-worktrees/openspec-kan-18-focus-mode-does-not-hide-tool-windows`
**Branch:** `openspec/kan-18-focus-mode-does-not-hide-tool-windows`

Automated state at handoff: **259 tests, 0 failures, 0 errors, 0 skipped**; `./gradlew build`
successful. Baseline before the change was 215 tests.

**Why this guide matters more than usual for this change.** The whole fix turns on one fact — which
client session the sweep runs as — and *no automated test can observe the real one*. The suite runs
against a stub manager with fabricated sessions; a plain local sandbox has only a `LOCAL` session,
which is the configuration in which KAN-18 does not reproduce. Section 1 is the only check in the
project that sees the real `ToolWindowManager` answering for a real session.

---

## Run the app

One app: the plugin itself, launched in a sandbox IDE.

```bash
cd /Users/tweety53/Projects/intellij-review-queue-worktrees/openspec-kan-18-focus-mode-does-not-hide-tool-windows
./gradlew runIde
```

**A real display is required.** Every check below is a UI check and none was reachable from the
automated suite. A headless sandbox can only confirm the plugin loads.

To review this change's own diff with the plugin, open the worktree in the sandbox IDE, set the
scope to **Staged**, and press **Start Review** (<kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>R</kbd>).

```bash
open -na "IntelliJ IDEA" --args "/Users/tweety53/Projects/intellij-review-queue-worktrees/openspec-kan-18-focus-mode-does-not-hide-tool-windows"
```

The sandbox log this guide reads:

```
/Users/tweety53/Projects/intellij-review-queue-worktrees/openspec-kan-18-focus-mode-does-not-hide-tool-windows/.intellijPlatform/sandbox/review-queue/IU-2026.2/log/idea.log
```

---

## 1. The bug itself — a pass hides what the reviewer can see

This is the reported symptom and the reason the change exists.

- [x] In the sandbox, open a project with a git repository.
- [x] Open **Project** and **Terminal** so both are visibly on screen. Open **Database** too if it is
      available — the original report named all three.
- [x] Run **Start Review**.
- [x] **All of them disappear.** This is the check that fails before this change and passes after it.
- [x] The first file opens as a diff.
- [x] Run **End Review**. **Every window that was hidden comes back**, and nothing else opens.

## 2. Which session was swept — the one fact the change turns on

```bash
grep "hideForReview" /Users/tweety53/Projects/intellij-review-queue-worktrees/openspec-kan-18-focus-mode-does-not-hide-tool-windows/.intellijPlatform/sandbox/review-queue/IU-2026.2/log/idea.log
```

- [x] Every `hideForReview()` line ends with a session suffix of the form
      `[session <id> (<TYPE>)]`. No line is missing it.
- [x] All lines from one pass name the **same** session and the **same** manager — they are computed
      once per sweep, so two lines disagreeing would be a defect.
- [x] **Record which `ClientType` it names, here:** `________________`

      This is the single most valuable fact this guide produces. In a plain local sandbox expect
      `LOCAL`, and expect the manager to be `ToolWindowManagerImpl`. If it names `LOCAL` while the
      manager is `BackendServerToolWindowManager`, the session enumeration is not seeing the frontend
      session and section 1 will have failed — report both values.

## 3. A window the user had already closed is not reopened

The safety property the whole "measure visibility in the session you mutate" design protects.

- [ ] Close **Terminal** so it is not on screen. Leave **Project** open.
- [ ] Run **Start Review**, then **End Review**.
- [ ] **Terminal stays closed.** Only Project comes back.

## 4. Ending a pass after quitting the IDE mid-pass

Why `restore()` reaches every non-guest session rather than one.

- [ ] Run **Start Review** so the tool windows are hidden.
- [ ] Quit the sandbox IDE entirely while the pass is running.
- [ ] Relaunch (`./gradlew runIde`) and reopen the same project.
- [ ] The hidden tool windows are **reopened** on startup — the pass does not leave them stranded.

### 4a. The open question the review panel could not settle — please answer it here

**This is the most valuable thing you can record in this guide after section 2.** A reviewer argued,
from the IU-2026.2 bytecode, that on a **split / remote-dev** IDE this sequence can lose a window:
hiding runs as `FRONTEND` (so only that client's state is marked hidden, never the host's); the IDE is
quit mid-pass; at restart `ReviewLayoutRestorer` runs while only the `LOCAL` session exists; showing
under `LOCAL` succeeds *trivially*, against host state nothing ever hid; the plugin reads that as
"reopened" and drops the id; and the frontend later reattaches with its window still hidden and nothing
left to retry it.

**The whole argument rests on one unverified premise:** that the frontend's per-client hidden state
*survives the backend restart* and is still set when the frontend reattaches. Nobody established that.
If per-client state is created fresh for each new session, the scenario cannot happen at all.

- [ ] **On a split / remote-dev setup only:** run section 4 above, then reconnect the frontend and
      record whether any tool window is **still hidden** on the frontend after the restart.
      Answer: `________________`

      "Nothing stayed hidden" means the premise is false and the concern dissolves. "A window stayed
      hidden" means it is real, and the fix is a design decision — see the handoff notes.
      On a plain local IDE this check does not apply; write `n/a`.

## 5. A second pass

- [ ] Run **Start Review**, then **End Review**, then **Start Review** again.
- [ ] The second pass hides what is visible *at that moment* — not what the first pass hid.
- [ ] **End Review** restores correctly again.

## 6. The re-show warning, and its two disclosed limits

Read the spec's requirement *A window re-shown during a pass is reported where the platform announces
it* before this section. Two limits are **expected behaviour**, not defects:

- the warning names the session the pass **swept**, not the session the window was shown in;
- under a split / remote-dev IDE the platform publishes no event at all, so **no warning appears**.

- [ ] Run **Start Review**, then reopen a hidden tool window by hand (e.g. click **Project** in the
      stripe) *without* ending the pass.
- [ ] **On a plain local IDE:** the log carries one `warn` naming that window and the swept session.
- [ ] Reopen and re-close the same window a few more times. **Still exactly one warning for it** —
      one finding per window per pass, not a stream.
- [ ] **On a split / remote-dev IDE:** no warning is expected. Silence here is the documented
      limitation, not a failure. If you see a warning, that is *better* than specified — record it.
- [ ] Run **End Review**. **No re-show warning is produced by the restore itself** — the plugin's own
      reopening must never be reported.

## 7. Nothing else regressed

- [ ] Marking the last file reopens the hidden tool windows and fires the completion balloon.
- [ ] Closing the review diff tab by hand ends the pass and reopens the windows.
- [ ] The progress banner, rename detection and the diff context menu (KAN-6's features) still work.

---

## Not checkable here

- **A Code With Me guest's layout is never touched.** Covered by the automated suite; reproducing it
  by hand needs a second machine joining a CWM session. If you ever have that setup, the check is:
  start a pass as host, and confirm the guest's tool windows do not move.
- **A controlling remote client is chosen when there is no frontend.** Same reason.
- **The exact text of the two `info` log branches.** `LoggedErrorProcessor` intercepts only
  `warn`/`error`, so the suite cannot assert `info` text. Section 2 above is the only check on it.
