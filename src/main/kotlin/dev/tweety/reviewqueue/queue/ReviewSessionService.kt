package dev.tweety.reviewqueue.queue

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.tweety.reviewqueue.actions.diff.DiffEndReviewAction
import dev.tweety.reviewqueue.actions.diff.DiffRefreshQueueAction
import dev.tweety.reviewqueue.actions.diff.DiffResetAllAction
import dev.tweety.reviewqueue.actions.diff.DiffScopeAction
import dev.tweety.reviewqueue.actions.diff.DiffStartReviewAction
import dev.tweety.reviewqueue.core.ReviewSession
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.notify.QueueNotices
import dev.tweety.reviewqueue.ui.EditorTabDiffPresenter
import dev.tweety.reviewqueue.ui.IdeLayoutController
import dev.tweety.reviewqueue.ui.ReviewDiffPresenter

/**
 * Runs a guided review pass: hide the Project panel, walk the files one at a time, restore it on the
 * way out.
 *
 * Composes the queue (what exists), the state (marks), the layout controller and the presenter.
 * It re-implements none of them.
 */
@Service(Service.Level.PROJECT)
class ReviewSessionService(private val project: Project) : Disposable {

    private val queue get() = ReviewQueueService.getInstance(project)
    private val layout get() = IdeLayoutController.getInstance(project)

    /** Swapped for a fake in tests; the real one needs a live diff framework. */
    internal var presenter: ReviewDiffPresenter = EditorTabDiffPresenter(project)

    private var session: ReviewSession? = null

    /**
     * The diff viewer's toolbar, in two groups: per-file navigation on the left, then a separator,
     * then the session and queue controls.
     *
     * The scope control leads the second group: since KAN-5 removed the tool window, the diff
     * toolbar is where the scope is chosen from inside a pass, and it belongs with the session
     * commands rather than among the per-file navigation.
     *
     * The `RightAlignedToolbarAction` marker is still applied to the five session controls, but it
     * does not push them to the toolbar's right edge here: `DiffHeaderToolbarUtil.createLayoutPanel`
     * lays this toolbar out with `align(AlignX.LEFT).resizableColumn()`, which anchors the whole
     * component at its preferred width on the left, so there is no right edge to align against
     * inside it. The marker is kept anyway — it is harmless, documents the intended grouping, and
     * would take effect unchanged if the surrounding layout ever gave the toolbar room to flush
     * against. Until then, the `Separator` below is what actually draws the boundary between the
     * two groups.
     *
     * The navigation actions are resolved by id, because that is what makes the button tooltip carry
     * the keyboard shortcut. The second historical reason — that a pass could be started from Find
     * Action without the tool window ever being constructed — is now simply always the case: KAN-5
     * deleted the tool window, so every pass starts without one. A guided diff with no toolbar buttons
     * would strand the user with only a tab close as the way out, which is what these ids prevent.
     *
     * The session controls are constructed directly instead. Registering the confirming variants in
     * plugin.xml would list them in Find Action beside the originals: eight entries for four
     * commands, half of them confirming and half not. `DiffScopeAction` is constructed here for a
     * different reason — it is a combo box with no meaning outside this toolbar, and its children
     * are the registered `ReviewQueue.ScopeMenu` group, which Find Action already lists.
     */
    internal val diffActions: List<AnAction> by lazy {
        val manager = ActionManager.getInstance()
        listOfNotNull(
            manager.getAction("ReviewQueue.ShowFileList"),
            manager.getAction("ReviewQueue.PreviousFile"),
            manager.getAction("ReviewQueue.NextFile"),
            manager.getAction("ReviewQueue.MarkReviewed"),
            manager.getAction("ReviewQueue.ToggleReviewed"),
        ) + listOf(
            Separator.getInstance(),
            DiffScopeAction(),
            DiffStartReviewAction(),
            DiffEndReviewAction(),
            DiffRefreshQueueAction(),
            DiffResetAllAction(),
        )
    }

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    // The user closed the review tab by hand: treat it as leaving the review, or the
                    // IDE is left with the Project window hidden and no diff open — no obvious way
                    // back, and nothing on screen to explain why the panel is gone. KAN-5 reduced the
                    // hidden set from two windows to one; it did not make this recovery optional.
                    if (session != null && presenter.isShowing(file)) end()
                }
            },
        )
    }

    val isActive: Boolean get() = session != null

    /** True when the pass is sitting on its first file, where Previous File has nothing to do. */
    val isAtFirstFile: Boolean get() = session?.isAtFirst ?: false

    /** True when the pass is sitting on its last file, where Next File has nothing to do. */
    val isAtLastFile: Boolean get() = session?.isAtLast ?: false

    fun currentKey(): ReviewKey? = session?.current

    /** The files a pass should walk: unreviewed, and renderable by the diff framework. */
    private fun unreviewedShowableKeys(): List<ReviewKey> =
        queue.snapshot().items
            .filterNot { queue.isReviewed(it) }
            .map { it.key }
            .filter { queue.changeFor(it) != null }

    /**
     * Begins a pass, and reports whether one is actually running afterwards.
     *
     * The return value exists because callers cannot predict it. `unreviewedShowableKeys()` filters on
     * what the queue holds, but whether a file can be *shown* is only answerable by asking the
     * presenter, so a pass can still fail to start after that filter passes. `StartReviewAction` used
     * to guess with its own weaker predicate and stayed silent when the guess was wrong.
     *
     * The layout is hidden only once a file is genuinely on screen. Hiding first meant a pass that
     * could not render anything hid the Project window and immediately restored it through [end] — a
     * flash, with nothing reported.
     */
    fun start(): Boolean {
        if (session != null) return true
        session = ReviewSession.start(unreviewedShowableKeys()) ?: return false
        showCurrent()
        if (session == null) return false
        layout.hideForReview()
        return true
    }

    /**
     * Changes the review scope, restarting a running pass in the new scope.
     *
     * With no pass running this records the scope **and resolves it immediately**:
     * [ReviewQueueService.setScope] fires `refresh()`. Two consequences follow, both currently
     * accepted rather than hidden. Git is resolved twice per user intent, because the next
     * [ReviewQueueService.resolveNow] — from Start Review, Show File List or Refresh — resolves the
     * same scope again and the first result is discarded. And `setScope` records the scope *before*
     * its rebuild lands, so `snapshot()` reports a scope that does not yet describe `items` — the same
     * scope/items mismatch `resolveNow` was rewritten to avoid, and this is now the last path that can
     * produce it.
     *
     * Do **not** "fix" this by deleting `setScope`'s `refresh()` on its own: that makes the mismatch
     * permanent rather than brief, since nothing would then replace `items` until the next gesture.
     * Either change here is a behaviour change and needs its own test.
     *
     * With a pass running it resolves synchronously and rebuilds the session **in place**: the
     * layout is already hidden and stays hidden, and [showCurrent] replaces the diff tab. Going
     * through [end] then [start] instead would restore the layout and re-hide it, flashing the
     * Project tool window open and shut mid-pass.
     *
     * [ReviewQueueService.resolveNow] runs before `session` is reassigned, so a cancelled progress
     * leaves the pass exactly where it was.
     *
     * **There are two ways the new scope can end the pass, and both must report it**, because a gesture
     * that silently ends the pass the reviewer was running is the one outcome `QueueNotices` exists
     * for. [ReviewSession.start] returns null when the queue holds no unreviewed key at all; and when
     * it does return a session, [showCurrent] can still fail to render every key in it — the diff
     * framework refuses binaries — and ends the pass itself. The second route only asks the queue, not
     * the presenter, so it cannot be predicted from `unreviewedShowableKeys()`; it is detected after the
     * fact, by `session` having gone null. Either way the layout is restored by the ordinary path,
     * through [end].
     *
     * The completion balloon is deliberately **suppressed** for this resolve. Marks are
     * content-addressed, so files carried into the new scope arrive already marked and a scope that
     * happens to be fully reviewed would fire "All N files reviewed" — with its `/myflow-do-done` copy
     * action — for a pass that was never run, immediately before the pass that *did* have unreviewed
     * work ended. The balloon means "you have finished reviewing", not "this scope is complete", so
     * selecting a scope must not be able to raise it; the arming state is still consumed, so the next
     * genuine completion behaves exactly as it would have.
     *
     * Deliberately does not confirm: `SetScopeAction` owns that dialog, because
     * `ReviewSessionServiceTest` drives this service headlessly and a `Messages` call here would
     * hang or fail the suite.
     */
    fun switchScope(scope: ReviewScope) {
        if (session == null) {
            queue.setScope(scope)
            return
        }
        if (!queue.resolveNow(scope, announceCompletion = false)) return
        val rebuilt = ReviewSession.start(unreviewedShowableKeys())
        if (rebuilt == null) {
            end()
        } else {
            session = rebuilt
            showCurrent()
            // showCurrent() ends the pass itself when nothing left in the new scope can be rendered.
            if (session != null) return
        }
        QueueNotices.emptyResult(project, queue.snapshot())
    }

    /**
     * Marks the file on screen and moves on — unless the mark did not land.
     *
     * A background rebuild (a fix round, a `git add`, an IDE save) can drop the current file from
     * the queue while the user is reading it. `markReviewed` then stores nothing, and advancing
     * anyway would skip the file unmarked with no signal at all: the progress count would not move
     * and the user would never know. Re-show instead, which re-settles against the live queue and
     * lands on something real or ends the pass.
     */
    fun markCurrent() {
        val key = session?.current ?: return
        if (queue.markReviewed(key)) {
            advance()
        } else {
            thisLogger().warn(
                "Review Queue: ${key.storageKey()} left the queue before it could be marked; " +
                    "re-settling instead of advancing"
            )
            showCurrent()
        }
    }

    fun toggleCurrent() {
        val key = session?.current ?: return
        queue.toggleReviewed(key)
    }

    /** Back one live file, marks untouched. At the first file, or with nothing live behind, a no-op. */
    fun previous() {
        val active = session ?: return
        navigate(active, (active.index - 1) downTo 0)
    }

    /**
     * Forward one file, marks untouched — the counterpart to [previous].
     *
     * Deliberately not routed through the private `advance()`, which ends the pass when it runs off
     * the end because that is what marking the last file should do. A plain forward move off the last
     * file has nothing to do and must leave the reviewer where they are.
     */
    fun nextFile() {
        val active = session ?: return
        navigate(active, (active.index + 1) until active.keys.size)
    }

    /**
     * The shared move for [previous] and [nextFile]: land on the first file in [candidates] that is
     * still in the queue and can be rendered, or leave the pass exactly where it is.
     *
     * Deliberately **not** routed through [showCurrent]. That method settles *forward* and ends the
     * pass when nothing showable remains — correct for `advance()` after a mark and for [jumpTo],
     * both of which document ending, and wrong for navigation: a Next File press whose successor has
     * left the scope mid-pass would end the pass, which [nextFile]'s own contract forbids, and a
     * Previous File press whose predecessor has left would settle forward onto the file already on
     * screen, closing and reopening the tab and losing the scroll position.
     *
     * [candidates] is ordered by distance from the cursor and carries the direction, so a settle can
     * never overshoot backwards past the current file.
     */
    private fun navigate(active: ReviewSession, candidates: IntProgression) {
        val live = liveKeys()
        val target = candidates.firstOrNull { active.keys[it] in live } ?: return
        val moved = active.copy(index = target)
        val key = moved.current ?: return
        // A dead destination and an unrenderable one are handled *differently*, on purpose. The search
        // above skips over files that have left the queue; a file that is still queued but will not
        // render stops the move instead, leaving the reviewer where they are. Chaining on to a further
        // candidate would be a second move nobody asked for. The delta spec spells both cases out —
        // an earlier draft of it described the chaining this declines to do.
        if (presenter.show(key, moved.position, moved.total, diffActions)) session = moved
    }

    /**
     * Moves the pass to [key] and shows it. Returns false when there is no session, [key] is not
     * part of it, or the jump ends the pass because nothing at or after the target is still
     * showable, so the caller can open it as a browsing diff instead. In every `true` case, a file
     * is on screen.
     *
     * Goes through [showCurrent] like every other move, so a jump to a file that has since left the
     * queue settles forward onto the next live one rather than failing — the same behaviour marking
     * already has.
     */
    fun jumpTo(key: ReviewKey): Boolean {
        val moved = session?.jumpTo(key) ?: return false
        session = moved
        showCurrent()
        // showCurrent() ends the pass when nothing at or after the target is still showable.
        return session != null
    }

    /** Ends the pass, restoring the layout. Every mark made so far is kept. */
    fun end() {
        session = null
        presenter.close()
        // Deliberately no explicit show of any tool window: restore() reopens exactly what
        // hideForReview() recorded — the Project window, and only when it was open before the pass.
        // Forcing it open would pop a panel the user never had, which is the ordinary case when the
        // review was started from Find Action with the Project window already closed. The rule is
        // "restore only what was hidden", and it outlived the second window KAN-5 removed.
        layout.restore()
    }

    private fun advance() {
        val next = session?.advance()
        if (next == null) {
            end()
            return
        }
        session = next
        showCurrent()
    }

    /**
     * Shows the current file, skipping forward over anything that has left the queue or cannot be
     * rendered. Ends the pass when nothing showable remains.
     */
    private fun showCurrent() {
        var candidate = session?.settleOn(liveKeys())
        while (candidate != null) {
            val key = candidate.current
            if (key != null && presenter.show(key, candidate.position, candidate.total, diffActions)) {
                session = candidate
                return
            }
            candidate = candidate.advance()
        }
        end()
    }

    /** The keys the queue still holds — what "live" means to every move in this class. */
    private fun liveKeys(): Set<ReviewKey> =
        queue.snapshot().items.mapTo(mutableSetOf()) { it.key }

    override fun dispose() {
        session = null
    }

    companion object {
        fun getInstance(project: Project): ReviewSessionService = project.service()
    }
}
