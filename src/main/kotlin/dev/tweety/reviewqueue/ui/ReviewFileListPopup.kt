package dev.tweety.reviewqueue.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.EmptyIcon
import dev.tweety.reviewqueue.core.ReviewFileList
import dev.tweety.reviewqueue.core.ReviewFileRow
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * The whole change, from inside the diff: every file in the current scope with its reviewed state.
 *
 * Lists the full scope rather than just the pass, because "which files are already done" is most of
 * the question being asked. Picking a file in the pass moves the cursor; picking one outside it —
 * anything already reviewed when the pass started — opens a browsing diff and leaves the pass alone,
 * which is exactly what activating a row in the tool-window tree does.
 */
object ReviewFileListPopup {

    fun show(project: Project, dataContext: DataContext) {
        val queue = ReviewQueueService.getInstance(project)
        val session = ReviewSessionService.getInstance(project)
        val snapshot = queue.snapshot()

        val rows = ReviewFileList.rows(
            items = snapshot.items,
            reviewed = { queue.isReviewed(it) },
            current = session.currentKey(),
        )
        if (rows.isEmpty()) return

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setTitle("${snapshot.reviewedCount} / ${snapshot.items.size} reviewed")
            .setRenderer(
                SimpleListCellRenderer.create<ReviewFileRow> { label, row, _ ->
                    label.text = row.label
                    label.icon = if (row.isReviewed) AllIcons.Actions.Checked else EmptyIcon.ICON_16
                }
            )
            .setSelectedValue(rows.firstOrNull { it.isCurrent }, true)
            .setItemChosenCallback { row -> open(project, session, queue, row) }
            .setNamerForFiltering { it.label }
            .createPopup()
            .showInBestPositionFor(dataContext)
    }

    private fun open(
        project: Project,
        session: ReviewSessionService,
        queue: ReviewQueueService,
        row: ReviewFileRow,
    ) {
        // jumpTo refuses anything outside the pass, which is the signal to browse instead of
        // reshuffling what the reviewer is walking through.
        if (session.jumpTo(row.key)) return
        // Guarded the same way the tree guards it: a change the diff framework cannot render would
        // otherwise open an empty tab.
        if (queue.changeFor(row.key) != null) ReviewDiffOpener.open(project, row.key)
    }
}
