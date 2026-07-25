package dev.tweety.reviewqueue.queue

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
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

    /** Actions shown in the diff toolbar. Set by the UI layer once, at tool window creation. */
    internal var diffActions: List<AnAction> = emptyList()

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

    fun markCurrent() {
        val key = session?.current ?: return
        queue.markReviewed(key)
        advance()
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

    /** Ends the pass, restoring the layout. Every mark made so far is kept. */
    fun end() {
        session = null
        presenter.close()
        layout.restore()
        ToolWindowManager.getInstance(project).getToolWindow("Review Queue")?.show(null)
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
