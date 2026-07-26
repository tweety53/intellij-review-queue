# Diff toolbar controls, file list and hotkey — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Mark Reviewed shortcut actually fire, put the four session/queue controls on the diff toolbar right-aligned and confirming, and add a navigable file list reachable from inside the diff.

**Architecture:** Nothing new is invented. The diff toolbar is already fed by `ReviewSessionService.diffActions`, a `List<AnAction>` handed to the diff chain as `DiffUserDataKeys.CONTEXT_ACTIONS`; this plan lengthens that list. Confirmation and right alignment come from four thin subclasses of the existing tool-window actions — subclassing inherits each `update()` unchanged. The file list adds one pure cursor move (`ReviewSession.jumpTo`) and one pure row model (`ReviewFileList`), so all new logic is unit-testable without an IDE; only the Swing chooser needs a live IDE, and that goes to the manual checklist like every other UI surface in this repo.

**Tech Stack:** Kotlin 2.4.10 / JVM 21, IntelliJ Platform 2026.2 (`intellijIdeaUltimate`), IntelliJ Platform Gradle Plugin 2.18.1, JUnit 4.13.2, `HeavyPlatformTestCase` for anything needing a project.

**Spec:** `docs/superpowers/specs/2026-07-26-diff-toolbar-and-hotkey-design.md`

## Global Constraints

- Platform `2026.2`, `sinceBuild = "262"`, `untilBuild = null`. Do not change these.
- Kotlin JVM toolchain 21. `kotlin.stdlib.default.dependency = false` — the platform supplies the stdlib; add no Kotlin stdlib dependency.
- Tests are JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`). Tests needing a `Project` extend `com.intellij.testFramework.HeavyPlatformTestCase` and use `testXxx` method names (JUnit 3 style — that is what the base class requires). Pure tests use `@Test` with backtick names.
- **This plugin must never mutate a repository.** Publish no `VcsDataKeys.CHANGES` / `VcsDataKeys.SELECTED_CHANGES`, install no VCS context menus, add no `UiDataProvider` that could feed Rollback.
- Every new action's `getActionUpdateThread()` returns `ActionUpdateThread.EDT`, matching the existing seven.
- Actions that act on "the file on screen" stay gated on `e.getData(DiffDataKeys.DIFF_CONTEXT) != null`.
- Full test command: `./gradlew test`. Single class: `./gradlew test --tests "dev.tweety.reviewqueue.core.ReviewSessionTest"`. Compile only: `./gradlew compileKotlin compileTestKotlin`.
- Commit after each task. Branch is `diff-toolbar-and-hotkey`.

## File Structure

**Create**

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/dev/tweety/reviewqueue/actions/Confirm.kt` | The one yes/no dialog shape used by every confirming action |
| `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffStartReviewAction.kt` | Start Review, right-aligned + confirming |
| `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffEndReviewAction.kt` | End Review, right-aligned + confirming |
| `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffRefreshQueueAction.kt` | Refresh, right-aligned + confirming |
| `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffResetAllAction.kt` | Reset All, right-aligned (parent already confirms) |
| `src/main/kotlin/dev/tweety/reviewqueue/actions/ShowFileListAction.kt` | Toolbar entry point for the file list |
| `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewFileList.kt` | Pure row model for the file list |
| `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewFileListPopup.kt` | Builds the chooser, routes the pick |
| `src/test/kotlin/dev/tweety/reviewqueue/MarkReviewedShortcutTest.kt` | Guards the shortcut declarations in `plugin.xml` |
| `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewFileListTest.kt` | Row model |

**Modify**

| File | Change |
| --- | --- |
| `src/main/resources/META-INF/plugin.xml` | New shortcuts on `ReviewQueue.MarkReviewed`; register `ReviewQueue.ShowFileList` |
| `actions/MarkReviewedAction.kt` | Rewrite the `update()` comment; class stays as-is otherwise |
| `actions/StartReviewAction.kt`, `EndReviewAction.kt`, `RefreshQueueAction.kt`, `ResetAllAction.kt` | `open class`; `ResetAllAction` routes through `confirmed()` |
| `queue/ReviewSessionService.kt` | `diffActions` becomes `internal` and gains the file list + four right-aligned controls; new `jumpTo` |
| `core/ReviewSession.kt` | New `jumpTo` |
| `src/test/.../core/ReviewSessionTest.kt` | `jumpTo` cases |
| `src/test/.../queue/ReviewSessionServiceTest.kt` | `jumpTo` cases + diff toolbar composition |
| `README.md`, `docs/manual-verification.md` | Documentation |

---

### Task 1: Fix the Mark Reviewed shortcut

`ctrl shift SPACE` is `SmartTypeCompletion` in both `keymaps/$default.xml` and
`keymaps/Mac OS X 10.5+.xml`, so the binding never won; and on macOS the chord users reach for is
Cmd+Shift+Space, which was never declared. `meta shift SPACE` and `control alt shift SPACE` were
both verified free in both keymaps.

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml:53-58`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/actions/MarkReviewedAction.kt:20-25`
- Modify: `docs/manual-verification.md` (item 20e)
- Test: `src/test/kotlin/dev/tweety/reviewqueue/MarkReviewedShortcutTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks.

**Why a test at all:** a wrong keymap *name* is accepted silently by the platform and the shortcut
simply never applies — the exact failure mode being fixed. A plain unit test cannot check the name
against the IDE's keymap registry, but it can pin the declarations so this cannot silently regress.
Correct keymap-name resolution is covered by the manual check.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/tweety/reviewqueue/MarkReviewedShortcutTest.kt`:

```kotlin
package dev.tweety.reviewqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Mark Reviewed key bindings.
 *
 * A `keymap` name the platform does not recognise is ignored without a warning, so a typo here
 * produces exactly the bug this guards: a shortcut that silently never fires. Reads the shipped
 * resource rather than the platform's keymap registry, so it needs no IDE.
 */
class MarkReviewedShortcutTest {

    private val pluginXml: String =
        checkNotNull(javaClass.getResource("/META-INF/plugin.xml")) { "plugin.xml not on the test classpath" }
            .readText()

    private val markReviewedBlock: String =
        Regex("""<action id="ReviewQueue\.MarkReviewed".*?</action>""", RegexOption.DOT_MATCHES_ALL)
            .find(pluginXml)
            ?.value
            ?: error("no ReviewQueue.MarkReviewed action block in plugin.xml")

    @Test
    fun `no binding uses the chord that collides with smart type completion`() {
        assertFalse(
            "ctrl shift SPACE is SmartTypeCompletion in both \$default and the macOS keymap",
            pluginXml.contains("\"ctrl shift SPACE\""),
        )
    }

    @Test
    fun `the default keymap binds a chord that is free everywhere`() {
        assertTrue(
            markReviewedBlock,
            markReviewedBlock.contains(
                """<keyboard-shortcut keymap="${'$'}default" first-keystroke="ctrl alt shift SPACE"/>"""
            ),
        )
    }

    @Test
    fun `macOS binds cmd shift space and drops the inherited chord`() {
        val mac = Regex("""<keyboard-shortcut keymap="Mac OS X 10\.5\+"[^/]*/>""")
            .find(markReviewedBlock)
            ?.value
            ?: error("no macOS keyboard-shortcut in:\n$markReviewedBlock")
        assertTrue(mac, mac.contains("""first-keystroke="meta shift SPACE""""))
        assertTrue("replace-all is what stops \$default's chord being inherited on macOS",
            mac.contains("""replace-all="true""""))
    }

    @Test
    fun `mark reviewed declares exactly the two bindings`() {
        assertEquals(2, Regex("<keyboard-shortcut").findAll(markReviewedBlock).count())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.MarkReviewedShortcutTest"
```

Expected: FAIL, all four tests:

| Test | Failure |
| --- | --- |
| `no binding uses the chord that collides…` | `ctrl shift SPACE` is still in the file |
| `the default keymap binds a chord that is free everywhere` | the `ctrl alt shift SPACE` line is absent |
| `macOS binds cmd shift space…` | `error("no macOS keyboard-shortcut in: …")` |
| `mark reviewed declares exactly the two bindings` | `expected:<2> but was:<1>` |

- [ ] **Step 3: Rewrite the shortcut declarations**

In `src/main/resources/META-INF/plugin.xml`, replace the single `<keyboard-shortcut>` line inside
`ReviewQueue.MarkReviewed` so the action reads:

```xml
        <action id="ReviewQueue.MarkReviewed"
                class="dev.tweety.reviewqueue.actions.MarkReviewedAction"
                text="Mark Reviewed"
                description="Mark this file reviewed and open the next one">
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt shift SPACE"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta shift SPACE" replace-all="true"/>
        </action>
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.MarkReviewedShortcutTest"
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Rewrite the stale justification on the DIFF_CONTEXT gate**

The gate stays — it keeps the chord inert in a normal editor mid-session, where "the file on screen"
has no meaning. But its KDoc currently justifies it by a collision that no longer exists, which
invites a later reader to delete it. In `actions/MarkReviewedAction.kt`, replace the KDoc above
`update()` with:

```kotlin
    /**
     * Scoped to the diff viewer, not just to an active session. The shortcut is registered
     * IDE-wide, so mid-session it is live in every editor — and marking "the file on screen" means
     * nothing in a normal editor. Without this gate the chord there would silently mark whichever
     * file the review happens to be sitting on.
     *
     * This is not about a keymap collision: the bindings are chords that are free in both
     * `$default` and the macOS keymap (see MarkReviewedShortcutTest). The gate earns its place
     * independently of that.
     */
```

- [ ] **Step 6: Update the manual checklist**

In `docs/manual-verification.md`, replace item `20e` with:

```markdown
- [ ] 20e. The Mark Reviewed shortcut marks and advances in the review diff — Cmd+Shift+Space on
      macOS, Ctrl+Alt+Shift+Space on Windows/Linux — and **while the session is still running**
      the chord in a normal editor does nothing at all (no mark, no ambiguity popup), with Smart
      Type Completion on Ctrl+Shift+Space still working there. Confirm in Settings → Keymap that
      Mark Reviewed shows the expected chord and no conflict warning: a keymap name the platform
      does not recognise is ignored silently, and this is the only check that catches it.
```

- [ ] **Step 7: Verify the whole suite and the plugin descriptor**

```bash
./gradlew test verifyPlugin
```

Expected: BUILD SUCCESSFUL. `verifyPlugin` parses the descriptor, so a malformed `<action>` block
fails here.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml \
        src/main/kotlin/dev/tweety/reviewqueue/actions/MarkReviewedAction.kt \
        src/test/kotlin/dev/tweety/reviewqueue/MarkReviewedShortcutTest.kt \
        docs/manual-verification.md
git commit -m "fix: bind Mark Reviewed to chords that are free in every keymap

Ctrl+Shift+Space is SmartTypeCompletion in both \$default and the macOS
keymap, so the binding never won; and on macOS the chord reached for is
Cmd+Shift+Space, which was never declared at all."
```

---

### Task 2: Right-aligned confirming session controls on the diff toolbar

A session hides the Review Queue tool window, so Start Review, Refresh and Reset All are unreachable
for the duration of the pass. All four session/queue controls move onto the diff toolbar, flush
right, each asking before acting.

End Review **moves** into that group rather than appearing in both places. Two End buttons — one
confirming, one not — is a trap: muscle memory built on one fires the other.

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/Confirm.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffStartReviewAction.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffEndReviewAction.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffRefreshQueueAction.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffResetAllAction.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/actions/StartReviewAction.kt:11`, `EndReviewAction.kt:11`, `RefreshQueueAction.kt:12`, `ResetAllAction.kt:12-30`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt:38-49`
- Modify: `docs/manual-verification.md`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `dev.tweety.reviewqueue.actions.confirmed(project: Project, message: String, title: String): Boolean`
  - `ReviewSessionService.diffActions: List<AnAction>` — visibility widened from `private` to `internal` so the test can assert the toolbar's composition. Task 5 appends to this same list.

**Why the toolbar composition is the test:** the confirmation dialogs and the visual right alignment
need a live IDE and go to the manual checklist, as every UI surface in this repo does. What *can* be
pinned without one is the thing most likely to break silently — that the four controls are present,
carry the right-alignment marker, that End Review appears exactly once, and that the navigation
actions still come from `ActionManager` so their tooltips carry the keyboard shortcut.

- [ ] **Step 1: Write the failing test**

Append to `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt`:

```kotlin
    fun testTheDiffToolbarSplitsNavigationFromRightAlignedSessionControls() {
        val actions = ReviewSessionService.getInstance(project).diffActions
        val manager = ActionManager.getInstance()

        val navigation = actions.filterNot { it is RightAlignedToolbarAction }
        val sessionControls = actions.filterIsInstance<RightAlignedToolbarAction>()

        assertEquals(
            "navigation actions must be resolved by id, or their tooltips lose the shortcut",
            listOf(
                manager.getAction("ReviewQueue.PreviousFile"),
                manager.getAction("ReviewQueue.MarkReviewed"),
                manager.getAction("ReviewQueue.ToggleReviewed"),
            ),
            navigation,
        )
        assertEquals(
            listOf(
                DiffStartReviewAction::class.java,
                DiffEndReviewAction::class.java,
                DiffRefreshQueueAction::class.java,
                DiffResetAllAction::class.java,
            ),
            sessionControls.map { it.javaClass },
        )
    }

    /** Two End buttons, one confirming and one not, is a trap: muscle memory fires the wrong one. */
    fun testEndReviewAppearsExactlyOnceOnTheDiffToolbar() {
        val ends = ReviewSessionService.getInstance(project).diffActions
            .filter { it is EndReviewAction }
        assertEquals("End Review must appear once, as the confirming variant", 1, ends.size)
        assertTrue(ends.single() is DiffEndReviewAction)
    }

    /** Overriding actionPerformed here would stack a second dialog on the parent's own. */
    fun testResetAllIsNotDoubleConfirmed() {
        try {
            DiffResetAllAction::class.java.getDeclaredMethod("actionPerformed", AnActionEvent::class.java)
            fail("DiffResetAllAction must not override actionPerformed; ResetAllAction already confirms")
        } catch (expected: NoSuchMethodException) {
            // The marker interface is the whole of this subclass.
        }
    }
```

Add these imports to that file:

```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.EndReviewAction
import dev.tweety.reviewqueue.actions.diff.DiffEndReviewAction
import dev.tweety.reviewqueue.actions.diff.DiffRefreshQueueAction
import dev.tweety.reviewqueue.actions.diff.DiffResetAllAction
import dev.tweety.reviewqueue.actions.diff.DiffStartReviewAction
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.queue.ReviewSessionServiceTest"
```

Expected: FAIL at compilation — `Unresolved reference: DiffStartReviewAction`, and `diffActions` is
private. That is the correct first failure; do not proceed past it by weakening the test.

- [ ] **Step 3: Add the confirmation helper**

Create `src/main/kotlin/dev/tweety/reviewqueue/actions/Confirm.kt`:

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * The one yes/no shape in the plugin, so every destructive or disruptive button asks the same way.
 *
 * Returns true when the user said yes. Closing the dialog counts as no.
 */
fun confirmed(project: Project, message: String, title: String): Boolean =
    Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon()) == Messages.YES
```

- [ ] **Step 4: Open the four existing actions for subclassing**

Four one-word edits plus one rewrite. In each file, `class X : AnAction(...)` becomes
`open class X : AnAction(...)`:

- `actions/StartReviewAction.kt:11` — `open class StartReviewAction : AnAction(`
- `actions/EndReviewAction.kt:11` — `open class EndReviewAction : AnAction(`
- `actions/RefreshQueueAction.kt:12` — `open class RefreshQueueAction : AnAction(`
- `actions/ResetAllAction.kt:12` — `open class ResetAllAction : AnAction(`

**The `actionPerformed` methods need no change.** `AnAction.actionPerformed` is abstract, so an
override of it is already open unless marked `final`. Adding `open` there would be redundant noise.
Leave `update()` alone too — inheriting it unchanged is the point.

Then rewrite `ResetAllAction.actionPerformed` to route through the new helper, so there is one
confirmation shape rather than two:

```kotlin
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Clear every reviewed mark in this project?", "Reset Review Progress")) {
            ReviewQueueService.getInstance(project).resetAll()
        }
    }
```

Drop the now-unused `import com.intellij.openapi.ui.Messages` from that file.

- [ ] **Step 5: Add the four diff variants**

Create `src/main/kotlin/dev/tweety/reviewqueue/actions/diff/DiffStartReviewAction.kt`:

```kotlin
package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.StartReviewAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * Start Review on the diff toolbar, right-aligned and confirming.
 *
 * Subclassed rather than wrapped so `update()` is inherited untouched: StartReviewAction already
 * disables itself while a session is active, which is exactly the presentation wanted here.
 *
 * The confirmation is therefore unreachable today — ending a pass closes the diff tab, so the
 * button is only ever visible while disabled. It is written anyway so the group is uniform, and so
 * this does not quietly become a live unconfirmed press if that ever changes.
 */
class DiffStartReviewAction : StartReviewAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Start a new review pass over everything unreviewed?", "Start Review")) {
            super.actionPerformed(e)
        }
    }
}
```

Create `DiffEndReviewAction.kt`:

```kotlin
package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.EndReviewAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * End Review on the diff toolbar, right-aligned and confirming.
 *
 * The only End Review on this toolbar. An unconfirmed twin alongside it would be a trap: the
 * buttons sit directly above the code being read, and muscle memory built on one would fire the
 * other.
 */
class DiffEndReviewAction : EndReviewAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Leave the guided review? Every mark made so far is kept.", "End Review")) {
            super.actionPerformed(e)
        }
    }
}
```

Create `DiffRefreshQueueAction.kt`:

```kotlin
package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.RefreshQueueAction
import dev.tweety.reviewqueue.actions.confirmed

/**
 * Refresh on the diff toolbar, right-aligned and confirming.
 *
 * Confirmed here but not in the tool window, and deliberately so: a refresh mid-pass can move the
 * cursor underneath the reader, whereas in the tool window it is a cheap re-read of a list.
 */
class DiffRefreshQueueAction : RefreshQueueAction(), RightAlignedToolbarAction {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (confirmed(project, "Re-read the review scope now?", "Refresh")) {
            super.actionPerformed(e)
        }
    }
}
```

Create `DiffResetAllAction.kt`:

```kotlin
package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.ResetAllAction

/**
 * Reset All on the diff toolbar, right-aligned.
 *
 * The marker interface is the whole of this class. ResetAllAction already confirms, so overriding
 * `actionPerformed` to add a confirmation would stack two dialogs.
 */
class DiffResetAllAction : ResetAllAction(), RightAlignedToolbarAction
```

- [ ] **Step 6: Rebuild the diff toolbar list**

In `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt`, replace the `diffActions`
property with:

```kotlin
    /**
     * The diff viewer's toolbar, in two groups: per-file navigation on the left, session and queue
     * controls right-aligned via `RightAlignedToolbarAction`.
     *
     * The navigation actions are resolved by id, because that is what makes the button tooltip
     * carry the keyboard shortcut, and because Start Review is reachable from Find Action without
     * the tool window ever being constructed — a guided diff with no toolbar buttons strands the
     * user with only a tab close as the way out.
     *
     * The four session controls are constructed directly instead. Registering the confirming
     * variants in plugin.xml would list them in Find Action beside the originals: eight entries for
     * four commands, half of them confirming and half not.
     */
    internal val diffActions: List<AnAction> by lazy {
        val manager = ActionManager.getInstance()
        listOfNotNull(
            manager.getAction("ReviewQueue.PreviousFile"),
            manager.getAction("ReviewQueue.MarkReviewed"),
            manager.getAction("ReviewQueue.ToggleReviewed"),
        ) + listOf(
            DiffStartReviewAction(),
            DiffEndReviewAction(),
            DiffRefreshQueueAction(),
            DiffResetAllAction(),
        )
    }
```

Note `ReviewQueue.EndReview` is gone from the id list — `DiffEndReviewAction` replaces it. Add
imports:

```kotlin
import dev.tweety.reviewqueue.actions.diff.DiffEndReviewAction
import dev.tweety.reviewqueue.actions.diff.DiffRefreshQueueAction
import dev.tweety.reviewqueue.actions.diff.DiffResetAllAction
import dev.tweety.reviewqueue.actions.diff.DiffStartReviewAction
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.queue.ReviewSessionServiceTest"
```

Expected: PASS, including the three new methods. If `RightAlignedToolbarAction` will not resolve,
its package is `com.intellij.openapi.actionSystem` in `lib/intellij.platform.editor.ui.jar` — the
`bundledModule("intellij.platform.diff.impl")` dependency already pulls the editor UI module in;
no build file change should be needed.

- [ ] **Step 8: Verify right alignment in a live IDE**

This is the one unverified assumption in the design: whether the *diff viewer's* toolbar honours the
marker. Do this before moving on — the fallback changes the code.

```bash
./gradlew runIde
```

In the sandbox IDE: open a project with staged changes, Review Queue tool window → Start Review.
Expect the four session controls flush right on the diff toolbar, separated from the three
navigation buttons. Press End Review and confirm the dialog appears.

If they are **not** right-aligned, add `com.intellij.openapi.actionSystem.Separator.getInstance()`
between the two groups in `diffActions` and note in the spec's *Known risk* section that the
platform does not honour the marker there. Keep the marker interfaces — they are harmless and
document intent.

- [ ] **Step 9: Update the manual checklist**

Append to `docs/manual-verification.md`, after item `20m`:

```markdown
- [ ] 21a. The four session controls (Start Review, End Review, Refresh, Reset All) sit flush right
      on the review diff toolbar, visually separated from the navigation buttons
- [ ] 21b. Each of the four asks before acting, and answering No leaves the pass exactly as it was
- [ ] 21c. Reset All from the diff toolbar shows **one** dialog, not two
- [ ] 21d. Start Review is visibly disabled on the diff toolbar during a pass
- [ ] 21e. Refresh in the *tool window* still acts on one click with no dialog
- [ ] 21f. Find Action lists each of Start Review / End Review / Refresh / Reset All exactly once
```

- [ ] **Step 10: Run the full suite and commit**

```bash
./gradlew test
git add src/main/kotlin/dev/tweety/reviewqueue/actions \
        src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt \
        src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt \
        docs/manual-verification.md
git commit -m "feat: right-aligned confirming session controls on the diff toolbar

A session hides the Review Queue tool window, so Start Review, Refresh and
Reset All were unreachable for the length of the pass. End Review moves into
the new group rather than appearing twice: one confirming and one not is a
trap when the buttons sit above the code being read."
```

---

### Task 3: Jump the cursor to a named file

The cursor move the file list needs. Pure, and it keeps the pass's fixed-list invariant: `jumpTo`
moves within `keys` and refuses anything outside it, so a pick can never grow the pass.

**Files:**
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewSession.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewSessionTest.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt`

**Interfaces:**
- Consumes: `ReviewSessionService.diffActions` exists as `internal` (Task 2). Not otherwise coupled.
- Produces, both used by Task 5:
  - `ReviewSession.jumpTo(key: ReviewKey): ReviewSession?` — null when `key` is not in `keys`
  - `ReviewSessionService.jumpTo(key: ReviewKey): Boolean` — true only when a file is now on screen; false when there is no session, the key is not in the pass, or the jump ended the pass, so the caller can fall back to a browsing diff

- [ ] **Step 1: Write the failing pure tests**

Append to `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewSessionTest.kt`:

```kotlin
    @Test
    fun `jumpTo moves the cursor to a file in the pass`() {
        val session = ReviewSession.start(keys)!!
        val jumped = session.jumpTo(key("c.kt"))!!
        assertEquals(key("c.kt"), jumped.current)
        assertEquals(3, jumped.position)
        assertEquals("the pass itself must not change", keys, jumped.keys)
    }

    @Test
    fun `jumpTo can move backwards`() {
        val third = ReviewSession(keys, 2)
        assertEquals(key("a.kt"), third.jumpTo(key("a.kt"))!!.current)
    }

    /** The pass is fixed when it starts. A jump moves the cursor; it never grows the list. */
    @Test
    fun `jumpTo a file outside the pass yields null`() {
        val session = ReviewSession.start(keys)!!
        assertNull(session.jumpTo(key("elsewhere.kt")))
    }

    @Test
    fun `jumpTo the current file leaves the session where it is`() {
        val second = ReviewSession(keys, 1)
        assertEquals(second, second.jumpTo(key("b.kt")))
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.core.ReviewSessionTest"
```

Expected: FAIL at compilation — `Unresolved reference: jumpTo`.

- [ ] **Step 3: Add `ReviewSession.jumpTo`**

In `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewSession.kt`, after `back()`:

```kotlin
    /**
     * Moves the cursor to [key], or returns null when it is not part of this pass.
     *
     * Refusing rather than appending is what keeps the fixed-list invariant: the file list can
     * offer every file in scope, and a pick outside the pass falls back to a browsing diff instead
     * of silently reshuffling what the reviewer is walking through.
     */
    fun jumpTo(key: ReviewKey): ReviewSession? {
        val target = keys.indexOf(key)
        return if (target < 0) null else copy(index = target)
    }
```

- [ ] **Step 4: Run them to verify they pass**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.core.ReviewSessionTest"
```

Expected: PASS, 13 tests.

- [ ] **Step 5: Write the failing service test**

Append to `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt`:

```kotlin
    fun testJumpToShowsAnotherFileInTheSamePass() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()
        assertEquals(keys[0], service.currentKey())

        assertTrue("a key in the pass must be accepted", service.jumpTo(keys[1]))

        assertEquals(keys[1], service.currentKey())
        assertEquals(listOf(keys[0], keys[1]), presenter.shown)
    }

    fun testJumpToAFileOutsideThePassIsRefusedAndChangesNothing() {
        val queue = stagedQueueOfTwoFiles()
        val keys = queue.snapshot().items.map { it.key }
        val service = ReviewSessionService.getInstance(project)
        val presenter = FakePresenter()
        service.presenter = presenter

        service.start()

        assertFalse(
            "refusing is what lets the caller fall back to a browsing diff",
            service.jumpTo(ReviewKey("/nowhere", "absent.txt")),
        )
        assertEquals(keys[0], service.currentKey())
        assertEquals(listOf(keys[0]), presenter.shown)
    }

    fun testJumpToWithNoSessionIsRefused() {
        val service = ReviewSessionService.getInstance(project)
        service.presenter = FakePresenter()
        assertFalse(service.jumpTo(ReviewKey("/repo", "a.txt")))
    }
```

- [ ] **Step 6: Run it to verify it fails**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.queue.ReviewSessionServiceTest"
```

Expected: FAIL at compilation — `Unresolved reference: jumpTo` on the service.

- [ ] **Step 7: Add `ReviewSessionService.jumpTo`**

In `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt`, after `previous()`:

```kotlin
    /**
     * Moves the pass to [key] and shows it. Returns true only when a file is now on screen, so a
     * false lets the caller open [key] as a browsing diff instead.
     *
     * False covers three cases: no session, [key] is not part of this pass, and the jump ended the
     * pass because nothing at or after [key] could still be shown.
     *
     * Goes through [showCurrent] like every other move, so a jump to a file that has since left the
     * queue settles forward onto the next live one rather than failing — the same behaviour marking
     * already has.
     */
    fun jumpTo(key: ReviewKey): Boolean {
        val moved = session?.jumpTo(key) ?: return false
        session = moved
        showCurrent()
        // showCurrent() ends the pass when nothing at or after the target is still showable.
        return session != null
    }
```

> **Amended after Task 3's review.** This step originally returned `true` unconditionally.
> `showCurrent()` calls `end()` when its skip loop exhausts, so that `true` could mean "the pass
> just ended and nothing is on screen" — the exact case Task 5's caller uses the boolean to catch.
> Ruled by the human partner: the contract is what changes, not `showCurrent()`.

- [ ] **Step 8: Run the full suite to verify it passes**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/core/ReviewSession.kt \
        src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt \
        src/test/kotlin/dev/tweety/reviewqueue/core/ReviewSessionTest.kt \
        src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt
git commit -m "feat: move the review cursor to a named file

jumpTo refuses a key outside the pass rather than appending it, so the
fixed-list invariant survives a file list that offers every file in scope."
```

---

### Task 4: The file list row model

Pure model behind the popup, so labels, ordering, reviewed state and the root prefix are testable
without an IDE.

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewFileList.kt`
- Test: `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewFileListTest.kt`

**Interfaces:**
- Consumes: `ReviewItem(key: ReviewKey, contentHash: String)`, `ReviewKey(rootPath: String, relPath: String)`.
- Produces, used by Task 5:
  - `data class ReviewFileRow(val key: ReviewKey, val label: String, val isReviewed: Boolean, val isCurrent: Boolean)`
  - `ReviewFileList.rows(items: List<ReviewItem>, reviewed: (ReviewItem) -> Boolean, current: ReviewKey?): List<ReviewFileRow>`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewFileListTest.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFileListTest {

    private fun item(root: String, path: String) = ReviewItem(ReviewKey(root, path), "hash-$root-$path")

    private val single = listOf(item("/repo", "a.kt"), item("/repo", "src/b.kt"))

    @Test
    fun `rows keep the order they arrive in`() {
        val rows = ReviewFileList.rows(single, reviewed = { false }, current = null)
        assertEquals(listOf("a.kt", "src/b.kt"), rows.map { it.label })
        assertEquals(single.map { it.key }, rows.map { it.key })
    }

    @Test
    fun `a single root needs no prefix`() {
        val rows = ReviewFileList.rows(single, reviewed = { false }, current = null)
        assertEquals("src/b.kt", rows[1].label)
    }

    @Test
    fun `multiple roots prefix each label with the root name`() {
        val items = listOf(item("/work/app", "a.kt"), item("/work/lib", "b.kt"))
        val rows = ReviewFileList.rows(items, reviewed = { false }, current = null)
        assertEquals(listOf("app/a.kt", "lib/b.kt"), rows.map { it.label })
    }

    @Test
    fun `reviewed state comes from the predicate`() {
        val rows = ReviewFileList.rows(single, reviewed = { it.key.relPath == "a.kt" }, current = null)
        assertTrue(rows[0].isReviewed)
        assertFalse(rows[1].isReviewed)
    }

    @Test
    fun `exactly one row is marked current`() {
        val rows = ReviewFileList.rows(single, reviewed = { false }, current = ReviewKey("/repo", "src/b.kt"))
        assertEquals(listOf(false, true), rows.map { it.isCurrent })
    }

    @Test
    fun `no row is current outside a session`() {
        val rows = ReviewFileList.rows(single, reviewed = { false }, current = null)
        assertTrue(rows.none { it.isCurrent })
    }

    @Test
    fun `an empty queue yields no rows`() {
        assertEquals(emptyList<ReviewFileRow>(), ReviewFileList.rows(emptyList(), { false }, null))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.core.ReviewFileListTest"
```

Expected: FAIL at compilation — `Unresolved reference: ReviewFileList`.

- [ ] **Step 3: Write the model**

Create `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewFileList.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey

/** One row of the file list: what to draw, and the key to act on when it is picked. */
data class ReviewFileRow(
    val key: ReviewKey,
    val label: String,
    val isReviewed: Boolean,
    val isCurrent: Boolean,
)

/**
 * Turns a queue snapshot into rows for the file-list popup.
 *
 * Order is the caller's: the queue is already sorted by git root then path by [ReviewOrdering], and
 * that order is what the review cursor indexes into, so re-sorting here would make the popup
 * disagree with the pass.
 *
 * The list is flat rather than a tree. A tree inside a chooser popup costs keyboard-navigable rows,
 * and buys nothing in the single-root case — which is the normal one. The root name appears as a
 * label prefix only when there is more than one root to tell apart.
 */
object ReviewFileList {

    fun rows(
        items: List<ReviewItem>,
        reviewed: (ReviewItem) -> Boolean,
        current: ReviewKey?,
    ): List<ReviewFileRow> {
        val multiRoot = items.mapTo(mutableSetOf()) { it.key.rootPath }.size > 1
        return items.map { item ->
            val prefix = if (multiRoot) item.key.rootPath.substringAfterLast('/') + "/" else ""
            ReviewFileRow(
                key = item.key,
                label = prefix + item.key.relPath,
                isReviewed = reviewed(item),
                isCurrent = item.key == current,
            )
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.core.ReviewFileListTest"
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/core/ReviewFileList.kt \
        src/test/kotlin/dev/tweety/reviewqueue/core/ReviewFileListTest.kt
git commit -m "feat: row model for the review file list"
```

---

### Task 5: The file list popup

The visible feature: a list of every file in scope, with its reviewed state, opened from the diff
toolbar, that navigates on pick.

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewFileListPopup.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/ShowFileListAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt` (`diffActions`)
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt`
- Modify: `docs/manual-verification.md`

**Interfaces:**
- Consumes: `ReviewFileList.rows(...)` and `ReviewFileRow` (Task 4); `ReviewSessionService.jumpTo(key): Boolean` (Task 3); `ReviewSessionService.diffActions` as `internal` (Task 2); existing `ReviewDiffOpener.open(project, key)`; existing `ReviewQueueService.snapshot()`, `.isReviewed(item)`, `.changeFor(key)`.
- Produces: `ReviewQueue.ShowFileList` action id; `ReviewFileListPopup.show(project, dataContext)`.

- [ ] **Step 1: Write the failing test**

The Swing chooser needs a live IDE and goes to the manual checklist. What is worth pinning is that
the button is actually on the toolbar and in the navigation group — the failure mode where the
feature ships invisible. Update the composition test written in Task 2, in
`src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt`: replace the `navigation`
assertion's expected list with

```kotlin
            listOf(
                manager.getAction("ReviewQueue.ShowFileList"),
                manager.getAction("ReviewQueue.PreviousFile"),
                manager.getAction("ReviewQueue.MarkReviewed"),
                manager.getAction("ReviewQueue.ToggleReviewed"),
            ),
```

and add a test that the id resolves at all — `getAction` returns null for an unregistered id, and
`listOfNotNull` would then drop the button silently:

```kotlin
    fun testTheFileListActionIsRegistered() {
        assertNotNull(
            "an unregistered id resolves to null and listOfNotNull drops it silently",
            ActionManager.getInstance().getAction("ReviewQueue.ShowFileList"),
        )
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew test --tests "dev.tweety.reviewqueue.queue.ReviewSessionServiceTest"
```

Expected: FAIL — `testTheFileListActionIsRegistered` fails on the null assertion, and the
composition test fails because the toolbar has three navigation actions where four are expected.

- [ ] **Step 3: Write the popup**

Create `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewFileListPopup.kt`:

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.EmptyIcon
import dev.tweety.reviewqueue.core.ReviewFileList
import dev.tweety.reviewqueue.core.ReviewFileRow
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService

/**
 * The whole change, from inside the diff: every file in the current scope with its reviewed state.
 *
 * Lists the full scope rather than just the pass, because "which files are already done" is most of
 * the question being asked. Picking a file in the pass moves the cursor; picking one outside it —
 * anything already reviewed when the pass started — opens a browsing diff and leaves the pass alone,
 * which is exactly what activating a row in the tool-window tree does.
 */
object ReviewFileListPopup {

    fun show(project: Project, dataContext: DataContext) {
        val queue = ReviewQueueService.getInstance(project)
        val session = ReviewSessionService.getInstance(project)
        val snapshot = queue.snapshot()

        val rows = ReviewFileList.rows(
            items = snapshot.items,
            reviewed = { queue.isReviewed(it) },
            current = session.currentKey(),
        )
        if (rows.isEmpty()) return

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setTitle("${snapshot.reviewedCount} / ${snapshot.items.size} reviewed")
            .setRenderer(
                SimpleListCellRenderer.create<ReviewFileRow> { label, row, _ ->
                    label.text = row.label
                    label.icon = if (row.isReviewed) AllIcons.Actions.Checked else EmptyIcon.ICON_16
                }
            )
            .setSelectedValue(rows.firstOrNull { it.isCurrent }, true)
            .setItemChosenCallback { row -> open(project, session, queue, row) }
            .setNamerForFiltering { it.label }
            .createPopup()
            .showInBestPositionFor(dataContext)
    }

    private fun open(
        project: Project,
        session: ReviewSessionService,
        queue: ReviewQueueService,
        row: ReviewFileRow,
    ) {
        // jumpTo refuses anything outside the pass, which is the signal to browse instead of
        // reshuffling what the reviewer is walking through.
        if (session.jumpTo(row.key)) return
        // Guarded the same way the tree guards it: a change the diff framework cannot render would
        // otherwise open an empty tab.
        if (queue.changeFor(row.key) != null) ReviewDiffOpener.open(project, row.key)
    }
}
```

- [ ] **Step 4: Write the action**

Create `src/main/kotlin/dev/tweety/reviewqueue/actions/ShowFileListAction.kt`:

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.ui.ReviewFileListPopup

/**
 * Opens the file list from the diff toolbar: the whole change and its reviewed state, without
 * ending the pass to go and look at the tool window that the pass has hidden.
 */
class ShowFileListAction : AnAction(
    "Show File List",
    "List every file in the review scope with its reviewed state",
    AllIcons.Actions.ListFiles,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        // Diff-viewer only, and only during a pass: outside one the tool window's own tree is
        // already on screen and is the better way to browse.
        e.presentation.isEnabled = project != null &&
            ReviewSessionService.getInstance(project).isActive &&
            e.getData(DiffDataKeys.DIFF_CONTEXT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewFileListPopup.show(project, e.dataContext)
    }
}
```

- [ ] **Step 5: Register the action**

In `src/main/resources/META-INF/plugin.xml`, inside `<actions>` and before
`ReviewQueue.PreviousFile`, add:

```xml
        <action id="ReviewQueue.ShowFileList"
                class="dev.tweety.reviewqueue.actions.ShowFileListAction"
                text="Show File List"
                description="List every file in the review scope with its reviewed state"/>
```

No `<keyboard-shortcut>`. Unlike the four confirming session controls this is a single command with
no unconfirmed twin, so listing it in Find Action duplicates nothing.

- [ ] **Step 6: Put it at the head of the navigation group**

In `ReviewSessionService.diffActions`, add `manager.getAction("ReviewQueue.ShowFileList"),` as the
first entry of the `listOfNotNull(...)` block, ahead of `ReviewQueue.PreviousFile`.

- [ ] **Step 7: Run the tests to verify they pass**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. If `SimpleListCellRenderer`, `EmptyIcon` or `setNamerForFiltering` will
not resolve, fix the import or drop the `setNamerForFiltering` line — it only adds speed-search over
the labels and nothing else depends on it.

- [ ] **Step 8: Verify in a live IDE**

```bash
./gradlew runIde
```

In the sandbox IDE, with several staged files and at least one already marked:

1. Start Review. The file list button is leftmost on the diff toolbar.
2. Open it. Every file in scope is listed; reviewed files carry a check; the file on screen is
   preselected; the title reads `N / M reviewed`.
3. Pick another unreviewed file. The diff swaps to it and the tab title's `Review N/M` follows.
4. Mark it. The pass continues from there.
5. Pick a file that was already reviewed before the pass began. It opens as a separate browsing
   diff and the review tab and its progress are untouched.
6. Type to filter the list.

- [ ] **Step 9: Update the manual checklist**

Append to `docs/manual-verification.md`:

```markdown
- [ ] 22a. The file list opens from the diff toolbar and lists every file in scope, reviewed ones
      marked, with the file on screen preselected and the title showing `N / M reviewed`
- [ ] 22b. Picking another file in the pass swaps the diff and moves the tab title's `Review N/M`;
      marking then continues from there
- [ ] 22c. Picking a file that was already reviewed when the pass started opens a browsing diff and
      leaves the pass and its progress untouched
- [ ] 22d. Speed-search filtering in the popup selects the right file
- [ ] 22e. Jumping to a file that has left the queue lands on the next live file, never on a blank
      diff
```

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewFileListPopup.kt \
        src/main/kotlin/dev/tweety/reviewqueue/actions/ShowFileListAction.kt \
        src/main/resources/META-INF/plugin.xml \
        src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionService.kt \
        src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewSessionServiceTest.kt \
        docs/manual-verification.md
git commit -m "feat: navigable file list from the diff toolbar

The pass hides the tool window, so there was no way to see the whole change
without ending it. Picking a file in the pass moves the cursor; picking one
outside it browses, leaving the fixed pass alone."
```

---

### Task 6: Documentation

The README's *Use* section describes a diff toolbar that no longer matches: it names
`Ctrl+Shift+Space`, lists End Review among the per-file actions, and documents Refresh and Reset All
as tool-window-only. One coherent rewrite rather than five partial edits across the earlier tasks.

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-07-26-diff-toolbar-and-hotkey-design.md` (only if Task 2 Step 8 found the marker unhonoured)

**Interfaces:** none.

- [ ] **Step 1: Rewrite the diff-actions list in README.md**

Replace the bulleted list of diff actions (the `Mark Reviewed` / `Previous File` /
`Toggle Reviewed` / `End Review` block) with two groups matching the shipped toolbar:

```markdown
The diff viewer's own toolbar carries two groups. On the left, the actions for the file on screen:

- **Show File List** — every file in the scope with its reviewed state, without leaving the pass.
  Picking a file in the pass jumps the diff to it; picking one that was already reviewed when the
  pass started opens it as a separate browsing diff and leaves the pass alone.
- **Mark Reviewed** (also <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd> on macOS,
  <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd> on Windows and Linux) — marks the
  file on screen reviewed and opens the next unreviewed one. Advancing **replaces** the diff tab
  rather than opening a new one, so there is only ever one review tab.
- **Previous File** — steps back to the file shown before this one, without changing any mark. Use
  it together with **Toggle Reviewed** to fix a mis-mark: step back, toggle the wrong mark off, then
  Mark Reviewed to continue from there.
- **Toggle Reviewed** — adds or removes the reviewed mark on the file currently on screen without
  moving to another file.

Right-aligned on the same toolbar, the session and queue controls — **Start Review**, **End
Review**, **Refresh** and **Reset All**, the same four as in the tool window. A pass hides the tool
window, so these are how you reach them without ending it. Each one **asks before acting**: they sit
directly above the code you are reading, where an accidental press is expensive. The tool-window
copies are unchanged — only Reset All confirms there.

**End Review** leaves the guided pass early. Every mark made so far is kept, and both tool windows
are restored. Closing the review diff tab by hand does the same thing.
```

- [ ] **Step 2: Fix the remaining stale references**

Search the README for anything the change invalidates:

```bash
grep -n "Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space\|Refresh\|Reset All" README.md
```

The **Refresh** and **Reset All** sentence in the paragraph about the tool window outside a session
stays accurate — leave it. Any surviving `Ctrl+Shift+Space` is a leftover; remove it.

- [ ] **Step 3: Record the right-alignment outcome in the spec**

Only if Task 2 Step 8 found the diff toolbar did *not* honour `RightAlignedToolbarAction`: update the
*Known risk* paragraph in
`docs/superpowers/specs/2026-07-26-diff-toolbar-and-hotkey-design.md` to say so and to describe the
`Separator` fallback as what shipped. If it did honour it, replace "unverified" in that paragraph
with a note that it was verified in 2026.2, and drop the fallback sentence.

- [ ] **Step 4: Verify the docs match the build**

```bash
./gradlew test buildPlugin
```

Expected: BUILD SUCCESSFUL, `build/distributions/review-queue-0.1.0.zip` produced.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-07-26-diff-toolbar-and-hotkey-design.md
git commit -m "docs: document the two-group diff toolbar, file list and new shortcut"
```

---

## Notes for the implementer

**Do not "simplify" these two things.** Both look redundant and are not:

- The `DiffDataKeys.DIFF_CONTEXT` gate on `MarkReviewedAction`, `ToggleReviewedAction`,
  `PreviousFileAction` and `ShowFileListAction`. Removing it makes the IDE-wide shortcut live in
  every editor mid-session, where it marks whichever file the review happens to be sitting on.
- `DiffResetAllAction` having no body. `ResetAllAction` already confirms; adding a confirmation here
  produces two dialogs.

**Never publish change data keys.** Nothing in this plan adds a `UiDataProvider`, a VCS context menu,
or `VcsDataKeys.CHANGES` / `SELECTED_CHANGES`. Each of those hands Rollback a live selection inside a
read-only review UI. The plugin must never mutate a repository.

**The pass is fixed once it starts.** `jumpTo` moves the cursor inside `keys` and refuses everything
else. Do not make it append, and do not rebuild `keys` from the live queue.
