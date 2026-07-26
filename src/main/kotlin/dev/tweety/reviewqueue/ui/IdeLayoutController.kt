package dev.tweety.reviewqueue.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * The only component that changes the user's IDE layout.
 *
 * The set of hidden windows is persisted even though a session is not: quitting the IDE mid-review
 * would otherwise leave the Project window hidden with nothing to explain why. [ReviewLayoutRestorer]
 * replays it on the next project open.
 */
@Service(Service.Level.PROJECT)
@State(name = "ReviewQueueLayout", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class IdeLayoutController(private val project: Project) :
    PersistentStateComponent<IdeLayoutController.State> {

    class State {
        @JvmField
        var hiddenByReview: MutableList<String> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** Hides the managed windows that are currently visible, remembering which they were. */
    fun hideForReview() {
        // A leftover record means a previous restore could not resolve every id — most likely it ran
        // before the tool windows were registered. Reclaim it now, when the IDE is fully up, instead
        // of refusing to hide: an early return here would leave the record latched and silently stop
        // hiding anything for the rest of the run.
        //
        // This also covers the case the early return was written for. A second hide with nothing
        // restored in between would otherwise overwrite the first record with an empty one — the
        // windows are already hidden, so they drop out of the filter below and would never be
        // reopened. Restoring first puts them back in it.
        if (myState.hiddenByReview.isNotEmpty()) restore()

        val manager = ToolWindowManager.getInstance(project)
        val hidden = MANAGED_IDS.filter { manager.getToolWindow(it)?.isVisible == true }
        // Recorded before the windows are hidden: if hiding threw part-way, the record must already
        // name everything this call touched, or a window is hidden with nothing to reopen it.
        myState.hiddenByReview = hidden.toMutableList()
        hidden.forEach { manager.getToolWindow(it)?.hide(null) }
    }

    /**
     * Reopens whatever [hideForReview] hid, then forgets only what it actually reopened. Safe to
     * call when nothing was hidden.
     *
     * An id that does not resolve to a registered tool window stays on the record. Tool-window
     * registration is not guaranteed complete when [ReviewLayoutRestorer] runs at post-startup, and
     * dropping an unresolved id would leave that window hidden with no record that it ever was.
     */
    fun restore() {
        val manager = ToolWindowManager.getInstance(project)
        val unresolved = mutableListOf<String>()
        myState.hiddenByReview.forEach { id ->
            val window = manager.getToolWindow(id)
            if (window == null) unresolved += id else window.show(null)
        }
        myState.hiddenByReview = unresolved
    }

    companion object {
        private val MANAGED_IDS = listOf("Project", "Review Queue")

        fun getInstance(project: Project): IdeLayoutController = project.service()
    }
}
