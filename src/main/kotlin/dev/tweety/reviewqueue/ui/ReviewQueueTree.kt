package dev.tweety.reviewqueue.ui

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangeNodeDecorator
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.tree.TreeUtil
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.queue.QueueSnapshot
import dev.tweety.reviewqueue.queue.ReviewQueueService
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/**
 * The queue as a platform changes tree: repo grouping, path shortening and file icons come from
 * the platform; the reviewed marker is the only thing this class adds.
 *
 * Deliberately omitted, and to stay omitted: `installPopupHandler`, subclassing `ChangesListView`,
 * and publishing `VcsDataKeys.CHANGES` / `VcsDataKeys.SELECTED_CHANGES` from this component. Each
 * of those hands the standard VCS change actions — Rollback above all — a live selection inside a
 * read-only review UI, putting a repository mutation one keystroke away. This plugin must never
 * mutate a repository, so the tree installs no context menu and exposes no change data keys.
 */
class ReviewQueueTree(
    private val project: Project,
    private val service: ReviewQueueService,
) : ChangesTree(project, false, false) {

    private var reviewedKeys: Set<String> = emptySet()
    private var refreshing = false
    private var activationHandler: ((ReviewKey) -> Unit)? = null

    init {
        setEmptyText("No files in the current review scope")

        // The diff opens only from an explicit user gesture — never from a programmatic
        // re-selection, which would steal focus on every background VCS event.
        setDoubleClickAndEnterKeyHandler { fireActivated() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger || e.button != MouseEvent.BUTTON1) return
                if (getPathForLocation(e.x, e.y) == null) return
                fireActivated()
            }
        })
    }

    /** Called when the user clicks a row, double-clicks it, or presses Enter on it. */
    fun setActivationHandler(handler: (ReviewKey) -> Unit) {
        activationHandler = handler
    }

    /**
     * True while this tree is rewriting its own model and selection. Selection events raised in
     * that window are the tree talking to itself, not the user, and must not be fed back into the
     * service: `ChangesTree` fires `valueChanged` synchronously, so without this guard
     * service -> refreshFrom -> selection -> service recurses until the EDT stack overflows.
     */
    val isProgrammaticUpdate: Boolean
        get() = refreshing || isModelUpdateInProgress

    override fun rebuildTree() {
        refreshFrom(service.snapshot())
    }

    fun refreshFrom(snapshot: QueueSnapshot) {
        refreshing = true
        try {
            reviewedKeys = snapshot.items
                .filter { service.isReviewed(it) }
                .mapTo(mutableSetOf()) { it.key.storageKey() }

            val changes: List<Change> = snapshot.items.mapNotNull { service.changeFor(it.key) }
            val model: DefaultTreeModel = TreeModelBuilder(project, grouping)
                .setChanges(changes, ReviewedDecorator())
                .build()
            updateTreeModel(model)

            val cursorKey = snapshot.cursor?.let { snapshot.items.getOrNull(it)?.key }
            if (cursorKey != null) selectKey(cursorKey)
        } finally {
            refreshing = false
        }
    }

    /**
     * The queue key for a change. Looked up, never re-derived: deriving it again from a
     * longest-prefix match over root paths disagrees with the queue whenever roots are nested
     * (submodules), and the disagreement ends with the user marking a different file than the one
     * they selected.
     */
    fun keyFor(change: Change): ReviewKey? = service.keyFor(change)

    fun selectedKey(): ReviewKey? {
        val node = selectionPath?.lastPathComponent as? ChangesBrowserNode<*> ?: return null
        val change = node.userObject as? Change ?: return null
        return keyFor(change)
    }

    private fun fireActivated() {
        val handler = activationHandler ?: return
        selectedKey()?.let(handler)
    }

    private fun selectKey(key: ReviewKey) {
        val root = model.root as? TreeNode ?: return
        val match = TreeUtil.treeNodeTraverser(root)
            .traverse()
            .filter(ChangesBrowserNode::class.java)
            .find { node -> (node.userObject as? Change)?.let { keyFor(it) } == key }
        if (match == null) {
            thisLogger().warn("Review queue: no tree node for cursor key ${key.storageKey()}")
            return
        }
        TreeUtil.selectNode(this, match)
    }

    /** Appends the reviewed marker; everything else about the row is the platform's rendering. */
    private inner class ReviewedDecorator : ChangeNodeDecorator {
        override fun decorate(change: Change, component: SimpleColoredComponent, isShowFlatten: Boolean) {
            val key = keyFor(change) ?: return
            if (key.storageKey() in reviewedKeys) {
                component.append("  ✓ reviewed", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        override fun preDecorate(change: Change, renderer: ChangesBrowserNodeRenderer, isShowFlatten: Boolean) = Unit
    }
}
