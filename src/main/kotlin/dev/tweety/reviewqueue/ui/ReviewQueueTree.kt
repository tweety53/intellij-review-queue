package dev.tweety.reviewqueue.ui

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
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/**
 * The queue as a platform changes tree: repo grouping, path shortening and file icons come from
 * the platform; the reviewed marker is the only thing this class adds.
 */
class ReviewQueueTree(
    private val project: Project,
    private val service: ReviewQueueService,
) : ChangesTree(project, false, false) {

    private var reviewedKeys: Set<String> = emptySet()
    private var rootPaths: List<String> = emptyList()

    init {
        setEmptyText("No files in the current review scope")
    }

    override fun rebuildTree() {
        refreshFrom(service.snapshot())
    }

    fun refreshFrom(snapshot: QueueSnapshot) {
        reviewedKeys = snapshot.items
            .filter { service.isReviewed(it) }
            .mapTo(mutableSetOf()) { it.key.storageKey() }
        rootPaths = snapshot.items.map { it.key.rootPath }.distinct()

        val changes: List<Change> = snapshot.items.mapNotNull { service.changeFor(it.key) }
        val model: DefaultTreeModel = TreeModelBuilder(project, grouping)
            .setChanges(changes, ReviewedDecorator())
            .build()
        updateTreeModel(model)

        val cursorKey = snapshot.cursor?.let { snapshot.items.getOrNull(it)?.key }
        if (cursorKey != null) selectKey(cursorKey)
    }

    /** The queue key for a change, resolved against the roots currently in the tree. */
    fun keyFor(change: Change): ReviewKey? {
        val path = (change.afterRevision ?: change.beforeRevision)?.file?.path ?: return null
        val root = rootPaths
            .filter { path == it || path.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: return null
        return ReviewKey(root, path.removePrefix(root).removePrefix("/"))
    }

    fun selectedKey(): ReviewKey? {
        val node = selectionPath?.lastPathComponent as? ChangesBrowserNode<*> ?: return null
        val change = node.userObject as? Change ?: return null
        return keyFor(change)
    }

    private fun selectKey(key: ReviewKey) {
        val root = model.root as? TreeNode ?: return
        val match = TreeUtil.treeNodeTraverser(root)
            .traverse()
            .filter(ChangesBrowserNode::class.java)
            .find { node -> (node.userObject as? Change)?.let { keyFor(it) } == key }
            ?: return
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
