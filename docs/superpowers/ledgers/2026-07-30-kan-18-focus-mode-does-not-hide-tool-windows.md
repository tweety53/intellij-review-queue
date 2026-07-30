# SDD ledger — plan: openspec/changes/kan-18-focus-mode-does-not-hide-tool-windows/tasks.md

Worktree: /Users/tweety53/Projects/intellij-review-queue-worktrees/openspec-kan-18-focus-mode-does-not-hide-tool-windows
Merge base: b06a3646f92cf5c4435f385be777e7cdef4fa4e0
Baseline: 215 tests, 0 failures (./gradlew test @ merge base)

**myflow mode: NO COMMITS.** Diffs for review are `git diff TASK_BASE`, written to
`.superpowers/sdd/task-N.diff`. Implementers run on Opus per myflow model policy
(pipeline.md → Model policy), overriding subagent-driven-development's cheapest-tier guidance.

Pre-flight scan of the plan:
- Task 2 has no test of its own by design (the class is the seam; its behaviour is the platform
  call it wraps, covered from `IdeLayoutControllerTest` in tasks 3–5). Plan-mandated and argued in
  the plan text.
- Task 5 introduces a deliberately temporary no-op `disarmReshowWatch()` that task 6 replaces.
  Plan-mandated and argued.
- Step 8.4 (`./gradlew runIde` + interactive sandbox check) is an interactive GUI verification.
  No subagent can perform it. Routed into `docs/manual-test/<name>.md`, which is exactly the
  surface `/myflow-do` produces for the human gate. Recorded here rather than dispatched.

## Progress

Task 1: complete (uncommitted, review clean, model: opus; reviewer model: sonnet). Tree e9ae1e7, diff `.superpowers/sdd/task-1.diff`.
  Provenance: both `unverified:` claims confirmed by `javap` on `intellij.platform.core.jar` and by
  the RED compile — `ClientId(String)` exists and is a data class; `ClientType` is a Kotlin enum with
  exactly LOCAL/FRONTEND/CONTROLLER/GUEST. `ClientKind.OWNER`/`REMOTE` confirmed as the plan states.
  Carried concerns for later tasks:
  - Task 2 must confirm whether `ClientSessionsManager.getProjectSessions` order is stable; if two
    FRONTEND sessions can attach, `hideTarget`'s first-wins tie-break is arbitrary.
  - The premise that `ToolWindowManager` dispatches on the current `ClientId` is unproven by a unit
    test; it is established from the bytecode in `design.md` and is checked for real only by the
    sandbox run in the manual test guide.

Task 2: complete (uncommitted, review clean, model: opus; reviewer model: sonnet). Tree ddea9ef, diff `.superpowers/sdd/task-2.diff`.
  Two of the brief's `unverified:` hypotheses were FALSE, and both were corrected in the code:
  - `@Service` on an `open` class never registers — light services must be final
    (`isLightService` = `Modifier.isFinal(cls) && cls.isAnnotationPresent(Service)`), and
    `doGetService` throws `PluginException`. Would have shipped green, because `replaceService`
    bypasses the light-service path: every test in tasks 3–5 would pass while the first real
    `hideForReview()` on a user's machine threw. Restructured to a private final nested holder.
  - **There is no per-session message bus.** `ClientSession.messageBus` is `@Deprecated(level =
    ERROR)` ("sessions don't have their own message bus"); `ClientSessionImpl.getMessageBus()` is
    final and throws; `isMessageBusSupported()` is constant `false`. The brief's named fallback is
    the throwing method itself. `messageBus(session)` now returns the project bus.
  Controller action: `design.md` (*The re-show watch*, the `reshow-watch-listener` decision, and the
  listener-fidelity risk) and `proposal.md` corrected to state the project bus and why. Task 6 must
  filter by watched id alone and must not claim session scoping.
  Ordering question from task 1, answered: `getProjectSessions` filters a `ConcurrentHashMap.values()`
  with no sort — order is an implementation artifact of `ClientId.hashCode()` and table capacity, not
  a contract. With two FRONTEND sessions `hideTarget`'s tie-break is arbitrary. Task 1 unchanged; a
  plain IDE returns exactly one session, and `restoreTargets` is broad, so restore is unaffected.
Task 2: minor (deferred): `messageBus()` navigates `getProjectSession(...)?.project?.messageBus`; the
  `.project` hop is redundant since it necessarily equals the constructor's `project`. Indirect, not wrong.
Task 2: minor (deferred): class KDoc sentence "which is the defect KAN-18 describes stated as an
  assertion" is awkward — inherited verbatim from the plan's own snippet, not introduced here.
  The reviewer independently reproduced every platform fact above from the resolved jars via `javap`,
  including the exact `PluginException` message and the ERROR-level deprecation, and confirmed
  `replaceServiceInstance` and `getServiceIfCreated` share one instance container (the load-bearing
  question for tasks 3-6). Residual unverified-by-reviewer item: the live runtime probe, which only
  the implementer ran.

Task 3: implemented (uncommitted, model: opus). Tree 55108cd, diff `.superpowers/sdd/task-3.diff`.
  The brief's load-bearing hypothesis is FALSE, and the *stronger* shape is what is in the tree:
  `install` does NOT call `ClientId.addFakeLocalId`. That call adds the id's value to
  `ClientId.fakeLocalIds`, after which `withClientIdImpl` substitutes `ClientId.localId` — so every
  fabricated session would have arrived recorded as `Host` and every session-scoping assertion in
  tasks 4-6 would have held vacuously. Measured, not inferred: with the line present the assertion
  failed `expected <[ClientId(value=kan-18-frontend)]> but was <[ClientId(value=Host)]>`. This is the
  third green-but-wrong trap this plan has produced. A regression test pins it.
  Carried constraints for tasks 4-6:
  - Do not add `addFakeLocalId`, whatever justification is offered.
  - `ClientId.getCurrent()` is a Kotlin property: write `ClientId.current`; the method form does not
    compile from Kotlin.
  - Recording lists are cumulative and never reset — a test doing both a hide and a restore sees both
    passes. Assert on the whole list, not `.last()`.
  - Recordings are exposed as `List<ClientId>` over private mutable backing (encapsulation), not as
    public `MutableList` as the brief's snippet had it. Read-only access is unchanged.
Task 3: review — Spec OK, quality Approved (reviewer model: sonnet). Reviewer independently
  reproduced the `addFakeLocalId` bytecode finding. Two Important carry-forwards:
  (a) `design.md` Risks/Trade-offs still claimed `addFakeLocalId` keeps `ClientId.current` returning
      the fake id — CONTROLLER FIXED, that bullet now records the measured opposite.
  (b) `testRecordedIdsTellTwoSessionsApart` does not call `install()`, so it cannot fail if
      `addFakeLocalId` is re-added there; the "guards re-introduction" claim overstates it.
      → fix round 1 dispatched to the task 3 implementer.
Task 3: fix round 1/5 dispatched (model: opus) — finding (b).
Task 3: fix round 1/5 — implementer took route 2: added
  `testASessionInstalledByTheFakeEntersTheSweepAsItsOwnId`, which goes through
  `FakeClientSessions.install`, reads the session back from
  `ReviewClientSessions.getInstance(project).sessions()`, enters it, hides, and asserts the recorded
  id. Proved it bites: with `addFakeLocalId` re-added, 1 of 9 fails
  `expected:<[ClientId(value=kan-18-frontend)]> but was:<[ClientId(value=Host)]>`, while
  `testRecordedIdsTellTwoSessionsApart` passed unchanged — confirming the reviewer's diagnosis.
  Probe reverted. Suite 231/0. Tree f2b6baa, diff `.superpowers/sdd/task-3-fix-1.diff`.
Task 3: complete (uncommitted, review clean after 1 fix round, model: opus; reviewer model: sonnet).
  Re-reviewer traced the new test's path end-to-end and confirmed it would really fail on
  re-introduction; old case unchanged in body, KDoc correctly narrowed; no new breakage. 231/0.

Task 4: implemented (uncommitted, model: opus). Tree c9c0115, diff `.superpowers/sdd/task-4.diff`.
  RED was real: the three cases failed `expected:<[ClientId(value=frontend)]> but was:
  <[ClientId(value=Host)]>` before the fix — KAN-18 itself, observed as a test failure for the first
  time. Both `unverified:` blocks held: `ClientId.withExplicitClientId(id) { … }` resolves to the
  inline `Function0` overload, so the `AccessToken`/`.use { }` fallback is NOT in the tree.
  Suite 234/0 (231 + 3). Sweep body moved verbatim; every load-bearing behaviour byte-identical.
  Implementer concern to be adjudicated by review: `sweep(target)`'s parameter is unused until
  tasks 6-7, an artifact of the mandated verbatim move. Named drop-in alternative: move the
  `withExplicitClientId` entry inside `sweep`.
Task 4: complete (uncommitted, review clean, model: opus; reviewer model: sonnet). Reviewer verified
  the verbatim move character-for-character against the pre-image, confirmed nothing escapes the
  `ClientId` scope (all three recorded call sites), confirmed the no-target path touches nothing, and
  confirmed all 14 pre-existing layout cases are byte-identical.
Task 4: minor (deferred, adjudicated): `sweep(target)`'s parameter is unused until tasks 6-7.
  Reviewer's recommendation, which I accept: leave it. The signature is the brief's `Produces`
  contract so tasks 5-7 extend the body without re-touching the verbatim move; the named alternative
  trades one transient parameter for another. Kotlin does not warn on it, and a forced clean recompile
  emitted only one pre-existing unrelated warning (`GitReviewSource.kt:39`). Flagged in `tasks.md` so
  it is not "cleaned up" by mistake.

Task 5: implemented (uncommitted, model: opus). Tree 5eadac0, diff `.superpowers/sdd/task-5.diff`.
  RED confirmed: all three cases failed with `ClientId(value=Host)` where one id per session was
  required. Suite 237/0 (234 + 3); layout suite 20/20.
  A FOURTH vacuous-test trap caught: the brief's `testRestoreKeepsAnIdThatResolvedInNoSession` PASSED
  against the old single-session `restore()` — it asserted nothing, because the fixture keys windows by
  id and knows nothing about sessions. Implementer added a per-session `getToolWindow` lookup-count
  assertion (`{local=2, frontend=2}`), counted per session rather than as an ordered list so it does
  not also pin loop nesting. It then failed for the right reason.
  Plan-provenance question settled: snapshotting `myState.hiddenByReview` before the loop is safe —
  three writers repo-wide, all inside `IdeLayoutController`; no plugin component subscribes to a
  tool-window topic, so no path from `show(null)` back to a writer. Also strictly safer than the old
  body, which iterated the live list while reassigning the field.
  Open for a ruling: the mixed-resolution case (id resolves in session A but not B — the case that
  actually occurs at post-startup) is unproven by test; the fixture keys by id alone and cannot express
  per-session registration. Production is correct by construction (`resolved` is a union set), but that
  is an argument, not a test. Asked the reviewer to recommend close-now vs accept-and-record.
Task 5: complete (uncommitted, review clean, model: opus; reviewer model: sonnet). Reviewer verified
  the union is a true union (not last-session, not all-sessions), that guest exclusion is structural
  rather than merely observed, that both pre-existing restore cases are unedited, and independently
  re-derived the snapshot-safety argument (3 writers; the only message-bus listener in the plugin is
  `FILE_EDITOR_MANAGER` in `ReviewSessionService`, caller-driven, unreachable from `show(null)`).
  Agreed the `recorded.isEmpty()` guard is correctly left untested — the only distinguishing
  observation would be mock-invocation testing.
Task 5: RULING on the mixed-resolution gap — CLOSE IT NOW, per the reviewer's recommendation. Reasons:
  the plan's own constraint says this fix "has to close it rather than work around it", and two prior
  bugs in this class already hid behind exactly this fixture blind spot; accepting an argument-only
  basis a third time would repeat the failure mode the change exists to end. Out of task 5's file
  scope, so task 5 is not blocked; added as **Task 5b**, documented in `tasks.md` BEFORE implementing,
  and sequenced before task 6 so task 6 builds on the final fixture.
  Judgment recorded: no Jira description sync. The added scope is test coverage for a contract the
  delta spec already states ("forgotten once it resolved ... in at least one session"); it changes no
  user-visible behaviour and adds no requirement the issue does not already describe.

Task 5b: dispatched (model: opus) — session-aware fixture, additive by requirement; test-only.
Task 5b: implemented (uncommitted, model: opus). Tree ef8bfa4, diff `.superpowers/sdd/task-5b.diff`.
  Suite 241/0 (237 + 4). `src/main` verified byte-identical to task 5 by the controller.
  RED proven in two stages, both for the right reason: first a compile failure (`No parameter with
  name 'resolvesOnlyIn' found` — the fixture literally could not express the case), then, with the
  parameter accepted but ignored, `expected:<[local]> but was:<[local, frontend]>` — the blind spot
  itself rather than an assertion typo.
  **The union is now measured, not argued.** The implementer temporarily mutated `restore()` twice —
  a per-session `resolved` set ("last session wins") and an intersection reading — and each mutation
  is caught by the new test AND BY NOTHING ELSE, which also proves
  `testRestoreKeepsAnIdThatResolvedInNoSession` really was blind to it. Both mutations reverted.
  Deviation to be judged by review: `toolWindowIds` is filtered by session as well as `getToolWindow`,
  because that override's KDoc already asserts "every id this returns resolves, and nothing else
  does"; filtering only `getToolWindow` would have falsified it. Three cases added to a third file
  (`RecordingToolWindowsTest`) to cover the new fixture paths and pin the additive default.
Task 5b: complete (uncommitted, review clean, model: opus; reviewer model: sonnet). Reviewer traced all
  three readings of the record contract by hand and confirmed the new test catches both wrong ones, and
  that the `showClientIds` assertions stop the record assertion from passing vacuously. Confirmed
  additivity holds across all ~28 pre-existing `register(` sites (none passes `resolvesOnlyIn`; the
  parameter is trailing-with-default) and that no pre-existing test body was edited.
  Reviewer's ruling on the third-file deviation: NOT scope creep, and load-bearing. `restore()` never
  calls `toolWindowIds` — only `sweep()` does — so without the `RecordingToolWindowsTest` cases the
  enumeration-filtering half of the diff would have shipped completely untested, recreating this very
  task's failure mode inside its own fix.

Task 6: dispatched (model: opus) — the re-show watch, with the design's session-bus mechanism replaced
  by the project bus per task 2's finding, and the "no session-scoping claim" constraint made explicit.

Task 6: implemented (uncommitted, model: opus). Tree 8bdc9d5, diff `.superpowers/sdd/task-6.diff`.
  Suite 248/0 (241 + 7). Three plan-provenance findings changed the code:
  1. The brief overrides the WRONG listener overload. `toolWindowShown(String, ToolWindow)` is
     `@Deprecated(forRemoval = true)` with an empty body — it exists only as the delegate target of the
     one-arg default, and nothing in the platform publishes it. `ToolWindowManagerImpl
     .fireToolWindowShown` publishes the one-arg `toolWindowShown(ToolWindow)` from `doShowWindow`,
     which every show path funnels through. One-arg form overridden, id read from the window.
  2. `stateChanged`/`ShowToolWindow` is unnecessary — strictly narrower, fired only from
     `showToolWindow(String)` AFTER `doShowWindow` already fired `toolWindowShown`.
  3. `connect()`/`disconnect()` confirmed from the platform's `util.jar`. Trap recorded:
     `kotlin-compiler.jar` ships a stale shaded copy of those interfaces with no `disconnect()`.
  Vacuity avoided: `testRestoringDoesNotReportItselfAsAReShow` would have stayed vacuous even AFTER
  implementation, because `MockToolWindow.show` announces nothing. The fixture's `show` now publishes
  `toolWindowShown` as `fireToolWindowShown` does, so tests drive `show(null)` rather than
  hand-publishing. Three mutants killed: disarm removed, seam swapped for `project.messageBus`,
  `reported.add` demoted.
  **MEASURED LIMITATION, needs an operator decision at the gate:** in a split IDE the watch is SILENT.
  `BackendServerToolWindowManager.showToolWindow(String)` calls `super` only when the session is local;
  otherwise it routes to `updateBackendToolwindowState`, so `doShowWindow` is never reached and neither
  `toolWindowShown` nor a `ShowToolWindow` change is published. A frontend re-show emits nothing any
  `ToolWindowManagerListener` can see. No listener choice closes it without an `rdserver` symbol, which
  the plan forbids. It degrades to silence, never a wrong answer — the outcome `design.md` already
  accepted, now measured. The delta spec states the requirement unconditionally, so the spec overclaims
  in the very configuration the change targets; pending the reviewer's soundness check, the spec needs
  qualifying and the operator needs telling.
Task 6: complete (uncommitted, review clean, model: opus; reviewer model: sonnet). The reviewer
  re-derived EVERY platform bytecode claim independently with `javap` and all of them checked out,
  including the split-IDE silence finding traced end-to-end into `updateBackendToolwindowState`. It
  judged the fixture widening a faithful model rather than a baked-in answer, because the watched-id
  set comes from enumeration while the event's id comes from `getId()` — two independent paths, each
  pinned by its own fixture test. Confirmed no listener-based mechanism can close the split-IDE gap
  without an `rdserver` symbol.
Task 6: two Minors, both planning-artifact accuracy, both CONTROLLER FIXED:
  (a) `design.md` read as either/or on the split routing; `updateBackendToolwindowState` is in fact
      called on BOTH paths, and what a non-local session loses is specifically the `super` call.
  (b) **The delta spec overclaimed.** It required the warning to name "the session it was shown in",
      which the project bus cannot supply, and stated the requirement unconditionally although it
      cannot hold in a split IDE. Requirement retitled "...where the platform announces it"; both
      measured limits written into it; a once-per-id scenario added to match the implemented
      behaviour. This is the change's most important disclosure and goes in the handoff.

Task 7: implemented (uncommitted, model: opus). Suite 251/0; layout suite 29/29. RED confirmed — the
  three new cases failed on messages that warned but named no session.
  Plan-provenance correction worth keeping: the brief's claim that no test asserts the `ids.isEmpty()`
  string is FALSE. `testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible` asserts
  `contains("zero tool window ids")` as its positive control, so that substring is LOAD-BEARING — any
  later reword of that branch must preserve it or the control silently stops controlling. The other two
  claims held, refined: the post-hide split is two `warn` + one `info`.
  The plan's predicted total of 231 is stale (written before tasks 5b and 6 added cases); 251 is real.
Task 7: fix round 1/5 dispatched (model: opus) — the hide-loop `warn` in `sweep` was the one KAN-6
  sweep line still not naming the session, and the task's own heading says *every* line. It is also the
  line that fires when something has actually gone wrong, so it is where the session matters most.
  Constraint restated on dispatch: the suffix only — the rethrow and fail-fast behaviour must not move.
Task 7: noted for step 8 (controller-owned): the plan's step 8.2 grep `rdserver\|jetbrains\.rd\b`
  expects `clean` but will report two PRE-EXISTING KDoc prose hits in `IdeLayoutController.kt`. The real
  constraint (no `rdserver`/`cwm-plugin` *symbol*) holds; the grep is broader than the constraint. The
  expectation in step 8.2 is the defect, not the tree.
Task 7: fix round 1/5 — hide-loop `warn` now renders the session through the SAME private helper the
  two log helpers use, so all three lines cannot drift. Control flow untouched: still catches per id,
  still names the id, still rethrows immediately. New case
  `testTheHideThrewWarningNamesTheSweptSession` asserts BOTH that the throwable still propagates as
  `IllegalStateException` AND that the message names the session — so the suffix cannot have been
  bought by swallowing the throw. Suite 252/0 (248 + 3 + 1).

Task 8 (controller-executed; verification only):
  8.2 done — grep returns exactly the two pre-existing KDoc prose hits in `IdeLayoutController.kt`
      (lines 232, 291). Neither is an import, type name or call. The plan's `clean` expectation was
      wrong and has been corrected in `tasks.md` with the rule for reading the result.
  8.3 done — README DID contradict this change, in the way step 8.3 anticipated: three places said
      focus mode hides/restores "the Project tool window". Corrected to say it hides every open tool
      window, that a window already closed stays closed, and that ending a pass reopens what it hid.
  8.4 (sandbox `runIde` + interactive check) is NOT dispatchable to a subagent — it needs a human at a
      GUI. Routed into `docs/manual-test/<name>.md`, per the pre-flight note at the top of this ledger.
Task 7: review — Spec OK; Task quality CHANGES REQUESTED (reviewer model: sonnet). Reviewer confirmed
  the warn/info split never moved, the rethrow/fail-fast path is untouched, the new case rules out a
  suffix bought by swallowing the throw, all sites render through one pair of helpers, and found no
  sixth vacuous test. One Important finding:
  - The implementer's own correction (that the brief's "no test asserts this string" claim is FALSE,
    because `testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible` asserts
    `contains("zero tool window ids")` as a POSITIVE CONTROL) lives only in the gitignored report.
    `tasks.md` step 7.2 still carries the disproven claim. A future reword would break the control,
    and a positive control that stops controlling FAILS SILENTLY. → fix round 2 dispatched: record it
    in `tasks.md` step 7.2 and in `logSweepOutcome`'s KDoc.
Task 7: fix round 2/5 dispatched (model: opus) — durable record only, no behaviour change.
Task 7: complete (uncommitted, review clean after 2 fix rounds, model: opus; reviewer model: sonnet).
  Re-reviewer independently verified the positive-control substring really is asserted and really does
  still appear in the message, and confirmed the round changed no executable line.
Task 8: complete (controller-executed) EXCEPT step 8.4.
  8.1 `./gradlew build` BUILD SUCCESSFUL — **252 tests, 0 failures, 0 errors, 0 skipped** (baseline 215).
  8.4 deliberately LEFT UNTICKED. It is the sandbox `runIde` + interactive GUI check, which no agent can
      perform. Ticking a check nobody performed would be exactly the silent falsehood this change spent
      six rounds eliminating. It is routed into `docs/manual-test/` as section 1-2, and the handoff says
      so plainly.

Manual test guide written: `docs/manual-test/kan-18-focus-mode-does-not-hide-tool-windows.md`.
  Section 2 asks the operator to record the `ClientType` the log names — the one fact this whole change
  turns on and the only one no automated test in the project can observe.

Final review panel — pass 1, full roster. See `.superpowers/sdd/final-review-panel.md` for the roster,
  the optional-slot reasoning, and the placeholder resolution. Six slots dispatched in parallel, all on
  sonnet: primary, bug hunter, principles (Merged), adversarial, principles (Lens B), principles
  (Lens C). Security EXCLUDED — no trigger fires. Slot 1 substitution recorded: `bugbot` is not an
  available subagent type in this harness, so the slot runs as general-purpose against this skill's own
  `bug-hunter-reviewer-prompt.md` rather than being skipped.
  `[STANDARDS_PATHS]` resolved EMPTY — this project has no `.myflow/project.md`, so no `## standards`
  entries exist, none resolved, and none was substituted from another project.

Panel pass 1 complete — six slots, all sonnet. Clean: principles Merged, principles Lens B,
  principles Lens C, adversarial. Findings: primary 1 Important (controller-fixed, `tasks.md` task 6
  as-built note), bug hunter 3 Important.
  Strongest evidence in the panel: the adversarial slot ran TEN targeted production-line reverts and
  confirmed every load-bearing behaviour fails its corresponding test when broken, with nothing failing
  spuriously — and could not find a seventh vacuous test by either static analysis or empirical revert.
Panel fix wave 1 dispatched (ONE subagent, model: opus) with the deduped union — bug hunter's 3
  Important plus the adversarial slot's KDoc-accuracy Minor, which is in a method the fix already
  touches. Lens C's Minor is the same defect as bug hunter 3 and was folded into it rather than
  dispatched twice. FIX_BASE tree bb1770a.
Panel fix wave 1 applied (model: opus). Suite 254/0 (was 252; +2). Test-file diff is additions only
  (94/0) and both fixture flags are new defaulted parameters, so every pre-existing test still passes
  unedited. Two deliberate decisions the fix wave flagged for the panel, both justified in KDoc:
  - an id whose `show()` threw STAYS on the record (contract wording moved from "resolved in no
    session" to "not reopened in any session"; the union still holds because `resolved += id` only
    follows a `show()` that returned normally);
  - `dispose()` is deliberately EMPTY, because the connection is a Disposer child already disconnected
    by then, and a cleanup call there would mask the parenting having been dropped.
  Finding 3 has no new test; the implementer judged it untestable without reaching into platform
  internals and rested it on a verified bytecode fact (`initializeComponentOrLightService` registers a
  service implementing `Disposable` with `serviceParentDisposable`) plus in-repo precedent, rather than
  writing an eighth test that asserts nothing.
Panel pass 2 — FULL re-run, escalated automatically (not asked), for two triggers: the fix diff is 279
  changed lines (>~150), and it altered a public contract (`IdeLayoutController` gained a `Disposable`
  supertype; `restore()`'s record contract was reworded). All six slots re-dispatched on sonnet against
  a rewritten `final-review.diff`. Each carries what the fix wave changed so it can aim, plus its own
  pass-1 findings so it can judge whether severity moved.
Panel pass 2 outcome: THREE of six slots independently found the same Critical — the bug hunter by
  tracing, Lens C by robustness analysis, the adversarial slot by REPRODUCING it with a probe test.
  The primary slot examined the identical code path and judged it benign ("no latch — it just gets
  superseded"); the probe settles that against it. **This is the clearest demonstration in the change
  of why the panel's breadth is bought rather than its depth: one strong reviewer would plausibly have
  returned the primary's verdict and shipped the defect.**
  The Merged lens's SRP Important was adjudicated and PARKED — its premise (that the `Disposable`
  supertype is unjustified) was contradicted with evidence by two other slots, one of which
  disassembled the platform to check. Extraction recorded as a follow-up. Ruling in the panel record.
Panel fix wave 2 applied (model: opus). Suite 256/0. `hideForReview()` now reads back what the reclaim
  could not reopen and `sweep()` unions it in; new `logCarriedForward` warn; delta spec rewritten to
  say **reopened** rather than "resolved", with the union rule and anti-latch guarantee made normative
  and two scenarios added. RED was literally the reviewer's probe result.
Panel pass 3 — FULL re-run, escalated automatically again: the fix diff is 180 changed lines (>~150)
  AND it altered the delta spec. All six slots re-dispatched on sonnet.
Manual test guide refreshed: automated-state line 252 -> 256 (the primary slot's Minor).
Panel pass 3: four slots clean (primary, principles Merged, Lens B, Lens C). Two findings:
  - Bug hunter, CRITICAL: `restore()`'s cross-session `resolved` set forgets an id the instant it
    succeeds in ANY one session, even if it threw in every other session it resolved in. Same data-loss
    class as pass 2's Critical, reached through `restore()` rather than `sweep()`, so fix wave 2 (which
    touched only `sweep()`) did not close it. Unrecoverable, because the union only helps if the id is
    still on the record when `hideForReview()` reads it back.
  - Adversarial, IMPORTANT: `logCarriedForward`'s "silent on the normal path" guarantee is untested —
    mutating the call to pass `hidden` instead of `carriedForward` left 255 of 256 tests passing, the
    one failure incidental. The design argues a diagnostic that fires on the normal path stops being
    read, so the silence is the operationally important half and is protected only by coincidence.
  Lens C also validated that pass 2's Critical is genuinely closed rather than moved again — the slot
  that raised it. Lens B proved idempotence and boundedness. Merged declined to re-raise the parked SRP
  finding and confirmed the class's growth did not cross a new line.
Panel fix wave 3 dispatched (ONE subagent, model: opus) with both findings, including a fixture
  extension to make `throwsOnShow` session-scoped — the fixture could not express the Critical's
  scenario, which is why no test caught it. FIX_BASE tree 7f39cb3.
**Second consecutive pass in which a single slot caught a real data-loss defect that the others — the
  primary included, which listed the very mechanism among behaviours it had verified as correct —
  cleared. This is the panel's breadth paying for itself twice.**

Panel pass 4 (third full re-run): FIVE slots clean — primary, principles Merged, Lens B, Lens C,
  adversarial. The adversarial slot reproduced both of fix wave 3's mutation claims exactly and added a
  third of its own against the fixture.
  ONE Critical, from the bug hunter — its third consecutive one — and it is a DESIGN-level collision,
  not a code defect: hiding as FRONTEND never flips host state, so a later `show()` under LOCAL succeeds
  trivially and the record reads that no-op as "reopened", dropping an id the frontend still has hidden.
  ADJUDICATED: NOT fixed here; surfaced to the operator as the handoff's headline.
  Reasons: (1) real and bytecode-derived; (2) NOT this change's regression — the rule predates all three
  fix waves and wave 3 made bookkeeping strictly more conservative; (3) every available fix reopens a
  decision `design.md` deliberately made and documented (per-session record; session listener + retry),
  which is the operator's call, not a fix wave's; (4) its load-bearing premise — that the frontend's
  per-client hidden state survives a backend restart — is UNVERIFIED by either reviewer, and if false the
  scenario cannot occur. The adversarial slot independently derived the same sequence and graded it a
  pre-existing accepted trade-off, which is the disagreement that makes this the human's decision.
  Recorded in `design.md` Open Questions and as a new §4a in the manual test guide that asks the operator
  to settle exactly that premise on a real split setup.

FINAL STATE: 259 tests, 0 failures, 0 errors, 0 skipped; `./gradlew build` successful, verified twice by
  the controller directly. Zero commits (`git log <merge-base>..HEAD` empty). Everything staged.
  Main checkout's stale staged copies of the openspec artifacts re-synced to match the worktree, so the
  two cannot conflict at merge; verified identical.
