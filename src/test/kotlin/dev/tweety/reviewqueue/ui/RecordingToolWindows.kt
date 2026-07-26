package dev.tweety.reviewqueue.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl

/**
 * The injectable seam for everything `IdeLayoutController` does.
 *
 * `ScopeSwitchTest` used to record that there was no such seam and fall back to counting presenter
 * closes as a proxy for "no tool window is hidden or restored". That was false: swapping the project's
 * [ToolWindowManager] through `replaceService` is all it takes, and the proxy could not catch a stray
 * `hideForReview()` on a path that is supposed to leave the layout alone.
 *
 * The platform's own headless tool window hard-codes `isVisible = false` and no-ops `show`/`hide`, so
 * asserting against it would only test the mock. [RecordingToolWindow] actually tracks visibility,
 * which is what lets a test say whether a window was really reopened rather than merely dropped from
 * the record.
 */
class RecordingToolWindow(project: Project, private var visible: Boolean) :
    ToolWindowHeadlessManagerImpl.MockToolWindow(project) {

    var shows = 0
        private set

    var hides = 0
        private set

    override fun isVisible() = visible

    override fun show(runnable: Runnable?) {
        shows++
        visible = true
    }

    override fun hide(runnable: Runnable?) {
        hides++
        visible = false
    }
}

/**
 * Resolves only the windows a test [register]s, so an id the plugin should not be touching resolves to
 * null exactly as an unregistered window would.
 */
class RecordingToolWindowManager(private val project: Project) : ToolWindowHeadlessManagerImpl(project) {

    private val windows = mutableMapOf<String, RecordingToolWindow>()

    fun register(id: String, visible: Boolean): RecordingToolWindow =
        RecordingToolWindow(project, visible).also { windows[id] = it }

    override fun getToolWindow(id: String?): ToolWindow? = windows[id]

    companion object {
        /** Installs a manager for [project] and returns it, undone when [parent] is disposed. */
        fun install(project: Project, parent: Disposable): RecordingToolWindowManager =
            RecordingToolWindowManager(project).also {
                project.replaceService(ToolWindowManager::class.java, it, parent)
            }
    }
}
