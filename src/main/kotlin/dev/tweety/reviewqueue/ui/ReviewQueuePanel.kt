package dev.tweety.reviewqueue.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Tool window content: the queue tree, a toolbar, and a progress label.
 *
 * Implements [Disposable] so [ReviewQueueToolWindowFactory] can wire it as the content's disposer;
 * that is what unregisters the [ReviewQueueService] listener when the tool window content is torn down.
 */
class ReviewQueuePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = ReviewQueueService.getInstance(project)
    private val tree = ReviewQueueTree(project, service)
    private val progress = JBLabel("0 / 0 reviewed").apply { border = JBUI.Borders.empty(4, 8) }
    private val errorLabel = JBLabel("").apply { border = JBUI.Borders.empty(0, 8, 4, 8) }

    init {
        val group = ActionManager.getInstance().getAction("ReviewQueue.Toolbar") as DefaultActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val bottom = JPanel(BorderLayout()).apply {
            add(progress, BorderLayout.NORTH)
            add(errorLabel, BorderLayout.SOUTH)
        }
        val content = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        setContent(content)

        tree.addSelectionListener {
            tree.selectedKey()?.let { key ->
                service.selectByKey(key)
                if (service.changeFor(key) != null) {
                    ReviewDiffOpener.open(project, key)
                }
            }
        }

        service.addListener(::update, this)
        service.refresh()
    }

    private fun update() {
        val snapshot = service.snapshot()
        tree.refreshFrom(snapshot)
        progress.text = "${snapshot.reviewedCount} / ${snapshot.items.size} reviewed" +
            "  •  ${snapshot.scope.displayName()}"
        errorLabel.text = snapshot.errors.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        errorLabel.isVisible = snapshot.errors.isNotEmpty()
    }

    override fun dispose() = Unit
}
