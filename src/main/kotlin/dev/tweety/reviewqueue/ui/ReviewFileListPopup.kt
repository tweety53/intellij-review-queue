package dev.tweety.reviewqueue.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.EmptyIcon
import dev.tweety.reviewqueue.core.ReviewFileList
import dev.tweety.reviewqueue.core.ReviewFileRow
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService
import javax.swing.JList

/**
 * The whole change, from inside the diff: every file in the current scope with its reviewed state.
 *
 * Lists the full scope rather than just the pass, because "which files are already done" is most of
 * the question being asked. Picking a file in the pass moves the cursor; picking one outside it —
 * anything already reviewed when the pass started — opens a browsing diff and leaves the pass alone.
 *
 * That browse-vs-jump rule was inherited from the tool window's queue tree, which KAN-5 deleted along
 * with the rest of the panel. This popup is now the only way to browse the queue, in a pass or out of
 * one, so the rule no longer has a counterpart to stay consistent with — it stands on its own merits.
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
        // Empty is now routine, not a race: enablement follows git-root existence, so this action is
        // clickable with nothing staged. ShowFileListAction reports that case via
        // QueueNotices.emptyResult before calling here, so this stays a plain no-op guard for
        // the narrow window where the queue empties between that check and this call.
        if (rows.isEmpty()) return

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            // The scope name is here because the deleted panel's label carried it: `N / M files
            // reviewed  •  <scope>` was the only always-visible statement of what the queue was
            // listing. Formatted through ReviewProgressBanner.text so the two surfaces that read the
            // same QueueSnapshot cannot drift in wording the way they already had.
            .setTitle(ReviewProgressBanner.text(snapshot.reviewedCount, snapshot.items.size, snapshot.scope))
            .setRenderer(rowRenderer())
            .setSelectedValue(rows.firstOrNull { it.isCurrent }, true)
            .setItemChosenCallback { row -> open(project, session, queue, row) }
            .setNamerForFiltering { it.label }
            .createPopup()
            .showInBestPositionFor(dataContext)
    }

    /**
     * How one row is drawn. Extracted from the builder chain so the label can be asserted without a
     * real popup — `showInBestPositionFor` needs a live component, which is why the popup itself stays a
     * Gate C check.
     *
     * Subclassed rather than `SimpleListCellRenderer.create { ... }`: that Customizer overload is
     * deprecated and scheduled for removal, and KAN-5 holds `verifyPlugin` at zero deprecated-API
     * usages. `customize` is the supported seam and takes the same two assignments.
     *
     * **The label goes through [HtmlChunk], because the path is repository-controlled and this is a
     * `JBLabel`.** Swing renders a label's text as HTML whenever it begins with `<html>`, and a tracked
     * file may legally be named `<html><img src="http://…">` — which made merely *drawing* the row issue
     * an outbound request to a host the file name picked, in a plugin whose one invariant is that it only
     * ever reads. Escaping alone would be safe but would show the reviewer literal `&lt;` entities, so the
     * text is escaped *and* wrapped: the label is in HTML mode deliberately, and nothing from the path can
     * be markup inside it.
     */
    internal fun rowRenderer(): SimpleListCellRenderer<ReviewFileRow> =
        object : SimpleListCellRenderer<ReviewFileRow>() {
            override fun customize(
                list: JList<out ReviewFileRow>,
                row: ReviewFileRow,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = HtmlChunk.html().addText(row.label).toString()
                icon = if (row.isReviewed) AllIcons.Actions.Checked else EmptyIcon.ICON_16
            }
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
        // Guarded because a change the diff framework cannot render would otherwise open an empty
        // tab. The queue tree used to carry the same guard and KAN-5 deleted it, so this is now the
        // only copy: nothing else stands between a browse pick and ReviewDiffOpener.
        if (queue.changeFor(row.key) != null) ReviewDiffOpener.open(project, row.key)
    }
}
