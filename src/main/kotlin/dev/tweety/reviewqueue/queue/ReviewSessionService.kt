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
import dev.tweety.reviewqueue.actions.diff.DiffStartReviewAction
import dev.tweety.reviewqueue.core.ReviewSession
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.ui.EditorTabDiffPresenter
import dev.tweety.reviewqueue.ui.IdeLayoutController
import dev.tweety.reviewqueue.ui.ReviewDiffPresenter

/**
 * Runs a guided review pass: hide the panels, walk the files one at a time, restore on the way out.
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
     * The `RightAlignedToolbarAction` marker is still applied to the four session controls, but it
     * does not push them to the toolbar's right edge here: `DiffHeaderToolbarUtil.createLayoutPanel`
     * lays this toolbar out with `align(AlignX.LEFT).resizableColumn()`, which anchors the whole
     * component at its preferred width on the left, so there is no right edge to align against
     * inside it. The marker is kept anyway — it is harmless, documents the intended grouping, and
     * would take effect unchanged if the surrounding layout ever gave the toolbar room to flush
     * against. Until then, the `Separator` below is what actually draws the boundary between the
     * two groups.
     *
     * The navigation actions are resolved by id, because that is what makes the button tooltip
     * carry the keyboard shortcut, and because Start Review is reachable from Find Action without
     * the tool window ever being constructed — a guided diff with no toolbar buttons strands the
     * user with only a tab close as the way out.
     *
     * The four session controls are constructed directly instead. Registering the confirming
     * variants in plugin.xml would list them in Find Action beside the originals: eight entries for
     * four commands, half of them confirming and half not.
     */
    internal val diffActions: List<AnAction> by lazy {
        val manager = ActionManager.getInstance()
        listOfNotNull(
            manager.getAction("ReviewQueue.ShowFileList"),
            manager.getAction("ReviewQueue.PreviousFile"),
            manager.getAction("ReviewQueue.MarkReviewed"),
            manager.getAction("ReviewQueue.ToggleReviewed"),
        ) + listOf(
            Separator.getInstance(),
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
                    // IDE is left with both tool windows hidden and no obvious way back.
                    if (session != null && presenter.isShowing(file)) end()
                }
            },
        )
    }

    val isActive: Boolean get() = session != null

    /** True when the pass is sitting on its first file, where Previous File has nothing to do. */
    val isAtFirstFile: Boolean get() = session?.isAtFirst ?: false

    fun currentKey(): ReviewKey? = session?.current

    fun start() {
        if (session != null) return
        val snapshot = queue.snapshot()
        val keys = snapshot.items
            .filterNot { queue.isReviewed(it) }
            .map { it.key }
            .filter { queue.changeFor(it) != null }
        session = ReviewSession.start(keys) ?: return
        layout.hideForReview()
        showCurrent()
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

    fun previous() {
        val active = session ?: return
        if (active.isAtFirst) return
        session = active.back()
        showCurrent()
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
        // Deliberately no explicit show of the Review Queue tool window: restore() already reopens
        // it when it was open before the session. Forcing it open pops a panel the user never had
        // when the review was started from Find Action.
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
        val live = queue.snapshot().items.mapTo(mutableSetOf()) { it.key }
        var candidate = session?.settleOn(live)
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

    override fun dispose() {
        session = null
    }

    companion object {
        fun getInstance(project: Project): ReviewSessionService = project.service()
    }
}
