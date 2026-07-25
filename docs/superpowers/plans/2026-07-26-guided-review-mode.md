# Guided Review Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Review Queue from a panel you click through into a guided mode: pick a scope, press Start Review, the side panels hide, and files open one at a time with Mark Reviewed directly above the code.

**Architecture:** A new pure `ReviewSession` holds the position of a guided pass. `ReviewSessionService` orchestrates it, composing the existing `ReviewQueueService` (what files exist), `ReviewStateService` (marks), a new `IdeLayoutController` (hide/restore tool windows), and a `ReviewDiffPresenter` (owns the diff tab). Session actions live in the diff viewer's toolbar via `DiffUserDataKeys.CONTEXT_ACTIONS`. Advancing replaces the diff tab rather than navigating a chain, because the chain processor is not reachable from an action through public API.

**Tech Stack:** Kotlin 2.4.10, Gradle 9.6.1, IntelliJ Platform Gradle Plugin 2.18.1, JDK 21, IntelliJ IDEA Ultimate 2026.2, JUnit 4.

## Global Constraints

- Base package `dev.tweety.reviewqueue`. Plugin ID `dev.tweety.reviewqueue`, name `Review Queue`.
- JDK/toolchain 21, Kotlin JVM target 21. `sinceBuild = "262"`, no `untilBuild`. **Do not change any version.**
- All tests use JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`). Never JUnit 5.
- **The plugin never mutates a repository.** Read-only git queries only. Specifically: never call `installPopupHandler`, never subclass `ChangesListView`, and never publish `VcsDataKeys.CHANGES` / `VcsDataKeys.SELECTED_CHANGES` — each would put Rollback one keystroke from the review UI.
- **Never run git under a read action.** `OSProcessHandler.checkEdtAndReadAction` forbids waiting on a process inside one; `ReviewQueueService.refresh` uses a plain background executor for exactly this reason. Do not reintroduce `ReadAction.nonBlocking` around anything that touches git.
- **Never prune stored marks.** `ReviewStateService` has no `prune`, deliberately — two attempts at a pruning rule both silently deleted the user's review progress. `ReviewMarkRetentionTest` guards this.
- **No raw control characters in source files.** Before each commit run:
  `perl -ne 'print "CTRL: $ARGV line $.\n" if /[^\x09\x0a\x20-\x7e\x80-\xff]/' $(git diff --cached --name-only --diff-filter=ACM | grep -E '\.(kt|md)$')`
- Mark Reviewed's shortcut is <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd>.
- Baseline before this plan: **65 tests, 0 failures.**

## File Structure

```
src/main/kotlin/dev/tweety/reviewqueue/
  core/ReviewSession.kt              NEW  pure session state + transitions
  ui/IdeLayoutController.kt          NEW  hide/restore Project + Review Queue, persisted
  ui/ReviewDiffPresenter.kt          NEW  interface + editor-tab implementation
  ui/ReviewLayoutRestorer.kt         NEW  ProjectActivity restoring layout after a mid-session quit
  queue/ReviewSessionService.kt      NEW  orchestrates a session
  actions/StartReviewAction.kt       NEW
  actions/EndReviewAction.kt         NEW  also used as "Exit Review" in the diff toolbar
  actions/PreviousFileAction.kt      NEW
  actions/MarkReviewedAction.kt      MOD  session-based, no cursor
  actions/ToggleReviewedAction.kt    MOD  acts on the session's current file
  ui/ReviewQueuePanel.kt             MOD  drop data keys + uiDataSnapshot + selectByKey
  ui/ReviewQueueTree.kt              MOD  drop cursor-following selection
  queue/ReviewQueueService.kt        MOD  drop cursor; add markReviewed(key)
  resources/META-INF/plugin.xml      MOD  action registrations + shortcut + ProjectActivity
src/test/kotlin/dev/tweety/reviewqueue/
  core/ReviewSessionTest.kt          NEW
  ui/IdeLayoutControllerTest.kt      NEW  persistence round-trip only
  queue/ReviewSessionServiceTest.kt  NEW  session flow with a fake presenter
  queue/ReviewMarkRetentionTest.kt   MOD  marks via the session service
```

---

### Task 1: `ReviewSession` — pure session state

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewSession.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewSessionTest.kt`

**Interfaces:**
- Consumes: `dev.tweety.reviewqueue.model.ReviewKey` (existing: `data class ReviewKey(val rootPath: String, val relPath: String)`).
- Produces:
  - `data class ReviewSession(val keys: List<ReviewKey>, val index: Int)`
  - `val current: ReviewKey?`, `val isAtFirst: Boolean`, `val position: Int`, `val total: Int`
  - `fun advance(): ReviewSession?` — null means the pass is finished
  - `fun back(): ReviewSession`
  - `fun settleOn(live: Set<ReviewKey>): ReviewSession?` — null means nothing left to show
  - `companion object { fun start(keys: List<ReviewKey>): ReviewSession? }`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewSessionTest.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionTest {

    private fun key(path: String) = ReviewKey("/repo", path)

    private val keys = listOf(key("a.kt"), key("b.kt"), key("c.kt"))

    @Test
    fun `start on an empty list yields no session`() {
        assertNull(ReviewSession.start(emptyList()))
    }

    @Test
    fun `start positions on the first file`() {
        val session = ReviewSession.start(keys)!!
        assertEquals(key("a.kt"), session.current)
        assertEquals(1, session.position)
        assertEquals(3, session.total)
        assertTrue(session.isAtFirst)
    }

    @Test
    fun `advance walks forward one file at a time`() {
        val first = ReviewSession.start(keys)!!
        val second = first.advance()!!
        assertEquals(key("b.kt"), second.current)
        assertEquals(2, second.position)
        assertEquals(key("c.kt"), second.advance()!!.current)
    }

    @Test
    fun `advance past the last file finishes the pass`() {
        val last = ReviewSession(keys, 2)
        assertNull(last.advance())
    }

    @Test
    fun `back steps to the previous file without changing the list`() {
        val third = ReviewSession(keys, 2)
        val second = third.back()
        assertEquals(key("b.kt"), second.current)
        assertEquals(keys, second.keys)
    }

    @Test
    fun `back at the first file is a no-op`() {
        val first = ReviewSession.start(keys)!!
        assertSame(first, first.back())
    }

    @Test
    fun `settleOn keeps the position when the current file is still live`() {
        val session = ReviewSession(keys, 1)
        assertEquals(key("b.kt"), session.settleOn(keys.toSet())!!.current)
    }

    @Test
    fun `settleOn skips forward over files that vanished from the queue`() {
        val session = ReviewSession(keys, 1)
        val settled = session.settleOn(setOf(key("a.kt"), key("c.kt")))!!
        assertEquals(key("c.kt"), settled.current)
    }

    @Test
    fun `settleOn yields null when nothing at or after the cursor is live`() {
        val session = ReviewSession(keys, 1)
        assertNull(session.settleOn(setOf(key("a.kt"))))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*ReviewSessionTest*'`
Expected: FAIL with `Unresolved reference 'ReviewSession'`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewSession.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey

/**
 * The position of one guided review pass.
 *
 * The list is fixed when the pass starts: a fix round landing mid-review must not reshuffle what
 * the reviewer is walking through. [settleOn] handles the one case that cannot be ignored — a file
 * that has since left the queue entirely and can no longer be shown.
 *
 * Deliberately free of platform types. Every transition is a pure function, so the whole flow is
 * testable without an IDE.
 */
data class ReviewSession(val keys: List<ReviewKey>, val index: Int) {

    val current: ReviewKey? get() = keys.getOrNull(index)

    val isAtFirst: Boolean get() = index <= 0

    /** 1-based position, for display. */
    val position: Int get() = index + 1

    val total: Int get() = keys.size

    /** The next file, or null when the pass is finished. */
    fun advance(): ReviewSession? =
        if (index + 1 >= keys.size) null else copy(index = index + 1)

    /** The previous file. Marks are untouched; at the first file this is a no-op. */
    fun back(): ReviewSession = if (index <= 0) this else copy(index = index - 1)

    /**
     * Moves forward to the first file at or after the cursor that is still in [live], or returns
     * null when none remain. Without this, a file removed from the queue mid-pass would leave the
     * session pointing at something that can never be displayed or marked.
     */
    fun settleOn(live: Set<ReviewKey>): ReviewSession? {
        var candidate = index
        while (candidate < keys.size && keys[candidate] !in live) candidate++
        return if (candidate >= keys.size) null else copy(index = candidate)
    }

    companion object {
        fun start(keys: List<ReviewKey>): ReviewSession? =
            if (keys.isEmpty()) null else ReviewSession(keys, 0)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*ReviewSessionTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add pure ReviewSession state for a guided pass"
```

---

### Task 2: `IdeLayoutController` — hide and restore tool windows

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewLayoutRestorer.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Produces:
  - `class IdeLayoutController : PersistentStateComponent<IdeLayoutController.State>`
  - `fun hideForReview()`, `fun restore()`
  - `class State { @JvmField var hiddenByReview: MutableList<String> }`
  - `companion object { fun getInstance(project: Project): IdeLayoutController }`
  - `class ReviewLayoutRestorer : ProjectActivity`

**Honest scope note:** only the persistence round-trip is unit-testable here — actually hiding and showing tool windows needs a running IDE. Do NOT write a test that asserts visibility through a mock; Task 8 adds manual checklist steps that cover it for real.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`:

```kotlin
package dev.tweety.reviewqueue.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeLayoutControllerTest {

    @Test
    fun `state round trips the hidden window ids`() {
        val state = IdeLayoutController.State()
        state.hiddenByReview = mutableListOf("Project", "Review Queue")

        val restored = IdeLayoutController.State()
        restored.hiddenByReview = state.hiddenByReview.toMutableList()

        assertEquals(listOf("Project", "Review Queue"), restored.hiddenByReview)
    }

    @Test
    fun `state defaults to nothing hidden`() {
        assertTrue(IdeLayoutController.State().hiddenByReview.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*IdeLayoutControllerTest*'`
Expected: FAIL with `Unresolved reference 'IdeLayoutController'`.

- [ ] **Step 3: Write `IdeLayoutController.kt`**

```kotlin
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
```

If `hide(null)` / `show(null)` do not resolve, check the real signatures before improvising and record what you found:

```bash
cd "/Applications/IntelliJ IDEA.app/Contents/lib" && javap -cp intellij.platform.ide.jar com.intellij.openapi.wm.ToolWindow | grep -iE "void (hide|show)"
```

- [ ] **Step 4: Write `ReviewLayoutRestorer.kt`**

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Restores the layout after an IDE quit that happened mid-session. A session is not persisted, so
 * on the next open there is nothing running — but the windows it hid would still be hidden.
 */
class ReviewLayoutRestorer : ProjectActivity {
    override suspend fun execute(project: Project) {
        IdeLayoutController.getInstance(project).restore()
    }
}
```

- [ ] **Step 5: Register the activity in `plugin.xml`**

Inside the existing `<extensions defaultExtensionNs="com.intellij">` block, add:

```xml
        <postStartupActivity implementation="dev.tweety.reviewqueue.ui.ReviewLayoutRestorer"/>
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests '*IdeLayoutControllerTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: hide and restore tool windows around a review session"
```

---

### Task 3: `ReviewDiffPresenter` — owns the session's diff tab

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffPresenter.kt`

**Interfaces:**
- Consumes: `ReviewQueueService.changeFor(key: ReviewKey): Change?`, `DiffChainPlanner` (unchanged).
- Produces:
  - `interface ReviewDiffPresenter { fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean; fun close(); fun isShowing(file: VirtualFile): Boolean }`
  - `class EditorTabDiffPresenter(project: Project) : ReviewDiffPresenter`

**Why an interface:** `ReviewSessionService` is otherwise untestable — a real presenter needs a live diff framework. Task 4's test substitutes a fake. This is the seam that makes the session flow verifiable.

- [ ] **Step 1: Write `ReviewDiffPresenter.kt`**

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import com.intellij.openapi.vfs.VirtualFile
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Shows one file of a session at a time. Implemented against an interface so the session flow can be tested. */
interface ReviewDiffPresenter {
    /** Returns false when the file cannot be rendered, so the caller can skip it. */
    fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean
    fun close()
    fun isShowing(file: VirtualFile): Boolean
}

/**
 * Opens each file as its own diff editor tab, replacing the previous one.
 *
 * Deliberately not one chain navigated with `setCurrentRequest`: `DiffRequestProcessor` exposes no
 * data key and no accessor, so reaching it from an action would mean casting into `impl` internals.
 * Replacing the tab costs a visible swap per file and depends on nothing internal.
 */
class EditorTabDiffPresenter(private val project: Project) : ReviewDiffPresenter {

    private var openFile: ChainDiffVirtualFile? = null

    override fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean {
        val change = ReviewQueueService.getInstance(project).changeFor(key) ?: return false
        val producer = ChangeDiffRequestProducer.create(project, change) ?: return false

        val chain = ChangeDiffRequestChain(listOf(producer), 0)
        chain.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions)

        val name = key.relPath.substringAfterLast('/')
        val file = ChainDiffVirtualFile(chain, "Review $position/$total - $name")

        close()
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
        openFile = file
        return true
    }

    override fun close() {
        val file = openFile ?: return
        // Cleared first so the session's file-closed listener does not treat our own close as the
        // user abandoning the review.
        openFile = null
        FileEditorManager.getInstance(project).closeFile(file)
    }

    override fun isShowing(file: VirtualFile): Boolean = openFile == file
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

**If `chain.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions)` compiles but the buttons do not appear in the diff toolbar during Task 8's sandbox check, report it — do not silently switch mechanisms.** The alternative is attaching the actions to the `DiffRequest` instead of the chain. Record which one actually worked.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add a diff presenter that owns the session's tab"
```

---

### Task 4: `ReviewSessionService` — orchestration

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt` (add `markReviewed(key)`)
- Test: `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt`

**Interfaces:**
- Consumes: `ReviewSession`, `ReviewDiffPresenter`, `IdeLayoutController`, `ReviewQueueService.snapshot()/isReviewed(item)/changeFor(key)/toggleReviewed(key)`, `ReviewStateService`.
- Produces:
  - `class ReviewSessionService(project: Project) : Disposable`
  - `val isActive: Boolean`, `fun currentKey(): ReviewKey?`
  - `fun start()`, `fun markCurrent()`, `fun toggleCurrent()`, `fun previous()`, `fun end()`
  - `internal var presenter: ReviewDiffPresenter` (test seam)
  - `companion object { fun getInstance(project: Project): ReviewSessionService }`
- On `ReviewQueueService`: `fun markReviewed(key: ReviewKey)`

- [ ] **Step 1: Add `markReviewed` to `ReviewQueueService`**

Marking must go through the queue service because that is what owns `fireChanged()`, which drives the panel refresh and the completion balloon. Insert immediately after the existing `toggleReviewed`:

```kotlin
    /** Marks [key] reviewed at its current content hash. No-op when the key is not in the queue. */
    fun markReviewed(key: ReviewKey) {
        val item = items.firstOrNull { it.key == key } ?: return
        state.markReviewed(item)
        fireChanged()
    }
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt`:

```kotlin
package dev.tweety.reviewqueue.queue

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.ui.ReviewDiffPresenter

/**
 * Drives the real session service with a fake presenter, so the flow is verified without a live
 * diff framework. The presenter interface exists for exactly this.
 */
class ReviewSessionServiceTest : HeavyPlatformTestCase() {

    private class FakePresenter : ReviewDiffPresenter {
        val shown = mutableListOf<ReviewKey>()
        var closed = 0
        override fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean {
            shown += key
            return true
        }
        override fun close() { closed++ }
        override fun isShowing(file: VirtualFile) = false
    }

    fun testStartIsInactiveWhenTheQueueIsEmpty() {
        val service = ReviewSessionService.getInstance(project)
        service.presenter = FakePresenter()

        service.start()

        assertFalse("an empty queue must not start a session", service.isActive)
    }

    fun testEndClosesThePresenterAndDeactivates() {
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.end()

        assertFalse(service.isActive)
        assertNull(service.currentKey())
    }
}
```

**Note on coverage:** these two cases are what can be asserted without a git fixture. Task 7 extends `ReviewMarkRetentionTest`, which already builds a real repo, to drive start → mark every file → finish. Do not fake a repo here.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests '*ReviewSessionServiceTest*'`
Expected: FAIL with `Unresolved reference 'ReviewSessionService'`.

- [ ] **Step 4: Write `ReviewSessionService.kt`**

```kotlin
package dev.tweety.reviewqueue.queue

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.tweety.reviewqueue.core.ReviewSession
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.ui.EditorTabDiffPresenter
import dev.tweety.reviewqueue.ui.IdeLayoutController
import dev.tweety.reviewqueue.ui.ReviewDiffPresenter

/**
 * Runs a guided review pass: hide the panels, walk the files one at a time, restore on the way out.
 *
 * Composes the queue (what exists), the state (marks), the layout controller and the presenter.
 * It re-implements none of them.
 */
@Service(Service.Level.PROJECT)
class ReviewSessionService(private val project: Project) : Disposable {

    private val queue get() = ReviewQueueService.getInstance(project)
    private val layout get() = IdeLayoutController.getInstance(project)

    /** Swapped for a fake in tests; the real one needs a live diff framework. */
    internal var presenter: ReviewDiffPresenter = EditorTabDiffPresenter(project)

    private var session: ReviewSession? = null

    /** Actions shown in the diff toolbar. Set by the UI layer once, at tool window creation. */
    internal var diffActions: List<AnAction> = emptyList()

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    // The user closed the review tab by hand: treat it as leaving the review, or the
                    // IDE is left with both tool windows hidden and no obvious way back.
                    if (session != null && presenter.isShowing(file)) end()
                }
            },
        )
    }

    val isActive: Boolean get() = session != null

    fun currentKey(): ReviewKey? = session?.current

    fun start() {
        if (session != null) return
        val snapshot = queue.snapshot()
        val keys = snapshot.items
            .filterNot { queue.isReviewed(it) }
            .map { it.key }
            .filter { queue.changeFor(it) != null }
        session = ReviewSession.start(keys) ?: return
        layout.hideForReview()
        showCurrent()
    }

    fun markCurrent() {
        val key = session?.current ?: return
        queue.markReviewed(key)
        advance()
    }

    fun toggleCurrent() {
        val key = session?.current ?: return
        queue.toggleReviewed(key)
    }

    fun previous() {
        val active = session ?: return
        if (active.isAtFirst) return
        session = active.back()
        showCurrent()
    }

    /** Ends the pass, restoring the layout. Every mark made so far is kept. */
    fun end() {
        session = null
        presenter.close()
        layout.restore()
        ToolWindowManager.getInstance(project).getToolWindow("Review Queue")?.show(null)
    }

    private fun advance() {
        val next = session?.advance()
        if (next == null) {
            end()
            return
        }
        session = next
        showCurrent()
    }

    /**
     * Shows the current file, skipping forward over anything that has left the queue or cannot be
     * rendered. Ends the pass when nothing showable remains.
     */
    private fun showCurrent() {
        val live = queue.snapshot().items.mapTo(mutableSetOf()) { it.key }
        var candidate = session?.settleOn(live)
        while (candidate != null) {
            val key = candidate.current
            if (key != null && presenter.show(key, candidate.position, candidate.total, diffActions)) {
                session = candidate
                return
            }
            candidate = candidate.advance()
        }
        end()
    }

    override fun dispose() {
        session = null
    }

    companion object {
        fun getInstance(project: Project): ReviewSessionService = project.service()
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*ReviewSessionServiceTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: PASS, 78 tests (65 baseline + 9 + 2 + 2).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add the review session service"
```

---

### Task 5: Session actions and registration

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/StartReviewAction.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/EndReviewAction.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/PreviousFileAction.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/actions/MarkReviewedAction.kt` (rewrite)
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/actions/ToggleReviewedAction.kt` (rewrite)
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: `ReviewSessionService.getInstance(project)` with `isActive`, `start()`, `markCurrent()`, `toggleCurrent()`, `previous()`, `end()`, `currentKey()`; `ReviewQueueService.snapshot()` and `isReviewed(item)`.
- Produces: action IDs `ReviewQueue.StartReview`, `ReviewQueue.EndReview`, `ReviewQueue.PreviousFile`, `ReviewQueue.MarkReviewed`, `ReviewQueue.ToggleReviewed`.

- [ ] **Step 1: Write `StartReviewAction.kt`**

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService

/** Begins a guided pass over everything still unreviewed in the current scope. */
class StartReviewAction : AnAction(
    "Start Review",
    "Hide the side panels and walk the unreviewed files one at a time",
    AllIcons.Actions.Execute,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val queue = ReviewQueueService.getInstance(project)
        val hasUnreviewed = queue.snapshot().items.any { !queue.isReviewed(it) }
        e.presentation.isEnabled = !ReviewSessionService.getInstance(project).isActive && hasUnreviewed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).start()
    }
}
```

- [ ] **Step 2: Write `EndReviewAction.kt`**

This one action is shown in both the panel toolbar ("End Review") and the diff toolbar ("Exit Review"). One class, one handler.

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService

/** Leaves the guided pass and restores the layout. Marks already made are kept. */
class EndReviewAction : AnAction(
    "End Review",
    "Leave the guided review and restore the tool windows",
    AllIcons.Actions.Exit,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null && ReviewSessionService.getInstance(project).isActive
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).end()
    }
}
```

- [ ] **Step 3: Write `PreviousFileAction.kt`**

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * Steps back one file without changing any mark. This is what makes a mis-mark recoverable:
 * marking advances immediately, so the wrong file is already behind you when you notice.
 */
class PreviousFileAction : AnAction(
    "Previous File",
    "Go back one file without changing its reviewed mark",
    AllIcons.Actions.Back,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val service = project?.let { ReviewSessionService.getInstance(it) }
        e.presentation.isEnabled = service != null && service.isActive && service.currentKey() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).previous()
    }
}
```

- [ ] **Step 4: Rewrite `MarkReviewedAction.kt`**

Replace the whole file:

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService

/** Marks the file on screen reviewed and moves to the next one. The core interaction. */
class MarkReviewedAction : AnAction(
    "Mark Reviewed",
    "Mark this file reviewed and open the next one",
    AllIcons.Actions.Checked,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null && ReviewSessionService.getInstance(project).isActive
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).markCurrent()
    }
}
```

- [ ] **Step 5: Rewrite `ToggleReviewedAction.kt`**

Replace the whole file. It now acts on the session's current file rather than a tree selection, because during a session the panel is hidden.

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * Adds or removes the mark on the file currently displayed, without moving. Paired with
 * Previous File this is the recovery path for a mis-mark; without it the only undo is Reset All,
 * which clears every mark in the project.
 */
class ToggleReviewedAction : AnAction(
    "Toggle Reviewed",
    "Add or remove the reviewed mark on this file, without moving to another file",
    AllIcons.Actions.Undo,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val service = project?.let { ReviewSessionService.getInstance(it) }
        e.presentation.isEnabled = service != null && service.isActive && service.currentKey() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewSessionService.getInstance(project).toggleCurrent()
    }
}
```

- [ ] **Step 6: Rewrite the `<actions>` block in `plugin.xml`**

Replace the entire existing `<actions>` block with:

```xml
    <actions>
        <group id="ReviewQueue.Toolbar" class="com.intellij.openapi.actionSystem.DefaultActionGroup">
            <action id="ReviewQueue.Scope"
                    class="dev.tweety.reviewqueue.ui.ScopeSelector"
                    text="Scope"
                    description="Choose what the review queue lists"/>
            <action id="ReviewQueue.StartReview"
                    class="dev.tweety.reviewqueue.actions.StartReviewAction"
                    text="Start Review"
                    description="Hide the side panels and walk the unreviewed files one at a time"/>
            <action id="ReviewQueue.EndReview"
                    class="dev.tweety.reviewqueue.actions.EndReviewAction"
                    text="End Review"
                    description="Leave the guided review and restore the tool windows"/>
            <action id="ReviewQueue.Refresh"
                    class="dev.tweety.reviewqueue.actions.RefreshQueueAction"
                    text="Refresh"
                    description="Re-read the review scope"/>
            <action id="ReviewQueue.ResetAll"
                    class="dev.tweety.reviewqueue.actions.ResetAllAction"
                    text="Reset All"
                    description="Clear every reviewed mark in this project"/>
        </group>

        <action id="ReviewQueue.PreviousFile"
                class="dev.tweety.reviewqueue.actions.PreviousFileAction"
                text="Previous File"
                description="Go back one file without changing its reviewed mark"/>
        <action id="ReviewQueue.MarkReviewed"
                class="dev.tweety.reviewqueue.actions.MarkReviewedAction"
                text="Mark Reviewed"
                description="Mark this file reviewed and open the next one">
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl shift SPACE"/>
        </action>
        <action id="ReviewQueue.ToggleReviewed"
                class="dev.tweety.reviewqueue.actions.ToggleReviewedAction"
                text="Toggle Reviewed"
                description="Add or remove the reviewed mark on this file, without moving to another file"/>
    </actions>
```

Note the diff-toolbar actions are registered at top level, not inside `ReviewQueue.Toolbar` — they are handed to the diff viewer as instances, not through the panel's group.

- [ ] **Step 7: Build and run the full suite**

Run: `./gradlew build test`
Expected: BUILD SUCCESSFUL, 78 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add session actions and move marking into the diff toolbar"
```

---

### Task 6: Wire the actions into the diff and clean up the panel

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueuePanel.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueueTree.kt`

**Interfaces:**
- Consumes: `ReviewSessionService.diffActions`, action IDs from Task 5.
- Produces: a panel with no data keys and no `uiDataSnapshot`.

- [ ] **Step 1: Populate `diffActions` and drop the data key from the panel**

In `ReviewQueuePanel`:

1. Delete the `ReviewQueueDataKeys` object entirely (top of the file) and the `uiDataSnapshot` override. Toggle Reviewed was their only consumer, and the panel now publishes no data keys at all — which is deliberate, since anything it published could feed a VCS action.
2. Remove `UiDataProvider` from the class's supertype list, and the now-unused `DataKey` / `DataSink` / `UiDataProvider` / `ReviewKey` imports.
3. Replace the two `service.selectByKey(key)` calls: the selection listener body becomes empty of service calls, and the activation handler opens the diff directly.
4. In `init`, before `service.refresh()`, hand the diff actions to the session service:

```kotlin
        ReviewSessionService.getInstance(project).diffActions = listOf(
            ActionManager.getInstance().getAction("ReviewQueue.PreviousFile"),
            ActionManager.getInstance().getAction("ReviewQueue.MarkReviewed"),
            ActionManager.getInstance().getAction("ReviewQueue.ToggleReviewed"),
            ActionManager.getInstance().getAction("ReviewQueue.EndReview"),
        )
```

The resulting listener block:

```kotlin
        // Selection alone does nothing: opening the diff here would fire on every programmatic
        // re-selection, i.e. on every background VCS event, stealing focus.
        tree.addSelectionListener { }
        // Explicit user gesture: click, double click or Enter on a row. Browsing only — a guided
        // session drives the diff through ReviewSessionService instead.
        tree.setActivationHandler { key ->
            if (service.changeFor(key) != null) {
                ReviewDiffOpener.open(project, key)
            }
        }
```

Add `import dev.tweety.reviewqueue.queue.ReviewSessionService`.

- [ ] **Step 2: Stop the tree following a cursor**

In `ReviewQueueTree.refreshFrom`, delete these two lines:

```kotlin
        val cursorKey = snapshot.cursor?.let { snapshot.items.getOrNull(it)?.key }
        if (cursorKey != null) selectKey(cursorKey)
```

The tree is now a read-only overview; nothing selects rows programmatically. Leave `selectKey` and `isProgrammaticUpdate` in place only if something still calls them — if they become unused, delete them too and say so in your report.

- [ ] **Step 3: Build and run the full suite**

Run: `./gradlew build test`
Expected: BUILD SUCCESSFUL, 78 tests, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: wire diff actions and drop the panel's data keys"
```

---

### Task 7: Remove the queue cursor

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt`
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewMarkRetentionTest.kt`

**Interfaces:**
- Produces: `QueueSnapshot` without a `cursor` field; `ReviewQueueService` without `markCurrentReviewed()`, `selectByKey()` or any cursor state.

**Why now:** the cursor's only consumers were the panel's Mark Reviewed and Toggle Reviewed, both of which moved to the diff toolbar in Task 5. Position belongs to `ReviewSession`. Doing this last means nothing is broken in between.

- [ ] **Step 1: Delete the cursor from `ReviewQueueService`**

1. Remove `val cursor: Int?` from `QueueSnapshot` and the argument from the `snapshot()` call.
2. Delete the `private var cursor: Int? = null` field.
3. Delete `fun markCurrentReviewed()` and `fun selectByKey(key: ReviewKey)`.
4. In `applyRebuild`, delete the `previousKey` / `previousIndex` locals and the whole cursor-recomputation block, leaving:

```kotlin
    private fun applyRebuild(rebuild: Rebuild) {
        if (project.isDisposed) return
        // A scope change raced this rebuild; the refresh for the new scope is already in flight.
        if (rebuild.scope != scope) return

        items = rebuild.assembled.items
        errors = rebuild.assembled.errors
        changesByKey = rebuild.assembled.changesByKey
        keysByChange = rebuild.assembled.keysByChange

        fireChanged()
    }
```

5. Remove the now-unused `ReviewCursor` import.

`ReviewCursor` itself stays — `ReviewSession` does not use it, but `ReviewCursorTest` still passes and the object may be useful. If it ends up with no production caller, say so in your report rather than deleting it unilaterally.

- [ ] **Step 2: Update `ReviewMarkRetentionTest` to mark through the session**

Replace the two `service.markCurrentReviewed()` calls with a session-driven pass, which also gives the session service real end-to-end coverage:

```kotlin
        val sessionService = ReviewSessionService.getInstance(project)
        sessionService.presenter = object : ReviewDiffPresenter {
            override fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>) = true
            override fun close() = Unit
            override fun isShowing(file: VirtualFile) = false
        }
        sessionService.start()
        assertTrue("session should be running with files to review", sessionService.isActive)
        while (sessionService.isActive) {
            sessionService.markCurrent()
        }
```

Add the imports the block needs: `com.intellij.openapi.actionSystem.AnAction`, `com.intellij.openapi.vfs.VirtualFile`, `dev.tweety.reviewqueue.model.ReviewKey`, `dev.tweety.reviewqueue.ui.ReviewDiffPresenter`.

Keep every existing assertion in that test exactly as strong as it is. It is the regression guard for the never-prune decision, and it must still fail if pruning is reintroduced.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew test`
Expected: PASS, 78 tests, 0 failures. If `ReviewMarkRetentionTest` fails, the session pass is not marking what the old code marked — investigate rather than weakening the assertions.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove the queue cursor now that sessions own position"
```

---

### Task 8: Sandbox verification, docs and packaging

**Files:**
- Modify: `docs/manual-verification.md`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-07-25-intellij-review-queue-design.md`

- [ ] **Step 1: Verify in the sandbox**

Run the sandbox against a scratch repo with staged files (create one in a temp directory — do **not** stage anything under `/Users/tweety53/Projects/gymie*` or in this plugin's own checkout). Do not use `timeout`; it does not exist on macOS. Background the launch and poll the log.

```bash
./gradlew runIde --console=plain > /tmp/reviewqueue-runide.log 2>&1 &
```

Confirm from the log that the IDE reached `PluginManager - Problems found loading plugins:` and that `dev.tweety.reviewqueue` does **not** appear in that block, then check the UI:

1. The Review Queue tool window lists the staged files.
2. **Start Review** hides Project and Review Queue and opens the first file.
3. The diff toolbar shows **all four** actions: Previous File, Mark Reviewed, Toggle Reviewed, End/Exit Review.
4. Mark Reviewed advances; the tab title tracks `Review N/M`.
5. <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd> marks the file — and **Smart Type Completion still works in a normal editor**.
6. Marking the last file restores both tool windows and fires the completion balloon.

Terminate the sandbox (`pgrep -f runIde`, then kill) and confirm it is gone.

**Report exactly what you observed and what you could not.** If the diff toolbar actions do not appear, that is the `CONTEXT_ACTIONS`-on-the-chain risk flagged in Task 3 — report it with the evidence instead of switching mechanisms silently.

- [ ] **Step 2: Update `docs/manual-verification.md`**

Add a section covering the new flow, keeping the existing numbered structure and sign-off list coherent:

- Start Review hides both tool windows; End Review restores them.
- Quit the IDE mid-session, reopen the project, and confirm the tool windows come back (the persisted layout snapshot).
- Close the review diff tab by hand and confirm the layout restores and the session ends.
- Mis-mark recovery: mark the wrong file → Previous File → Toggle Reviewed → Mark Reviewed to continue.
- The shortcut fires in the diff and does not leak into normal editors.
- Start Review after a fix round walks only the files that changed.
- **Re-test carried over:** Toggle Reviewed looked permanently disabled in the previous release. The cause was almost certainly that `refresh()` was dying under a read action, leaving the queue empty. That is fixed — confirm the action now enables.

- [ ] **Step 3: Update `README.md`**

Rewrite the "Use" section around the new flow: open the tool window, pick a scope, press Start Review, mark each file with the button or <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd>, use Previous File and Toggle Reviewed to fix a mis-mark, End Review to leave early. State plainly that advancing replaces the diff tab.

- [ ] **Step 4: Note the supersession in the original spec**

At the top of `docs/superpowers/specs/2026-07-25-intellij-review-queue-design.md`, under Status, add:

```markdown
**Superseded in part by** `2026-07-26-guided-review-mode-design.md`: marking, un-marking and
navigation moved from the tool window into the diff viewer, and the queue no longer holds a cursor.
```

- [ ] **Step 5: Build the distributable and verify compatibility**

```bash
./gradlew clean test buildPlugin verifyPlugin
```

Expected: BUILD SUCCESSFUL; 78 tests, 0 failures; `verifyPlugin` reports **Compatible** with no compatibility problems. The deprecated/experimental API notices on `ToolWindowFactory` are Kotlin-generated interface bridges and are expected — do not try to fix them. Report the zip path and size.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: document guided review mode and verify the package"
```
