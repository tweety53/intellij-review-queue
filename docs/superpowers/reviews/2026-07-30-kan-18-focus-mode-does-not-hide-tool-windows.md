# Final review panel — kan-18-focus-mode-does-not-hide-tool-windows

## Pass 1 — full roster

**Diff read by every slot:** `.superpowers/sdd/final-review.diff`
(`git diff -U15 HEAD` at merge base `b06a364`; 3308 insertions, 61 deletions across 14 files.)

**Automated state:** `./gradlew build` successful; 252 tests, 0 failures, 0 errors, 0 skipped.
Baseline at merge base was 215.

### Roster

| # | Slot | Required | Model | Included? |
|---|------|----------|-------|-----------|
| 0 | Primary — plan alignment + code quality | always | sonnet | yes |
| 1 | Bug hunter | always | sonnet | yes — see substitution note |
| 2 | Principles — lens **Merged** | always | sonnet | yes |
| 3 | Security | conditional | — | **excluded** |
| 4 | Adversarial | conditional | sonnet | yes |
| 5 | Principles — lens **B (simplicity & state)** | conditional | sonnet | yes |
| 5 | Principles — lens **C (robustness & ops)** | conditional | sonnet | yes |

**Substitution note (slot 1).** The skill dispatches Bugbot by `subagent_type: bugbot`. That agent
type is **not available in this harness** — the available types are `claude`, `claude-code-guide`,
`Explore`, `general-purpose`, `Plan`, `statusline-setup`. The slot is therefore run as
`general-purpose` on Sonnet against this skill's own `bug-hunter-reviewer-prompt.md`, which exists
for exactly this fallback. The slot is **not** skipped. The same would have applied to slot 3
(`security-review`) had it been selected.

### Optional slot selection, evaluated against the diff before dispatch

- **Security — EXCLUDED.** No trigger fires. The diff touches no auth/authz, tokens, crypto, secrets
  or config, no query construction, no path or file handling, no deserialization, and no CORS/HTTP
  edge. `build.gradle.kts` is untouched, so there is no new dependency and not even the "config file
  changed" case that would make this a borderline ask. The one service added is a read-only
  enumeration of the IDE's own client sessions.
- **Adversarial — INCLUDED.** Three triggers: it changes behaviour in a class with an extensive
  existing test suite; test files were modified (KDoc on two pre-existing cases, and the fixture
  widened twice); and at ~3300 changed lines it is an order of magnitude past the >~300 threshold.
- **Lens B (simplicity & state) — INCLUDED.** Well past >~200 changed lines, and it adds new types
  (`SessionRef`, `ReviewSessionTargeting`, `ReviewClientSessions` + its nested holder,
  `FakeClientSessions`), plus new mutable state on `IdeLayoutController` (`reshowWatch`).
- **Lens C (robustness & ops) — INCLUDED.** The diff is dense with error handling and lifecycle:
  a rethrowing hide loop, a deliberately swallowing post-hide re-query, a message-bus subscription
  with arm/disarm lifecycle, and diagnostics that are the only field evidence for this class.

**No two principle reviewers share a lens** (Merged, B, C).

### Placeholder resolution

- `[DIFF_PATH]` = `/Users/tweety53/Projects/intellij-review-queue-worktrees/openspec-kan-18-focus-mode-does-not-hide-tool-windows/.superpowers/sdd/final-review.diff`
- `[PRINCIPLES_PATH]` = `/Users/tweety53/.claude/skills/myflow-do/engineering-principles.md` — confirmed to exist before dispatch.
- `[STANDARDS_PATHS]` = **empty.** This project has no `.myflow/project.md`, so there is no
  `## standards` section and **no entries resolved**. No standards file was passed to any principles
  slot, and none was substituted from another project. The Hard Invariants section is correctly empty.
- `[LENS]` = Merged / Lens B / Lens C, one per principles slot.

### Results — pass 1

**Slot 2 — Principles, lens Merged (sonnet): CLEAN.** Principles-compliant. Zero Critical, zero
Important. Confirmed independently that `build.gradle.kts` and `plugin.xml` are untouched, no
suppressions exist, and the staged files match the diff. Recorded that Hard Invariants is empty **by
design** because no standards resolved, and that it did not import rules from another project.
Two Minors, both judged deliberate tradeoffs rather than defects:
- `IdeLayoutController` now also owns the re-show-watch lifecycle on top of persistence, sweep
  orchestration, logging and restore. Suggests extracting the watch **if** another layout-adjacent
  concern is ever added. Not blocking.
- `ReviewClientSessions` is an `open` class extended by a test double rather than an interface — a
  platform-imposed workaround (an `@Service` open class fails `isLightService`), documented in its
  KDoc, so raised only as Minor.

**Slot 0 — Primary, plan alignment + code quality (sonnet): ONE Important, no code defects.**
Verified all three delta-spec requirements met scenario by scenario; confirmed every load-bearing
behaviour intact; confirmed the two pre-existing `rdserver` KDoc hits are present at the merge base
via `git show <merge-base>:…`; noted that two pre-existing suites (`ScopeSwitchTest`,
`EmptyResolveNoticeTest`) use the fixture additively with no fake sessions, so the local-only path is
still exercised. Hunted for a seventh vacuous test across all three test files and found none, giving
its reasoning per case. Judged the manual test guide to match the spec.
- **Important — `tasks.md` Task 6 still described the superseded approach** (session-owned bus; the
  deprecated two-arg `toolWindowShown` overload), and was the one task whose promised "as built"
  correction was missing, although the plan header promised it. Following it literally would build a
  watch that compiles, subscribes and silently never fires.
  → **CONTROLLER FIXED:** an "Implementation note (task 6, as built)" callout now records both
  supersessions, in the same style as tasks 2, 3 and 4, and points at the split-IDE silence limit.

**Slot 5 — Principles, Lens B (simplicity & state) (sonnet): CLEAN.** Principles-compliant. Zero
Critical, zero Important. Judged the new mutable state minimal (one nullable `MessageBusConnection`)
with its arm/disarm invariant enforced structurally rather than by convention, the selection rule
pure, and the light-service indirection a cited platform consequence rather than unearned
abstraction. Two Minors, both explicitly "not asking for a change":
- `messageBus(session)`'s nullable return conflates "which bus to watch" with "does this session
  still exist"; since the bus is always the project's, the null-ness does all the work. Disclosed in
  its KDoc as deliberate; an existence check plus a constant would name the two facts separately.
- `getInstance`'s two-branch lookup runs a `getServiceIfCreated` that can only be non-null in a test
  that called `replaceService`. Minimal shape available given light services must be final while the
  seam must stay `open`; noted only because the KDoc is what makes it legible.

**Slot 1 — Bug hunter (sonnet, substituted per the note above): THREE Important.** Confirmed the core
KAN-18 scoping itself is correct throughout — none of these is a scoping regression.
1. **`armReshowWatch` is not exception-safe** (`IdeLayoutController.kt:477-504`, esp. 480/484/486).
   `messageBus()` resolves `getProjectSession(...)?.project?.messageBus` unguarded, and both calls can
   throw `AlreadyDisposedException` for a project or session mid-teardown. If the target session
   disconnects between enumeration and arming — i.e. AFTER the hide loop and post-hide check have both
   already succeeded — the throw propagates out of `sweep`, `hideForReview` and
   `ReviewSessionService.start()`, so an already-correct hide surfaces to the user as a failure. This
   directly contradicts the class's own stated rule, written a few lines above at 303-309: *a purely
   diagnostic check must never turn a successful hide into a broken `hideForReview()`*. Coverage only
   exercises `messageBus` returning `null`, never throwing.
2. **`restore()`'s `window.show(null)` is unguarded, and the multi-session loop is NEW blast radius**
   (`IdeLayoutController.kt:404-430`, esp. 422). The omission predates KAN-18, but previously a throw
   aborted the only loop there was; now a throw in an EARLIER session means no later session is ever
   entered. Scenario: `[LOCAL, FRONTEND]`, a plugin disposed `Database`'s content, `LOCAL` throws
   first → the frontend the reviewer is actually looking at is never restored, `hiddenByReview` is
   never reassigned, and the failure repeats on every future restore. There is no `throwsOnShow`
   fixture flag, confirming this path was never exercised.
3. **The watch's `MessageBusConnection` is not tied to a `Disposable`** (`IdeLayoutController.kt:440`,
   `:484`, `:503`). Bare `connect()`, where this codebase's own comparable subscription
   (`ReviewSessionService.kt:108`) uses `connect(this)`. If a pass is armed and the plugin is
   dynamically reloaded while the project stays open, nothing disconnects it, and the listener plus
   the old plugin classloader stay reachable from the live bus — the classloader-leak pattern the
   platform's dynamic-unload verification looks for.

**Slot 5 — Principles, Lens C (robustness & ops) (sonnet): CLEAN.** Principles-compliant. Zero
Critical, zero Important. Confirmed both opposite error-handling policies (rethrow in the hide loop,
swallow in the post-hide re-query) are preserved and extended consistently with session context; that
the new session-enumeration dependency degrades gracefully in every case it could construct (empty
list, guest-only, session vanished before arming — the last explicitly tested); and that the persisted
record recovers from a partly-failed pass because it is written in full before any `hide()`.
One Minor, which **independently corroborates the bug hunter's finding 3**: the
`MessageBusConnection` is created with the no-arg `connect()`, so teardown rests entirely on
arm/disarm symmetry rather than a structural guarantee. It traced every current call path and
confirmed today's code never double-arms and never outlives a `restore()`; the unhandled case is
abandonment (project closed or IDE quit mid-pass), where cleanup happens only because the *bus* dies.
It notes pointedly that every other departure from platform convention in this diff carries an
explicit KDoc justification and this one does not. Graded Minor here, Important by slot 1 — being
found twice from different angles, it is going into the fix wave.

**Slot 4 — Adversarial (sonnet): CLEAN.** Zero Critical, zero Important. The strongest evidence in the
panel: it ran **ten targeted production-line reverts**, each restored before the next, verifying the
final tree clean via `git diff --stat -- src/` = empty. Every load-bearing behaviour failed its
corresponding test when broken, and nothing failed spuriously — hide-session entry (2 tests),
restore breadth/union (4 tests), disarm-before-show ordering (1), once-per-id dedup (1), guest
exclusion (2). **It hunted for a seventh vacuous test by both static analysis and empirical revert and
could not find one.** Confirmed every "corrected during implementation" claim in `design.md` matches
the shipped code. Two Minors:
- `restore()`'s KDoc overstates why the manager is re-fetched per session: `getInstance(project)`
  returns the same project-level singleton whenever fetched; it is `getToolWindow`/`hideToolWindow`
  that read `ClientId.current` at call time. Correct code, misleading justification.
- Four of the sixteen new cases verify **log text**, not the scoping mechanism — reverting the
  `withExplicitClientId` wrap leaves them green. Not vacuous (each fails under its own defect), but
  the scoping story is really carried by two tests, not six. Recorded as coverage context.

## Pass 1 verdict

| Slot | Critical | Important | Minor |
|------|----------|-----------|-------|
| 0 Primary | 0 | 1 (controller-fixed: `tasks.md` task 6 note) | 0 |
| 1 Bug hunter | 0 | **3** | 0 |
| 2 Principles Merged | 0 | 0 | 2 |
| 4 Adversarial | 0 | 0 | 2 |
| 5 Principles Lens B | 0 | 0 | 2 |
| 5 Principles Lens C | 0 | 0 | 1 (corroborates bug hunter 3) |

**Open at end of pass 1: the bug hunter's three Important findings.** Deduped by file:line + theme
against every other slot: Lens C's Minor is the same defect as bug hunter 3 and is folded into it; no
other finding overlaps. One fix subagent takes the combined list, per the "one fix dispatch, not one
fixer per finding" rule. FIX_BASE tree: `bb1770adc51f7809e008d749e35e7c060a886fd7`.

---

## Pass 2 — FULL re-run (escalated automatically)

**Escalated, not asked, and here is why — two independent triggers fired:**
1. **The fix diff is 279 changed lines** (245 added, 34 deleted), well past the ~150 threshold.
2. **It altered a public contract:** `IdeLayoutController` now implements `Disposable`, changing the
   class's supertypes; and `restore()`'s record contract was deliberately reworded from "resolved in
   no session" to "not reopened in any session" so an id whose `show()` threw stays on the record.

Targeting is a cost optimisation, never a coverage waiver, so **every slot in this run's roster
re-runs** against a rewritten `final-review.diff` — not just the slots that raised findings.

### What the fix wave changed (given to each slot so it can aim, not so it can skip)
- `armReshowWatch` now catches `Throwable`, logs at `warn` and leaves the watch unarmed, mirroring
  `logPostHideVerification`'s existing swallow-the-diagnostic rule.
- `restore()` isolates a throwing `window.show(null)` so one bad window cannot stop later sessions
  being restored. **Deliberate decision to scrutinise:** an id whose `show()` threw **stays on the
  record**.
- The watch's `MessageBusConnection` is now parented to a `Disposable`; `IdeLayoutController`
  implements `Disposable` with a deliberately **empty** `dispose()`.
- `restore()`'s KDoc justification for re-resolving the manager per session was corrected.
- Fixture gained a `throwsOnShow` flag (additive, mirroring `throwsOnHide`).
- Suite: **254 tests, 0 failures** (was 252).

### Results — pass 2

**Slot 2 — Principles, lens Merged (sonnet): ONE Important (escalated from its own pass-1 Minor).**
All global constraints re-checked mechanically and clean; Hard Invariants again empty by design, and it
confirmed for itself that no standards file exists rather than assuming.
- **Important — SRP.** `IdeLayoutController` now changes for five independent reasons: persistence
  schema, sweep/hide semantics, diagnostic log text (itself pinned by string-matching tests), restore
  semantics, and — new since pass 1 — the re-show-watch lifecycle plus its `Disposable` wiring. The
  class grew 297 → 598 lines. It graded this Minor in pass 1 and explicitly said extraction would be
  warranted **if another layout-adjacent concern were added**; the fix wave added one, so the
  escalation is consistent with its earlier reasoning rather than a reversal. Its objection is not
  the method count but that a **platform lifecycle interface** was pulled onto the class whose sole
  justification is one collaborator's subscription — a real cost-to-undo signal.
  Proposed fix: extract `reshowWatch` + `armReshowWatch` + `disarmReshowWatch` + the `Disposable`
  implementation into a `ReshowWatch` collaborator with `arm(target, hidden)` / `disarm()`, called
  from the same two sites, after which `IdeLayoutController` need not implement `Disposable` at all.
- Minor (carried, unmoved): `ReviewClientSessions` is an `open` class rather than an interface — a
  platform-imposed workaround; it notes fixing it would only trade one workaround for another.
- Minor: the empty `dispose()` is mildly astonishing but thoroughly documented; it observes this would
  be resolved for free by the extraction above.

**Slot 5 — Principles, Lens B (simplicity & state) (sonnet): CLEAN.** Zero Critical, zero Important.
**It reaches the opposite conclusion to the Merged lens on the `Disposable`,** and with evidence:
`ReviewSessionService` in this same codebase already does the identical `Disposable` + `connect(this)`
pattern, and parenting to the service rather than to `Project` directly matches standard IntelliJ
Platform guidance. It agrees the lifecycle is not obvious from the type alone, but argues that is not
the bar simplicity sets — the question is whether the mechanism is justified and not duplicated as an
ad-hoc variant, and it is neither.
Asked explicitly to rule on the record now having two causes, it judged the record's meaning still
**single and clear** — "still owed a restore", where the cause is irrelevant to what any caller does
with the fact — and noted the KDoc says so outright, so it is not an undocumented conflation.
Three Minors, none asking for a change: the Disposer-parenting rationale is stated authoritatively in
two KDocs (a `@see` would remove the duplication); and the two carried pass-1 Minors re-examined and
**explicitly unchanged in severity**.

**Slot 1 — Bug hunter (sonnet): all three pass-1 findings VERIFIED FIXED; ONE new Important, caused by
the fix wave itself.**
Verification of the pass-1 fixes was thorough rather than nominal: it confirmed the `try` covers every
throwing call and that `reshowWatch` is assigned *before* `subscribe`, so the `catch` can unwind a
connection created but never given a listener — no partial state; and it disassembled
`MessageBusConnectionImpl` to establish that `disconnect()` is exactly `Disposer.dispose(this)` and is
idempotent, so there is no double-dispose and no connection can outlive its parent, which also makes
the empty `dispose()` legitimate.
- **Important — the leftover-reclaim path silently drops an id that `restore()` had just decided to
  keep.** `hideForReview()` calls `restore()` for a leftover record, then falls through to `sweep()`,
  whose `myState.hiddenByReview = hidden.toMutableList()` **overwrites the record wholesale**.
  Scenario: a third-party plugin disposes `Database`'s content mid-pass, so `show()` always throws and
  the window stays registered but invisible. `restore()` correctly keeps `Database` on the record;
  `sweep()` then recomputes visibility, finds `Database` invisible, excludes it, and overwrites the
  record — so `Database` vanishes silently, stays hidden, and no future `restore()` or startup replay
  will ever attempt it again.
  **This is the exact failure `LEGACY_IDS` pruning exists to prevent — except that pruning is
  deliberate, logged and documented, and this loss is incidental and silent.** It is specific to the
  "resolves but `show()` threw" branch that fix wave 1 introduced; the "does not resolve at all" branch
  is handled correctly today. No test covers it: the leftover-reclaim test uses a non-throwing
  leftover, and the throwing-show test calls `restore()` directly, never through `hideForReview()`.
- No other code defects: no lambda or sequence escapes any `withExplicitClientId`; the listener closure
  reads nothing scope-sensitive; nothing can re-arm mid-loop; no shared mutable state crosses the
  message-bus callback and the EDT path.

**Slot 5 — Principles, Lens C (robustness & ops) (sonnet): ONE CRITICAL — independently the same
defect the bug hunter found, graded higher, with two facts that slot did not have.**
- **Critical — the reclaim-then-sweep interaction silently discards a window from the persisted
  record.** Same mechanism: `restore()` keeps an id whose `show()` threw, then `sweep()`'s
  `myState.hiddenByReview = hidden.toMutableList()` overwrites the record and the id is gone.
  Two additions beyond the bug hunter's report:
  1. **It contradicts a written delta-spec requirement**, not just a KDoc —
     `specs/review-layout-management/spec.md`: *"An id that resolves nowhere stays on the record …
     a later restore reopens it."* The "later restore" that happens inside the *same*
     `hideForReview()` call silently erases that promise.
  2. **The fix wave moved the gap rather than closing it.** Before fix wave 1, `restore()` had no
     catch at all, so this scenario threw and propagated — a loud, blocking failure. Fix wave 1
     traded it for a quiet, silently-losing one.
  Its fix direction matches the bug hunter's: union `hidden` with whatever remained on the record
  going into the sweep, plus a regression test pairing `throwsOnShow` with the leftover-reclaim path.
- **Zero Important, zero Minor — and it said so deliberately**, noting it would not manufacture
  Minors to fill the section.
- **It validates the fix wave's other two items against the Merged lens's objection.** It disassembled
  `MessageBusConnectionImpl` against this project's own IU-2026.2 jar to confirm `disconnect()` is
  `Disposer.dispose(this)`, so repeated arm/disarm cycles cannot accumulate orphaned Disposer
  children; confirmed `ReviewSessionService` uses the identical `connect(this)` + `@Service Disposable`
  pattern; and confirmed project close, IDE quit and dynamic plugin reload are all covered by
  `serviceParentDisposable`. It also verified `armReshowWatch`'s catch is scoped to exactly the three
  platform calls and that the rethrow-vs-swallow split survived the restructuring intact.

### Adjudication — the Merged lens's SRP Important (contested)

**Ruling: PARKED, with the extraction recorded as a follow-up. The code stands for this change.**

The panel disagreed with itself here, and the disagreement is the reason for this ruling rather than
an obstacle to it. The Merged lens graded the `Disposable` supertype an Important on the premise that
it is *"a platform lifecycle interface pulled onto the class whose sole justification is one
collaborator's subscription"*. Two other slots examined that same premise directly and contradicted
it, with evidence rather than preference:

- **Lens B** found the pattern already established in this codebase: `ReviewSessionService` does the
  identical `@Service Disposable` + `connect(this)`, and parenting to the service rather than to
  `Project` matches standard IntelliJ Platform guidance.
- **Lens C** went further and disassembled `MessageBusConnectionImpl` against this project's own
  IU-2026.2 jar, confirming `disconnect()` is `Disposer.dispose(this)`, that arm/disarm cycles cannot
  accumulate orphaned Disposer children, and that project close, IDE quit and dynamic plugin reload
  are all covered. It rated the design **sound, with zero findings**, and said explicitly that it
  would not manufacture Minors to fill the section.

So the escalation's premise does not hold: the supertype is conventional here, not novel. What remains
is a genuine but ordinary structural observation — `IdeLayoutController` is 598 lines across five axes
of change — and the Merged lens itself, in pass 1, said extraction would be warranted *"if a future
change adds another layout-adjacent concern"*, which is a forward-looking trigger, not a defect in
this diff.

Parked because it is **real but nothing downstream builds on it**: no task in this change depends on
the extraction, no behaviour is wrong without it, and every reviewer that examined the lifecycle for
*correctness* found it correct. Doing a structural refactor of the change's central class as the last
act of the change — after a Critical has just been found in this very area — would add risk to the one
file the panel has been scrutinising, for a benefit that is stylistic in this diff and only becomes
material at the next layout-adjacent feature.

**Follow-up, for whoever adds that next feature:** extract `reshowWatch`, `armReshowWatch`,
`disarmReshowWatch` and the `Disposable` implementation into a `ReshowWatch` collaborator exposing
`arm(target, hidden)` / `disarm()`, called from the same two sites. `IdeLayoutController` then need not
implement `Disposable` at all, and the empty `dispose()` disappears with it.

This ruling is surfaced to the operator in the handoff rather than buried here.

**Slot 0 — Primary (sonnet): zero Critical, zero Important, two Minors — but it examined the disputed
path and reached the opposite conclusion, which is worth recording precisely.**
It verified all six fix-wave items, walked the delta spec requirement by requirement (all three ADDED
requirements and their 12 scenarios have a correctly-asserting test), confirmed the two disclosed
limitations are stated identically in `design.md`, `proposal.md`, the delta spec and the manual test
guide with no drift, and confirmed `tasks.md`'s Task 6 as-built note is present and accurate. It ran
its own mutation experiments — removing `restore()`'s try/catch and `armReshowWatch`'s try/catch in
turn, restoring byte-for-byte after each — and found each caught by exactly one dedicated test, with no
eighth vacuous test. It also honestly reported and dismissed a false alarm from stale build output.

**Where it disagrees with slots 1 and 5C:** it traced the same "can a kept id latch the leftover-reclaim
branch" question and concluded *"No latch — `sweep()` unconditionally overwrites the record, so a
leftover id from a failed restore can never permanently block the next hide; it just gets
superseded."* That is the same mechanism the other two slots identified, judged benign. **The
disagreement is about the consequence, not the mechanism, and on that the other two are right:**
"superseded" here means the id is silently dropped while its window stays hidden, so nothing will ever
retry it. Not blocking the next hide is true and is not the harm being claimed.

Two Minors:
1. The manual test guide's automated-state line says 252 tests; the tree now has 254.
2. The delta spec's forget rule says an id is forgotten "once it resolved to a registered tool window
   in at least one session", which read literally means "as soon as `getToolWindow` returns non-null".
   The shipped code additionally requires `show()` to have **succeeded**. It judged the code's
   behaviour the correct and safer one and asked for the prose to be tightened to match — which is
   directly entangled with the Critical above, so both are being fixed together.

**Slot 4 — Adversarial (sonnet): ONE CRITICAL — the same defect, this time REPRODUCED.**
It re-verified that both new tests genuinely bite by reverting each fix and watching exactly one
dedicated test fail; re-verified the hide loop's rethrow and the disarm-before-show ordering, both
adjacent to changed code, still fail correctly when broken; and found no eighth vacuous test.
- **Critical — same reclaim-then-sweep defect, confirmed with a temporary probe test** (a `Stuck`
  window registered `throwsOnShow = true` alongside a normally-visible `Project`). Result:
  `controller.state.hiddenByReview == ["Project"]` — `Stuck` gone from the record while still hidden on
  screen. Probe removed and the tree verified clean afterwards. Its framing: the fix wave's own
  headline change is *"undone by an existing, unmodified line in `sweep()` the moment `hideForReview()`
  is called again, which is the normal way a second review pass starts."*
- Zero Important beyond it. It cleared the empty `dispose()` on the same evidence as Lens C, and
  confirmed `throwsOnShow` is genuinely additive: it was inserted *before* the pre-existing
  `resolvesOnlyIn` parameter, but every call site in the suite uses named arguments, so no positional
  shift occurred.
- Noted transient Gradle daemon flakiness in this sandbox under rapid repeated invocations
  (`EOFException`/`NoClassDefFoundError`/`ZipException`), reproduced as tooling-only and cleared on
  retry with the tree unchanged. Recorded so a later reader does not mistake it for a source problem.

### Pass 2 verdict

| Slot | Critical | Important | Minor |
|------|----------|-----------|-------|
| 0 Primary | 0 | 0 | 2 |
| 1 Bug hunter | 0 | 1 (the reclaim/sweep defect) | 0 |
| 2 Principles Merged | 0 | 1 (SRP — **adjudicated and parked**, see above) | 2 |
| 4 Adversarial | **1** (reproduced) | 0 | 0 |
| 5 Principles Lens B | 0 | 0 | 3 |
| 5 Principles Lens C | **1** | 0 | 0 |

**Three of six slots found the reclaim-then-sweep defect independently** — the bug hunter by tracing,
Lens C by robustness analysis, the adversarial slot by reproducing it. The primary slot examined the
same code path and judged it benign; the probe test settles that disagreement against it. This is the
value of the panel's breadth: one strong reviewer would plausibly have returned the primary's verdict.

**Fix wave 2 dispatched** (one subagent, opus) with the Critical plus the primary's spec-prose Minor,
which is entangled with it. FIX_BASE tree: `14584a5`.

---

## Pass 3 — FULL re-run (escalated automatically, again)

**Two triggers, same rule as pass 2, and neither was a judgement call:**
1. **The fix diff is 180 changed lines** (173 added, 7 deleted), past the ~150 threshold.
2. **It altered the delta spec** — the restore requirement gained three normative paragraphs and two
   scenarios.

### What fix wave 2 changed
- `hideForReview()` reads back what the reclaim `restore()` could not reopen and hands it to `sweep()`,
  which now **unions** it into the record instead of overwriting. RED was literally the reviewer's own
  probe result: `expected:<[Stuck, Project]> but was:<[Project]>`.
- A new `logCarriedForward` `warn` names the carried ids, the manager and the session — silent on the
  normal path — so an operator can tell "this pass hid it" from "an earlier pass hid it and nothing has
  reopened it since". The absence of exactly this signal is what made the defect invisible.
- The delta spec's restore requirement now says **reopened** (resolved *and* shown without error), with
  normative paragraphs for the two causes of "not reopened", the sweep's union rule, and the anti-latch
  guarantee; plus two scenarios, each mapping 1:1 onto a shipped test.
- Suite: **256 tests, 0 failures** (was 254).

**Anti-hollow-test evidence the fixer volunteered:** the second new test's log assertion was never
reached during RED, so it proved it separately — with the union in place and only the log call removed,
it was the single failing test in the class. Backup restored byte-for-byte and the tree verified clean.

### Results — pass 3

**Slot 5 — Principles, Lens B (simplicity & state) (sonnet): CLEAN.** Zero Critical, zero Important.
It answered each question put to it by tracing the code rather than trusting the KDoc:
- **Idempotency holds.** A second `hideForReview()` reclaims, recomputes `outstanding` from what is
  still unresolved, and re-sweeps, converging to the same state as a single call over the same visible
  set. A second `restore()` is a no-op when the record is empty. Both converge.
- **The record cannot grow unbounded.** A stuck id is deduplicated at each union
  (`filterNot { it in hidden }`, and `restore()`'s `filterNot { it in resolved }`), so it occupies one
  slot indefinitely rather than accumulating. Growth is bounded by the number of *distinct* stuck tool
  windows — which the IDE bounds — not by the number of passes.
- **Its standing ruling on the record's meaning HOLDS, restated more precisely.** Fix wave 2 adds a
  third way for an id to be on the record, but every entry still means exactly *"this plugin hid it,
  now or on an earlier pass, and nothing has reopened it."* The stored value has one meaning; the
  narrative of how an id got there now has three cases, and that narrative correctly lives in the
  **log** rather than in the data model — encoding "why" into persisted state would cost
  single-source-of-truth and a persistence-format change for no behavioural gain.
- One new Minor, not asking for a change: `sweep`'s `outstanding` parameter carries a real sequencing
  contract enforced only by straight-line ordering in one function plus KDoc, not by the type system.
  It judged this the better of the two available designs — an explicit parameter with same-function
  data flow, rather than implicit shared state — and low-risk because `sweep` is private with one call
  site. Three standing Minors from earlier passes re-checked and explicitly unchanged.

**Slot 5 — Principles, Lens C (robustness & ops) (sonnet): CLEAN.** Zero Critical, zero Important —
**the slot that raised the pass-2 Critical now judges it genuinely closed, not moved again.** It traced
the whole recovery path: a permanently-stuck window is carried forward every pass, reported every time,
and never blocks the sweep; an IDE quit mid-pass is covered by the record surviving restart and
`ReviewLayoutRestorer` calling the broadened `restore()`; and by induction over the three write points
(`loadState`, `sweep`, `restore`) the record cannot accumulate duplicates and is bounded by the
universe of distinct tool-window ids. Converges; does not thrash. It re-verified the anti-latch
guarantee, that both opposite error-handling policies survived this second restructuring of the same
method, and that record-before-hide is preserved.
One Minor (coverage, not a defect): no test runs `hideForReview()` three or more times with the same
permanently-unrecoverable id to pin that the record *settles* rather than merely surviving one round
trip. It states the code is structurally safe regardless and shows why.

**Slot 2 — Principles, lens Merged (sonnet): CLEAN.** Zero Critical, zero Important. Asked explicitly
whether the class's growth had crossed a line the parked ruling did not cover, it answered **no**: fix
wave 2 adds one small single-purpose method and threads one extra parameter along an axis the class
already owned, rather than layering a new responsibility. **It declined to re-raise the parked finding**
and confirmed the follow-up remains the right place to revisit the class's shape.
One Minor, carried forward unchanged and explicitly *not* escalated: `ReviewClientSessions` could be an
interface plus a private impl the `Default` holder constructs, which would satisfy the platform's
final-class rule *and* let the test double implement rather than subclass. Offered as a cheaper shape
for a future pass, not a defect.

**Slot 1 — Bug hunter (sonnet): ONE NEW CRITICAL — same failure class as pass 2's, reached by a
different path that fix wave 2 did not touch.**
- **Critical — `restore()`'s cross-session `resolved` set forgets an id that is still hidden in the
  session it was hidden in.** `IdeLayoutController.restore()` accumulates one `resolved` set across
  the whole session loop and writes `recorded.filterNot { it in resolved }`. Nothing removes an id
  from `resolved` when a *later* session's `show()` throws for that same id — the per-id catch fix
  wave 1 added only logs. So an id is forgotten **the instant it succeeds in any one session**, even
  if it failed in every other session it resolved in.
  Scenario, in exactly the split/remote-dev configuration this change targets: a pass hides `Database`
  while sweeping `FRONTEND`. At the end of the pass `restore()` iterates `[LOCAL, FRONTEND]`. Under
  `LOCAL`, `getToolWindow` resolves the host `ToolWindowImpl` and `show()` succeeds → `resolved`.
  Under `FRONTEND`, the same id resolves to that client's **different** `BackendToolWindow` object —
  per `design.md`'s own bytecode analysis of the extractor-mode branch — and its `show()` throws. The
  id is dropped from the record while still hidden in the session the reviewer is looking at, and
  **nothing can recover it**: fix wave 2's union only helps if the id is still on the record when
  `hideForReview()` reads it back, and `restore()` has already erased it.
- **The fixture cannot currently express this case**, which is why no test caught it:
  `RecordingToolWindowManager` keys one window object per id shared across sessions, and
  `throwsOnShow` is a single flag on that object — not session-scoped the way `resolvesOnlyIn` is. The
  real platform's extractor-mode branching is what makes the mixed outcome possible.
- Its fix sketch: also track `failedAnywhere` in the catch, and forget only when
  `it in resolved && it !in failedAnywhere`. That changes only the final record write, not the show
  attempts.
- Everything else it attacked came back clean, with reasoning: the `target == null` and empty-record
  paths lose nothing; `carriedForward = outstanding.filterNot { it in hidden }` prevents double-count;
  a stuck id is one entry per pass and never gates the sweep; the record readback is a plain field
  read so being outside `withExplicitClientId` is safe; `messageBus()` takes the target's `clientId`
  explicitly rather than reading `ClientId.current`; the watch callback reads only captured immutable
  state; and no new unguarded throw was introduced.

**Slot 0 — Primary (sonnet): CLEAN.** Zero Critical, zero Important, zero Minor. It ran three test
classes green (7/7, 14/14, 34/34) after clearing the disclosed Gradle flakiness; verified the union
cannot latch and that `LEGACY_IDS` cannot be resurrected (`outstanding` only ever holds ids already on
the post-prune record); confirmed record-before-hide; mapped **every** delta-spec requirement and
scenario to a named test in a table; confirmed no planning artifact still asserts a superseded claim;
and hunted a ninth vacuous test three ways — mentally reverting the overwrite, a scripted check for
tests that never invoke the method under test, and an audit of substring `contains` assertions for
coincidental matches. It found none, and volunteered one soft observation it explicitly declined to
inflate into a Minor.
**Note the divergence, again:** it lists `restore()`'s "cross-session union via a single `resolved` set
accumulated across the session loop" among the load-bearing behaviours it verified as *retained* — the
very mechanism the bug hunter identified as this pass's Critical. Two slots, same code, opposite
readings, for the second pass running.

**Slot 4 — Adversarial (sonnet): no Critical; ONE Important (a coverage hole, not a shipped defect).**
It reproduced the pass-2 probe and confirmed it now behaves correctly; verified both wave-2 tests bite
by reverting each production line in isolation; and **independently verified the fixer's own claim**
that it had to prove the log assertion separately because RED never reached it — confirming JUnit
aborts at the first assertion, so the fixer's isolated reversion was genuinely the only way that
assertion was ever driven RED. It also traced the "user manually reopened the window" race and found
`carriedForward = outstanding.filterNot { it in hidden }` is **load-bearing rather than defensive**
there, and it holds.
- **Important — `logCarriedForward`'s "silent on the normal path" guarantee is untested.** It mutated
  the call to pass `hidden` instead of `carriedForward` — simulating the diagnostic firing on every
  ordinary pass — and **255 of 256 tests still passed.** The one failure was incidental: a substring
  that happened not to collide. Every other test either ignores log output or uses `any { }` checks
  that cannot detect an *extra* spurious warning. The design's own text says a diagnostic that fires
  on the normal path stops being read, so this is the operationally important half of the new logging,
  currently protected only by coincidence. Its fix: a plain-hide case asserting
  `warnings.none { it.contains("carrying") }`.
- Noted, explicitly not raised: if a plugin owning a stuck window is uninstalled, its id has no path
  off the record short of a `LEGACY_IDS` entry — pre-existing, and the spec's stated intent.

### Pass 3 verdict

| Slot | Critical | Important | Minor |
|------|----------|-----------|-------|
| 0 Primary | 0 | 0 | 0 |
| 1 Bug hunter | **1** | 0 | 0 |
| 2 Principles Merged | 0 | 0 | 1 |
| 4 Adversarial | 0 | **1** | 0 |
| 5 Principles Lens B | 0 | 0 | 4 |
| 5 Principles Lens C | 0 | 0 | 1 |

**Fix wave 3 dispatched** (one subagent, opus) with both open findings. FIX_BASE tree: `7f39cb33b7e1ea9d867d945d2debf4cf7bfce8b1`.
Note again that the primary and adversarial slots both examined `restore()`'s cross-session
`resolved` set this pass and did not flag it — the primary even listed it among behaviours verified
as correctly retained — while the bug hunter identified it as a Critical. **Second consecutive pass in
which a single slot caught a real data-loss defect the others cleared.**

---

## Pass 4 — FULL re-run (escalated automatically; three triggers)

1. The fix diff is **242 changed lines** (>~150).
2. It **altered the delta spec** again (a new normative paragraph and scenario for the mixed outcome).
3. **Three fix rounds have now run** — itself an escalation trigger.

**This is the third full re-run, and it is justified by evidence rather than ceremony:** passes 2 and 3
each surfaced a genuine data-loss Critical that four of six slots had cleared. The code area is
demonstrably subtle, and each previous round's fix opened the next defect.

### What fix wave 3 changed
- `restore()` now tracks **`failedAnywhere`** alongside `resolved`, and forgets an id only when
  `it in resolved && it !in failedAnywhere`. Show attempts are unchanged; only the final record write
  differs.
- **A deliberate asymmetry to scrutinise:** a session where a recorded id **does not resolve** does
  *not* count toward `failedAnywhere` — only a resolve-then-throw does. Otherwise the fix would have
  broken `testRestoreForgetsAnIdThatResolvedInOnlyOneOfTwoSessions`, the existing split-IDE contract.
  Written into both the `restore()` KDoc and the new normative spec paragraph.
- The fixture's `throwsOnShow` is now **session-scoped**, mirroring `resolvesOnlyIn`, additive — the
  fixture previously could not express the mixed outcome, which is why no test caught the Critical.
- A new test pins that the carry-forward diagnostic is **silent on the normal path**.
- Suite: **259 tests, 0 failures** (was 256).

**Mutation proof the fixer supplied and I am recording rather than assuming:** reverting the filter to
`resolved`-only made the new mixed-outcome test the *single* failure in the class; passing `hidden` to
`logCarriedForward` produced 2 failures including the new silence test on its `none` assertion. Tree
restored byte-for-byte, `build/test-results` cleared, suite re-run green, no probe left behind.

### Results — pass 4

**Slot 2 — Principles, lens Merged (sonnet): CLEAN.** Zero Critical, zero Important. It verified every
global constraint by direct inspection with line numbers rather than inference — including that the
record write precedes the hide loop, that the two `rdserver` hits are pre-existing KDoc prose, and that
no `plugin.xml` entry exists for any of the three new or touched classes.
Asked whether `restore()`'s two-set decision had become a rule that only survives because tests pin it,
it answered **no**, and showed its working: `reopened` and `failedAnywhere` are two disjoint per-(id,
session) outcomes built in **one** loop and consumed in **one** filter immediately after, so
`filterNot { it in reopened && it !in failedAnywhere }` reads directly as its own English rule and
matches the normative spec text. It contrasted this with state threaded through several methods, which
is what it would have objected to. It judged the tests specification-by-example for a genuinely
irreducible three-way outcome, not scaffolding propping up an opaque rule.
It **declined to re-raise the parked SRP finding**, noting nothing in this wave grew the class's reasons
to change. One carried Minor restated for continuity only. One documentation-weight observation it
explicitly declined to raise as a finding: `restore()`'s KDoc is now ~4x its body, though this wave's
growth was entirely KDoc rather than logic.

**Slot 5 — Principles, Lens B (simplicity & state) (sonnet): CLEAN.** Zero Critical, zero Important;
four standing Minors re-checked and explicitly unchanged.
Its central argument, which settles the "is two sets too much state" question: the requirement is **two
independent binary facts** per id ("reopened somewhere" AND "failed somewhere"), and they are not
mutually exclusive — the whole point of the fix is the case where both are true. A single set with
"add on failure / remove on success" collapses to whichever event was processed **last**, which is
order-dependent and wrong. Two monotonic accumulators combined by one boolean at the end is
order-independent and is therefore genuinely the minimum, not an accretion.
It confirmed the record's meaning is unchanged (the sets are locals, never persisted; `restore()` still
only ever removes), that the asymmetry is documented **at the point of use** rather than only in
`design.md`, and that idempotency and boundedness both survive because the wave only narrows *when* an
id may be removed and adds no new path for growth.

**Slot 5 — Principles, Lens C (robustness & ops) (sonnet): CLEAN.** Zero Critical, zero Important —
the slot that raised the pass-2 Critical and warned once that a fix had "moved rather than closed" the
gap now confirms **this one is genuinely closed**. It verified the anti-latch, fail-fast/swallow split,
boundedness, `LEGACY_IDS` pruning and record-before-hide invariants all intact through a third
restructuring, and confirmed the new silence test uses a positive control so its negative assertion is
not vacuous.
It traced one deeper question at length and **explicitly declined to raise it**, which is the right
call: because the record is flat and session-agnostic by design, a session where the id was never
hidden supplies an essentially-guaranteed `show()` that counts as "reopened". It confirmed this is
exactly what the spec requires and what `design.md` deliberately chose over a session listener plus
retry path — a knowingly-taken tradeoff, not a violation this diff introduced.
Two Minors: "resolved nowhere" has no immediate log signal (it surfaces only at the next sweep's
`logCarriedForward`, so an operator sees nothing if no further pass ever runs — costs observability,
not correctness); and one new test relies on an unexplained service-singleton aliasing trick to reset
state between its two halves, which a future reader could "clean up" and silently break.

**Slot 0 — Primary (sonnet): CLEAN.** Zero Critical, zero Important. Mapped every spec scenario to a
named test in a table, re-verified every global constraint and load-bearing behaviour by direct line
reading, independently traced both of fix wave 3's mutations rather than trusting the fixer's log, and
hunted a twelfth vacuous test by checking every silence assertion for a positive control — finding
each one has it. Two Minors:
- **Spec prose ambiguity** — "Both causes SHALL be given the same answer: … and the failure SHALL be
  reported" reads as if reporting applies to *both* causes, which would contradict the paragraph above
  it and the shipped code. The scenarios disambiguate correctly, so this misleads only a reader working
  from the requirement text alone. → **CONTROLLER FIXED:** the sentence now separates "the same answer
  *about the record*" from the reporting rule, which differs by cause.
- The new silence test's bare `controllerWithRecord()` call, whose load-bearing purpose is its
  `loadState` side effect on the project-service singleton, has no comment saying so — a future reader
  could remove it as dead code and silently break the test's isolation. **Lens C independently flagged
  the same line**, so two slots agree.

**Slot 1 — Bug hunter (sonnet): A THIRD CONSECUTIVE CRITICAL, and this one collides with a documented
design decision rather than with the code.**
It first confirmed fix wave 3's asymmetry is **correct** and matches the spec exactly. Then it went a
level deeper, decompiling `BackendServerToolWindowManager`, `BackendToolWindow` and `ToolWindowImpl`
from the real IU-2026.2 jars:
- `hideToolWindow` skips `super` under a non-local session, so hiding as `FRONTEND` never flips the
  host `WindowInfo` — only that client's state.
- Under a `LOCAL` id, `getToolWindow` **always** returns the host `ToolWindowImpl`, and `show()` on it
  succeeds trivially against state that was never hidden — **a silent no-op success**.
- For extraction-mode windows, `BackendToolWindow` carries its own model and its `show()` enters its
  own baked-in session; only entering `FRONTEND`'s own id can clear that client's `Hidden`.
**Sequence:** hide as `FRONTEND` → quit mid-pass → restart → `ReviewLayoutRestorer` runs while only
`LOCAL` exists → `show()` under `LOCAL` "succeeds" → id enters `reopened`, not `failedAnywhere` →
**dropped from the record** → the frontend later reattaches with its window still hidden and nothing
left to retry it.
It notes this is **not** the parked Open Question, and that it is precisely the failure `design.md`'s
own *Considered* section says the rejected single-target-restore alternative would cause — reached
anyway, because the always-present `LOCAL` session can "succeed" without doing anything.
Its other three hunt directions came back clean (no inverse failure; `LEGACY_IDS` cannot resurrect or
be dodged; scope/re-entrancy/threading invariants hold). It ran 58/58 green with `--no-daemon` after
clearing the documented sandbox flakiness, and correctly treated one transient failure as noise after
it did not reproduce.

**Slot 4 — Adversarial (sonnet): CLEAN for this wave. Zero Critical, zero Important.**
It **reproduced both of fix wave 3's mutation claims exactly** rather than reading them (reverting the
filter → exactly one failure, the named test; passing `hidden` to `logCarriedForward` → exactly two),
reverted each and verified `git diff` empty, and added a third mutation of its own against the
*fixture* to prove the session-scoped `throwsOnShowIn` bites in two places. It confirmed the new
silence test cannot pass for the wrong reason (`warningsWhile` allocates a fresh list per call, and the
state and visibility assertions prove the sweep genuinely ran), and that the new fixture parameter is
positionally safe because every call site uses named arguments and it was appended last with a default.

**Critically, it independently constructed the same scenario the bug hunter filed as a Critical — and
graded it differently, with reasons.** It classes it a **pre-existing architectural trade-off, not a
wave-3 regression**: the "forgotten if resolved anywhere" rule predates all three fix waves, wave 3's
`failedAnywhere` tracking only made the record-keeping *more* conservative, and the risk family is
already named and accepted in `design.md`'s Risks section. It notes closing it would require the
session-attach listener and retry path that `design.md` explicitly rejected.

## Pass 4 verdict

| Slot | Critical | Important | Minor |
|------|----------|-----------|-------|
| 0 Primary | 0 | 0 | 2 (one controller-fixed) |
| 1 Bug hunter | **1** (adjudicated below) | 0 | 0 |
| 2 Principles Merged | 0 | 0 | 1 |
| 4 Adversarial | 0 | 0 | 2 |
| 5 Principles Lens B | 0 | 0 | 4 (all standing) |
| 5 Principles Lens C | 0 | 0 | 2 |

## Adjudication — the bug hunter's pass-4 Critical

**Ruling: NOT fixed in this change. Surfaced to the operator as the headline item of the handoff,
because it is a decision about the design, not about the code.**

Two slots examined the same mechanism and agreed on the facts while disagreeing on severity. Weighing
them:

- **It is real.** The bytecode analysis is sound and extends `design.md`'s own findings rather than
  contradicting them: hiding as `FRONTEND` never flips the host `WindowInfo`, and a later `show()`
  under `LOCAL` therefore succeeds trivially against state nothing hid — a no-op the record reads as
  "reopened".
- **It is not this change's regression.** The rule it exploits predates all three fix waves. Wave 3
  made the bookkeeping strictly *more* conservative. Nothing here made it worse.
- **It collides with an approved design decision, and that is what makes it the operator's call.**
  `design.md` rejected a per-session record because *"client ids are not stable across an IDE restart,
  so the persisted record could not be replayed by `ReviewLayoutRestorer`, the one case the persistence
  exists for"*, and rejected a session listener plus retry as *"adds a session listener and a retry path
  to a class whose safety argument rests on being simple"*. The only fixes available reopen one of those
  two rejections. Choosing between them is a design decision the operator made at `/myflow-start`, and
  remaking it silently, in a fix wave, at the end of a change, would be the wrong way to revisit it.
- **One premise is unverified, and it is the load-bearing one.** The scenario requires the frontend's
  per-client `Hidden` state to *survive* the backend restart and still be set when the frontend
  reattaches. Neither slot established that; both assumed it. If per-client state is created fresh per
  session, the scenario cannot occur at all. That is checkable — and the manual test guide's section 4
  (quit mid-pass, relaunch) is exactly where to check it.

Per the SDD adjudication rules, a finding that is real and reveals a plan-level defect is **reported to
the human partner with the finding, the plan text it collides with, and the fix history** — not parked
silently and not patched around. That is what the handoff does.

**Deferred Minors, none blocking and none entering the fix loop:** the missing comment on the silence
test's `controllerWithRecord()` side-effect call (flagged by the primary *and* Lens C); "resolved
nowhere" having no immediate restore-side log signal (Lens C); the resolve-to-nothing spec sentence
having no dedicated negative test, structurally guaranteed because that branch contains no logging call
at all (adversarial); and the four standing Lens B / Merged Minors, all re-checked and unchanged.
