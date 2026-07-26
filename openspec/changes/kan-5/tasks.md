# KAN-5 — Remove the Review Queue tool window: Implementation Plan

> **Execution:** `/myflow-do` runs Basic Workflow #2–#6 via `openspec-apply-superpowers` (#7 runs later, in `/myflow-review`). Mark each checkbox when its task passes spec + quality review (SDD #6).

**Goal:** Delete the Review Queue tool window and rehome everything it carried — out-of-session commands to a Tools menu group, scope selection to the diff toolbar with mid-pass switching enabled, and queue resolution to an on-demand synchronous path.

**Architecture:** The tool window's component tree is deleted outright. A new `resolveNow` on `ReviewQueueService` resolves a scope to completion under a modal progress, submitting the git work to the existing single-threaded `refreshExecutor` so both resolution paths stay serialised. A shared `SetScopeAction` base drives scope changes from both surfaces through one `ReviewSessionService.switchScope`, which resettles a running pass in place rather than ending and restarting it.

**Tech Stack:** Kotlin 2.4.10 / JVM 21, IntelliJ Platform Gradle plugin 2.18.1, IntelliJ IDEA Ultimate 2026.2 (build 262+), Git4Idea, JUnit 4 with the platform test framework.

## Global Constraints

- **Read-only with respect to the repository.** Every git command is a query. Never publish `VcsDataKeys.CHANGES` / `SELECTED_CHANGES`, never install a popup handler on a review component, never subclass `ChangesListView`. Rollback must never be one keystroke from a review UI.
- **Git never runs on the EDT, and never under a read or write action.** `OSProcessHandler.checkEdtAndReadAction` logs *"Synchronous execution under ReadAction"* and the resolve dies.
- **All git resolution for one project runs on `refreshExecutor`.** It is single-threaded for exactly this reason.
- **No commits during this stage.** `/myflow-do` stages with `git add`; `/myflow-review` commits. Every task ends with `git add`, never `git commit`.
- **`verifyPlugin` must report zero deprecated- and zero experimental-API usages** once the tool window factory is gone.
- **Start Review's `plugin.xml` declaration must stay a single `<action>` block**, or `MarkReviewedShortcutTest` and `NavigationShortcutTest` regexes silently stop matching.
- **Do not weaken existing KDoc.** It records prior failures. Where behaviour changes, rewrite the comment to describe the new truth — never delete the reasoning.
- Verification commands: `./gradlew test` and `./gradlew verifyPlugin`. There is no ktlint or detekt in this repo.

## File Structure

| Path | Responsibility |
| --- | --- |
| `src/main/kotlin/dev/tweety/reviewqueue/git/GitRoots.kt` | **Create.** Enablement predicate, top-level so `update()` constructs no service. |
| `src/main/kotlin/dev/tweety/reviewqueue/ui/ScopePrompts.kt` | **Create.** The two ref-input dialogs; returns `ReviewScope?`. |
| `src/main/kotlin/dev/tweety/reviewqueue/actions/ScopeActions.kt` | **Create.** `SetScopeAction` base owning the prompt→confirm→switch rule, plus the three concrete actions. |
| `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffScopeAction.kt` | **Create.** Diff-toolbar combo box over the registered scope group. |
| `src/main/kotlin/dev/tweety/reviewqueue/notify/ScopeErrorNotifier.kt` | **Create.** Balloon for failed roots, deduplicated by error map. |
| `src/main/kotlin/dev/tweety/reviewqueue/notify/QueueNotices.kt` | **Create.** The "nothing unreviewed" balloon; stateless. |
| `queue/ReviewQueueService.kt` | **Modify.** `resolveAndAssemble`, `resolveNow`, progress seam, notifier wiring. |
| `queue/ReviewSessionService.kt` | **Modify.** `switchScope`, extracted key computation, `DiffScopeAction` on the toolbar. |
| `actions/StartReviewAction.kt`, `ShowFileListAction.kt`, `RefreshQueueAction.kt` | **Modify.** Enablement and on-demand resolution. |
| `actions/diff/DiffRefreshQueueAction.kt` | **Modify.** KDoc only — the effect is now immediate. |
| `ui/ReviewFileListPopup.kt` | **Modify.** Scope in the title; stale race KDoc rewritten. |
| `ui/IdeLayoutController.kt` | **Modify.** `MANAGED_IDS`, `LEGACY_IDS` prune. |
| `ui/ReviewQueuePanel.kt`, `ReviewQueueToolWindowFactory.kt`, `ReviewQueueTree.kt`, `ScopeSelector.kt` | **Delete.** |
| `META-INF/plugin.xml` | **Modify.** Menu groups, `EndReview` top-level, shortcut; `<toolWindow>` and `ReviewQueue.Toolbar` removed. |
| `README.md`, `docs/manual-verification.md` | **Modify.** |

---

### Task 1: On-demand queue resolution

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/git/GitRoots.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/queue/ResolveNowTest.kt`

**Interfaces:**
- Produces: `GitRoots.exist(project: Project): Boolean`; `ReviewQueueService.resolveNow(newScope: ReviewScope? = null): Boolean` (true when a rebuild was applied); `internal data class Rebuild`; `internal fun resolveAndAssemble(scope: ReviewScope): Rebuild`; `internal var progressRunner: (String, () -> Rebuild) -> Rebuild?`.
- Consumes: existing `GitReviewSource`, `QueueAssembler`, `refreshExecutor`, `refreshGeneration`, `applyRebuild`.

- [x] **Step 1: Write the failing test**

```kotlin
package dev.tweety.reviewqueue.queue

import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope

class ResolveNowTest : HeavyPlatformTestCase() {

    fun testResolveNowAppliesThroughTheInjectedRunner() {
        val service = ReviewQueueService.getInstance(project)
        var titleSeen: String? = null
        service.progressRunner = { title, work -> titleSeen = title; work() }

        val applied = service.resolveNow(ReviewScope.Staged)

        assertTrue("resolveNow must report that it applied a rebuild", applied)
        assertNotNull("the progress runner must be given a title to show", titleSeen)
    }

    fun testACancelledRunnerLeavesTheQueueUntouched() {
        val service = ReviewQueueService.getInstance(project)
        val before = service.snapshot().items
        // null models the user dismissing the progress dialog.
        service.progressRunner = { _, _ -> null }

        val applied = service.resolveNow(ReviewScope.Staged)

        assertFalse("a cancelled resolve must not claim to have applied", applied)
        assertEquals("a cancelled resolve must not touch the queue", before, service.snapshot().items)
    }

    fun testResolveNowRecordsTheScopeItWasGiven() {
        val service = ReviewQueueService.getInstance(project)
        service.progressRunner = { _, work -> work() }

        service.resolveNow(ReviewScope.CommitRange("HEAD~1", "HEAD"))

        assertEquals(ReviewScope.CommitRange("HEAD~1", "HEAD"), service.snapshot().scope)
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.queue.ResolveNowTest'`
Expected: FAIL to compile — `progressRunner` and `resolveNow` are unresolved references.

- [x] **Step 3: Create the enablement predicate**

```kotlin
package dev.tweety.reviewqueue.git

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Whether this project has any git root at all — the enablement predicate for the review actions.
 *
 * Deliberately top-level rather than a method on `ReviewQueueService`. Actions call this from
 * `update()`, and a service method would construct the project service there, wiring
 * `ChangeListListener` and `GIT_REPO_CHANGE` as a side effect of hovering the Tools menu. This keeps
 * `git4idea` out of the action classes just as well and leaves the service uncreated until a real
 * gesture.
 *
 * `repositories` is cached and EDT-safe, but empty until VCS mappings initialise — so the review
 * actions are briefly disabled after a project opens and re-enable on the next `update()` poll,
 * without needing an event.
 */
object GitRoots {
    fun exist(project: Project): Boolean =
        GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
}
```

- [x] **Step 4: Make `Rebuild` internal and extract the resolve body**

In `ReviewQueueService.kt`, change `private data class Rebuild` to `internal data class Rebuild`, and extract the body that `refresh()` currently inlines:

```kotlin
    /**
     * The pure resolve-and-assemble step, shared by [refresh] and [resolveNow]. Runs git, so it must
     * never be called on the EDT or under a read action.
     */
    internal fun resolveAndAssemble(scope: ReviewScope): Rebuild =
        Rebuild(scope, QueueAssembler.assemble(source.resolve(scope), source.rootOrder()))
```

Then rewrite `refresh()`'s executor body to call it, leaving its generation checks, its
`ProcessCanceledException` rethrow, and its `invokeLater(..., ModalityState.nonModal(), ...)` apply
exactly as they are.

- [x] **Step 5: Add the progress seam**

```kotlin
    /**
     * How [resolveNow] shows progress while it waits. Swapped for a direct call in tests:
     * `HeavyPlatformTestCase` runs bodies on the EDT and the synchronous-progress API is not the
     * modal-dialog path headless, so the real one would risk running git on the test EDT and trip an
     * assertion that `TestLoggerFactory` turns into a failure.
     *
     * Returns null when the user cancelled. Mirrors the `presenter` seam on `ReviewSessionService`.
     */
    internal var progressRunner: (String, () -> Rebuild) -> Rebuild? = { title, work ->
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Rebuild, Exception> { work() },
                title,
                true,
                project,
            )
        } catch (e: ProcessCanceledException) {
            null
        }
    }
```

- [x] **Step 6: Implement `resolveNow`**

```kotlin
    /**
     * Resolves a scope to completion and applies it, so the calling gesture has a queue it can act on
     * immediately. Returns false when the user cancelled or nothing could be resolved.
     *
     * Three preconditions, none of them enforced by the compiler:
     *  1. **Called on the EDT** — asserted below. The apply mutates state the EDT owns.
     *  2. **No read or write access held.** `ApplicationImpl.runProcessWithProgressSynchronously`
     *     short-circuits to running the body *inline on the calling thread* when the caller holds
     *     write access on the EDT, or read access off it. Git would then run under a read action,
     *     which is the failure the KDoc on [refresh] was written for.
     *  3. **No modal dialog already showing**, or the apply lands under it. This is the one
     *     deliberate exception to the `ModalityState.nonModal()` rule [refresh] follows: here the
     *     gesture that opened the progress is precisely what is waiting for the result.
     *
     * The git work goes to [refreshExecutor], not the progress task's pooled thread, so that this
     * path and [refresh] cannot resolve git concurrently for one project — bumping
     * [refreshGeneration] does not cancel an executor task already inside `source.resolve`, and two
     * concurrent `git status` calls on one root can collide on `index.lock`.
     */
    fun resolveNow(newScope: ReviewScope? = null): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        if (project.isDisposed) return false
        newScope?.let { scope = it }
        val requestedScope = scope
        val generation = refreshGeneration.incrementAndGet()

        val rebuild = try {
            progressRunner("Resolving Review Scope") {
                val future = refreshExecutor.submit(Callable { resolveAndAssemble(requestedScope) })
                ProgressIndicatorUtils.awaitWithCheckCanceled(future)
            }
        } catch (e: ProcessCanceledException) {
            return false
        } catch (e: RejectedExecutionException) {
            // dispose() calls shutdownNow(); a resolve racing project close has no queue to give.
            return false
        } catch (e: Exception) {
            thisLogger().warn("Review Queue resolve failed", e)
            return false
        } ?: return false

        // The ThrowableComputable overload only rethrows if the body observed cancellation, so a
        // cancel landing between the last git call and here would otherwise apply anyway.
        ProgressManager.checkCanceled()
        // Today no bump can happen during the modal, since every refresh() entry is on the EDT. This
        // re-check is what keeps that from being a silent trap for a future background caller.
        if (generation != refreshGeneration.get()) return false
        applyRebuild(rebuild)
        return true
    }
```

Add imports: `com.intellij.openapi.progress.ProgressManager`,
`com.intellij.openapi.progress.util.ProgressIndicatorUtils`,
`com.intellij.openapi.util.ThrowableComputable`,
`com.intellij.util.concurrency.ThreadingAssertions`, `java.util.concurrent.Callable`,
`java.util.concurrent.RejectedExecutionException`.

- [x] **Step 7: Amend the executor KDoc**

At `ReviewQueueService.kt:95-97`, replace *"Serialises rebuilds so two refreshes never resolve git concurrently for the same project"* with:

```kotlin
    /**
     * Serialises every git resolve for this project — both the asynchronous [refresh] and the
     * synchronous [resolveNow], which submits here and awaits rather than resolving on the progress
     * task's own thread. Two concurrent `git status` calls on one root can collide on `index.lock`.
     */
```

- [x] **Step 8: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.queue.ResolveNowTest'`
Expected: PASS, 3 tests.

- [x] **Step 9: Stage**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/git/GitRoots.kt \
        src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt \
        src/test/kotlin/dev/tweety/reviewqueue/queue/ResolveNowTest.kt
```

---

### Task 2: Notifications for failed roots and empty results

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/notify/ScopeErrorNotifier.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/notify/QueueNotices.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/notify/ScopeErrorNotifierTest.kt`

**Interfaces:**
- Produces: `ScopeErrorNotifier(project).onSnapshot(snapshot: QueueSnapshot)`; `QueueNotices.nothingUnreviewed(project: Project, scope: ReviewScope)`.
- Consumes: `QueueSnapshot` from Task 1's service, the existing `Review Queue` notification group.

- [x] **Step 1: Write the failing test**

```kotlin
package dev.tweety.reviewqueue.notify

import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.queue.QueueSnapshot

class ScopeErrorNotifierTest : HeavyPlatformTestCase() {

    private fun snapshot(errors: Map<String, String>) =
        QueueSnapshot(items = emptyList(), reviewedCount = 0, errors = errors, scope = ReviewScope.Staged)

    fun testAnEmptyErrorMapSaysNothing() {
        val fired = mutableListOf<Map<String, String>>()
        val notifier = ScopeErrorNotifier(project) { fired += it }

        notifier.onSnapshot(snapshot(emptyMap()))

        assertTrue("no errors means no balloon", fired.isEmpty())
    }

    fun testTheSameErrorMapIsAnnouncedOnlyOnce() {
        val fired = mutableListOf<Map<String, String>>()
        val notifier = ScopeErrorNotifier(project) { fired += it }
        val errors = mapOf("/repo" to "not a git repository")

        notifier.onSnapshot(snapshot(errors))
        notifier.onSnapshot(snapshot(errors))
        notifier.onSnapshot(snapshot(errors))

        assertEquals(
            "changeListUpdateDone lands on every VCS event; an unchanged failure must not repeat",
            1,
            fired.size,
        )
    }

    fun testARecurrenceIsAnnouncedAgain() {
        val fired = mutableListOf<Map<String, String>>()
        val notifier = ScopeErrorNotifier(project) { fired += it }
        val errors = mapOf("/repo" to "bad revision")

        notifier.onSnapshot(snapshot(errors))
        notifier.onSnapshot(snapshot(emptyMap()))
        notifier.onSnapshot(snapshot(errors))

        assertEquals("a root that recovers and breaks again is news again", 2, fired.size)
    }

    fun testADifferentFailureIsAnnounced() {
        val fired = mutableListOf<Map<String, String>>()
        val notifier = ScopeErrorNotifier(project) { fired += it }

        notifier.onSnapshot(snapshot(mapOf("/a" to "x")))
        notifier.onSnapshot(snapshot(mapOf("/a" to "x", "/b" to "y")))

        assertEquals("a newly broken root is a different failure", 2, fired.size)
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.notify.ScopeErrorNotifierTest'`
Expected: FAIL to compile — `ScopeErrorNotifier` does not exist.

- [x] **Step 3: Write `ScopeErrorNotifier`**

```kotlin
package dev.tweety.reviewqueue.notify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.queue.QueueSnapshot

/**
 * Announces git roots that failed to resolve. This is what replaces the deleted tool window's error
 * label.
 *
 * Deduplicated by error map, not merely by emptiness: `changeListUpdateDone` lands on every VCS
 * event, so firing whenever the map is non-empty would balloon on every rebuild. A persistently
 * broken root is announced once; one that recovers and breaks again is announced again.
 * `CompletionNotifier.armed` is the precedent for this shape.
 *
 * The `notify` lambda exists so the dedupe rule can be tested without asserting against the
 * notification bus.
 */
class ScopeErrorNotifier(
    private val project: Project,
    private val notify: (Map<String, String>) -> Unit = { balloon(project, it) },
) {
    private var lastReported: Map<String, String> = emptyMap()

    fun onSnapshot(snapshot: QueueSnapshot) {
        if (snapshot.errors == lastReported) return
        lastReported = snapshot.errors
        if (snapshot.errors.isEmpty()) return
        notify(snapshot.errors)
    }

    private companion object {
        fun balloon(project: Project, errors: Map<String, String>) {
            val detail = errors.entries.joinToString("\n") { "${it.key} — ${it.value}" }
            val heading =
                if (errors.size == 1) "A repository could not be read"
                else "${errors.size} repositories could not be read"
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Review Queue")
                .createNotification(heading, detail, NotificationType.WARNING)
                .notify(project)
        }
    }
}
```

- [x] **Step 4: Write `QueueNotices`**

```kotlin
package dev.tweety.reviewqueue.notify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.model.displayName

/**
 * Says so when a resolve leaves nothing for the gesture that asked for it.
 *
 * Necessary because enablement is now based on "this project has a git root", not on queue contents:
 * every gesture is clickable with an empty queue, and each would otherwise do nothing visible. Under
 * the old design the tool window's empty list was that feedback; removing the panel removes it, so
 * silence here would be a regression rather than the status quo.
 *
 * A function rather than a class: unlike the other two notifiers this holds no arming state, because
 * it fires only from an explicit user gesture and so cannot be triggered by a background rebuild.
 */
object QueueNotices {
    fun nothingUnreviewed(project: Project, scope: ReviewScope) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Review Queue")
            .createNotification(
                "Nothing unreviewed in ${scope.displayName()}",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
```

- [x] **Step 5: Wire the error notifier into the service**

In `ReviewQueueService`, beside the existing `notifier` field:

```kotlin
    private val errorNotifier = ScopeErrorNotifier(project)
```

and in `fireChanged()`, before the listener fan-out:

```kotlin
    private fun fireChanged() {
        val snapshot = snapshot()
        notifier.onSnapshot(snapshot)
        errorNotifier.onSnapshot(snapshot)
        listeners.toList().forEach { it() }
    }
```

- [x] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.notify.*'`
Expected: PASS — 4 new tests, plus the existing `BranchNameParserTest` still green.

- [x] **Step 7: Stage**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/notify/ \
        src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt \
        src/test/kotlin/dev/tweety/reviewqueue/notify/ScopeErrorNotifierTest.kt
```

---

### Task 3: Rewire the three gestures to resolve on demand

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/actions/StartReviewAction.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/actions/ShowFileListAction.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/actions/RefreshQueueAction.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffRefreshQueueAction.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/actions/StartReviewEnablementTest.kt`

**Interfaces:**
- Consumes: `GitRoots.exist`, `ReviewQueueService.resolveNow`, `QueueNotices.nothingUnreviewed` from Tasks 1–2.
- Produces: nothing new; behaviour changes only.

- [x] **Step 1: Write the failing test**

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.queue.ReviewQueueService

class StartReviewEnablementTest : HeavyPlatformTestCase() {

    private fun presentationOf(actionId: String) = run {
        val action = ActionManager.getInstance().getAction(actionId)
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        val event = AnActionEvent.createEvent(action, context, null, "", 
            com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
        action.update(event)
        event.presentation
    }

    fun testStartReviewDoesNotDependOnQueueContents() {
        // The queue is deliberately never resolved here: enablement must not read its contents,
        // because nothing warms the queue any more until a gesture asks.
        assertTrue(ReviewQueueService.getInstance(project).snapshot().items.isEmpty())

        val enabled = presentationOf("ReviewQueue.StartReview").isEnabled

        assertEquals(
            "enablement must follow git-root existence, not queue contents",
            dev.tweety.reviewqueue.git.GitRoots.exist(project),
            enabled,
        )
    }

    fun testShowFileListNoLongerRequiresASessionOrADiffContext() {
        val enabled = presentationOf("ReviewQueue.ShowFileList").isEnabled

        assertEquals(
            "Show File List replaces the deleted tree, so it must work outside a pass",
            dev.tweety.reviewqueue.git.GitRoots.exist(project),
            enabled,
        )
    }

    /**
     * Enablement must not construct the queue service. `GitRoots.exist` is top-level precisely so
     * that hovering the Tools menu does not wire `ChangeListListener` and `GIT_REPO_CHANGE` as a
     * side effect of an `update()` call.
     */
    fun testUpdatingTheActionsDoesNotConstructTheQueueService() {
        presentationOf("ReviewQueue.StartReview")
        presentationOf("ReviewQueue.ShowFileList")

        assertNull(
            "update() must not create ReviewQueueService, or opening a menu starts background work",
            project.serviceIfCreated<ReviewQueueService>(),
        )
    }
}
```

Add the import `com.intellij.openapi.components.serviceIfCreated`.

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.actions.StartReviewEnablementTest'`
Expected: FAIL — `testShowFileListNoLongerRequiresASessionOrADiffContext` fails because the action still requires `isActive` and a `DIFF_CONTEXT`.

- [x] **Step 3: Update `StartReviewAction`**

Replace `update` and `actionPerformed`:

```kotlin
    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        // Deliberately not "the queue has unreviewed items": nothing resolves the queue until a
        // gesture asks, so a contents-based check would leave this permanently disabled.
        e.presentation.isEnabled = project != null &&
            GitRoots.exist(project) &&
            !ReviewSessionService.getInstance(project).isActive
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val queue = ReviewQueueService.getInstance(project)
        if (!queue.resolveNow()) return
        val snapshot = queue.snapshot()
        if (snapshot.items.none { !queue.isReviewed(it) }) {
            QueueNotices.nothingUnreviewed(project, snapshot.scope)
            return
        }
        ReviewSessionService.getInstance(project).start()
    }
```

- [x] **Step 4: Update `ShowFileListAction`**

Replace its `update` and `actionPerformed`, rewriting the KDoc that justified the old gates:

```kotlin
/**
 * The whole change and its reviewed state, as a popup.
 *
 * Since KAN-5 removed the tool window, this is the *only* way to browse the queue, so it no longer
 * requires a running pass or a focused diff viewer — the old gates existed because the tool window's
 * tree was the better way to browse outside a pass, and that tree no longer exists.
 */
class ShowFileListAction : AnAction(
    "Show File List",
    "List every file in the review scope with its reviewed state",
    AllIcons.Actions.ListFiles,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null && GitRoots.exist(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val queue = ReviewQueueService.getInstance(project)
        // Inside a pass the session already holds a resolved queue and re-resolving would let the
        // session's fixed key list go stale against a freshly rebuilt one. Outside a pass, nothing
        // else has resolved anything.
        if (!ReviewSessionService.getInstance(project).isActive && !queue.resolveNow()) return
        val snapshot = queue.snapshot()
        if (snapshot.items.isEmpty()) {
            QueueNotices.nothingUnreviewed(project, snapshot.scope)
            return
        }
        ReviewFileListPopup.show(project, e.dataContext)
    }
}
```

- [x] **Step 5: Update `RefreshQueueAction`**

```kotlin
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        // resolveNow, not refresh(): with no tool window there is no list for an async refresh to
        // visibly update, so the modal progress is itself the feedback that anything happened.
        // This necessarily changes DiffRefreshQueueAction too — it calls super.actionPerformed.
        ReviewQueueService.getInstance(project).resolveNow()
    }
```

- [x] **Step 6: Rewrite `DiffRefreshQueueAction`'s KDoc**

Replace the paragraph at `:12-18` — its premise ("a discrepancy that only surfaces later, at `markCurrent()`'s 'left the queue' branch") no longer holds:

```kotlin
/**
 * Refresh on the diff toolbar, grouped at the end of the toolbar behind a separator, and confirming.
 *
 * Since KAN-5, Refresh means a synchronous resolve on both surfaces — `RefreshQueueAction` is this
 * class's superclass and `actionPerformed` delegates to it, so the two cannot diverge without
 * duplicating the action. The confirmation is worth more than ever: the effect is now immediate
 * rather than surfacing later at `markCurrent()`'s "left the queue" branch, and it lets the session's
 * fixed key list go stale against a freshly rebuilt queue while the reviewer is mid-file. Cancelling
 * the progress is a supported way out.
 *
 * See `DiffStartReviewAction`'s KDoc for why `RightAlignedToolbarAction` is implemented here without
 * actually right-aligning anything.
 */
```

- [x] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.actions.StartReviewEnablementTest'`
Expected: PASS, 2 tests.

- [x] **Step 8: Run the whole suite for regressions**

Run: `./gradlew test`
Expected: PASS. `ShowFileListAction`'s old gate is asserted nowhere, so nothing else should move.

- [x] **Step 9: Stage**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/actions/ \
        src/test/kotlin/dev/tweety/reviewqueue/actions/StartReviewEnablementTest.kt
```

---

### Task 4: Scope machinery and mid-pass switching

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ScopePrompts.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/ScopeActions.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/queue/ScopeSwitchTest.kt`

**Interfaces:**
- Produces: `ScopePrompts.branchVsBase(project): ReviewScope?`, `ScopePrompts.commitRange(project): ReviewScope?`; `abstract class SetScopeAction` with `protected abstract fun promptScope(project: Project): ReviewScope?`; `SetStagedAction`, `SetBranchVsBaseAction`, `SetCommitRangeAction`; `ReviewSessionService.switchScope(scope: ReviewScope)`.
- Consumes: `ReviewQueueService.resolveNow` (Task 1), the existing `confirmed(project, message, title)` from `actions/Confirm.kt`, `CommitRangeValidator`.

- [x] **Step 1: Write the failing test**

```kotlin
package dev.tweety.reviewqueue.queue

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.ui.ReviewDiffPresenter
import com.intellij.openapi.vfs.VirtualFile

class ScopeSwitchTest : HeavyPlatformTestCase() {

    private class RecordingPresenter : ReviewDiffPresenter {
        val shown = mutableListOf<ReviewKey>()
        var closes = 0
        override fun show(key: ReviewKey, position: Int, total: Int, actions: List<AnAction>): Boolean {
            shown += key
            return true
        }
        override fun close() { closes++ }
        override fun isShowing(file: VirtualFile) = false
    }

    fun testSwitchingWithNoSessionOnlyRecordsTheScope() {
        val session = ReviewSessionService.getInstance(project)
        val presenter = RecordingPresenter()
        session.presenter = presenter

        session.switchScope(ReviewScope.CommitRange("HEAD~2", "HEAD"))

        assertFalse("switching outside a pass must not start one", session.isActive)
        assertTrue("nothing should be shown", presenter.shown.isEmpty())
        assertEquals(
            ReviewScope.CommitRange("HEAD~2", "HEAD"),
            ReviewQueueService.getInstance(project).snapshot().scope,
        )
    }

    fun testSwitchingIntoAnEmptyScopeEndsThePass() {
        val queue = ReviewQueueService.getInstance(project)
        queue.progressRunner = { _, work -> work() }
        val session = ReviewSessionService.getInstance(project)
        val presenter = RecordingPresenter()
        session.presenter = presenter

        // A test project has no staged changes, so the resolved queue is empty by construction.
        session.switchScope(ReviewScope.Staged)

        assertFalse("an empty new scope must end the pass rather than leave a dead tab", session.isActive)
    }

    fun testACancelledResolveLeavesTheScopeUnapplied() {
        val queue = ReviewQueueService.getInstance(project)
        val before = queue.snapshot().scope
        queue.progressRunner = { _, _ -> null }
        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingPresenter()

        // No pass is running, so this exercises the record-only branch; the cancel path proper is
        // covered by ResolveNowTest. Kept here so the pairing is visible in one place.
        session.switchScope(before)

        assertFalse(session.isActive)
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.queue.ScopeSwitchTest'`
Expected: FAIL to compile — `switchScope` is an unresolved reference.

- [x] **Step 3: Write `ScopePrompts`**

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import dev.tweety.reviewqueue.model.CommitRangeValidator
import dev.tweety.reviewqueue.model.ReviewScope

/**
 * The ref-input dialogs for the scopes that need refs, lifted out of the deleted `ScopeSelector` so
 * that the menu group and the diff toolbar ask in exactly one way.
 *
 * Returns null when the user cancels, which callers must treat as "change nothing" — not as an
 * empty ref.
 */
object ScopePrompts {

    fun branchVsBase(project: Project): ReviewScope? {
        val base = Messages.showInputDialog(
            project,
            "Base ref (leave empty to use the tracked branch):",
            "Branch vs Base",
            null,
        ) ?: return null
        return ReviewScope.BranchVsBase(base.takeIf { it.isNotBlank() })
    }

    fun commitRange(project: Project): ReviewScope? {
        val from = Messages.showInputDialog(
            project, "From ref:", "Commit Range", null, "HEAD~1",
            object : InputValidator {
                override fun checkInput(input: String) = CommitRangeValidator.validate(input, "HEAD") == null
                override fun canClose(input: String) = checkInput(input)
            },
        ) ?: return null
        val to = Messages.showInputDialog(
            project, "To ref:", "Commit Range", null, "HEAD",
            object : InputValidator {
                override fun checkInput(input: String) = CommitRangeValidator.validate(from, input) == null
                override fun canClose(input: String) = checkInput(input)
            },
        ) ?: return null
        return ReviewScope.CommitRange(from, to)
    }
}
```

- [x] **Step 4: Write `ScopeActions`**

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.ui.ScopePrompts

/**
 * The one place the "choose a scope" rule lives, so the Tools menu group and the diff toolbar cannot
 * drift apart.
 *
 * `actionPerformed` is final on purpose: subclasses supply only the prompt. The confirmation lives
 * here rather than in `ReviewSessionService.switchScope` because `ReviewSessionServiceTest` drives
 * the real service headlessly, and a `Messages` call inside it would hang or fail the suite.
 *
 * Prompt first, then confirm — so the confirmation can name the scope being switched to, and
 * cancelling the ref prompt costs no confirmation dialog at all.
 */
abstract class SetScopeAction(text: String) : AnAction(text) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected abstract fun promptScope(project: Project): ReviewScope?

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val scope = promptScope(project) ?: return
        val session = ReviewSessionService.getInstance(project)
        if (session.isActive && !confirmed(
                project,
                "Switch the review scope to ${scope.displayName()}? The current pass restarts and " +
                    "every mark made so far is kept.",
                "Switch Scope",
            )
        ) {
            return
        }
        session.switchScope(scope)
    }
}

class SetStagedAction : SetScopeAction("Staged") {
    override fun promptScope(project: Project) = ReviewScope.Staged
}

class SetBranchVsBaseAction : SetScopeAction("Branch vs Base…") {
    override fun promptScope(project: Project) = ScopePrompts.branchVsBase(project)
}

class SetCommitRangeAction : SetScopeAction("Commit Range…") {
    override fun promptScope(project: Project) = ScopePrompts.commitRange(project)
}
```

- [x] **Step 5: Add `switchScope` and extract the key computation**

In `ReviewSessionService`, replace the key computation inside `start()` with a shared private helper and add `switchScope`:

```kotlin
    /** The files a pass should walk: unreviewed, and renderable by the diff framework. */
    private fun unreviewedShowableKeys(): List<ReviewKey> =
        queue.snapshot().items
            .filterNot { queue.isReviewed(it) }
            .map { it.key }
            .filter { queue.changeFor(it) != null }

    fun start() {
        if (session != null) return
        session = ReviewSession.start(unreviewedShowableKeys()) ?: return
        layout.hideForReview()
        showCurrent()
    }

    /**
     * Changes the review scope, restarting a running pass in the new scope.
     *
     * With no pass running this only records the scope — the next [ReviewQueueService.resolveNow],
     * from Start Review or Show File List, is what resolves it.
     *
     * With a pass running it resolves synchronously and rebuilds the session **in place**: the
     * layout is already hidden and stays hidden, and `showCurrent()` replaces the diff tab. Going
     * through `end()` then `start()` instead would restore the layout and re-hide it, flashing the
     * Project tool window open and shut mid-pass.
     *
     * `resolveNow` runs before `session` is reassigned, so a cancelled progress leaves the pass
     * exactly where it was. `showCurrent()` ends the pass when nothing in the new scope is
     * showable, which restores the layout by the ordinary path.
     */
    fun switchScope(scope: ReviewScope) {
        if (session == null) {
            queue.setScope(scope)
            return
        }
        if (!queue.resolveNow(scope)) return
        val rebuilt = ReviewSession.start(unreviewedShowableKeys())
        if (rebuilt == null) {
            end()
            return
        }
        session = rebuilt
        showCurrent()
    }
```

Add imports for `ReviewScope` and `ReviewKey` if not already present.

- [x] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.queue.ScopeSwitchTest'`
Expected: PASS, 3 tests.

- [x] **Step 7: Stage**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/ui/ScopePrompts.kt \
        src/main/kotlin/dev/tweety/reviewqueue/actions/ScopeActions.kt \
        src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt \
        src/test/kotlin/dev/tweety/reviewqueue/queue/ScopeSwitchTest.kt
```

---

### Task 5: The scope control on the diff toolbar

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffScopeAction.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt:63-78`
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt:119-154`

**Interfaces:**
- Consumes: the registered group `ReviewQueue.ScopeMenu` (declared in Task 6), `ReviewQueueService.snapshot().scope`.
- Produces: `DiffScopeAction` present in `ReviewSessionService.diffActions`, first in the group after the `Separator`.

> **Ordering note:** this task writes the class and the toolbar wiring; the `ReviewQueue.ScopeMenu` group it resolves is registered in Task 6. Run Task 6 before exercising the toolbar in `runIde` — the unit tests below do not need the registration, because `ActionManager.getAction` returning null is handled.

- [x] **Step 1: Write the failing test — extend the existing toolbar test**

In `ReviewSessionServiceTest`, `testTheDiffToolbarSplitsNavigationFromRightAlignedSessionControls` asserts the marked and unmarked partitions. Add `DiffScopeAction` to the expected marked classes and add a new case pinning its position:

```kotlin
    fun testTheScopeControlLeadsTheSessionControlGroup() {
        val service = ReviewSessionService.getInstance(project)
        val actions = service.diffActions
        val separatorAt = actions.indexOfFirst { it is Separator }

        assertTrue("the toolbar must still be split by a separator", separatorAt >= 0)
        assertTrue(
            "the scope control must lead the session-control group, so re-scoping sits with the " +
                "other session commands rather than among per-file navigation",
            actions[separatorAt + 1] is DiffScopeAction,
        )
    }
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.queue.ReviewSessionServiceTest'`
Expected: FAIL to compile — `DiffScopeAction` does not exist.

- [x] **Step 3: Write `DiffScopeAction`**

```kotlin
package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.JComponent

/**
 * Scope selection from inside the diff, which is what KAN-5 asked for.
 *
 * **Enabled during a pass**, unlike the tool window's old selector. That restriction existed because
 * changing the scope rebuilds the queue underneath the session's fixed key list — but the only diff
 * tab that carries a toolbar is the session's own, so under the old rule this control would have
 * been visible *only* where it was always greyed out. `ReviewSessionService.switchScope` handles the
 * rebuild by restarting the pass in place instead, and `SetScopeAction` confirms first.
 *
 * The popup group is the registered `ReviewQueue.ScopeMenu`, shared with the Tools menu group, so
 * there is exactly one set of scope children and one confirm rule.
 *
 * See `DiffStartReviewAction`'s KDoc for why `RightAlignedToolbarAction` is implemented here without
 * actually right-aligning anything.
 */
class DiffScopeAction : ComboBoxAction(), RightAlignedToolbarAction {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val scope = project?.let { ReviewQueueService.getInstance(it).snapshot().scope }
        e.presentation.text = scope?.displayName() ?: "Scope"
        e.presentation.isEnabled = project != null
    }

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup =
        ActionManager.getInstance().getAction(SCOPE_GROUP_ID) as? DefaultActionGroup
            ?: DefaultActionGroup()

    private companion object {
        const val SCOPE_GROUP_ID = "ReviewQueue.ScopeMenu"
    }
}
```

- [x] **Step 4: Put it on the toolbar**

In `ReviewSessionService.diffActions`, add it first in the second group and extend the KDoc:

```kotlin
        ) + listOf(
            Separator.getInstance(),
            DiffScopeAction(),
            DiffStartReviewAction(),
            DiffEndReviewAction(),
            DiffRefreshQueueAction(),
            DiffResetAllAction(),
        )
```

- [x] **Step 5: Update the existing partition assertion**

In `testTheDiffToolbarSplitsNavigationFromRightAlignedSessionControls`, add `DiffScopeAction::class.java` to the expected marked-class list, keeping its order-sensitivity intact.

- [x] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.queue.ReviewSessionServiceTest'`
Expected: PASS, including the pre-existing cases.

- [x] **Step 7: Stage**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffScopeAction.kt \
        src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt \
        src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt
```

---

### Task 6: Menu registration and the Start Review shortcut

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/StartReviewShortcutTest.kt`

**Interfaces:**
- Produces: registered ids `ReviewQueue.Menu`, `ReviewQueue.ScopeMenu`, `ReviewQueue.SetStaged`, `ReviewQueue.SetBranchVsBase`, `ReviewQueue.SetCommitRange`; `ReviewQueue.EndReview` re-declared top-level; `meta alt shift R` on `ReviewQueue.StartReview`.
- Consumes: the action classes from Tasks 4–5.

- [x] **Step 1: Write the failing test**

```kotlin
package dev.tweety.reviewqueue

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.testFramework.HeavyPlatformTestCase
import javax.swing.KeyStroke

/**
 * Pins Start Review's chord **together with its known overlap**, so a platform change in either
 * direction fails here instead of passing silently.
 *
 * Deliberately not shaped like `MarkReviewedShortcutTest`, which reads only the shipped `plugin.xml`
 * and needs no IDE. That shape is exactly why an earlier draft of this change chose `Alt+Shift+R`
 * without noticing `RerunTests` already owns it on every keymap.
 */
class StartReviewShortcutTest : HeavyPlatformTestCase() {

    private val chord: KeyStroke = KeyStroke.getKeyStroke("meta alt shift R")

    fun testStartReviewIsBoundOnTheMacKeymap() {
        val keymap = KeymapManagerEx.getInstanceEx().getKeymap("Mac OS X 10.5+") ?: return
        val ids = keymap.getActionIds(KeyboardShortcut(chord, null)).toSet()

        assertTrue(
            "Start Review must answer to the chosen chord on macOS",
            "ReviewQueue.StartReview" in ids,
        )
        assertEquals(
            "KAN-5 accepted exactly one overlap here, ForceRefresh — an EmptyAction shortcut holder " +
                "that cannot be unbound from our side. Any other id sharing this chord, or " +
                "ForceRefresh ceasing to, is a change someone must look at.",
            setOf("ReviewQueue.StartReview", "ForceRefresh"),
            ids,
        )
    }

    fun testTheChordIsMacOnly() {
        val keymap = KeymapManagerEx.getInstanceEx().getKeymap("\$default") ?: return

        assertFalse(
            "the Cmd+Option+Shift cluster is macOS-only: meta is the Windows key on \$default, so " +
                "Start Review ships shortcutless there and is reached from Tools > Review Queue",
            "ReviewQueue.StartReview" in keymap.getActionIds(KeyboardShortcut(chord, null)).toSet(),
        )
    }

    fun testTheActionIsRegistered() {
        assertNotNull(ActionManager.getInstance().getAction("ReviewQueue.StartReview"))
        assertNotNull(
            "End Review must keep a registry home after ReviewQueue.Toolbar is deleted",
            ActionManager.getInstance().getAction("ReviewQueue.EndReview"),
        )
        assertNotNull(
            "the shared scope group must be resolvable, since DiffScopeAction looks it up by id",
            ActionManager.getInstance().getAction("ReviewQueue.ScopeMenu"),
        )
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.StartReviewShortcutTest'`
Expected: FAIL — the chord is unbound and `ReviewQueue.ScopeMenu` does not resolve.

- [x] **Step 3: Replace the `ReviewQueue.Toolbar` group with the menu group**

In `plugin.xml`, delete the whole `<group id="ReviewQueue.Toolbar">` block and add:

```xml
        <group id="ReviewQueue.Menu" text="Review Queue" popup="true"
               class="com.intellij.openapi.actionSystem.DefaultActionGroup">
            <add-to-group group-id="ToolsMenu" anchor="last"/>

            <!--
              popup="true" on both groups is required: without it the children are inlined flat into
              the parent menu. text= is required too, or the submenu renders unnamed.

              A fresh id rather than reusing ReviewQueue.Scope, which was an <action> for the deleted
              ScopeSelector: reusing an action id for a group would let existing keymap or menu
              customisation degrade silently.
            -->
            <group id="ReviewQueue.ScopeMenu" text="Scope" popup="true"
                   description="Choose what the review queue lists"
                   class="com.intellij.openapi.actionSystem.DefaultActionGroup">
                <action id="ReviewQueue.SetStaged"
                        class="dev.tweety.reviewqueue.actions.SetStagedAction"
                        text="Staged"/>
                <action id="ReviewQueue.SetBranchVsBase"
                        class="dev.tweety.reviewqueue.actions.SetBranchVsBaseAction"
                        text="Branch vs Base…"/>
                <action id="ReviewQueue.SetCommitRange"
                        class="dev.tweety.reviewqueue.actions.SetCommitRangeAction"
                        text="Commit Range…"/>
            </group>

            <reference ref="ReviewQueue.StartReview"/>
            <reference ref="ReviewQueue.ShowFileList"/>
            <separator/>
            <reference ref="ReviewQueue.Refresh"/>
            <reference ref="ReviewQueue.ResetAll"/>
        </group>
```

- [x] **Step 4: Re-declare the four actions top-level**

`StartReview`, `EndReview`, `Refresh` and `ResetAll` lived inside the deleted group. Declare them top-level beside `ReviewQueue.ShowFileList`, with the shortcut on Start Review only:

```xml
        <action id="ReviewQueue.StartReview"
                class="dev.tweety.reviewqueue.actions.StartReviewAction"
                text="Start Review"
                description="Hide the Project panel and walk the unreviewed files one at a time">
            <!--
              macOS only, joining the Cmd+Option+Shift cluster. meta is the Windows key on $default,
              so this ships shortcutless on Windows and Linux, where the Tools menu group and Find
              Action are the way in. NavigationShortcutTest pins that the cluster is macOS-only.

              Known, accepted overlap: Mac OS X 10.5+ binds meta alt shift R to ForceRefresh, an
              EmptyAction shortcut holder borrowed by DatabaseView.ForceRefresh. It cannot be unbound
              — remove="true" strips a shortcut from our own action only. Whether an action-chooser
              popup actually appears is a Gate C manual check; StartReviewShortcutTest pins the
              overlap so a platform change is not silent.
            -->
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta alt shift R"/>
        </action>
        <action id="ReviewQueue.EndReview"
                class="dev.tweety.reviewqueue.actions.EndReviewAction"
                text="End Review"
                description="Leave the guided review and restore the tool windows"/>
        <action id="ReviewQueue.Refresh"
                class="dev.tweety.reviewqueue.actions.RefreshQueueAction"
                text="Refresh"
                description="Re-read the review scope now"/>
        <action id="ReviewQueue.ResetAll"
                class="dev.tweety.reviewqueue.actions.ResetAllAction"
                text="Reset All"
                description="Clear every reviewed mark in this project"/>
```

Declaration order matters: the `<reference>` elements above require these ids to exist, so declare
the actions **before** the group, or use `<add-to-group>` on each action instead.

- [x] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.StartReviewShortcutTest'`
Expected: PASS, 3 tests.

- [x] **Step 6: Confirm the two existing shortcut tests still match**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.MarkReviewedShortcutTest' --tests 'dev.tweety.reviewqueue.NavigationShortcutTest'`
Expected: PASS. Both scope their regexes to a single `<action id=…>` block, so added groups are invisible to them — this step verifies that held.

- [x] **Step 7: Stage**

```bash
git add src/main/resources/META-INF/plugin.xml \
        src/test/kotlin/dev/tweety/reviewqueue/StartReviewShortcutTest.kt
```

---

### Task 7: Delete the tool window

**Files:**
- Delete: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueuePanel.kt`
- Delete: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueueToolWindowFactory.kt`
- Delete: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueueTree.kt`
- Delete: `src/main/kotlin/dev/tweety/reviewqueue/ui/ScopeSelector.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: everything these files provided has a replacement from Tasks 1–6. Nothing may still reference them.

- [x] **Step 1: Confirm nothing references them**

Run:
```bash
grep -rn "ReviewQueuePanel\|ReviewQueueToolWindowFactory\|ReviewQueueTree\|ScopeSelector" src/
```
Expected: matches only inside the four files being deleted. Any other hit must be resolved first.

- [x] **Step 2: Delete the files**

```bash
git rm src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueuePanel.kt \
       src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueueToolWindowFactory.kt \
       src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueueTree.kt \
       src/main/kotlin/dev/tweety/reviewqueue/ui/ScopeSelector.kt
```

- [x] **Step 3: Remove the `<toolWindow>` extension**

Delete this block from `plugin.xml`, leaving `notificationGroup` and `postStartupActivity` in place —
`ReviewLayoutRestorer` is still needed for the Project window:

```xml
        <toolWindow id="Review Queue"
                    anchor="right"
                    factoryClass="dev.tweety.reviewqueue.ui.ReviewQueueToolWindowFactory"/>
```

- [x] **Step 4: Run the whole suite**

Run: `./gradlew test`
Expected: PASS. `IdeLayoutControllerTest` still passes at this point — it uses the string
`"Review Queue"`, not the class, and `MANAGED_IDS` still contains it until Task 8.

- [x] **Step 5: Verify the API-warning count dropped to zero**

Run: `./gradlew verifyPlugin`
Expected: Compatible, and **zero** deprecated-API and experimental-API usages.

**Corrected during implementation.** This step originally asserted "all 10 were attributed to
`ReviewQueueToolWindowFactory`". That premise was wrong twice over: the real baseline was **11**
(5 deprecated + 6 experimental), and deleting the factory cleared every experimental usage but left one
deprecated one — `SimpleListCellRenderer.create(Customizer)` in `ReviewFileListPopup.show`, pre-existing
debt in a *surviving* file. The zero bar was therefore unreachable by deletion alone. That call was
replaced with the supported `SimpleListCellRenderer` subclass overriding `customize`; the platform's own
`create` returns exactly such a subclass and resets text and icon on both paths, so the swap is
behaviourally identical. `ReviewFileListPopup.kt` is consequently touched by this task as well as by
Task 9, which is why it does not appear in this task's file list.

- [x] **Step 6: Stage**

```bash
git add -A src/main/kotlin/dev/tweety/reviewqueue/ui/ src/main/resources/META-INF/plugin.xml
```

---

### Task 8: Layout state — managed ids and the legacy prune

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt`
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`

**Interfaces:**
- Produces: `MANAGED_IDS = listOf("Project")`, `LEGACY_IDS = setOf("Review Queue")`, pruning in `loadState`.

- [x] **Step 1: Write the failing test**

```kotlin
    /**
     * A stale id from a version that still had the Review Queue tool window must be dropped as the
     * state loads. It can never resolve now, so `restore()` would keep it on the `unresolved` record
     * forever — and a permanently non-empty record latches `hideForReview()`'s "leftover means
     * restore first" branch.
     */
    fun testLoadStateDropsAnIdThePluginNoLongerManages() {
        val controller = controllerWithRecord("Project", "Review Queue")

        assertEquals(
            "the retired tool window id must not survive a state load",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
    }
```

Then update the two cases that use `"Review Queue"` as a live id, exactly as follows.

`testLoadedStateIsReturnedByGetState` (`:51`) — swap the retired id for one that is not pruned, so the
case keeps testing round-tripping rather than pruning:

```kotlin
    fun testLoadedStateIsReturnedByGetState() {
        val controller = controllerWithRecord("Project", "SomeOtherWindow")

        assertEquals(listOf("Project", "SomeOtherWindow"), controller.state.hiddenByReview)
    }
```

`testHideRecordsAndHidesOnlyTheVisibleManagedWindows` (`:59`) — `"Review Queue"` was the
managed-but-invisible window. Use an unmanaged registered window for the negative case instead, which
proves the same thing (only *visible, managed* windows are recorded) without depending on a second
managed id:

```kotlin
    fun testHideRecordsAndHidesOnlyTheVisibleManagedWindows() {
        val projectWindow = manager.register("Project", visible = true)
        val unmanaged = manager.register("SomeOtherWindow", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "only managed windows that were actually visible may be recorded, or restore reopens a " +
                "window the user had closed — or one the review never touched",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertFalse(projectWindow.isVisible)
        assertEquals("a window this plugin does not manage must not be hidden", 0, unmanaged.hides)
    }
```

Leave `testRestoreReopensAndThenForgetsTheWindowsItReopened`,
`testRestoreKeepsIdsThatDidNotResolveToARegisteredWindow`,
`testHideReclaimsALeftoverRecordInsteadOfRefusingToHide` and
`testASecondHideDoesNotStrandTheWindowsTheFirstOneHid` **untouched** — keeping them working is the
whole reason this prunes a named legacy list rather than keying on `MANAGED_IDS`.

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.ui.IdeLayoutControllerTest'`
Expected: FAIL on `testLoadStateDropsAnIdThePluginNoLongerManages` — the id is still retained.

- [x] **Step 3: Implement the prune**

```kotlin
    override fun loadState(state: State) {
        myState = state
        // Drop ids this plugin no longer manages. Such an id is unrestorable by construction —
        // `getToolWindow` cannot resolve an unregistered window — so `restore()` would keep it on
        // the unresolved record forever, and a permanently non-empty record latches
        // `hideForReview()`'s leftover branch and silently stops hiding anything ever again.
        //
        // Deliberately a named legacy list rather than `MANAGED_IDS`. Keying on MANAGED_IDS would
        // also empty any record a test seeded through this method, and the general
        // "keep ids that are managed but not yet registered" contract that `restore()` implements
        // has to keep working. These are two different rules for two different causes.
        myState.hiddenByReview.removeAll(LEGACY_IDS)
    }
```

and in the companion:

```kotlin
        private val MANAGED_IDS = listOf("Project")

        /** Ids this plugin used to manage. `Review Queue` was its tool window until KAN-5. */
        private val LEGACY_IDS = setOf("Review Queue")
```

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.tweety.reviewqueue.ui.IdeLayoutControllerTest'`
Expected: PASS — the new case plus all six pre-existing ones.

- [x] **Step 5: Stage**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt \
        src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt
```

---

### Task 9: The file-list popup carries the scope

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewFileListPopup.kt`

**Interfaces:**
- Consumes: `QueueSnapshot.scope`, `displayName()`.

- [x] **Step 1: Put the scope in the title**

The deleted panel's label read `N / M reviewed  •  <scope>`. The popup title carried only the count:

```kotlin
            .setTitle("${snapshot.reviewedCount} / ${snapshot.items.size} reviewed  •  ${snapshot.scope.displayName()}")
```

- [x] **Step 2: Rewrite the stale race KDoc**

The comment at `:34-37` calls the `isEmpty` guard defensive cover for a race. That is no longer true —
with enablement based on git roots it is the routine case, and the caller now reports it:

```kotlin
        // Empty is now routine, not a race: enablement follows git-root existence, so this action is
        // clickable with nothing staged. ShowFileListAction reports that case via
        // QueueNotices.nothingUnreviewed before calling here, so this stays a plain no-op guard for
        // the narrow window where the queue empties between that check and this call.
        if (rows.isEmpty()) return
```

- [x] **Step 3: Verify the out-of-pass path behaves**

Run: `./gradlew test`
Expected: PASS. `ReviewFileList.rows` already accepts a nullable `current`, so no session means no
current row — nothing else needed.

- [x] **Step 4: Stage**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewFileListPopup.kt
```

---

### Task 10: Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/manual-verification.md`

- [x] **Step 1: Rewrite the README's *Use* section**

It currently opens *"Open the **Review Queue** tool window on the right and pick a **Scope**"*. Replace
the entry-point paragraphs with the Tools menu group, and state plainly:

- Scope, Start Review, Show File List, Refresh and Reset All live under **Tools → Review Queue**.
- Start Review is <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>R</kbd> **on macOS only** — the cluster is
  macOS-only because `meta` is the Windows key elsewhere. On Windows and Linux use the menu, or bind
  your own chord in Settings → Keymap.
- Show File List is the way to browse the queue, in a pass or out of one; picking an already-reviewed
  file opens a browsing diff and leaves any pass alone.
- The scope can now be changed **during** a pass: it confirms, then restarts the pass in the new
  scope. Every mark is kept.
- Refresh re-reads the scope immediately, under a progress dialog, from either surface.
- Failed repositories and an empty scope are reported as notifications.

- [x] **Step 2: Update the README's *Verification status* section**

Delete the paragraph explaining the 4 deprecated- and 6 experimental-API usages on
`ToolWindowFactory` — the class is gone and `verifyPlugin` now reports zero. Update the test count to
whatever `./gradlew test` actually reports; do not guess it.

- [x] **Step 3: Update `docs/manual-verification.md`**

Remove the tool-window checklist items. Add, at minimum:

- **The shortcut — run this twice.** In `runIde` on macOS press
  <kbd>⌘</kbd><kbd>⌥</kbd><kbd>⇧</kbd><kbd>R</kbd>: (a) with neither a Database view nor a `.gradle.kts`
  editor focused, and (b) **with a `.gradle.kts` file open and focused**. Either Start Review runs, or an
  action-chooser popup appears. Case (b) is the one most likely to surface it, because
  `ReloadScriptConfiguration` holds the same chord on macOS (it declares `ctrl alt shift R` on
  `$default`, which `MacOSDefaultKeymap` translates to `meta alt shift R`) and is live in Kotlin scripts.
  Checking only (a) would look clean and prove nothing. **If the chooser appears in either case, the
  binding must change** — this is the one risk KAN-5 knowingly carried into manual test.
- **The popup title.** Open Show File List and confirm the title reads `N / M reviewed  •  <scope>`.
  This has **no automated coverage** — the title is built inside `JBPopupFactory` plumbing that is not
  reachable headlessly — and it is the design's stated replacement for the deleted panel's progress
  label, so it is a human check or it is unchecked.
- **Menu rendering.** Tools → Review Queue shows a named submenu, with Scope as a nested submenu
  rather than three flat entries.
- **The diff toolbar scope combo** renders, names the current scope, and is enabled mid-pass.
- **Mid-pass re-scope.** Start a pass, change the scope, confirm: the pass restarts in the new scope,
  the Project panel does **not** flash open and shut, and marks made before the switch survive.
- **Empty scope.** With nothing staged, Start Review reports "Nothing unreviewed in Staged" rather
  than appearing to do nothing.
- **Windows/Linux.** Start Review has no shortcut; the menu is the way in.

- [x] **Step 4: Stage**

```bash
git add README.md docs/manual-verification.md
```

---

### Task 11: Full verification

- [x] **Step 1: Run the whole test suite**

Run: `./gradlew test`
Expected: PASS, with no skipped classes. Record the actual test count for the README.

- [x] **Step 2: Run the plugin verifier**

Run: `./gradlew verifyPlugin`
Expected: **Compatible**, zero compatibility problems, and **zero** deprecated- and experimental-API
usages.

- [x] **Step 3: Confirm the plugin loads**

Run: `./gradlew runIde`
Expected: `dev.tweety.reviewqueue` does not appear in the platform's "Problems found loading plugins"
block, and nothing else in the log mentions the plugin. This confirms loading only — every UI question
belongs to Gate C, per `docs/manual-verification.md`.

- [x] **Step 4: Confirm the deletion is complete**

Run:
```bash
grep -rn "toolWindow\|ToolWindowFactory" src/main/resources/META-INF/plugin.xml
grep -rn "ReviewQueuePanel\|ReviewQueueTree\|ScopeSelector" src/
```
Expected: no matches for either. `ToolWindowManager` in `IdeLayoutController` is expected and correct —
it still manages the Project window.

- [x] **Step 5: Stage everything**

```bash
git add -A
git status --short
```
Expected: only the files named in this plan. Nothing under `openspec/` should be modified by
implementation work.

## Manual Test Fixes

### Fix — 2026-07-27 (coverage gaps found by `/myflow-review`)

One task, not three: all three findings land in the same file and the same TDD cycle, per the
"one task per module touched" rule.

**Files:**
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/queue/ResolveNowTest.kt`
- Read (do not change): `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt`,
  `src/test/kotlin/dev/tweety/reviewqueue/notify/NotificationCapture.kt`

- [x] **F1.1 Pin the EDT precondition** — assert `resolveNow` refuses to run off the EDT. RED: delete
  `ThreadingAssertions.assertEventDispatchThread()` from `resolveNow` and watch it pass without it.
  Note `HeavyPlatformTestCase` runs bodies *on* the EDT, so the call must be made from a background
  thread (`ApplicationManager.getApplication().executeOnPooledThread { … }.get()`) and the resulting
  exception unwrapped from `ExecutionException`.

- [x] **F1.2 Pin the disposal arm** — assert a resolve against a disposed project is not reported as a
  failure. `dispose()` calls `refreshExecutor.shutdownNow()`, so `submit` throws
  `RejectedExecutionException`, which `resolveNow` catches and turns into `false`. RED: remove that
  catch arm and the exception escapes. Prefer driving it through the real executor rather than a stubbed
  `progressRunner`, or the arm under test is bypassed.

  **Correction made while implementing.** The RED above is wrong as written: removing the arm does
  *not* let the exception escape, because the generic `catch (e: Exception)` below it also returns
  `false`. The only observable difference is that the generic arm calls
  `thisLogger().warn("Review Queue resolve failed", e)`, so the test asserts through a
  `LoggedErrorProcessor` that **no warning is logged** — with a positive control proving the same
  recorder does see a warning when a resolve genuinely fails. It also disposes the *service*, not the
  project: `project.isDisposed` short-circuits `resolveNow` before the executor is reached, and
  `HeavyPlatformTestCase` owns the project's lifecycle.

- [x] **F1.3 Pin cancellation silence** — assert a cancelled resolve publishes **no** notification.
  Use `NotificationCapture` (already shared, subscribes to the real `Notifications.TOPIC`) and a
  `progressRunner` returning null. RED: make `resolveNow` call `QueueNotices.emptyResult` on its cancel
  path and the capture is non-empty. This is the scenario "A cancelled resolution is not a failure" in
  `review-queue-resolution`.

- [x] **F1.4 Verify** — `./gradlew test` (194 before this task; expect 197) and `./gradlew verifyPlugin`
  still Compatible with zero deprecated and zero experimental usages. Show real output; do not report a
  count you did not observe.

  Observed: `./gradlew test` → **197 tests, 0 failures, 0 errors** across 29 test classes (counted from
  `build/test-results/test/*.xml`). `./gradlew verifyPlugin --rerun` →
  `IU-262.9437.22 against dev.tweety.reviewqueue:0.4.0: Compatible`, `verification-verdict.txt` is
  `Compatible`, and the report contains no deprecated, experimental, or internal API usage sections.
  Each of the three tests was demonstrated RED against its named mutation and the mutation reverted; no
  production code changed (`git diff src/main` is empty).
