> **Execution:** `/myflow-do` implements this plan. Mark each checkbox when its task passes spec +
> quality review.

**Goal:** Give the guided review pass a live reviewed-count banner, a full-screen focus mode, rename
detection in every scope, and the review actions on right-click — and delete the dead cursor code the
README still documents as a limitation.

**Architecture:** Four independent changes to existing components plus one deletion. The banner and
the context menu ride on user-data keys the presenter already stamps on each diff chain; focus mode
widens one constant into a sweep; rename detection changes which git call each scope makes. Nothing
new is introduced between the queue, the state and the presenter.

**Tech stack:** Kotlin, IntelliJ Platform 2026.2 (IU-262), git4idea, JUnit 3-style
`HeavyPlatformTestCase`, Gradle with the IntelliJ Platform Gradle Plugin.

**Design:** `openspec/changes/kan-6-plugin-updates/design.md` and
`docs/superpowers/specs/2026-07-29-kan-6-plugin-updates-design.md`.

## Global constraints

- **Kotlin, matching the surrounding style.** Every existing file carries dense KDoc explaining *why*
  a decision was made. Match that density; a bare implementation reads as a regression here.
- **No commits.** `/myflow-do` stages only — `git add`, never `git commit`. Each task ends with a
  stage step.
- **No lint tooling is configured.** `build.gradle.kts` declares no detekt, ktlint or spotless, so
  there is no lint command to run. Do not add one as part of this change.
- **Read-only git.** This plugin only ever queries a repository. Every ref reaching git stays behind
  `CommitRangeValidator.validateRef` — see `GitReviewSource.kt:63-107` for why, including the
  `.git/index` truncation that rule prevents.
- **`./gradlew test` must stay green.** Baseline measured on this branch at `c515c42` is **197
  tests, 0 failures**. (`README.md`'s "194 tests" is stale — task 6.6 corrects it.)
- Full suite: `./gradlew test`
- One class: `./gradlew test --tests "dev.tweety.reviewqueue.<pkg>.<Class>"`
- Compatibility: `./gradlew verifyPlugin` — must stay **Compatible** with zero deprecated and zero
  experimental API usages.

## 1. Delete the dead cursor code and its documentation

`ReviewCursor` has no caller anywhere in `src/main` — not `relocate`, not `firstUnreviewed`, not
`nextUnreviewed`. The live path is `ReviewSession.settleOn`. This task removes the object, its test,
and the README limitation that describes its behaviour as if it were live.

**Files:**
- Delete: `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewCursor.kt`
- Delete: `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewCursorTest.kt`
- Modify: `README.md` — the `### Known, deliberate limitations` section
- Modify: `docs/manual-verification.md:278` and the checklist item at `:691`

- [x] 1.1 Prove there is no caller before deleting anything. Run:
  `grep -rn "ReviewCursor" src/main/` — expect **no output**. If anything is printed, stop: the
  premise of this task is wrong and the change needs re-planning.

- [x] 1.2 Delete both files:
  `git rm src/main/kotlin/dev/tweety/reviewqueue/core/ReviewCursor.kt src/test/kotlin/dev/tweety/reviewqueue/core/ReviewCursorTest.kt`

- [x] 1.3 Run `./gradlew test` and confirm it compiles and passes, now with 4 fewer tests
  (`ReviewCursorTest` holds 4). A compile error here means 1.1 missed a caller.

- [x] 1.4 In `README.md`, delete the whole `### Known, deliberate limitations` section — both
  bullets and the heading. The rename bullet is resolved by task 5; the cursor bullet describes code
  that no longer runs. Leave the surrounding `## Develop` section untouched.

- [x] 1.5 In `docs/manual-verification.md`, delete the `**Renames show as delete+add.**` paragraph at
  line 278 and the checklist entry `- [ ] 17. Rename-as-delete+add — acceptable? (yes/no + comment)`
  at line 691. Do not renumber the remaining checklist items — the numbering is referenced from
  `README.md` ("section 20 covers the guided review flow, and section 24 the entry points"), and
  renumbering would silently break those references.

- [x] 1.6 Stage: `git add -A src/ README.md docs/manual-verification.md`

## 2. Focus mode — hide every visible tool window

`IdeLayoutController.MANAGED_IDS = listOf("Project")` becomes a sweep over every visible tool window.
Record and restore logic is **not** touched: it already records before hiding, keeps unresolved ids,
and restores only what it hid.

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt:47-66,86-93`
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindows.kt:49-65`
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt:44-59`

**Interfaces:**
- Produces: `IdeLayoutController.hideForReview()` — signature unchanged, behaviour widened. Nothing
  else in the codebase calls it except `ReviewSessionService.start()`.

- [x] 2.1 Give the test fixture the ability to enumerate ids. `RecordingToolWindowManager` overrides
  `getToolWindow` only, so a sweep would see an empty world. Add to
  `src/test/kotlin/dev/tweety/reviewqueue/ui/RecordingToolWindows.kt`, inside
  `RecordingToolWindowManager`:

```kotlin
    /**
     * The sweep in `hideForReview` enumerates ids rather than consulting a fixed list, so the
     * fixture has to answer this as well as [getToolWindow]. Returning only registered ids keeps
     * the two in agreement: every id this returns resolves, and nothing else does.
     */
    override fun getToolWindowIds(): Array<String> = windows.keys.toTypedArray()
```

- [x] 2.2 Write the failing test for the sweep. **Replace**
  `testHideRecordsAndHidesOnlyTheVisibleManagedWindows` in
  `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt:44-59` with:

```kotlin
    fun testHideRecordsAndHidesEveryVisibleWindow() {
        val projectWindow = manager.register("Project", visible = true)
        val terminal = manager.register("Terminal", visible = true)
        val thirdParty = manager.register("SomePluginWindow", visible = true)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "every visible window must be recorded, whichever plugin registered it",
            setOf("Project", "Terminal", "SomePluginWindow"),
            controller.state.hiddenByReview.toSet(),
        )
        assertFalse(projectWindow.isVisible)
        assertFalse(terminal.isVisible)
        assertFalse("a third-party window is hidden too; there is no allowlist", thirdParty.isVisible)
    }
```

- [x] 2.3 Add the already-closed case for a non-Project window, which is what stops the visibility
  filter from being weakened later. Append to the same test class:

```kotlin
    /**
     * The invisible half of the sweep. Without it, replacing the `isVisible == true` filter with a
     * bare "is registered" check would pass the whole suite while hiding — and then reopening —
     * windows the user had deliberately closed.
     */
    fun testHideSkipsWindowsThatAreAlreadyClosed() {
        val visible = manager.register("Project", visible = true)
        val closed = manager.register("Terminal", visible = false)
        val controller = controllerWithRecord()

        controller.hideForReview()

        assertEquals(
            "an already-closed window must not be recorded, or restore reopens it at end of pass",
            listOf("Project"),
            controller.state.hiddenByReview,
        )
        assertEquals("it must not be hidden either — it already was", 0, closed.hides)
        assertFalse(visible.isVisible)
    }
```

- [x] 2.4 Run and watch it fail:
  `./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"`
  Expected: `testHideRecordsAndHidesEveryVisibleWindow` fails — only `Project` was recorded.

- [x] 2.5 Implement the sweep. In
  `src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt`, replace the body of
  `hideForReview`'s middle two lines (`val hidden = MANAGED_IDS.filter { … }`) with:

```kotlin
        val manager = ToolWindowManager.getInstance(project)
        // Every visible window, on every side, whoever registered it — deliberately not a list of
        // ids. KAN-6 asks for the reviewer to be left with the diff alone, and a fixed list would
        // silently miss the next plugin the user installs. The visibility filter is the whole
        // safety property: only a window that was open is recorded, so `restore()` can never open
        // one the user had closed.
        val hidden = manager.toolWindowIds.filter { manager.getToolWindow(it)?.isVisible == true }
```

- [x] 2.6 Delete the now-unused `MANAGED_IDS` constant from the `companion object`
  (`IdeLayoutController.kt:87`). **Keep `LEGACY_IDS` and the `loadState` prune** — its KDoc at
  `:35-43` explains that it drops ids of tool windows that no longer *exist*, which is a different
  rule from the visible-sweep and is still needed.

- [x] 2.7 Update `IdeLayoutController`'s class KDoc first line if it still implies a fixed set, and
  the KDoc on `hideForReview` (`"Hides the managed windows that are currently visible"` →
  `"Hides every tool window that is currently visible"`).

- [x] 2.8 Run `./gradlew test --tests "dev.tweety.reviewqueue.ui.IdeLayoutControllerTest"` — all
  green, including the six pre-existing tests, which must not have been weakened.

- [x] 2.9 Run the full suite: `./gradlew test`. `ScopeSwitchTest` and `ReviewSessionServiceTest` both
  drive the layout controller; confirm neither regressed.

- [x] 2.10 Stage: `git add src/main/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutController.kt src/test/kotlin/dev/tweety/reviewqueue/ui/`

## 3. Progress banner above the diff

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewProgressBanner.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewProgressBannerTest.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffPresenter.kt:34-48`

**Interfaces:**
- Consumes: `QueueSnapshot(items, reviewedCount, errors, scope)` from
  `dev.tweety.reviewqueue.queue.ReviewQueueService.snapshot()`; `ReviewQueueService.addListener(listener: () -> Unit, parent: Disposable)`.
- Produces: `ReviewProgressBanner.text(reviewedCount: Int, total: Int, scope: ReviewScope): String`
  and `ReviewProgressBanner.provider(project: Project): DiffNotificationProvider`. Task 4 relies on
  the marker key `ReviewDiffKeys.REVIEW_DIFF` added in 3.5.

- [x] 3.1 Write the failing test for the text, which is the only part worth unit-testing — the Swing
  assembly is a Gate B human check. Create
  `src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewProgressBannerTest.kt`:

```kotlin
package dev.tweety.reviewqueue.ui

import dev.tweety.reviewqueue.model.ReviewScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewProgressBannerTest {

    @Test
    fun `reports the reviewed count against the scope total`() {
        assertEquals(
            "5 / 12 files reviewed  •  Staged",
            ReviewProgressBanner.text(5, 12, ReviewScope.Staged),
        )
    }

    @Test
    fun `reports zero reviewed without special-casing it`() {
        assertEquals(
            "0 / 10 files reviewed  •  Staged",
            ReviewProgressBanner.text(0, 10, ReviewScope.Staged),
        )
    }

    /** An empty scope must not read `0 / 0` as an error or divide by zero in the bar fraction. */
    @Test
    fun `an empty scope reads as nothing to review`() {
        assertEquals(
            "0 / 0 files reviewed  •  Staged",
            ReviewProgressBanner.text(0, 0, ReviewScope.Staged),
        )
        assertEquals(0.0, ReviewProgressBanner.fraction(0, 0), 0.0)
    }

    @Test
    fun `a fully reviewed scope is a full bar`() {
        assertEquals(1.0, ReviewProgressBanner.fraction(12, 12), 0.0)
    }
}
```

- [x] 3.2 Run it and watch it fail:
  `./gradlew test --tests "dev.tweety.reviewqueue.ui.ReviewProgressBannerTest"`
  Expected: compile failure — `ReviewProgressBanner` does not exist.

- [x] 3.3 Read `src/main/kotlin/dev/tweety/reviewqueue/model/ReviewScope.kt` and find how a scope
  renders its display name. `DiffScopeAction` already labels the toolbar combo, so a naming function
  exists — **reuse it** rather than writing a second one, or the banner and the combo can disagree
  about what the current scope is called. If it is private to `DiffScopeAction`, lift it to
  `ReviewScope` and update that caller in the same step.

- [x] 3.4 Create `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewProgressBanner.kt` with the pure
  functions plus the provider. Use the scope-naming function found in 3.3 in place of
  `<scopeName(scope)>`:

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.diff.FrameDiffTool
import com.intellij.diff.util.DiffNotificationProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.JComponent

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
        "$reviewedCount / $total files reviewed  •  ${/* scope name from 3.3 */ scopeName(scope)}"

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
        DiffNotificationProvider { viewer: FrameDiffTool.DiffViewer ->
            val panel = ReviewProgressPanel(project)
            // Bound to the viewer, not the project: the listener must die with the diff it decorates,
            // or every file shown in a long pass leaves another live listener behind.
            Disposer.register(viewer, panel)
            ReviewQueueService.getInstance(project).addListener(panel::refresh, viewer)
            panel.refresh()
            panel
        }
}
```

- [x] 3.5 Add `ReviewProgressPanel` in the same file:

```kotlin
/**
 * The banner's component. Holds no state of its own: every repaint re-reads the queue snapshot, so
 * there is exactly one source for the number on screen.
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
```

  Imports to add: `com.intellij.ui.components.JBLabel`, `com.intellij.ui.components.JBPanel`,
  `com.intellij.util.ui.JBUI`, `javax.swing.JProgressBar`.

  `refresh()` must be callable from the queue listener registered in 3.4, which runs on the EDT —
  `ReviewQueueService.fireChanged` is only ever reached from EDT paths (`applyRebuild` is invoked
  through `invokeLater`, and `markReviewed`/`toggleReviewed`/`resetAll` from actions). Do not add
  threading of your own; verify that claim by reading `ReviewQueueService.fireChanged` and its
  callers before relying on it.

- [x] 3.6 Add the marker key and the notification provider to the chain. In
  `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffPresenter.kt`, add above the presenter class:

```kotlin
/**
 * Data this plugin attaches to the diff chains it builds.
 *
 * [REVIEW_DIFF] exists so `ReviewDiffExtension` can tell one of our diffs from every other diff in
 * the IDE. `DiffExtension` is a global extension point — it fires for the Git log, local history and
 * Compare Files alike — so an extension with no marker to check would modify diffs this plugin never
 * opened. Deliberately not "is a review session running?": that answer is true for an unrelated diff
 * opened mid-pass.
 */
object ReviewDiffKeys {
    val REVIEW_DIFF: Key<Boolean> = Key.create("ReviewQueue.reviewDiff")
}
```

  and inside `EditorTabDiffPresenter.show`, beside the existing `putUserData` at line 39:

```kotlin
        chain.putUserData(ReviewDiffKeys.REVIEW_DIFF, true)
        chain.putUserData(
            DiffUserDataKeys.NOTIFICATION_PROVIDERS,
            listOf(ReviewProgressBanner.provider(project)),
        )
```

  Add `import com.intellij.openapi.util.Key` to the file's imports.

- [x] 3.7 Run `./gradlew test --tests "dev.tweety.reviewqueue.ui.ReviewProgressBannerTest"` — green.

- [x] 3.8 Run `./gradlew test` — full suite green, then `./gradlew verifyPlugin` — still
  **Compatible**, still zero deprecated and zero experimental usages. `DiffNotificationProvider` and
  `NOTIFICATION_PROVIDERS` are both stable API in IU-262; if the verifier flags either, stop and
  report rather than suppressing.

- [x] 3.9 Stage: `git add src/main/kotlin/dev/tweety/reviewqueue/ui/ src/test/kotlin/dev/tweety/reviewqueue/ui/`

## 4. Review actions on right-click

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/ReviewDiffPopupGroup.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffExtension.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/actions/diff/ReviewDiffPopupGroupTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` — one `<group>` and one `<extensions>` entry

**Interfaces:**
- Consumes: `ReviewDiffKeys.REVIEW_DIFF` from task 3.6; `ReviewSessionService.diffActions`
  (`ReviewSessionService.kt:74-90`), which is `internal` and already holds the toolbar's two groups
  in order, separated by a `Separator`.
- Produces: action group id `ReviewQueue.DiffPopup`.

- [x] 4.1 Write the failing test for the group's composition. Create
  `src/test/kotlin/dev/tweety/reviewqueue/actions/diff/ReviewDiffPopupGroupTest.kt`:

```kotlin
package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.HeavyPlatformTestCase

class ReviewDiffPopupGroupTest : HeavyPlatformTestCase() {

    private fun childIds(): List<String?> {
        val group = ActionManager.getInstance().getAction("ReviewQueue.DiffPopup")
        require(group is ReviewDiffPopupGroup) { "ReviewQueue.DiffPopup must be registered" }
        return group.getChildren(null).map { ActionManager.getInstance().getId(it) }
    }

    fun testMarkReviewedLeadsTheMenu() {
        assertEquals("ReviewQueue.MarkReviewed", childIds().first())
    }

    fun testEveryPerFileActionIsOffered() {
        val ids = childIds()
        listOf(
            "ReviewQueue.MarkReviewed",
            "ReviewQueue.ToggleReviewed",
            "ReviewQueue.ShowFileList",
            "ReviewQueue.PreviousFile",
            "ReviewQueue.NextFile",
            "ReviewQueue.PreviousChange",
            "ReviewQueue.NextChange",
        ).forEach { assertTrue("$it must be in the popup", ids.contains(it)) }
    }

    /**
     * The point of composing the platform tail from the live group rather than enumerating it: an
     * entry contributed by another plugin has to survive. Annotate comes from VcsActions.xml.
     */
    fun testAContributedPlatformEntrySurvives() {
        val platform = ActionManager.getInstance().getAction("Diff.EditorPopupMenu")
        val contributed = (platform as com.intellij.openapi.actionSystem.ActionGroup)
            .getChildren(null)
            .map { ActionManager.getInstance().getId(it) }
            .filterNotNull()
            .filter { it != "CompareClipboardWithSelection" }
        val ids = childIds()
        contributed.forEach { assertTrue("$it must survive into the review popup", ids.contains(it)) }
    }

    fun testCompareWithClipboardIsRemoved() {
        assertFalse(
            "Compare with Clipboard has no use during a review pass",
            childIds().contains("CompareClipboardWithSelection"),
        )
    }
}
```

- [x] 4.2 Run it and watch it fail:
  `./gradlew test --tests "dev.tweety.reviewqueue.actions.diff.ReviewDiffPopupGroupTest"`
  Expected: `require` fails — `ReviewQueue.DiffPopup` is not registered.

- [x] 4.3 Create `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/ReviewDiffPopupGroup.kt`:

```kotlin
package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * The review diff's context menu: the per-file actions, the session controls, then whatever the
 * platform puts on a diff's menu — minus Compare with Clipboard.
 *
 * **The platform tail is composed, never enumerated.** `Diff.EditorPopupMenu` is contributed to by
 * `VcsActions.xml` (Annotate with Git Blame), by `intellij.platform.collaborationTools` (review
 * comments) and by the Ultimate customization layer. A hand-written replacement list would silently
 * drop all three, and would keep dropping whatever a future IDE adds to that group. Reading the live
 * children and filtering one id out costs a lookup and stays correct.
 *
 * **The session controls sit below the per-file actions on purpose.** `Reset All` clears every mark
 * in the project. It must not occupy the position a slipped click lands on, directly under the
 * pointer that opened the menu.
 */
class ReviewDiffPopupGroup : DefaultActionGroup() {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = ActionManager.getInstance()
        val perFile = PER_FILE_IDS.mapNotNull { manager.getAction(it) }
        val session = e?.project?.let { sessionControls(it) } ?: emptyList()
        val platform = platformTail(manager)
        return (perFile + Separator.getInstance() + session + Separator.getInstance() + platform)
            .toTypedArray()
    }

    /**
     * The same confirming instances the diff toolbar builds, so a menu press and a toolbar press ask
     * the same question. `diffActions` already carries the toolbar's own `Separator` between its two
     * groups; only the tail after it is taken here, because the per-file half is listed above by id
     * (which is what makes the menu entries show their keyboard shortcuts).
     */
    private fun sessionControls(project: Project): List<AnAction> {
        val actions = ReviewSessionService.getInstance(project).diffActions
        val separator = actions.indexOfFirst { it is Separator }
        return if (separator < 0) emptyList() else actions.drop(separator + 1)
    }

    private fun platformTail(manager: ActionManager): List<AnAction> {
        val group = manager.getAction(PLATFORM_GROUP_ID) as? ActionGroup ?: return emptyList()
        return group.getChildren(null).filter { manager.getId(it) != CLIPBOARD_ACTION_ID }
    }

    companion object {
        /**
         * Resolved by id rather than constructed, because that is what makes each menu entry carry
         * its keyboard shortcut — the same reason `ReviewSessionService.diffActions` resolves the
         * navigation actions by id.
         */
        private val PER_FILE_IDS = listOf(
            "ReviewQueue.MarkReviewed",
            "ReviewQueue.ToggleReviewed",
            "ReviewQueue.ShowFileList",
            "ReviewQueue.PreviousFile",
            "ReviewQueue.NextFile",
            "ReviewQueue.PreviousChange",
            "ReviewQueue.NextChange",
        )

        /** Verified against `PlatformActions.xml:452` for IU-2026.2. */
        private const val PLATFORM_GROUP_ID = "Diff.EditorPopupMenu"
        private const val CLIPBOARD_ACTION_ID = "CompareClipboardWithSelection"
    }
}
```

- [x] 4.4 Register the group in `src/main/resources/META-INF/plugin.xml`, inside `<actions>` and
  **after** every `<action>` it references by id — the same structural ordering rule the existing
  `ReviewQueue.Menu` comment states, since `getAction` at popup time needs those ids registered:

```xml
        <!--
          The diff's context menu. Registered as a group with a class so its children can be composed
          at popup time: the platform tail must be read live, or entries other plugins contribute to
          Diff.EditorPopupMenu are silently dropped. Installed onto a viewer's editors by
          ReviewDiffExtension, never by add-to-group — the menu belongs to this plugin's diffs only.
        -->
        <group id="ReviewQueue.DiffPopup"
               class="dev.tweety.reviewqueue.actions.diff.ReviewDiffPopupGroup"/>
```

- [x] 4.5 Run `./gradlew test --tests "dev.tweety.reviewqueue.actions.diff.ReviewDiffPopupGroupTest"`
  — all four green.

- [x] 4.6 Write the failing test for the extension's guard, which is the safety property that keeps a
  global EP from touching unrelated diffs. Create
  `src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffExtensionTest.kt` asserting that
  `ReviewDiffExtension.shouldDecorate(request)` is **false** for a request with no marker and
  **true** for one carrying `ReviewDiffKeys.REVIEW_DIFF`. Expose the predicate as an `internal fun`
  so it is testable without constructing a live viewer:

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.contents.DiffContent
import com.intellij.testFramework.HeavyPlatformTestCase

class ReviewDiffExtensionTest : HeavyPlatformTestCase() {

    private fun request(): SimpleDiffRequest =
        SimpleDiffRequest("t", emptyList<DiffContent>(), emptyList<String>())

    fun testAnUnmarkedRequestIsNotDecorated() {
        assertFalse(
            "a global EP must refuse every diff this plugin did not open",
            ReviewDiffExtension.shouldDecorate(request()),
        )
    }

    fun testAMarkedRequestIsDecorated() {
        val marked = request().apply { putUserData(ReviewDiffKeys.REVIEW_DIFF, true) }
        assertTrue(ReviewDiffExtension.shouldDecorate(marked))
    }
}
```

- [x] 4.7 Run it and watch it fail (class does not exist), then create
  `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffExtension.kt`:

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.openapi.editor.ex.EditorEx

/**
 * Installs the review context menu on the editors of a review diff.
 *
 * `DiffExtension` is the only hook that reaches a diff viewer's editors, and it is **global**: it
 * fires for the Git log, local history, Compare Files and every other diff in the IDE. The guard
 * below is therefore the first thing that runs, and it is the whole safety property — without it
 * this plugin would remove Compare with Clipboard from diffs it never opened.
 */
class ReviewDiffExtension : DiffExtension() {

    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest,
    ) {
        if (!shouldDecorate(request)) return
        editorsOf(viewer).forEach { it.setContextMenuGroupId(POPUP_GROUP_ID) }
    }

    private fun editorsOf(viewer: FrameDiffTool.DiffViewer): List<EditorEx> =
        (viewer as? DiffViewerBase)?.editors.orEmpty().filterIsInstance<EditorEx>()

    companion object {
        private const val POPUP_GROUP_ID = "ReviewQueue.DiffPopup"

        /**
         * Deliberately keyed on data this plugin attached, not on whether a pass is running. A
         * session check would claim any diff opened mid-pass — open the Git log while reviewing and
         * that diff would get the review menu too.
         */
        internal fun shouldDecorate(request: DiffRequest): Boolean =
            request.getUserData(ReviewDiffKeys.REVIEW_DIFF) == true
    }
}
```

- [x] 4.8 The marker is stamped on the **chain** in 3.6, and `onViewerCreated` receives the
  **request**. Verify which object carries it: `ChangeDiffRequestChain` copies chain user data onto
  each produced request in some platform versions and not others. Check by reading
  `ChangeDiffRequestChain` / `DiffRequestChainBase` in the platform sources. If it does **not**
  propagate, stamp the marker on the producer's request instead — the simplest route is to also put
  it in `DiffUserDataKeys.DATA_PROVIDER`-adjacent context, or to have the extension check
  `context.getUserData(...)`, which `DiffContext` does receive from the chain. Adjust
  `shouldDecorate` to take whichever object carries it and update the test in 4.6 to match. **Do not
  guess** — a silently-never-true guard makes the whole feature a no-op that no test catches.

- [x] 4.9 Register the extension in `src/main/resources/META-INF/plugin.xml`, in the existing
  `<extensions defaultExtensionNs="com.intellij">` block:

```xml
        <diff.DiffExtension implementation="dev.tweety.reviewqueue.ui.ReviewDiffExtension"/>
```

- [x] 4.10 Run `./gradlew test` — full suite green. Then `./gradlew verifyPlugin` — **Compatible**,
  zero deprecated, zero experimental. `DiffViewerBase` is `@ApiStatus.Internal` in some builds; if
  the verifier flags it, replace `editorsOf` with a viewer-type dispatch over the public
  `SimpleDiffViewer` / `UnifiedDiffViewer` / `TwosideTextDiffViewer` types rather than suppressing
  the warning.

- [x] 4.11 Stage: `git add src/main/ src/test/ `

## 5. Rename detection in every scope

Ordered last on purpose: it replaces the resolution path every myflow Gate B review depends on, so a
problem here must not block tasks 1–4.

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/git/GitReviewSource.kt:49-82,109-119`
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/git/GitReviewSourceIntegrationTest.kt`
- Possibly delete: `src/main/kotlin/dev/tweety/reviewqueue/core/StagedFilter.kt` and
  `src/test/kotlin/dev/tweety/reviewqueue/core/StagedFilterTest.kt`

**Interfaces:**
- Consumes: `ChangeMapper.toItem(rootPath, change)` — unchanged; it already prefers `afterRevision`,
  so a rename keys to the new path with the new content's hash.
- Produces: `GitReviewSource.resolve(scope): List<RootResult>` — signature unchanged.

- [x] 5.1 Read `src/test/kotlin/dev/tweety/reviewqueue/git/GitReviewSourceIntegrationTest.kt` in full
  to learn how it builds a real repository fixture. Every test below reuses that fixture; do not
  invent a second one.

- [x] 5.2 Write the failing test for a staged rename. The fixture's `setUp` already commits
  `kept.txt`, then stages a modification to it plus a new `added.txt`, and leaves `untracked.txt`
  unstaged. `git mv` on top of that stages the rename. Append to
  `src/test/kotlin/dev/tweety/reviewqueue/git/GitReviewSourceIntegrationTest.kt`:

```kotlin
    /**
     * Without rename detection this resolves to three entries — a delete of kept.txt, an add of
     * renamed.txt, and added.txt — which is exactly the limitation KAN-6 removes.
     */
    fun testAStagedRenameIsOneEntryKeyedToTheNewPath() {
        git("mv", "kept.txt", "renamed.txt")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)

        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        assertNull(results[0].error)
        val paths = results[0].changes
            .mapNotNull { ChangeMapper.toItem(results[0].rootPath, it) }
            .map { it.key.relPath }
            .sorted()

        assertEquals(
            "a rename must be one entry keyed to the new path, not a delete plus an add",
            listOf("added.txt", "renamed.txt"),
            paths,
        )
    }
```

- [x] 5.3 Run it and watch it fail:
  `./gradlew test --tests "dev.tweety.reviewqueue.git.GitReviewSourceIntegrationTest"`
  Expected: the assertion reports three paths — `[added.txt, kept.txt, renamed.txt]` — because the
  delete of `kept.txt` is still its own entry.

- [x] 5.4 Replace `resolveStaged` in `GitReviewSource.kt:77-82`. `git4idea.index.getStatus` reads
  `git status --porcelain=v2`, which has no similarity pass; no combination of its three booleans
  produces rename entries (`docs/superpowers/plans/2026-07-25-review-queue.md:29` records the session
  that established this). Resolve HEAD-vs-index through `GitChangeUtils` instead, with rename
  detection on. Keep both existing guarantees: a failing root reports git's own message through
  `RootResult.error` — so use the **throwing** overload, as the `CommitRange` branch's comment at
  `:53-55` explains — and untracked and ignored files stay out.

- [x] 5.5 Run the staged rename test — green. Then run the two guarantee tests from 5.9 before
  moving on; if either is missing, write it now rather than after the other scopes.

- [x] 5.6 If `StagedFilter` now has no caller, confirm with `grep -rn "StagedFilter" src/main/`
  (expect no output) and delete it and `StagedFilterTest.kt` with `git rm`. If the new resolution
  still needs it, leave both in place and say so in the task notes — do not delete a file that is
  still load-bearing to satisfy a plan.

- [x] 5.7 Write the failing test for Commit Range, then switch
  `GitChangeUtils.getDiff(project, repository.root, scope.from, scope.to, null)` at
  `GitReviewSource.kt:59` to the rename-detecting form. **Keep the throwing overload** — the comment
  at `:53-55` records that the `(repository, from, to, detectRenames)` overload swallows the
  `VcsException` and returns null, degrading git's real message to a generic one. If the only
  rename-detecting overload is the swallowing one, resolve renames another way rather than losing the
  error message, and note the trade-off.

- [x] 5.8 Write the failing test for Branch vs Base, then make
  `resolveBranchVsBase` (`GitReviewSource.kt:109-119`) produce single rename entries.
  `getThreeDotDiffOrThrow(repository, base, head)` exposes no rename flag at the call site — find out
  by running the test whether it already detects renames, and only replace the call if it does not.
  **Every ref must stay validated**: `explicitBase`, the resolved `base`, and `head` all pass through
  `rejectUnsafeRef` today, and the KDoc at `:84-107` explains that removing any of them re-opens a
  `.git/index` truncation from a hostile repository. Preserve all three calls whatever the diff call
  becomes.

- [x] 5.9 Add the two guarantee tests the spec requires, if the fixture does not already cover them:
  a root whose git command fails still reports its own error while other roots contribute their
  files; and untracked plus ignored files stay out of the staged queue.

- [x] 5.10 Add the mark-follows-content test: mark a renamed file reviewed, change its content,
  rebuild, and assert the mark is dropped — the property content-addressed marks exist to provide,
  now exercised on a rename.

- [x] 5.11 Run `./gradlew test` — full suite green — then `./gradlew verifyPlugin`.

- [x] 5.12 Stage: `git add -A src/`

## 6. Manual verification guide and final checks

**Files:**
- Modify: `docs/manual-verification.md`

- [x] 6.1 Add a section for the progress banner: it appears above the diff on Start Review; it reads
  `0 / N files reviewed` on a fresh scope; **Toggle Reviewed moves the number without the tab being
  replaced**; marking the last file completes the pass with the banner reading `N / N`.

- [x] 6.2 Add a section for focus mode: open the Project panel, the Terminal, Git and one
  third-party tool window, start a pass, and confirm all four are gone; end the pass and confirm all
  four come back; repeat with one of them already closed and confirm it is **not** opened at the end.

- [x] 6.3 Add a section for the context menu: right-click inside the review diff and confirm Mark
  Reviewed is first, that all seven per-file actions and the five session controls are present, that
  **Compare with Clipboard is absent**, and that Annotate with Git Blame is still there. Then open a
  diff from the Git log **during a pass** and confirm its menu is the platform's, unchanged, with
  Compare with Clipboard still present.

- [x] 6.4 Add a section for renames: stage a rename in each of the three scopes and confirm one queue
  entry appears, keyed to the new path, in each.

- [x] 6.5 Confirm the renumbering constraint from 1.5 held: `README.md`'s references to
  "section 20" and "section 24" still point at the sections they name.

- [x] 6.6 Update `README.md`'s **Verification status** section: the new test count from
  `./gradlew test`, and the `verifyPlugin` result. State plainly that the banner, the focus sweep and
  the context menu are **unverified by a human** until Gate B runs — the existing section already
  makes that distinction and must keep making it.

- [x] 6.7 Run the full gate one last time: `./gradlew test && ./gradlew verifyPlugin`. Record the
  actual numbers; do not claim a count you did not read from the output.

- [x] 6.8 Stage everything: `git add -A`

## 7. Fix round 1 — attach the context menu so it actually wins

Root cause and the disassembly evidence are in `proposal.md`, section **Fix round 1**. In short:
`EditorEx.setContextMenuGroupId` only feeds the default handler at index 0 of
`EditorImpl.myPopupHandlers`, while `TwosideTextDiffViewer.installEditorListeners()` *appends* a
`ContextMenuPopupHandler.Simple(Diff.EditorPopupMenu)` afterwards, and
`EditorImpl.getPopupActionGroup` scans that list from the end. The platform always won.

- [x] 7.1 **RED.** In `src/test/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffExtensionTest.kt`, add a
  test that fails against the current implementation and asserts the **ordering**, not merely that a
  handler exists — a presence-only assertion passes against the broken code too.

  **Do not try to build a live `DiffRequestProcessor` or drive a real viewer's `init()`.** The same
  file's `FakeChainContext` KDoc already records why that aborts under this headless run
  (`no ComponentUI class for DiffHeaderToolbarPanel`), and the fix does not change that. Instead
  reproduce the platform's sequence directly: create a real `EditorEx` via `EditorFactory`, install
  a `ContextMenuPopupHandler.Simple(Diff.EditorPopupMenu)` on it exactly as
  `TextDiffViewerUtil.EditorActionsPopup.install` does, then run this plugin's attachment step, and
  assert `editor.popupHandlers.last()` is the plugin's and resolves to `ReviewQueue.DiffPopup`.
  `EditorImpl.getPopupActionGroup` scans that list from the end, so "last" is the property that
  decides the bug.

  Cover the wiring separately, since the above does not prove the listener is ever registered: with
  a stub `DiffViewerBase` subclass, assert that `onViewerCreated` **registers a listener** and
  installs nothing yet, and that the handler appears only once that listener's `onInit()` runs. That
  split is what distinguishes "installs at the right time" from "installs at all". If a stub
  `DiffViewerBase` proves impractical headless, say so in the ledger and state what you asserted
  instead — do not quietly drop the wiring half.

- [x] 7.2 **RED.** Add the negative case in the same file: a context **without** the marker leaves
  `getPopupHandlers()` exactly as the platform left it, so a Git-log diff opened mid-pass keeps
  Compare with Clipboard. This is the safety property the guard exists for, and it must keep failing
  loudly if the guard is ever dropped.

- [x] 7.3 **GREEN.** Change `ReviewDiffExtension.onViewerCreated` to register a
  `DiffViewerListener` on the viewer (via `DiffViewerBase.addListener`) and, from that listener's
  `onInit()`, call `installPopupHandler(ContextMenuPopupHandler.Simple(group))` on each editor from
  `editorsOf(viewer)`, where `group` is `ActionManager.getAction("ReviewQueue.DiffPopup")` as an
  `ActionGroup`. Keep `shouldDecorate(context)` as the first thing that runs. Log a warning and skip
  if the group id does not resolve to an `ActionGroup` — same reasoning as `platformTail`'s
  fallback, which is logged rather than silent.

- [x] 7.4 Handle the non-`DiffViewerBase` case explicitly: `editorsOf` already returns `emptyList()`
  for binary viewers, but `addListener` exists only on `DiffViewerBase`. Decide and document what
  happens for a text viewer that is not a `DiffViewerBase` — do not leave it to an unchecked cast.

- [x] 7.5 Rewrite the KDoc on `ReviewDiffExtension`. The current class comment explains at length why
  the guard reads `DiffContext` rather than `DiffRequest` — that part is correct and stays — but the
  file must no longer imply `setContextMenuGroupId` is the attachment mechanism. State the handler
  ordering rule and the `init()`/`fireEvent` sequence that makes the listener the correct hook.

- [x] 7.6 Update `ReviewDiffPopupGroup`'s KDoc only if the fix changes how it is invoked. It should
  not: the group is still resolved by id and still composes the platform tail live.

- [x] 7.7 Refresh `docs/manual-test/kan-6-plugin-updates.md` — re-open the context-menu section
  (6.3's checks), leave every other ticked box alone, and add one step that opens a **Git log** diff
  during a pass to confirm the stock menu is untouched there.

- [x] 7.8 Run the gate: `./gradlew test && ./gradlew verifyPlugin`. Record the real numbers.

- [x] 7.9 Stage everything: `git add -A`

## 8. Fix round 2 — instrument the focus sweep, correct the guide

See `proposal.md` section **Fix round 2**. Report A (Branch vs Base showing one file) is **not a
defect** — it is git's correct answer for a branch with one commit and 31 staged-uncommitted files,
and it needs a guide change only. Report B (focus mode not hiding) has **no established root cause**;
this section adds the evidence-gathering that the code currently makes impossible, and deliberately
does **not** guess at a fix.

- [x] 8.1 **RED.** In `src/test/kotlin/dev/tweety/reviewqueue/ui/IdeLayoutControllerTest.kt`, add a
  test that `hideForReview()` warns when it enumerates windows but finds none of them visible — the
  reported symptom. Use the existing `RecordingToolWindowManager` seam, registering windows with
  `visible = false`. Assert on the log, not on a return value: this method returns `Unit` and must
  keep doing so. Use `TestLoggerFactory`/`LoggedErrorProcessor` or the project's existing convention
  if one exists — check first; do not invent a logging-assertion helper if the codebase already has
  one.

- [x] 8.2 **GREEN.** Add logging to `IdeLayoutController.hideForReview()`:
  - at `info` (or `debug` if the project's convention prefers it — check the surrounding code), the
    ids enumerated, the subset judged visible, and what was hidden;
  - at `warn`, the case where `toolWindowIds` was **non-empty but nothing was visible**, naming the
    ids seen. This is the reported symptom and it is currently indistinguishable from "no tool
    windows exist";
  - at `warn`, the case where `toolWindowIds` itself is **empty**, which would mean the manager is
    not the one the IDE is using.
  Match the existing message style — `ReviewDiffPopupGroup` and `ReviewDiffExtension` both prefix
  `"Review Queue: "` and name the identifier needed to investigate.

- [x] 8.3 Do **not** change the sweep's logic in this round. `manager.toolWindowIds.filter {
  manager.getToolWindow(it)?.isVisible == true }` is correct against every test and against the
  platform API as read; changing it without evidence would be a guess, and this is the one component
  that mutates the user's IDE layout. If 8.1's test surfaces an actual logic defect, stop and report
  rather than widening this task.

- [x] 8.4 Record the limit of the current tests in `IdeLayoutControllerTest`'s KDoc: every case runs
  against `RecordingToolWindowManager`, which overrides `toolWindowIds` and `getToolWindow`, so the
  suite proves the sweep correct *given* a cooperating manager and cannot observe the real
  `ToolWindowManager` disagreeing. State it plainly — it is the same stub-versus-platform gap that
  produced fix round 1.

- [x] 8.5 Refresh `docs/manual-test/kan-6-plugin-updates.md`:
  - **Section 3 / scopes:** say that on *this* branch, Branch vs Base and Commit Range can only ever
    show the single committed design doc, because `/myflow-do` stages without committing. Point the
    reviewer at a branch with real commits (e.g. `openspec/kan-5`) to exercise them, and state that
    at the `IN_PROGRESS` gate **Staged is the only scope that can see the change under review**.
  - **Section 2 / focus mode:** add an explicit check in **each** of the three scopes, and ask for
    the `idea.log` lines from 8.2 when it fails. Focus mode has never been ticked in any scope, so
    do not present this as a two-scope problem.
  - Leave every already-ticked box alone (there are none, but the rule holds).

- [x] 8.6 Run the gate: `./gradlew cleanTest test verifyPlugin`. Record the real numbers, read from
  the JUnit XML — not `UP-TO-DATE`, which is not evidence.

- [x] 8.7 Stage everything: `git add -A`
