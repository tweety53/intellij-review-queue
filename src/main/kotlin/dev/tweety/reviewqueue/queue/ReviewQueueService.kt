package dev.tweety.reviewqueue.queue

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.ThreadingAssertions
import dev.tweety.reviewqueue.core.ReviewOrdering
import dev.tweety.reviewqueue.git.ChangeMapper
import dev.tweety.reviewqueue.git.GitReviewSource
import dev.tweety.reviewqueue.git.RootResult
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.state.ReviewStateService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

data class QueueSnapshot(
    val items: List<ReviewItem>,
    val reviewedCount: Int,
    val errors: Map<String, String>,
    val scope: ReviewScope,
)

/**
 * Turns per-root results into an ordered queue, a per-root error map, and the change lookup — in a
 * single pass over each change.
 */
object QueueAssembler {
    data class Assembled(
        val items: List<ReviewItem>,
        val errors: Map<String, String>,
        /**
         * The [Change] behind each key, for the callers that need the VCS object rather than the
         * item — `changeFor`, and through it the diff opener and the file-list popup's browse arm.
         *
         * Keyed by the root that *produced* the change, never by re-deriving a root from the path's
         * prefix: those two disagree whenever roots are nested (a submodule inside a repository),
         * and the prefix answer names the enclosing root for a file the inner root reported.
         */
        val changesByKey: Map<ReviewKey, Change>,
    )

    fun assemble(
        results: List<RootResult>,
        rootOrder: List<String>,
    ): Assembled {
        val errors = results.mapNotNull { r -> r.error?.let { r.rootPath to it } }.toMap()

        val items = mutableListOf<ReviewItem>()
        val changesByKey = LinkedHashMap<ReviewKey, Change>()

        for (result in results) {
            for (change in result.changes) {
                val item = ChangeMapper.toItem(result.rootPath, change) ?: continue
                // Duplicates are handled explicitly rather than collapsed by a map builder: a silent
                // collapse leaves `items` longer than the change lookup, so the two disagree about
                // how much there is to review — the file-list popup's `N / M` title counts `items`
                // while every lookup-driven gesture can only reach `M - 1` of them.
                if (changesByKey.containsKey(item.key)) {
                    thisLogger().warn(
                        "Review queue: duplicate entry for ${item.key.storageKey()}; keeping the first change"
                    )
                    continue
                }
                items += item
                changesByKey[item.key] = change
            }
        }

        return Assembled(ReviewOrdering.order(items, rootOrder), errors, changesByKey)
    }
}

/**
 * Owns the active scope and the ordered queue, and rebuilds on VCS change events so
 * a fix round that rewrites files returns exactly those files to the unreviewed set.
 *
 * The rebuild itself (git queries plus a content hash per file) runs on a background thread; only
 * the small apply step touches the EDT.
 */
@Service(Service.Level.PROJECT)
class ReviewQueueService(private val project: Project) : Disposable {

    private val source = GitReviewSource(project)
    private val state get() = ReviewStateService.getInstance(project)
    private val notifier = dev.tweety.reviewqueue.notify.CompletionNotifier(project)
    private val errorNotifier = dev.tweety.reviewqueue.notify.ScopeErrorNotifier(project)
    private val listeners = mutableListOf<() -> Unit>()

    /**
     * Serialises every git resolve for this project — both the asynchronous [refresh] and the
     * synchronous [resolveNow], which submits here and awaits rather than resolving on the progress
     * task's own thread. Two concurrent `git status` calls on one root can collide on `index.lock`.
     */
    private val refreshExecutor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Review Queue refresh", 1)

    /** Bumped per refresh request; a result whose generation is stale is discarded, not applied. */
    private val refreshGeneration = AtomicLong(0)

    private var scope: ReviewScope = ReviewScope.Staged
    private var items: List<ReviewItem> = emptyList()
    private var errors: Map<String, String> = emptyMap()
    private var changesByKey: Map<ReviewKey, Change> = emptyMap()

    internal data class Rebuild(
        val scope: ReviewScope,
        val assembled: QueueAssembler.Assembled,
    )

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleRefresh()
        })
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { scheduleRefresh() })
    }

    /** Cheap and EDT-safe: reads already-computed state, spawns no processes. */
    fun snapshot(): QueueSnapshot =
        QueueSnapshot(items, state.reviewedCount(items), errors, scope)

    fun setScope(scope: ReviewScope) {
        this.scope = scope
        refresh()
    }

    /**
     * Resolves the current scope off the EDT and applies the result on it: `getStatus` and the
     * per-file content read are git subprocesses, which must never run on the EDT.
     *
     * Deliberately NOT a `ReadAction.nonBlocking`. Resolving a scope needs no read lock, and the
     * platform forbids waiting on a process under one — `OSProcessHandler.checkEdtAndReadAction`
     * logs `Synchronous execution under ReadAction` and the refresh dies. An earlier version used
     * a non-blocking read action to get off the EDT and hit exactly that.
     *
     * A single-thread executor serialises rebuilds; [refreshGeneration] replaces `coalesceBy`,
     * collapsing refresh storms (every `changeListUpdateDone` lands here) by discarding any result
     * a newer request has superseded — checked both before the work and before the apply.
     */
    fun refresh() {
        if (project.isDisposed) return
        val requestedScope = scope
        val generation = refreshGeneration.incrementAndGet()
        refreshExecutor.execute {
            if (project.isDisposed || generation != refreshGeneration.get()) return@execute
            val rebuild = try {
                resolveAndAssemble(requestedScope)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Review Queue refresh failed", e)
                return@execute
            }
            ApplicationManager.getApplication().invokeLater(
                { if (generation == refreshGeneration.get()) applyRebuild(rebuild) },
                // Deliberately not ModalityState.any(): that would let the queue rebuild underneath
                // the Commit Range input and the Reset All confirmation, changing what the user is
                // deciding about while they decide. The apply waits until no modal dialog is up.
                ModalityState.nonModal(),
                project.disposed,
            )
        }
    }

    /**
     * The pure resolve-and-assemble step, shared by [refresh] and [resolveNow]. Runs git, so it must
     * never be called on the EDT or under a read action.
     */
    internal fun resolveAndAssemble(target: ReviewScope): Rebuild =
        Rebuild(target, QueueAssembler.assemble(source.resolve(target), source.rootOrder()))

    /**
     * How [resolveNow] shows progress while it waits. Swapped for a direct call in tests, because the
     * synchronous-progress API is not the modal-dialog path headless: `HeavyPlatformTestCase` runs
     * bodies on the EDT, and driving the real one from there risks the internal-mode process
     * assertion that `TestLoggerFactory` turns into a failure. (It is *not* that git would land on
     * the test EDT — the work lambda hands git to [refreshExecutor] and only awaits it.)
     *
     * Returns null when the user cancelled. Mirrors the `presenter` seam on `ReviewSessionService`.
     */
    internal var progressRunner: (String, () -> Rebuild) -> Rebuild? = { title, work ->
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Rebuild, Exception> { work() },
                title,
                true,
                project,
            )
        } catch (e: ProcessCanceledException) {
            null
        }
    }

    /**
     * Resolves a scope to completion and applies it, so the calling gesture has a queue it can act on
     * immediately. Returns false when the user cancelled or nothing could be resolved.
     *
     * Three preconditions, none of them enforced by the compiler; the first two are checked below.
     *  1. **Called on the EDT** — the apply mutates state the EDT owns.
     *  2. **No write access held.** `ApplicationImpl.runProcessWithProgressSynchronously`
     *     short-circuits to running the body *inline on the calling thread* when
     *     `isDispatchThread() && isWriteAccessAllowed()`. The consequence is a **deadlock, not a
     *     failure**: `awaitWithCheckCanceled` blocks the EDT while the write lock is held, with no
     *     progress dialog and no cancel button, and no platform assertion fires because no read
     *     action is involved. Note this is *not* the failure [refresh]'s KDoc was written for — git
     *     itself never runs under the write action, because the body hands it to [refreshExecutor]
     *     (decision `resolve-now-scheduling`); the EDT blocking under the lock is the hazard.
     *     Checked with a plain `check`, because `ThreadingAssertions` has no
     *     write-access-not-allowed assertion in 2026.2 — only `assertNoReadAccess`, which is a
     *     different and stricter precondition than this one.
     *  3. **No modal dialog already showing**, or the apply lands under it. This is the one
     *     deliberate exception to the `ModalityState.nonModal()` rule [refresh] follows: here the
     *     gesture that opened the progress is precisely what is waiting for the result.
     *
     * The git work goes to [refreshExecutor], not the progress task's pooled thread, so that this
     * path and [refresh] cannot resolve git concurrently for one project — bumping
     * [refreshGeneration] does not cancel an executor task already inside `source.resolve`, and two
     * concurrent `git status` calls on one root can collide on `index.lock`.
     *
     * [refreshGeneration] is bumped **with the apply, not at entry**. Bumping at entry meant a
     * cancelled or failed resolve still invalidated an in-flight [refresh] result that nothing would
     * reschedule, leaving the queue stale until the next VCS event. That is not self-correcting
     * mid-pass: `markCurrent`, `toggleCurrent`, `previous`, `nextFile` and `jumpTo` all read the
     * queue without resolving it, and `ShowFileListAction` skips the resolve during a pass on
     * purpose — so a file rewritten by a fix round would keep its mark against stale content and the
     * pass would skip it, which is the guarantee content-addressed marks exist to provide.
     *
     * One accepted cost remains, and it is latency rather than a wrong result: cancelling the modal
     * abandons the future without cancelling it, so the git resolve runs to completion in the
     * background and the next call queues behind it.
     */
    /**
     * @param announceCompletion false suppresses the "all files reviewed" balloon for this apply.
     * Used by a mid-pass scope switch: marks are content-addressed, so files carried into the new
     * scope arrive already marked, and a scope the reviewer has merely *selected* would otherwise
     * congratulate them — with a `/myflow-do-done` copy action — for a pass they never ran.
     */
    fun resolveNow(newScope: ReviewScope? = null, announceCompletion: Boolean = true): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        check(!ApplicationManager.getApplication().isWriteAccessAllowed) {
            "ReviewQueueService.resolveNow must not be called while holding write access: the " +
                "synchronous progress would run inline on the EDT and block it under the write lock, " +
                "with no dialog and no way to cancel."
        }
        if (project.isDisposed) return false
        // Deliberately does NOT assign `scope` yet. Every early return below would otherwise leave the
        // requested scope recorded without a queue to match it: `snapshot()` would report a scope that
        // does not describe `items`, and the next `changeListUpdateDone` would resolve and apply the
        // scope the user just cancelled. The assignment happens with the apply, or not at all.
        val requestedScope = newScope ?: scope

        val rebuild = try {
            progressRunner("Resolving Review Scope") {
                val future = refreshExecutor.submit(Callable { resolveAndAssemble(requestedScope) })
                val resolved = ProgressIndicatorUtils.awaitWithCheckCanceled(future)
                // Checked *inside* the progress body, which is the only place an indicator exists —
                // on return we are back on the bare EDT, where checkCanceled can never see anything.
                // Needed because the ThrowableComputable overload discards the cancelled flag and
                // returns normally when a cancel lands after the body's last git call. This narrows
                // that window rather than closing it: a cancel arriving after this line is invisible.
                ProgressManager.checkCanceled()
                resolved
            }
        } catch (e: ProcessCanceledException) {
            return false
        } catch (e: RejectedExecutionException) {
            // dispose() calls shutdownNow(); a resolve racing project close has no queue to give.
            return false
        } catch (e: Exception) {
            thisLogger().warn("Review Queue resolve failed", e)
            return false
        } ?: return false

        // Bumped only now, with the rebuild in hand and about to be applied — never at entry, so that
        // a cancelled or failed resolve cannot invalidate an in-flight refresh() result that nothing
        // would reschedule. Every path that could still apply a *stale* rebuild is covered without an
        // entry bump: refresh() re-checks the generation both before its work and, on the EDT, before
        // its apply, so any refresh still outstanding when this line runs is discarded; and
        // applyRebuild's own scope guard rejects a rebuild resolved for a scope no longer in effect.
        refreshGeneration.incrementAndGet()
        // Recorded here so that `scope` and `items` are only ever updated together — and so
        // applyRebuild's own `rebuild.scope != scope` guard, which exists for the async path, passes.
        scope = requestedScope
        applyRebuild(rebuild, announceCompletion)
        return true
    }

    private fun applyRebuild(rebuild: Rebuild, announceCompletion: Boolean = true) {
        if (project.isDisposed) return
        // A scope change raced this rebuild; the refresh for the new scope is already in flight.
        //
        // Logged rather than dropped in silence. This is the one discard that is not accounted for by a
        // generation check the caller can see, and a gesture whose rebuild lands here does nothing with
        // no other trace — Start Review reports "nothing unreviewed" for a queue that was never
        // replaced. If this line ever appears without a scope change immediately before it, the
        // discard is a defect rather than the race it is written for.
        if (rebuild.scope != scope) {
            thisLogger().warn(
                "Review Queue: discarding a rebuild resolved for ${rebuild.scope} — the active scope " +
                    "is now $scope"
            )
            return
        }

        items = rebuild.assembled.items
        errors = rebuild.assembled.errors
        changesByKey = rebuild.assembled.changesByKey

        fireChanged(announceCompletion)
    }

    /** Toggles the stored mark for [key]. */
    fun toggleReviewed(key: ReviewKey) {
        val item = items.firstOrNull { it.key == key } ?: return
        if (state.isReviewed(item)) state.unmark(key) else state.markReviewed(item)
        fireChanged()
    }

    /**
     * Marks [key] reviewed at its current content hash.
     *
     * Returns false when the key is no longer in the queue and nothing was stored, so a caller that
     * would otherwise move on can tell a silent no-op from a real mark.
     */
    fun markReviewed(key: ReviewKey): Boolean {
        val item = items.firstOrNull { it.key == key } ?: return false
        state.markReviewed(item)
        fireChanged()
        return true
    }

    fun resetAll() {
        state.resetAll()
        fireChanged()
    }

    fun isReviewed(item: ReviewItem): Boolean = state.isReviewed(item)

    fun changeFor(key: ReviewKey): Change? = changesByKey[key]

    fun addListener(listener: () -> Unit, parent: Disposable) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    override fun dispose() {
        // Bumping the generation invalidates any in-flight result, so nothing applies after this.
        refreshGeneration.incrementAndGet()
        refreshExecutor.shutdownNow()
    }

    private fun scheduleRefresh() {
        ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
    }

    private fun fireChanged(announceCompletion: Boolean = true) {
        val snapshot = snapshot()
        notifier.onSnapshot(snapshot, announceCompletion)
        errorNotifier.onSnapshot(snapshot)
        listeners.toList().forEach { it() }
    }

    companion object {
        fun getInstance(project: Project): ReviewQueueService = project.service()
    }
}
