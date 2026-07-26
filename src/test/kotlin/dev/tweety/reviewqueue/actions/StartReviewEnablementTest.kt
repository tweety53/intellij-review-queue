package dev.tweety.reviewqueue.actions

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.git.GitRoots
import dev.tweety.reviewqueue.queue.ReviewQueueService
import dev.tweety.reviewqueue.queue.ReviewSessionService
import dev.tweety.reviewqueue.ui.RecordingDiffPresenter
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Pins enablement to git-root existence rather than queue contents, and pins that `update()` stays
 * free of the side effects that reading the queue would drag in.
 *
 * A real git root is created in [setUp] on purpose. With no root, `GitRoots.exist` is false and every
 * assertion below would hold for the *old* contents-based gates too — the test would pass without
 * testing anything. The root is what makes the expected value `true` and the assertions load-bearing.
 */
class StartReviewEnablementTest : HeavyPlatformTestCase() {

    private lateinit var repoDir: File

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
    }

    override fun setUp() {
        super.setUp()
        repoDir = File(project.basePath!!)
        repoDir.mkdirs()
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        File(repoDir, "kept.txt").writeText("original\n")
        git("add", "kept.txt")
        git("commit", "-m", "initial")

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        vcsManager.setDirectoryMappings(listOf(VcsDirectoryMapping(repoDir.absolutePath, "Git")))
        runBlocking { vcsManager.awaitInitialization() }
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()

        assertTrue("the fixture must have a git root, or every assertion here is vacuous", GitRoots.exist(project))
    }

    /**
     * Every action id reachable from the registered `ReviewQueue.Menu` group, groups included, found by
     * walking it rather than by listing literals.
     *
     * The literal list this replaces had already needed extending twice — once for the queue-wide
     * commands, once for the nested Scope children — and each time the contract silently covered less
     * than it claimed in between. Deriving it means a new menu child inherits both contracts below on
     * the day it is added.
     *
     * `Separator` and anything else with no registered id is dropped; there is nothing to `update()` by
     * id for those, and `ActionManager.getId` returns null.
     */
    private fun menuActionIds(includeGroups: Boolean): List<String> {
        val manager = ActionManager.getInstance()
        fun collect(action: AnAction): List<String> {
            val own =
                if (action is ActionGroup && !includeGroups) emptyList() else listOfNotNull(manager.getId(action))
            val children = when (action) {
                // The ActionManager overload resolves `<reference>` children without an event, which is
                // what this walk has instead of a rendered menu.
                is DefaultActionGroup -> action.getChildren(manager)
                is ActionGroup -> action.getChildren(null)
                else -> return own
            }
            return own + children.flatMap { collect(it) }
        }
        val menu = checkNotNull(manager.getAction(MENU_GROUP_ID)) {
            "$MENU_GROUP_ID must be registered, or this test asserts nothing"
        }
        return collect(menu).filter { it != MENU_GROUP_ID }
    }

    private fun presentationOf(actionId: String) = run {
        val action = ActionManager.getInstance().getAction(actionId)
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        action.update(event)
        event.presentation
    }

    fun testStartReviewDoesNotDependOnQueueContents() {
        // The queue is deliberately never resolved here: enablement must not read its contents,
        // because nothing warms the queue any more until a gesture asks.
        assertTrue(ReviewQueueService.getInstance(project).snapshot().items.isEmpty())

        val enabled = presentationOf("ReviewQueue.StartReview").isEnabled

        // Asserted as `true`, not as `GitRoots.exist(project)`. Computing the expected value with the
        // predicate under test made the assertion tautological — `GitRoots.exist` mutated to
        // `return true` kept it green. setUp has already asserted that a root exists, so `true` is
        // strictly stronger and costs nothing.
        assertTrue(
            "enablement must follow git-root existence, not queue contents; this fixture has a root",
            enabled,
        )
    }

    fun testShowFileListNoLongerRequiresASessionOrADiffContext() {
        val enabled = presentationOf("ReviewQueue.ShowFileList").isEnabled

        assertTrue(
            "Show File List replaces the deleted tree, so it must work outside a pass",
            enabled,
        )
    }

    /**
     * Spec scenario "Start Review is disabled during a pass", which had no coverage at all: deleting
     * `&& !ReviewSessionService.getInstance(project).isActive` from
     * [StartReviewAction.update] left the whole suite green. Mid-pass the mutated action is pressable,
     * resolves the scope again under a modal progress, and then does nothing, because `start()`
     * returns early on a live session.
     *
     * The pass has to be genuinely running. With no queue `start()` returns false, no session exists,
     * and the assertion below would hold against the mutated predicate too.
     */
    fun testStartReviewIsDisabledDuringAPass() {
        File(repoDir, "kept.txt").writeText("edited\n")
        git("add", "kept.txt")
        val queue = ReviewQueueService.getInstance(project)
        queue.progressRunner = { _, work -> work() }
        assertTrue(queue.resolveNow())
        assertFalse(
            "the pass needs a file to walk, or no session starts and this asserts nothing",
            queue.snapshot().items.isEmpty(),
        )
        val session = ReviewSessionService.getInstance(project)
        session.presenter = RecordingDiffPresenter()
        assertTrue("a pass must be running", session.start())

        assertFalse(
            "Start Review must be disabled while a pass is running",
            presentationOf("ReviewQueue.StartReview").isEnabled,
        )
        assertTrue(
            "Show File List, by contrast, stays enabled during a pass — it is the only way to browse",
            presentationOf("ReviewQueue.ShowFileList").isEnabled,
        )
    }

    /**
     * The whole Tools menu group gates on a git root, not only the two entries that started out that
     * way. Until KAN-5 Refresh, Reset All and the scope children lived in the tool window, where
     * always-enabled was harmless. As menu entries an ungated Refresh opens a modal progress that
     * resolves nothing, and an ungated `Commit Range…` prompts for refs to record a scope nothing can
     * ever resolve.
     *
     * This fixture has a real git root, so every id below must come back **enabled**. That catches a
     * dropped gate and an inverted one alike — asserting `false` in a rootless project could not
     * distinguish the two.
     */
    fun testEveryMenuCommandGatesOnAGitRoot() {
        assertTrue("the fixture must provide a root, or this asserts nothing", GitRoots.exist(project))

        // A lower bound, not an exact count: the point of deriving the list is that a menu child added
        // later inherits this contract instead of escaping it. The bound is what stops the walk from
        // returning nothing and asserting nothing.
        val ids = menuActionIds(includeGroups = false)
        assertTrue("the walk must find all seven commands, got $ids", ids.size >= 7)
        ids.forEach { id ->
            assertTrue("$id must be enabled when the project has a git root", presentationOf(id).isEnabled)
        }
    }

    /**
     * Enablement must not construct the queue service. `GitRoots.exist` is top-level precisely so
     * that hovering the Tools menu does not wire `ChangeListListener` and `GIT_REPO_CHANGE` as a
     * side effect of an `update()` call.
     *
     * Every id registered under `ReviewQueue.Menu` is updated here, not just the two that were the
     * original concern. Opening a menu calls `update()` on the whole group it renders, including the
     * nested Scope submenu, so a contract that covered only some children would leave the rest free
     * to reintroduce exactly the side effect this pins against.
     */
    fun testUpdatingTheActionsDoesNotConstructTheQueueService() {
        val ids = menuActionIds(includeGroups = true)
        assertTrue("the walk must find something, or this test updates nothing", ids.size >= 7)

        ids.forEach { presentationOf(it) }

        assertNull(
            "update() must not create ReviewQueueService, or opening a menu starts background work",
            project.serviceIfCreated<ReviewQueueService>(),
        )
    }

    private companion object {
        const val MENU_GROUP_ID = "ReviewQueue.Menu"
    }
}
