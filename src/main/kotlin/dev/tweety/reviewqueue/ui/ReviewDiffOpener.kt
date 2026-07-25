package dev.tweety.reviewqueue.ui

import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import dev.tweety.reviewqueue.core.DiffChainPlanner
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.queue.ReviewQueueService

/**
 * Opens the queue's current file in the standard diff viewer. The chain holds every file in the
 * queue with the current one selected, so the diff tab is reused rather than accumulating tabs.
 */
object ReviewDiffOpener {

    fun open(project: Project, key: ReviewKey) {
        val service = ReviewQueueService.getInstance(project)
        val snapshot = service.snapshot()

        // ChangeDiffRequestProducer.create is @Nullable — it returns null for a change the diff
        // framework cannot render. The pairing, filtering and index arithmetic live in
        // DiffChainPlanner so they can be unit tested without a project or the diff framework.
        val (producers, index) = DiffChainPlanner.plan(
            keys = snapshot.items.map { it.key },
            producerFor = { itemKey ->
                service.changeFor(itemKey)?.let { ChangeDiffRequestProducer.create(project, it) }
            },
            selected = key,
        ) ?: return

        val chain = ChangeDiffRequestChain(producers, index)
        val file = ChainDiffVirtualFile(chain, "Review Queue")
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }
}
