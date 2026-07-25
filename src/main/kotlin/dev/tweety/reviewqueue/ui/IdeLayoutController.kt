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
        val manager = ToolWindowManager.getInstance(project)
        val hidden = MANAGED_IDS.filter { manager.getToolWindow(it)?.isVisible == true }
        hidden.forEach { manager.getToolWindow(it)?.hide(null) }
        myState.hiddenByReview = hidden.toMutableList()
    }

    /** Reopens whatever [hideForReview] hid, then forgets it. Safe to call when nothing was hidden. */
    fun restore() {
        val manager = ToolWindowManager.getInstance(project)
        myState.hiddenByReview.forEach { manager.getToolWindow(it)?.show(null) }
        myState.hiddenByReview = mutableListOf()
    }

    companion object {
        private val MANAGED_IDS = listOf("Project", "Review Queue")

        fun getInstance(project: Project): IdeLayoutController = project.service()
    }
}
