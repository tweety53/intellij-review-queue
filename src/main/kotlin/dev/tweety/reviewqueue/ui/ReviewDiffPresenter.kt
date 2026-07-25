package dev.tweety.reviewqueue.ui

import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import com.intellij.openapi.vfs.VirtualFile
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Shows one file of a session at a time. Implemented against an interface so the session flow can be tested. */
interface ReviewDiffPresenter {
    /** Returns false when the file cannot be rendered, so the caller can skip it. */
    fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean
    fun close()
    fun isShowing(file: VirtualFile): Boolean
}

/**
 * Opens each file as its own diff editor tab, replacing the previous one.
 *
 * Deliberately not one chain navigated with `setCurrentRequest`: `DiffRequestProcessor` exposes no
 * data key and no accessor, so reaching it from an action would mean casting into `impl` internals.
 * Replacing the tab costs a visible swap per file and depends on nothing internal.
 */
class EditorTabDiffPresenter(private val project: Project) : ReviewDiffPresenter {

    private var openFile: ChainDiffVirtualFile? = null

    override fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean {
        val change = ReviewQueueService.getInstance(project).changeFor(key) ?: return false
        val producer = ChangeDiffRequestProducer.create(project, change) ?: return false

        val chain = ChangeDiffRequestChain(listOf(producer), 0)
        chain.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions)

        val name = key.relPath.substringAfterLast('/')
        val file = ChainDiffVirtualFile(chain, "Review $position/$total - $name")

        close()
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
        openFile = file
        return true
    }

    override fun close() {
        val file = openFile ?: return
        // Cleared first so the session's file-closed listener does not treat our own close as the
        // user abandoning the review.
        openFile = null
        FileEditorManager.getInstance(project).closeFile(file)
    }

    override fun isShowing(file: VirtualFile): Boolean = openFile == file
}
