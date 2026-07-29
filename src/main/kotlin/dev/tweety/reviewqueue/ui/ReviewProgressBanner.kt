package dev.tweety.reviewqueue.ui

import com.intellij.diff.FrameDiffTool
import com.intellij.diff.util.DiffNotificationProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.JProgressBar

/**
 * The `N / M files reviewed` strip above the review diff.
 *
 * Delivered through `DiffUserDataKeys.NOTIFICATION_PROVIDERS` rather than a component the presenter
 * builds itself: that key is the platform's supported way to put something above a diff's content,
 * and it survives the presenter replacing the tab per file.
 *
 * The count is read from `QueueSnapshot.reviewedCount` and never recomputed here. A second count
 * could disagree with the file-list popup's own `N / M reviewed` title, which reads the same
 * snapshot — two numbers on screen describing the same thing is worse than none.
 */
object ReviewProgressBanner {

    fun text(reviewedCount: Int, total: Int, scope: ReviewScope): String =
        "$reviewedCount / $total files reviewed  •  ${scope.displayName()}"

    /** Guarded against an empty scope, where the obvious division is a NaN in a progress bar. */
    fun fraction(reviewedCount: Int, total: Int): Double =
        if (total <= 0) 0.0 else reviewedCount.toDouble() / total

    /**
     * `createNotification` runs once per viewer, so the component subscribes rather than rendering a
     * snapshot and stopping. Marking replaces the diff tab and would refresh the number by accident,
     * but **Toggle Reviewed does not replace the tab**, and neither does a background rebuild from a
     * fix round — the two gestures a reviewer uses to correct a mis-mark are exactly the ones a
     * render-once banner would show a stale count for.
     */
    fun provider(project: Project): DiffNotificationProvider =
        DiffNotificationProvider { viewer: FrameDiffTool.DiffViewer? ->
            // `createNotification`'s parameter is `@Nullable` in the platform signature; no viewer
            // means nothing to bind the panel's lifetime to, so there is no banner rather than one
            // that outlives what it decorates.
            if (viewer == null) return@DiffNotificationProvider null
            val panel = ReviewProgressPanel(project)
            // Bound to the viewer, not the project: the listener must die with the diff it decorates,
            // or every file shown in a long pass leaves another live listener behind.
            Disposer.register(viewer, panel)
            ReviewQueueService.getInstance(project).addListener(panel::refresh, viewer)
            panel.refresh()
            panel
        }
}

/**
 * The banner's component. Holds no *cached queue* state: every repaint re-reads the queue snapshot,
 * so there is exactly one source for the number on screen. (It does hold ordinary Swing component
 * state — the bar and label themselves.)
 */
private class ReviewProgressPanel(private val project: Project) : JBPanel<ReviewProgressPanel>(),
    com.intellij.openapi.Disposable {

    private val bar = JProgressBar(0, 100)
    private val label = JBLabel()

    init {
        layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))
        border = JBUI.Borders.empty(4, 8)
        bar.preferredSize = java.awt.Dimension(JBUI.scale(120), bar.preferredSize.height)
        add(bar)
        add(label)
    }

    fun refresh() {
        val snapshot = ReviewQueueService.getInstance(project).snapshot()
        val total = snapshot.items.size
        label.text = ReviewProgressBanner.text(snapshot.reviewedCount, total, snapshot.scope)
        bar.value = (ReviewProgressBanner.fraction(snapshot.reviewedCount, total) * 100).toInt()
    }

    override fun dispose() = Unit
}
