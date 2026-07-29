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
class RecordingToolWindow(
    project: Project,
    private var visible: Boolean,
    /**
     * Models `hide()` being called and returning normally without the window actually going
     * invisible — the failure mode fix round 2's post-hide check (finding D) exists to catch.
     * `hides` still counts the call; only the visibility flip is skipped.
     */
    private val ignoresHide: Boolean = false,
    /**
     * Models a plugin that disposes its content on `hide()`, so any *later* `isVisible()` query
     * throws (e.g. `AlreadyDisposedException`) instead of returning a value — pass-2 round 2's
     * Important 1: the post-hide diagnostic re-queries ids that were just passed to `hide()`, and
     * must never let that throw escape `hideForReview()`. `isVisible()` still answers normally
     * before `hide()` is called, so the sweep's own pre-hide filter is unaffected — only the
     * post-hide re-query can observe the throw, matching the real failure this models.
     */
    private val throwsOnIsVisibleAfterHide: Boolean = false,
    /**
     * Models `hide()` itself throwing (pass-3 panel Minor 2) — a third-party tool window whose
     * `hide()` blows up mid-sweep, e.g. because it eagerly tears down content. `hides` still counts
     * the call before throwing, so a test can tell "reached and attempted" apart from "never reached"
     * for the ids after it in the sweep.
     */
    private val throwsOnHide: Boolean = false,
) : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {

    var shows = 0
        private set

    var hides = 0
        private set

    private var disposedAfterHide = false

    override fun isVisible(): Boolean {
        if (disposedAfterHide) throw IllegalStateException("tool window content was disposed on hide")
        return visible
    }

    override fun show(runnable: Runnable?) {
        shows++
        visible = true
    }

    override fun hide(runnable: Runnable?) {
        hides++
        if (throwsOnHide) throw IllegalStateException("hide() blew up for this tool window")
        if (!ignoresHide) visible = false
        if (throwsOnIsVisibleAfterHide) disposedAfterHide = true
    }
}

/**
 * Resolves only the windows a test [register]s, so an id the plugin should not be touching resolves to
 * null exactly as an unregistered window would.
 */
class RecordingToolWindowManager(private val project: Project) : ToolWindowHeadlessManagerImpl(project) {

    private val windows = mutableMapOf<String, RecordingToolWindow>()

    fun register(
        id: String,
        visible: Boolean,
        ignoresHide: Boolean = false,
        throwsOnIsVisibleAfterHide: Boolean = false,
        throwsOnHide: Boolean = false,
    ): RecordingToolWindow =
        RecordingToolWindow(project, visible, ignoresHide, throwsOnIsVisibleAfterHide, throwsOnHide)
            .also { windows[id] = it }

    override fun getToolWindow(id: String?): ToolWindow? = windows[id]

    /**
     * The sweep in `hideForReview` enumerates ids rather than consulting a fixed list, so the
     * fixture has to answer this as well as [getToolWindow]. Returning only registered ids keeps
     * the two in agreement: every id this returns resolves, and nothing else does.
     */
    override val toolWindowIds: Array<String>
        get() = windows.keys.toTypedArray()

    companion object {
        /** Installs a manager for [project] and returns it, undone when [parent] is disposed. */
        fun install(project: Project, parent: Disposable): RecordingToolWindowManager =
            RecordingToolWindowManager(project).also {
                project.replaceService(ToolWindowManager::class.java, it, parent)
            }
    }
}
