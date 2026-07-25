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

        // ChangeDiffRequestProducer.create is @Nullable — it returns null for a change the diff
        // framework cannot render. Filtering keeps keys and producers aligned; letting a null into
        // the chain would break diff opening for every file in the queue, not just that one.
        val entries = snapshot.items.mapNotNull { item ->
            val change = service.changeFor(item.key) ?: return@mapNotNull null
            ChangeDiffRequestProducer.create(project, change)?.let { item.key to it }
        }
        if (entries.isEmpty()) return

        // With the list properly filtered, -1 is a genuine "this file has no diff to show".
        // Falling back to index 0 would open an unrelated file.
        val index = entries.indexOfFirst { it.first == key }
        if (index < 0) return

        val chain = ChangeDiffRequestChain(entries.map { it.second }, index)
        val file = ChainDiffVirtualFile(chain, "Review Queue")
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }
}
