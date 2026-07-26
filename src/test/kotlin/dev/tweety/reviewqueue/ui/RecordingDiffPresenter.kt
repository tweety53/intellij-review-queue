package dev.tweety.reviewqueue.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.vfs.VirtualFile
import dev.tweety.reviewqueue.model.ReviewKey

/**
 * The `ReviewSessionService.presenter` seam, filled in for tests.
 *
 * Shared because five test classes had grown their own copy of it, and two of the shapes had already
 * drifted apart — one recorded what was shown, the other only whether it could show. Both facts are
 * load-bearing: [shown] is how "the tab was replaced in place, never closed and reopened" is observed,
 * and [canShow] `= false` is the only way to reach the "unreviewed but unrenderable" branch that
 * `start()` reports on.
 *
 * @param canShow what [show] returns — false models a change the diff framework refuses to render.
 */
class RecordingDiffPresenter(private val canShow: Boolean = true) : ReviewDiffPresenter {

    /** Every key handed to [show], in order, including repeats — a repeat is a tab reload. */
    val shown = mutableListOf<ReviewKey>()

    var closes = 0
        private set

    override fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean {
        shown += key
        return canShow
    }

    override fun close() {
        closes++
    }

    /**
     * Always false: nothing here opens a real file, so no file can be the one on screen. Returning
     * true would make `ReviewSessionService`'s `fileClosed` listener end the pass on any tab close.
     */
    override fun isShowing(file: VirtualFile) = false
}
