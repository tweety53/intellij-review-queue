package dev.tweety.reviewqueue.ui

import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
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

        val withChanges = snapshot.items.mapNotNull { item ->
            service.changeFor(item.key)?.let { item.key to it }
        }
        if (withChanges.isEmpty()) return

        val producers = withChanges.map { (_, change) ->
            ChangeDiffRequestProducer.create(project, change)
        }
        val index = withChanges.indexOfFirst { it.first == key }.coerceAtLeast(0)

        val chain = ChangeDiffRequestChain(producers, index)
        val file = ChainDiffVirtualFile(chain, "Review Queue")
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }
}
