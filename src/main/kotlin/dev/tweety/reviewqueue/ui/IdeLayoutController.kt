package dev.tweety.reviewqueue.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

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
    PersistentStateComponent<IdeLayoutController.State> {

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
     * Fix round 2 instrumentation (`proposal.md` Fix round 2, Report B and finding D) logs the
     * sweep in two passes around the mutation, not one: [logSweepOutcome] runs before `hide()` is
     * called on anything, so the evidence survives a throw part-way through the loop below; and
     * [logPostHideVerification] runs immediately after, because the pre-hide log only restates
     * what the filter judged visible and cannot by itself tell "`hide()` worked and something
     * reopened the window afterwards" apart from "`hide()` silently no-oped".
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
        if (myState.hiddenByReview.isNotEmpty()) restore()

        val manager = ToolWindowManager.getInstance(project)
        val managerClass = manager.javaClass.name
        // Every visible window, on every side, whoever registered it — deliberately not a list of
        // ids. KAN-6 asks for the reviewer to be left with the diff alone, and a fixed list would
        // silently miss the next plugin the user installs. The visibility filter is the whole
        // safety property: only a window that was open is recorded, so `restore()` can never open
        // one the user had closed.
        val ids = manager.toolWindowIds.toList()
        val hidden = ids.filter { toolWindowVisibility(manager, it) == true }
        // Recorded before the windows are hidden: if hiding threw part-way, the record must already
        // name everything this call touched, or a window is hidden with nothing to reopen it.
        myState.hiddenByReview = hidden.toMutableList()

        // Logged here, before the loop below runs, so a throw part-way through it does not erase
        // this evidence — see the KDoc above.
        logSweepOutcome(managerClass, ids, hidden)

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
                        "did not",
                    t,
                )
                throw t
            }
        }

        logPostHideVerification(manager, hidden, managerClass)
    }

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
     * empty `ids` means `ToolWindowManager.getInstance(project)` is not resolving to the manager
     * the IDE is actually using; a non-empty `ids` with an empty `hidden` means every enumerated
     * window was judged already invisible — a legitimate outcome, not a failure, hence `info`
     * rather than `warn`.
     *
     * The concrete manager class is included in every branch: this IDE build ships `rdserver` /
     * frontend-split modules, so a backend manager not reflecting the visible frontend is a live
     * explanation for `ids.isEmpty()`, and naming the concrete class turns the next log read into
     * a confirmation instead of another patch-and-rerun cycle. It is computed once in
     * [hideForReview] and passed in here (pass-2 panel Minor 2), rather than re-derived, so it can
     * never disagree with the class named by [logPostHideVerification] for the same call.
     *
     * **Known residual gap (pass-3 panel Minor 1):** the exact *text* of the two `info` branches
     * below — in particular that `$managerClass`, `$ids` and `$hidden` actually get interpolated in
     * — is not, and cannot be, verified by this suite. `LoggedErrorProcessor` only intercepts
     * `warn`/`error`; `TestLoggerFactory.TestLogger.info()` writes straight to its buffer with no
     * processor hook to intercept, so there is no supported seam to assert against (see
     * `IdeLayoutControllerTest.testHideDoesNotWarnWhenIdsAreEnumeratedButNoneAreVisible`'s KDoc,
     * which already documents this for the `warn`-vs-`info` branch decision). An edit that silently
     * dropped `$managerClass` from one of these messages would pass CI. Deliberately not closed with
     * a buffer-reading test helper: that would mean reaching into `TestLoggerFactory` internals or
     * installing a custom `Logger.Factory` for the sole purpose of this one assertion, which is more
     * fragile than the gap it would close. The manual test guide
     * (`docs/manual-test/kan-6-plugin-updates.md`, section 2) is the only check on this text today.
     */
    private fun logSweepOutcome(managerClass: String, ids: List<String>, hidden: List<String>) {
        when {
            ids.isEmpty() -> thisLogger().warn(
                "Review Queue: hideForReview() sweep found zero tool window ids on $managerClass — " +
                    "that manager is not the one the IDE is using, or no tool windows are registered yet",
            )
            hidden.isEmpty() -> thisLogger().info(
                "Review Queue: hideForReview() sweep enumerated $ids on $managerClass but none of " +
                    "them were visible; nothing was hidden",
            )
            else -> thisLogger().info(
                "Review Queue: hideForReview() sweep enumerated $ids on $managerClass, judged " +
                    "$hidden visible",
            )
        }
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
     */
    private fun logPostHideVerification(manager: ToolWindowManager, hidden: List<String>, managerClass: String) {
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
                        "$managerClass after hide() returned — treating it as unverified",
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
                    "further investigation",
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
                    notes.joinToString("; ") + " immediately after hide() returned",
            )
        }
    }

    /**
     * Reopens whatever [hideForReview] hid, then forgets only what it actually reopened. Safe to
     * call when nothing was hidden.
     *
     * An id that does not resolve to a registered tool window stays on the record. Tool-window
     * registration is not guaranteed complete when [ReviewLayoutRestorer] runs at post-startup, and
     * dropping an unresolved id would leave that window hidden with no record that it ever was.
     */
    fun restore() {
        val manager = ToolWindowManager.getInstance(project)
        val unresolved = mutableListOf<String>()
        myState.hiddenByReview.forEach { id ->
            val window = manager.getToolWindow(id)
            if (window == null) unresolved += id else window.show(null)
        }
        myState.hiddenByReview = unresolved
    }

    companion object {
        /** Ids this plugin used to manage. `Review Queue` was its tool window until KAN-5. */
        private val LEGACY_IDS = setOf("Review Queue")

        fun getInstance(project: Project): IdeLayoutController = project.service()
    }
}
