# Manual test guide — kan-6-plugin-updates

**Change:** kan-6-plugin-updates · [KAN-6](https://tweety53.atlassian.net/browse/KAN-6) — Plugin Updates
**Worktree:** `/Users/tweety53/Projects/intellij-review-queue/.claude/worktrees/openspec-kan-6-plugin-updates`
**Branch:** `openspec/kan-6-plugin-updates`

Automated state at handoff: **215 tests, 0 failures, 0 errors, 0 skipped**; `verifyPlugin`
**Compatible** with IU-262.9437.22, zero deprecated and zero experimental API usages.

This guide covers only what KAN-6 changed. `docs/manual-verification.md` sections 25–28 carry the
same checks in the project's own long-form format; use whichever you prefer — they were written in
the same run and do not disagree.

---

## Run the app

One app: the plugin itself, launched in a sandbox IDE.

```bash
cd /Users/tweety53/Projects/intellij-review-queue/.claude/worktrees/openspec-kan-6-plugin-updates
./gradlew runIde
```

**A real display is required.** Every check below is a UI check, and none of them was reachable
from the automated suite. A headless sandbox can only confirm the plugin loads.

To review this change's own diff with the plugin, open the worktree in the sandbox IDE, set the
scope to **Staged**, and press **Start Review** (<kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>R</kbd>).

```bash
open -na "IntelliJ IDEA" --args "/Users/tweety53/Projects/intellij-review-queue/.claude/worktrees/openspec-kan-6-plugin-updates"
```

---

## 1. Progress banner

- [x] Start a review pass. A strip appears **above the diff** reading `N / M files reviewed` with a
      progress bar and the scope name — e.g. `0 / 12 files reviewed  •  Staged`.
- [x] The totals describe the **whole scope**, not the files left in the pass.
- [x] Press **Mark Reviewed**. The count increases by one as the next file opens.
- [x] **The check automated tests cannot reach:** press **Toggle Reviewed** on the file on screen.
      The count moves **while the diff tab stays put**. A banner that only refreshed when the tab was
      replaced would pass every unit test and fail here — this is the one step worth doing slowly.
- [x] Toggle it back. The count returns.
- [x] The banner's scope name matches the **Scope** combo in the toolbar exactly. Two different
      names for one scope is a defect.

## 2. Focus mode

- [x] Before starting a pass, open several tool windows on different sides — **Project** (left),
      **Terminal** (bottom), **Git** (bottom), and one from a third-party plugin if you have one.
- [x] Start a review pass. **All of them disappear.** The diff has the screen to itself.
- [x] End the pass (**End Review**, or close the diff tab). **Every window you had open comes back.**
- [x] **The check that protects the user's layout:** deliberately **close** one window (say Terminal)
      before starting the next pass. Start and end a pass. Terminal must **stay closed** — restoring
      it would mean the plugin reopened something you closed on purpose.
- [x] Start a pass with a build or long-running command in the Terminal. Hiding the window must not
      stop it; reopen at the end and confirm it ran.
- [ ] **Fix round 2, Report B — do this in `Staged`, then again in `Branch vs Base`, then again in
      `Commit Range`.** Focus mode has never been ticked off in any scope before, so do not treat this
      as a two-scope problem or assume a scope that already looked fine will keep looking fine. In
      each scope: open Project, Terminal and Git, then press **Start Review**. All three must
      disappear. If any window stays on screen in **any** scope, the pass still started (the diff
      opens either way), so this is not a crash to chase. The warn line below means "`hide()` was
      called and the window was still visible when it returned" — it does **not** by itself mean
      `hide()` silently no-oped. A same-stack reentrant reopen (something calling `show()`
      synchronously inside `hide()`) produces the identical message. The two remain distinguishable
      only by further investigation; the log shapes below are the starting point for that, not a
      verdict.
- [ ] **If focus mode fails in any scope above:** collect `idea.log`
      (`.intellijPlatform/sandbox/review-queue/IU-2026.2/log/idea.log`) from that run and paste every
      line containing `Review Queue: hideForReview()`. The sweep now logs **before** it calls
      `hide()` on anything and **again immediately after**, specifically so a throw or a reopen
      partway through can't erase the evidence — quote the lines rather than paraphrasing them:
      - `sweep found zero tool window ids on ...` (`warn`) — `ToolWindowManager.getInstance(project)`
        is not resolving to the manager the IDE is actually using for this project; the line names the
        concrete manager class, which matters on this IDE build's `rdserver` / frontend-split modules.
      - `sweep enumerated [...] but none of them were visible` (`info`) — the sweep ran and saw the
        ids, but judged every one of them already invisible before it ever called `hide()`, which is
        the reported symptom. Deliberately logged at `info`, not `warn` — a choice, not an oversight —
        precisely *because* this exact same line is also the normal shape for an already-closed
        window or a second Start Review with nothing restored in between: warning on it would cry
        wolf against a legitimate outcome every time either of those happened. It is not by itself
        abnormal — only relevant here because it is the state during a *failed* focus-mode check.
      - `sweep enumerated [...] on ..., judged [...] visible` (`info`) — the sweep is about to call
        `hide()` on these ids. This line alone does **not** prove hiding worked; it is captured before
        the loop runs and only restates what the filter judged visible.
      - `post-hide check on ...: [...] confirmed no longer visible` and/or `[...] no longer resolve to
        a tool window at all` (`info`) — checked right after the loop, `hide()` returned and every
        listed id is no longer visible. These two are reported separately: "confirmed no longer
        visible" means the id still resolves and `isVisible()` answered `false`; "no longer resolve"
        means `getToolWindow` returned nothing at all for it — a different outcome, not folded
        together. If the windows are on screen despite this line, something reopened them **after**
        `hideForReview()` returned.
      - `post-hide check on ... found [...] still visible immediately after hide() returned — hide()
        was called and the window was still visible when it returned` (`warn`) — this line means
        exactly that: `hide()` was called and the id was still visible right after. It does **not**
        settle whether `hide()` failed to take effect or something re-showed the window within the
        same call stack — those remain distinguishable only by further investigation (e.g. a
        breakpoint or a listener audit on that id), not by this line alone.
      - `post-hide check could not verify ... — treating it as unverified` (`warn`, names the id and
        the throwable) — the re-query itself threw (e.g. the window disposed its content on hide)
        instead of answering true/false/null. This is the diagnostic failing safe: `hideForReview()`
        still completed and the diff still opened; the id's post-hide visibility is simply unknown.
      - **No `Review Queue: hideForReview()` line at all** — the sweep line is now written before
        `hide()` is ever called, so its absence really does mean the call was never reached, which
        contradicts the diff having opened; report this exactly, it is its own finding. (A missing
        *post-hide* line on its own is not the same finding — see the note below.)
      - **A sweep line with no post-hide line after it:** expected if `hide()` threw partway through
        the loop, not evidence the method was never reached. Look for an exception from `dev.tweety.*`
        nearby; if there is none, report the gap exactly as seen.

## 3. Rename detection

Only the **Staged** scope changed here; Branch vs Base and Commit Range already behaved correctly.
Check all three anyway — the point is that they now agree.

**A note on `Branch vs Base` and `Commit Range` on *this* branch specifically:** at the
`IN_PROGRESS` gate this worktree carries exactly **one** commit
(`docs(kan-6): design for the plugin updates`) with the rest of the change — 31 files — staged but
uncommitted, because `/myflow-do` stages and leaves committing to `/myflow-finish`. Both of those
scopes compare *commits* (`getThreeDotDiffOrThrow(base, head)` and `getDiff(from, to)`), so
uncommitted work is invisible to them by construction — entering `main`, `origin/main`, or leaving
the base empty all resolve to the same merge base and all show only that one committed file. That
is git's correct answer, not a defect in this plugin, and it is a general property of reviewing a
myflow change at this gate, not a quirk of this branch: **`Staged` is the only scope that can see
the change under review.** To exercise `Branch vs Base` and `Commit Range` against real multi-commit
history instead, point the plugin at a branch that actually has commits ahead of its base — e.g.
`openspec/kan-5` in this repository.

- [x] Stage a rename with a small edit, e.g. `git mv a.kt b.kt` then append a line and `git add`.
      In **Staged** scope, the queue shows **one** entry, at the **new** path. Not a delete plus an add.
- [x] Open it. The diff shows the content change.
- [x] Repeat in **Branch vs Base** and in **Commit Range**. One entry each, new path.
- [x] **Expected, so it can fail:** a file you had already marked reviewed, then renamed, comes back
      as **unreviewed** at its new path. This is deterministic, not a judgement call — marks are
      keyed by `"<root>|<relative path>"`, and a rename changes the path, so the old mark is
      orphaned and the new path never matches it. Seeing the mark carry over would be a real
      regression worth reporting, not an acceptable variant.
- [x] Mark the renamed file reviewed, then change its content and let the queue refresh. The mark
      **drops** and the file returns to the queue — marks are addressed to content.
- [x] A rename with **no** content change also appears as a single entry.

## 4. Right-click context menu

> **This section failed at the first human gate and was fixed in round 1 — test it carefully.**
> The menu did not appear at all: right-clicking a review diff showed only *Compare with Clipboard*.
> The attachment used `EditorEx.setContextMenuGroupId`, which only feeds the default popup handler,
> while the platform *appends* its own handler afterwards and wins. The menu is now installed with
> `installPopupHandler` from a `DiffViewerListener.onInit()`, which runs after the platform's.
> `proposal.md` section **Fix round 1** has the full evidence.

- [x] Inside a review diff, right-click the code. A menu opens with **Mark Reviewed first**.
- [x] It also offers Toggle Reviewed, Show File List, Previous/Next File, Previous/Next Change.
- [x] Below a separator: Scope, Start Review, End Review, Refresh, Reset All.
- [x] Below a second separator: the platform's own entries — **Annotate with Git Blame is still
      there**.
- [x] **Compare with Clipboard is gone.**
- [x] Choosing **Reset All** from this menu still asks for confirmation before clearing anything.
- [x] **The check that a global extension point has not misfired:** while a pass is running, open a
      diff from the **Git log** (or local history, or Compare Files). Right-click it. That menu must
      be the platform's **stock** menu — no review actions, and **Compare with Clipboard still
      present**. If the review menu appears there, the guard is too permissive.

### Known and deliberate — please confirm it is acceptable

- [x] A **binary or image** file in a review pass gets **no right-click menu**. This is inherent, not
      an oversight: binary diff viewers expose no text editor for a menu to attach to. The diff
      **toolbar** still offers Mark Reviewed for those files, so they remain reviewable. Confirm you
      are happy with this, or say so and it becomes follow-up work.

---

## What automation could not establish

State this plainly because it is the residual risk in this change:

- **That the right-click menu appears at all in a real IDE.** Still the residual risk, and the one
  that actually bit: this check failed at the first gate. No live `SimpleDiffViewer` or
  `CacheDiffRequestChainProcessor` can be built in the harness — the Swing toolbar has no
  ComponentUI headless — so the automated tests reproduce the platform's install *sequence* on a
  real `EditorEx` and a stub `DiffViewerBase` rather than driving a real viewer.
  **Check 4's first box is therefore the real proof.**
- **That the banner renders above the toolbar** rather than somewhere else in the diff frame.
- **That the tool-window sweep leaves a visually clean screen.**

**If check 4's first box fails, do not assume the marker is the cause.** An earlier version of this
guide predicted exactly that, and it was wrong — the marker path was correct all along; the
attachment mechanism was not. Useful evidence, in order:

1. `idea.log` in the sandbox (`.intellijPlatform/sandbox/review-queue/IU-2026.2/log/idea.log`).
   `ReviewDiffPopupGroup` and `ReviewDiffExtension` both warn on every failure path they have. **No
   Review Queue warnings at all** means the group was never consulted — the menu was not installed,
   rather than installed-and-empty.
2. Whether *Compare with Clipboard* is present. It is filtered out of the review menu, so seeing it
   proves you are looking at the platform's stock menu, not this plugin's.
3. Only then the marker, which `testTheMarkerReachesTheContextThroughTheRealChainFallback` covers.
