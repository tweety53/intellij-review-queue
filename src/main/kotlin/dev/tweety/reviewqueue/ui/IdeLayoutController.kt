package dev.tweety.reviewqueue.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.messages.MessageBusConnection

/**
 * The only component that changes the user's IDE layout.
 *
 * The set of hidden windows is persisted even though a session is not: quitting the IDE mid-review
 * would otherwise leave the Project window hidden with nothing to explain why. [ReviewLayoutRestorer]
 * replays it on the next project open.
 */
@Service(Service.Level.PROJECT)
@State(name = "ReviewQueueLayout", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class IdeLayoutController(private val project: Project) :
    PersistentStateComponent<IdeLayoutController.State>, Disposable {

    /**
     * Nothing of this class's own is torn down here. [Disposable] is implemented for one reason: to
     * be the Disposer parent [armReshowWatch] gives its [MessageBusConnection], so that the
     * subscription's lifetime is bounded by this service's rather than by [disarmReshowWatch] being
     * reached. See [armReshowWatch]'s KDoc for why that matters.
     *
     * That the parent is a real bound was checked rather than assumed:
     * `ContainerUtilKt.initializeComponentOrLightService` registers any service instance that
     * implements [Disposable] with `ComponentManagerImpl.serviceParentDisposable`, so this object is
     * disposed with its project — read with `javap -c` from `intellij.platform.ide.jar` on
     * IU-2026.2 build 262.8665.258. `ReviewSessionService` is a `@Service` [Disposable] for the same
     * reason, and parents its own subscription the same way.
     *
     * The connection is a child of this object, so the Disposer has already disconnected it by the
     * time this runs — there is deliberately no cleanup call here that could mask the parenting
     * having been dropped.
     */
    override fun dispose() = Unit

    class State {
        @JvmField
        var hiddenByReview: MutableList<String> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        // Drop ids this plugin no longer manages. Such an id is unrestorable by construction —
        // `getToolWindow` cannot resolve an unregistered window — so `restore()` would keep it on
        // the unresolved record forever, and a permanently non-empty record latches
        // `hideForReview()`'s leftover branch and silently stops hiding anything ever again.
        //
        // Deliberately a named legacy list rather than the current sweep. Pruning by "does this id
        // still resolve" would also empty any record a test seeded through this method, and the
        // general "keep ids that are not yet registered" contract that `restore()` implements has
        // to keep working. These are two different rules for two different causes.
        myState.hiddenByReview.removeAll(LEGACY_IDS)
    }

    /**
     * Hides every tool window that is currently visible, remembering which they were.
     *
     * The sweep runs inside the target session's [ClientId] (KAN-18). `ToolWindowManager`'s
     * `getToolWindow` and `hideToolWindow` both resolve the *current* client id and address that
     * session's layout, so under a split or remote-dev IDE a sweep run with no client id in scope
     * mutates the local session — which has no screen — while the frontend the reviewer is looking at
     * is never told anything. [ReviewSessionTargeting.hideTarget] chooses the one session to act on;
     * its KDoc explains why it is one session and not all of them.
     *
     * No target is a legitimate state, not a failure: a project whose only session belongs to a Code
     * With Me guest has no layout of this plugin's to hide, and the layout is left alone.
     */
    fun hideForReview() {
        // A leftover record means a previous restore could not resolve every id — most likely it ran
        // before the tool windows were registered. Reclaim it now, when the IDE is fully up, instead
        // of refusing to hide: an early return here would leave the record latched and silently stop
        // hiding anything for the rest of the run.
        //
        // This also covers the case the early return was written for. A second hide with nothing
        // restored in between would otherwise overwrite the first record with an empty one — the
        // windows are already hidden, so they drop out of the filter below and would never be
        // reopened. Restoring first puts them back in it.
        //
        // Deliberately before the target lookup: a leftover record must be reclaimed whatever session
        // is available now, including none.
        //
        // What the reclaim could *not* reopen is read back out of the record and carried into the
        // sweep. `restore()` deliberately keeps such an id — the window is still hidden and this
        // plugin hid it — and the sweep computes its own record from what is currently visible, which
        // by construction excludes a window that was never reopened. Without this the sweep's record
        // write would erase exactly what the reclaim just preserved, leaving the window hidden with
        // nothing owing it a restore. Read here rather than inside `sweep` so the sweep is handed a
        // snapshot taken before it touches the record at all.
        val outstanding = if (myState.hiddenByReview.isNotEmpty()) {
            restore()
            myState.hiddenByReview.toList()
        } else {
            emptyList()
        }

        val sessions = ReviewClientSessions.getInstance(project).sessions()
        val target = ReviewSessionTargeting.hideTarget(sessions)
        if (target == null) {
            // `warn` rather than `info` because, while a guest-only project is a legitimate state, it
            // is an abnormal one for the reviewer's own IDE — and "nothing was hidden" is precisely
            // the symptom KAN-18 was reported as, so the next log read has to be able to tell "no
            // session to sweep" apart from "swept the wrong one". The session types are named for the
            // same reason.
            thisLogger().warn(
                "Review Queue: hideForReview() found no session to sweep among " +
                    "${sessions.map { it.type }} — the layout is left alone",
            )
            return
        }

        ClientId.withExplicitClientId(target.clientId) { sweep(target, outstanding) }
    }

    /**
     * Enumerates, records and hides, as the session [target] names — entered by [hideForReview], the
     * only caller, so that every `ToolWindowManager` call below addresses [target]'s layout.
     *
     * [outstanding] is what the caller's reclaim [restore] could not reopen, and is **unioned** into
     * the record rather than overwritten by it. What this sweep hides is computed from what is
     * visible now, and a window that was never reopened is not visible — so writing only that list
     * would erase the very ids the reclaim deliberately kept, leaving them hidden on screen with
     * nothing owing them a restore. [outstanding] changes the record alone: which windows are
     * enumerated, judged visible, hidden, verified and watched below is untouched by it.
     *
     * Fix round 2 instrumentation (`proposal.md` Fix round 2, Report B and finding D) logs the
     * sweep in two passes around the mutation, not one: [logSweepOutcome] runs before `hide()` is
     * called on anything, so the evidence survives a throw part-way through the loop below; and
     * [logPostHideVerification] runs immediately after, because the pre-hide log only restates
     * what the filter judged visible and cannot by itself tell "`hide()` worked and something
     * reopened the window afterwards" apart from "`hide()` silently no-oped".
     *
     * [armReshowWatch] then runs last, over the ids this pass hid, so that what happens to the layout
     * *after* the sweep returns is reported too — the limitation [logPostHideVerification] cannot
     * close.
     *
     * Every line one call emits names the same manager *and* the same session: both log helpers are
     * handed [target] along with the one manager class resolved below, and the hide loop's own `warn`
     * renders [target] with the same [logSuffix] they do. KAN-6's diagnostics named only the manager,
     * on the theory that the wrong manager was answering; KAN-18 found the manager was right and the
     * session was wrong, which is a distinction no KAN-6 log line could have made. The hide-loop line
     * is included because it is the one that fires when something has actually gone wrong, which is
     * when knowing whose layout was being swept matters most.
     *
     * If `hide()` throws part-way through the loop, [logPostHideVerification] is never reached —
     * that is expected, not evidence `hideForReview()` was never called. [logSweepOutcome] already
     * covers that case; a missing post-hide line only means the loop did not finish, not that the
     * sweep never ran.
     *
     * **Pass-3 panel Minor 2: the hide loop itself names the id it was on if `hide()` throws.** The
     * pre-hide sweep line ([logSweepOutcome]) lists every id judged visible, but on its own does not
     * say which of them actually had `hide()` invoked before a throw cut the loop short. The loop
     * below catches per id, logs a `warn` naming that id (and, by construction of the log message,
     * everything earlier and later in the same list), and immediately rethrows — fail-fast is kept
     * exactly as it always was, because catching-and-continuing has no fully sound form here: this
     * class's hard constraint against swallowing an exception from the hide loop rules out ever fully
     * absorbing one, and a "catch, log, continue, rethrow at the end" variant would still change
     * *which ids get `hide()` called* after a failure — a real behaviour change three review rounds
     * have deliberately not made. The record-before-hide invariant above is unaffected either way: it
     * assigns before this loop runs at all.
     */
    private fun sweep(target: SessionRef, outstanding: List<String>) {
        val manager = ToolWindowManager.getInstance(project)
        val managerClass = manager.javaClass.name
        // Every visible window, on every side, whoever registered it — deliberately not a list of
        // ids. KAN-6 asks for the reviewer to be left with the diff alone, and a fixed list would
        // silently miss the next plugin the user installs. The visibility filter is the whole
        // safety property: only a window that was open is recorded, so `restore()` can never open
        // one the user had closed.
        val ids = manager.toolWindowIds.toList()
        val hidden = ids.filter { toolWindowVisibility(manager, it) == true }
        // Whatever the reclaim `restore()` could not reopen, minus anything this sweep is recording
        // anyway. The `filterNot` is defensive rather than load-bearing — an id that was never
        // reopened is not visible, so it cannot be in `hidden` — but it is what makes the union
        // idempotent rather than able to list an id twice.
        val carriedForward = outstanding.filterNot { it in hidden }
        // Recorded before the windows are hidden: if hiding threw part-way, the record must already
        // name everything this call touched, or a window is hidden with nothing to reopen it.
        //
        // A union, never an overwrite. `hidden` describes only this sweep; the ids the reclaim could
        // not reopen are still hidden on screen and still owed a restore, and an overwrite would
        // launder them off the record — the failure `LEGACY_IDS` pruning guards against, inverted.
        // Keeping them cannot latch anything, because the leftover branch in `hideForReview` reclaims
        // and then hides regardless of the outcome; there is no early return left for a stuck id to
        // latch.
        myState.hiddenByReview = (hidden + carriedForward).toMutableList()

        // Logged here, before the loop below runs, so a throw part-way through it does not erase
        // this evidence — see the KDoc above.
        logSweepOutcome(managerClass, target, ids, hidden)
        logCarriedForward(managerClass, target, carriedForward)

        hidden.forEach { id ->
            try {
                manager.getToolWindow(id)?.hide(null)
            } catch (t: Throwable) {
                // Pass-3 panel Minor 2: names exactly which id `hide()` blew up on, so a throw
                // part-way through no longer leaves the question "how far did it get?" unanswered —
                // everything before this id in `hidden` already had `hide()` invoked; everything
                // after it in the list did not, because this rethrows instead of continuing.
                //
                // Deliberately not caught-and-continued: the hard constraint against swallowing an
                // exception from the hide loop rules out the only fully sound form of that ("catch,
                // log, continue for the rest, then swallow" — a plugin that throws on hide() would
                // otherwise be silently tolerated forever, exactly what the constraint exists to
                // prevent). A variant that deferred the throwable and rethrew it only after every id
                // was attempted would change *which ids get hide() called* after a failure — a real
                // behaviour change three review rounds have deliberately not made, and not one this
                // fix has a concrete reason to introduce. Logging here and rethrowing immediately adds
                // the missing diagnostic without touching that behaviour at all: the throw still
                // propagates out of `hideForReview()` exactly as it always did.
                thisLogger().warn(
                    "Review Queue: hideForReview() hide() threw for $id on $managerClass; ids " +
                        "earlier than $id in $hidden already had hide() invoked, ids later than it " +
                        "did not${target.logSuffix()}",
                    t,
                )
                throw t
            }
        }

        logPostHideVerification(manager, managerClass, target, hidden)
        armReshowWatch(target, hidden)
    }

    /**
     * How every diagnostic in this class names the session it is about: the client id, and the
     * [ClientType][com.intellij.openapi.client.ClientType] that says whether that id is the frontend
     * the reviewer is looking at, the backend, or a guest.
     *
     * Rendered here rather than at each call site so two lines from one pass cannot word it
     * differently. Lining log lines up against each other is the whole reason the session is in them:
     * KAN-18 was invisible in the KAN-6 logs precisely because no line said which layout it was about.
     */
    private fun SessionRef.describe(): String = "session ${clientId.value} ($type)"

    /**
     * [describe], as the trailing bracket every sweep diagnostic carries.
     *
     * A suffix rather than words woven into each sentence, so the sentences stay the ones KAN-6
     * reviewed and a log reader finds the session in the same place on every line. The one message
     * that names the session mid-sentence is `logSweepOutcome`'s `ids.isEmpty()` branch, where the
     * session *is* the finding rather than context for it.
     */
    private fun SessionRef.logSuffix(): String = " [${describe()}]"

    /**
     * The single predicate for "is this id visible right now", shared by the sweep's filter above
     * and the post-hide diagnostic below (pass-2 panel Minor 1) so the two can never drift apart.
     * Returns `null`, rather than collapsing it into `false`, when [id] no longer resolves to a
     * tool window at all — the sweep only cares about `== true`, but the post-hide diagnostic needs
     * to tell "confirmed invisible" apart from "no longer resolves" (Minor 3).
     */
    private fun toolWindowVisibility(manager: ToolWindowManager, id: String): Boolean? =
        manager.getToolWindow(id)?.isVisible

    /**
     * Logs what `hideForReview()` enumerated and judged visible, before it calls `hide()` on any
     * of it (`proposal.md` Fix round 2, Report B). A run where the sweep hides nothing looks
     * identical, on its own success path, to a run where there was genuinely nothing to hide —
     * these branches turn that into evidence for the next report instead of a second guess: an
     * empty `ids` means the swept session has no tool windows registered — the session named in the
     * message is the first thing to check, because KAN-18 was a wrong session rather than a wrong
     * manager; a non-empty `ids` with an empty `hidden` means every enumerated
     * window was judged already invisible — a legitimate outcome, not a failure, hence `info`
     * rather than `warn`.
     *
     * Every branch names both the concrete manager class and [session]. The manager class is the
     * fastest way to see *which* `ToolWindowManager` answered — this IDE build ships `rdserver` /
     * frontend-split modules, so which class that is remains worth reading — and [session] is the
     * fastest way to see *who it answered for*, which is what KAN-18 turned out to hinge on and what
     * the KAN-6 logs could not say. Both are computed once in [sweep] and passed in (pass-2 panel
     * Minor 2), rather than re-derived, so neither can disagree with what
     * [logPostHideVerification] names for the same call.
     *
     * **Known residual gap (pass-3 panel Minor 1):** the exact *text* of the two `info` branches
     * below — in particular that `$managerClass`, `$ids`, `$hidden` and the session suffix actually
     * get interpolated in — is not, and cannot be, verified by this suite. `LoggedErrorProcessor`
     * only intercepts `warn`/`error`; `TestLoggerFactory.TestLogger.info()` writes straight to its
     * buffer with no processor hook to intercept, so there is no supported seam to assert against (see
     * `IdeLayoutControllerTest.testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible`'s KDoc,
     * which already documents this for the `warn`-vs-`info` branch decision). An edit that silently
     * dropped `$managerClass` or the session from one of these two messages would pass CI.
     * Deliberately not closed with a buffer-reading test helper: that would mean reaching into
     * `TestLoggerFactory` internals or installing a custom `Logger.Factory` for the sole purpose of
     * this one assertion, which is more fragile than the gap it would close. The manual test guide
     * (`docs/manual-test/kan-6-plugin-updates.md`, section 2) is the only check on this text today.
     * The `ids.isEmpty()` branch is **not** in that gap, being a `warn`:
     * `IdeLayoutControllerTest.testTheZeroIdsWarningNamesTheSweptSessionRatherThanBlamingTheManager`
     * asserts its text.
     *
     * **Rewording the `ids.isEmpty()` message: keep the literal `"zero tool window ids"`.** A second
     * test depends on it, less obviously than the one above.
     * `IdeLayoutControllerTest.testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible` matches that
     * substring as its **positive control** — the proof that its recorder can observe a warning at all,
     * without which its real assertion (that the `hidden.isEmpty()` branch stays silent) would hold
     * vacuously. Drop or reword that substring and the control stops controlling *while the suite
     * still passes*, which is the one failure mode a control exists to prevent.
     */
    private fun logSweepOutcome(
        managerClass: String,
        session: SessionRef,
        ids: List<String>,
        hidden: List<String>,
    ) {
        when {
            ids.isEmpty() -> thisLogger().warn(
                "Review Queue: hideForReview() sweep found zero tool window ids on $managerClass for " +
                    "${session.describe()} — that session has no tool windows registered, or it is " +
                    "not the session the reviewer is looking at",
            )
            hidden.isEmpty() -> thisLogger().info(
                "Review Queue: hideForReview() sweep enumerated $ids on $managerClass but none of " +
                    "them were visible; nothing was hidden${session.logSuffix()}",
            )
            else -> thisLogger().info(
                "Review Queue: hideForReview() sweep enumerated $ids on $managerClass, judged " +
                    "$hidden visible${session.logSuffix()}",
            )
        }
    }

    /**
     * Reports the ids this sweep is keeping on the record without having hidden them — the ones the
     * reclaim [restore] could not reopen.
     *
     * Silence here is what made the record-overwrite defect invisible for two review rounds: the
     * sweep line above names only what *this* pass enumerated and hid, so an id carried over from a
     * previous pass appeared in neither the log nor — before the union — the record. An operator
     * reading `idea.log` has to be able to tell a record entry that means "this pass hid it" apart
     * from one that means "an earlier pass hid it and nothing has managed to reopen it since".
     *
     * `warn` rather than `info`, and deliberately so despite [logSweepOutcome]'s `hidden.isEmpty()`
     * branch choosing the opposite. That branch reports a legitimate outcome; this one reports a
     * window the reviewer's IDE is still hiding after a reclaim that ran with the IDE fully up, which
     * is the abnormal state. `warn` is also the only level this suite can observe
     * ([logSweepOutcome]'s KDoc records why), so the text below is asserted rather than assumed.
     *
     * Says nothing when there is nothing carried forward, which is every ordinary pass — a
     * diagnostic that fires on the normal path stops being read.
     */
    private fun logCarriedForward(
        managerClass: String,
        session: SessionRef,
        carriedForward: List<String>,
    ) {
        if (carriedForward.isEmpty()) return
        thisLogger().warn(
            "Review Queue: hideForReview() is carrying $carriedForward forward on the record on " +
                "$managerClass — an earlier pass hid them and the reclaim before this sweep could " +
                "not reopen them, so they stay recorded for a later restore to retry rather than " +
                "being dropped${session.logSuffix()}",
        )
    }

    /**
     * Re-queries visibility, immediately after `hide()` was called on everything the sweep judged
     * visible, for what the warn line below actually settles (`proposal.md` Fix round 2, finding D;
     * reworded at pass-2 Important 2): it proves `hide()` was called on an id and that id was still
     * visible when `hideForReview()` returned. It does **not** by itself prove `hide()` "did not take
     * effect" — a same-stack reentrant reopen (a listener calling `show()` synchronously inside
     * `hide()`) produces the identical message, and the two are distinguishable only by further
     * investigation, not by this line alone.
     *
     * What this line *can* rule out is asynchrony: `ToolWindowImpl.hide(Runnable)` calls
     * `ToolWindowManagerImpl.hideToolWindow$default(...)` synchronously and only defers the passed
     * `Runnable` via `callLater` — and `hideForReview()` passes `null` — so by the time `hide()`
     * returns here, the platform's own `WindowInfo` (the same state `isVisible()` reads) has already
     * been updated. This build's `rdserver` / frontend-split modules do not change that: the check
     * below still reads the same `WindowInfo` `hide()` just wrote. Record this so a future round does
     * not have to re-derive it: this was checked against the platform and confirmed, not assumed.
     *
     * The re-query itself must never be able to throw out of `hideForReview()` (pass-2 Important 1):
     * the sweep deliberately covers arbitrary third-party tool windows, and a plugin that disposes
     * its content on hide can make this query throw (e.g. `AlreadyDisposedException`) rather than
     * return null. Any [Throwable] from the re-query is caught here, logged at `warn` naming the id
     * and the throwable, and swallowed — a purely diagnostic check must never turn a successful hide
     * into a broken `hideForReview()`. Nothing from the *sweep* or the *hide loop* above is caught
     * here; only this diagnostic re-query is.
     *
     * Deliberately a no-op when [hidden] is empty: there is nothing to verify, and logging would
     * only repeat what the sweep line already said.
     *
     * Every line below carries [session] as well as the manager class, for the reason
     * [logSweepOutcome] gives: the manager says which `ToolWindowManager` answered, the session says
     * whose layout it answered about, and both come from [sweep] so this line and the sweep line from
     * the same call can never name different ones.
     */
    private fun logPostHideVerification(
        manager: ToolWindowManager,
        managerClass: String,
        session: SessionRef,
        hidden: List<String>,
    ) {
        if (hidden.isEmpty()) return

        val stillVisible = mutableListOf<String>()
        val confirmedInvisible = mutableListOf<String>()
        val noLongerResolves = mutableListOf<String>()

        hidden.forEach { id ->
            val visibility = try {
                toolWindowVisibility(manager, id)
            } catch (t: Throwable) {
                thisLogger().warn(
                    "Review Queue: hideForReview() post-hide check could not verify $id on " +
                        "$managerClass after hide() returned — treating it as " +
                        "unverified${session.logSuffix()}",
                    t,
                )
                return@forEach
            }
            when (visibility) {
                true -> stillVisible += id
                false -> confirmedInvisible += id
                null -> noLongerResolves += id
            }
        }

        if (stillVisible.isNotEmpty()) {
            thisLogger().warn(
                "Review Queue: hideForReview() post-hide check on $managerClass found $stillVisible " +
                    "still visible immediately after hide() returned — hide() was called and the " +
                    "window was still visible when it returned: either it did not take effect, or " +
                    "something re-showed it within the same call stack; distinguishing the two needs " +
                    "further investigation${session.logSuffix()}",
            )
            return
        }

        // Minor 3: "confirmed invisible" and "no longer resolves at all" are different outcomes —
        // the latter cannot even be asked whether it is visible — so they are reported separately
        // rather than folded into one "no longer visible" line.
        val notes = mutableListOf<String>()
        if (confirmedInvisible.isNotEmpty()) notes += "$confirmedInvisible confirmed no longer visible"
        if (noLongerResolves.isNotEmpty()) {
            notes += "$noLongerResolves no longer resolve to a tool window at all"
        }
        if (notes.isNotEmpty()) {
            thisLogger().info(
                "Review Queue: hideForReview() post-hide check on $managerClass: " +
                    notes.joinToString("; ") + " immediately after hide() returned" +
                    session.logSuffix(),
            )
        }
    }

    /**
     * Reopens whatever [hideForReview] hid, then forgets only what it actually reopened. Safe to
     * call when nothing was hidden.
     *
     * Runs in **every** non-guest session, not in the one session [hideForReview] sweeps (KAN-18).
     * The asymmetry is deliberate: hiding must never touch a layout whose visibility it did not
     * measure, or it would hide a window the user had closed there — while restoring is bounded by
     * the record, so it can only ever show ids a pass already hid. That breadth is what the
     * quit-mid-pass path needs, because the session present when a pass *ends* need not be the one
     * swept when it began: most sharply at post-startup, where [ReviewLayoutRestorer] may run before
     * a frontend session has attached. [ReviewSessionTargeting.restoreTargets] states the rule and
     * its KDoc explains why it is broader than [ReviewSessionTargeting.hideTarget].
     *
     * An id is forgotten only when it was reopened in at least one session **and** its `show()` threw
     * in none; otherwise it stays on the record. Tool-window registration is not guaranteed complete
     * when [ReviewLayoutRestorer] runs at post-startup, and dropping an id that never came back would
     * leave that window hidden with no record that it ever was.
     *
     * A success elsewhere may not launder a throw away, which is why plain "reopened anywhere" is not
     * the rule. `getToolWindow` hands each session its own window object for the same id (`design.md`,
     * "Root cause, from the bytecode"), so under a split IDE the host's copy can show cleanly in the
     * same pass in which the frontend's copy — the layout the reviewer is actually looking at — throws
     * and stays hidden. Forgetting the id on the strength of the success would strand exactly the
     * window that matters, unrecoverably: [sweep]'s union preserves only ids still on the record when
     * the next reclaim reads them back.
     *
     * A session where the id does not resolve at all is deliberately **not** counted against it. There
     * is no hidden window there to owe a restore to — the id names nothing in that layout, which is the
     * ordinary post-startup state before a session has registered its tool windows — whereas a window
     * that resolved and threw does exist and is still hidden. So an id registered on one side of a
     * split IDE and not the other is reopened and forgotten, exactly as it was before this rule
     * narrowed.
     *
     * "Not reopened" covers two causes, deliberately given the same answer. An id may fail to resolve
     * to a registered tool window at all — the post-startup case above — or it may resolve and have its
     * `show()` throw: this sweep covers arbitrary third-party tool windows, and a plugin that disposes
     * its content while a pass is running makes reopening it blow up, the same failure category the
     * hide loop and the post-hide check already defend against. Either way the window is still hidden
     * and this plugin hid it, so the record must keep it and a later restore must retry it. Keeping it
     * cannot latch anything: [hideForReview] reclaims a leftover record by calling this and then hides
     * regardless of the outcome. Nor is a kept id laundered back out by that hide: [sweep] unions what
     * this method could not reopen into its own record rather than overwriting it, so "kept here"
     * really does mean "a later restore retries it" and not merely "kept until the next pass".
     *
     * Each `show()` is isolated per id, so one throwing window costs only itself. Unlike the hide loop,
     * this must not rethrow, and the asymmetry is the point: a hide that fails part-way leaves a
     * *recorded* mess a later restore can still undo, while a restore that stops part-way leaves
     * windows hidden with no further attempt coming. Since this loops over every non-guest session, an
     * escaping throw would also mean no *later* session is entered — on a split IDE, the frontend the
     * reviewer is actually looking at is the one layout that would go un-restored. The failure is
     * logged at `warn` naming the id and the session, which is what keeps "silently kept on the record"
     * from being indistinguishable from "never registered yet".
     *
     * The record is read once into a local **before** the loop, so what is shown and what is
     * forgotten are decided from the same snapshot. That is safe rather than merely convenient:
     * `hiddenByReview` is written in exactly three places, all in this class ([loadState], [sweep]
     * and here), and no plugin component subscribes to any tool-window topic — so nothing reachable
     * from `show(null)` can re-enter and mutate it underneath the loop. [armReshowWatch]'s subscription
     * is the one component that does listen, and [disarmReshowWatch] runs before the first `show()`
     * below precisely so it cannot.
     */
    fun restore() {
        // Before anything is shown, so the plugin's own reopening can never look like a re-show.
        disarmReshowWatch()

        val recorded = myState.hiddenByReview.toList()
        if (recorded.isEmpty()) return

        val reopened = mutableSetOf<String>()
        val failedAnywhere = mutableSetOf<String>()
        ReviewSessionTargeting.restoreTargets(ReviewClientSessions.getInstance(project).sessions())
            .forEach { session ->
                ClientId.withExplicitClientId(session.clientId) {
                    // Resolved inside the scope for symmetry with `sweep`, not out of necessity: the
                    // manager is a project-level service and `getInstance` hands back the same
                    // instance whatever id is current. It is the individual `getToolWindow` /
                    // `hideToolWindow` calls that read `ClientId.current` at call time and address
                    // that session's layout (`design.md`, "Root cause, from the bytecode"), and those
                    // are inside the scope either way. Fetching it once above the loop would behave
                    // identically.
                    val manager = ToolWindowManager.getInstance(project)
                    recorded.forEach { id ->
                        val window = manager.getToolWindow(id) ?: return@forEach
                        try {
                            window.show(null)
                            reopened += id
                        } catch (t: Throwable) {
                            // Caught per id, and never rethrown — see this method's KDoc for why
                            // restoring isolates where hiding fails fast, and why the id stays on the
                            // record. `reopened` is deliberately not added to, and `failedAnywhere`
                            // is: the window was not reopened *in this session*, so a later restore
                            // must try again even if another session reopened its own copy.
                            failedAnywhere += id
                            thisLogger().warn(
                                "Review Queue: restore() show() threw for $id, which stays on the " +
                                    "record for a later restore to retry; the remaining ids and " +
                                    "sessions are unaffected${session.logSuffix()}",
                                t,
                            )
                        }
                    }
                }
            }

        // Forgotten only when it was reopened somewhere **and** failed nowhere. A success in one
        // session does not speak for another: `getToolWindow` hands each session its own window object
        // for the same id (`design.md`, "Root cause, from the bytecode"), so the host's copy can show
        // cleanly while the frontend's — the layout the reviewer is looking at — is still hidden
        // because its `show()` threw. Dropping the id on the strength of the success would leave that
        // window hidden with nothing owing it a restore, and nothing could recover it: [sweep]'s union
        // can only preserve ids that are still on the record when the next reclaim reads them back.
        //
        // Only the record write is narrowed. Every session is still entered and every recorded id is
        // still attempted in each, exactly as before — a kept id is retried, not abandoned.
        myState.hiddenByReview =
            recorded.filterNot { it in reopened && it !in failedAnywhere }.toMutableList()
    }

    /**
     * The subscription carrying the re-show watch for the pass currently running, or `null` when no
     * pass has hidden anything.
     *
     * Armed at the end of a sweep, disarmed at the start of [restore] — *before* it shows anything, so
     * the plugin's own reopening can never be reported. [hideForReview]'s leftover-reclaim branch calls
     * [restore] first, so a second pass always re-arms from a disarmed state.
     */
    private var reshowWatch: MessageBusConnection? = null

    /**
     * Stops the re-show watch from reporting the plugin's own reopening as a re-show.
     *
     * Disconnects rather than merely forgetting: the field holds the only reference to a live
     * subscription, so dropping it would leave the watch reporting for the rest of the project's life.
     */
    private fun disarmReshowWatch() {
        reshowWatch?.disconnect()
        reshowWatch = null
    }

    /**
     * Reports the first time each of [hidden] becomes visible again while the pass is running.
     *
     * [logPostHideVerification] can only say whether `hide()` had taken effect at the instant it
     * returned; it cannot tell a window re-shown a frame later from one that was never hidden. This
     * closes that gap by being told rather than by re-checking, so a re-show is caught whenever in the
     * pass it happens and there is no interval to justify.
     *
     * `toolWindowShown(ToolWindow)` is the one event to listen for. `ToolWindowManagerImpl` publishes
     * it from `doShowWindow`, which every show path funnels through, and it is the only overload the
     * platform ever fires — the two-argument form is `@Deprecated(forRemoval = true)` and exists only
     * as the default's delegate. The `ShowToolWindow` state change is fired by `showToolWindow` alone,
     * strictly narrower, so listening for it as well would add a second source of the same finding and
     * no coverage.
     *
     * Which id was shown is the whole discriminator: [ReviewClientSessions.messageBus] hands back the
     * project's bus, which cannot say what session an event came from, so [target] is named because it
     * is the session this pass acted on — not because the event is known to have come from it.
     * `ReviewClientSessions.messageBus`'s KDoc records why there is no narrower bus to have.
     *
     * A [target] that has gone away since it was enumerated has no bus, and leaves the watch unarmed —
     * a diagnostic that reports nothing, never a broken hide. Arming replaces any previous watch rather
     * than adding to it, so one re-show is one report.
     *
     * **Nothing here may throw out of [sweep].** This runs last, after the hide loop and the post-hide
     * check have both already succeeded: the windows are hidden, the record is written, and the pass
     * has worked. But [ReviewClientSessions.messageBus] resolves the session's project and bus with no
     * guard, and `connect`/`subscribe` register with the Disposer — all three throw (e.g.
     * `AlreadyDisposedException`) for a session or project torn down since the enumeration at the top
     * of [hideForReview]. Left uncaught, that propagates out of [hideForReview] and out of the caller
     * that started the pass, showing the user a failure for a pass that succeeded. Any [Throwable] is
     * therefore caught, logged at `warn`, and the watch left unarmed — the same rule
     * [logPostHideVerification] states for its own re-query, and for the same reason: a purely
     * diagnostic check must never turn a successful hide into a broken [hideForReview]. Only the arming
     * is covered; nothing from the sweep or the hide loop reaches this.
     *
     * The connection is parented to this service (`connect(this)`) rather than left unowned. The
     * platform disposes a project-level service with its project, so the subscription — and the plugin
     * classloader the listener below holds — cannot outlive it even if a pass is never ended and
     * [disarmReshowWatch] is never reached, which is what a dynamic plugin reload with the project
     * still open would otherwise leak. Cleanup is then structural rather than resting on arm/disarm
     * symmetry surviving every future edit. `ReviewSessionService` parents its own subscription the
     * same way. [disarmReshowWatch] is unaffected: disconnecting a parented connection unregisters it
     * from its parent, so the disarm-before-show ordering in [restore] keeps working exactly as it did.
     */
    private fun armReshowWatch(target: SessionRef, hidden: List<String>) {
        disarmReshowWatch()
        if (hidden.isEmpty()) return

        val watched = hidden.toSet()
        val reported = mutableSetOf<String>()
        try {
            val bus = ReviewClientSessions.getInstance(project).messageBus(target) ?: return
            val connection = bus.connect(this)
            // Assigned before subscribing so the catch below can disconnect a connection that was
            // created and then failed to take a listener, rather than leaving it live and unreachable.
            reshowWatch = connection
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun toolWindowShown(toolWindow: ToolWindow) {
                        val id = toolWindow.id
                        // `reported.add` is the once-per-id guard: a window shown, hidden and shown
                        // again during one pass is one finding, not a stream of them.
                        if (id in watched && reported.add(id)) {
                            thisLogger().warn(
                                "Review Queue: $id was hidden for this review pass but has been " +
                                    "shown again before the pass ended — the pass swept " +
                                    "${target.describe()}; something outside this plugin reopened " +
                                    "the window",
                            )
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            disarmReshowWatch()
            thisLogger().warn(
                "Review Queue: hideForReview() could not arm the re-show watch over $hidden; the " +
                    "windows are hidden and recorded, and only this diagnostic is " +
                    "lost${target.logSuffix()}",
                t,
            )
        }
    }

    companion object {
        /** Ids this plugin used to manage. `Review Queue` was its tool window until KAN-5. */
        private val LEGACY_IDS = setOf("Review Queue")

        fun getInstance(project: Project): IdeLayoutController = project.service()
    }
}
