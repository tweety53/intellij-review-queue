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
        // ShowFileListAction.update() already keeps the button disabled when the snapshot is empty;
        // this is a defensive no-op for the race where the queue empties between that update and the
        // click landing here, so this stays cheap rather than duplicating the emptiness message.
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
        // Picking the file already on screen must not touch the tab: jumpTo -> showCurrent() ->
        // EditorTabDiffPresenter.show closes and reopens it, which loses scroll position for no
        // reason the user asked for. This guard belongs here rather than in
        // ReviewSessionService.jumpTo: only the popup knows the pick was a no-op from the user's
        // point of view. A self-jump reached through jumpTo any other way — e.g. the current file
        // having left the queue — should still re-settle, and jumpTo needs to keep doing that.
        if (row.isCurrent) return
        // jumpTo refuses anything outside the pass, which is the signal to browse instead of
        // reshuffling what the reviewer is walking through.
        if (session.jumpTo(row.key)) return
        // Guarded the same way the tree guards it: a change the diff framework cannot render would
        // otherwise open an empty tab.
        if (queue.changeFor(row.key) != null) ReviewDiffOpener.open(project, row.key)
    }
}
