package dev.tweety.reviewqueue.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.impl.DiffContextOnDataHolders
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionGroupWrapper
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.HeavyPlatformTestCase
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

class ReviewDiffExtensionTest : HeavyPlatformTestCase() {

    /**
     * The lightest [DiffContext] that still exercises the real predicate: `DiffContext` itself
     * (not `DiffContextOnDataHolders`) implements `getUserData`/`putUserData` concretely against its
     * own `myUserDataHolder`, so this fake needs no wiring beyond the two abstract members.
     */
    private inner class FakeContext : DiffContext() {
        override fun getProject(): Project = this@ReviewDiffExtensionTest.project
        override fun isWindowFocused(): Boolean = false
        override fun isFocusedInWindow(): Boolean = false
        override fun requestFocusInWindow() = Unit
    }

    /**
     * The platform class the running IDE actually uses for a chain's [DiffContext]:
     * `DiffRequestProcessor$MyDiffContext` (private, unreachable from a test) extends this one and
     * adds nothing to `getUserData`/`putUserData` — [DiffContextOnDataHolders] is where the
     * chain-fallback logic this task depends on actually lives. Subclassing it directly, instead of
     * going through a live `CacheDiffRequestChainProcessor`, exercises that real logic without
     * building the Swing toolbar `DiffRequestProcessor`'s constructor also builds — which throws
     * `no ComponentUI class for DiffHeaderToolbarPanel` under this headless test run (no L&F
     * registered for that platform component) and aborts before any assertion would run.
     */
    private inner class FakeChainContext(initialContext: com.intellij.openapi.util.UserDataHolder) :
        DiffContextOnDataHolders(initialContext) {
        override fun getProject(): Project = this@ReviewDiffExtensionTest.project
        override fun isWindowFocused(): Boolean = false
        override fun isFocusedInWindow(): Boolean = false
        override fun requestFocusInWindow() = Unit
        override fun reopenDiffRequest() = Unit
        override fun reloadDiffRequest() = Unit
        override fun showProgressBar(value: Boolean) = Unit
        override fun setWindowTitle(title: String) = Unit
    }

    private class FakeRevision(private val path: String, private val bytes: ByteArray) : ByteBackedContentRevision {
        override fun getContentAsBytes(): ByteArray = bytes
        override fun getContent(): String = String(bytes, Charsets.UTF_8)
        override fun getFile(): FilePath = LocalFilePath(path, false)
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    fun testAnUnmarkedContextIsNotDecorated() {
        assertFalse(
            "a global EP must refuse every diff this plugin did not open",
            ReviewDiffExtension.shouldDecorate(FakeContext()),
        )
    }

    fun testAMarkedContextIsDecorated() {
        val marked = FakeContext().apply { putUserData(ReviewDiffKeys.REVIEW_DIFF, true) }
        assertTrue(ReviewDiffExtension.shouldDecorate(marked))
    }

    /**
     * The predicate alone only proves it works on a hand-built [DiffContext]. This proves the
     * marker actually reaches [DiffContext] the way the platform wires it for a real
     * `ChainDiffVirtualFile`: [EditorTabDiffPresenter] stamps the marker on the
     * `ChangeDiffRequestChain`, and `CacheDiffRequestChainProcessor`'s constructor passes that same
     * chain straight through to `DiffRequestProcessor`'s `UserDataHolder` parameter, which becomes
     * `DiffContextOnDataHolders`'s `myInitialContext` (verified by disassembling
     * `intellij.platform.diff.impl` and `intellij.platform.vcs.impl` for IU-2026.2 — `javap -c` on
     * `CacheDiffRequestChainProcessor.<init>` shows `super(project, chain)`, and on
     * `DiffContextOnDataHolders.getUserData` shows the fallback to `myInitialContext`). Passing the
     * same chain into a real `DiffContextOnDataHolders` here exercises that exact fallback, built
     * from a real `ChangeDiffRequestProducer` over a real `Change` — the only thing not exercised is
     * the Swing toolbar construction around it, which needs a live UI and is irrelevant to whether
     * the marker propagates.
     */
    fun testTheMarkerReachesTheContextThroughTheRealChainFallback() {
        val change = Change(null, FakeRevision("${project.basePath}/f.kt", "body".toByteArray(Charsets.UTF_8)))
        val producer = requireNotNull(ChangeDiffRequestProducer.create(project, change))
        val chain = ChangeDiffRequestChain(listOf(producer), 0)
        chain.putUserData(ReviewDiffKeys.REVIEW_DIFF, true)

        assertTrue(
            "DiffContext must carry the chain's marker for ReviewDiffExtension to fire",
            ReviewDiffExtension.shouldDecorate(FakeChainContext(chain)),
        )
    }

    // --- Fix round 1: the attachment itself, not just the guard. ---

    private fun createEditor(): EditorEx {
        val document = EditorFactory.getInstance().createDocument("one\ntwo\nthree")
        return EditorFactory.getInstance().createEditor(document, project) as EditorEx
    }

    private fun releaseEditor(editor: EditorEx) = EditorFactory.getInstance().releaseEditor(editor)

    /**
     * A minimal, real [DiffViewerBase]. Its own `createToolbarActions`/etc. all fall back to
     * [DiffViewerBase]'s own no-op defaults, so calling [init] on it in a headless test run stays
     * inside the same safe path the class comment on [ReviewDiffExtension] already relies on:
     * `Application.isHeadlessEnvironment()` is true here, which skips the only Swing-dimension check
     * `init()` performs, and every other step it takes — `processContextHints()`, `onInit()`,
     * building `ToolbarComponents` from the four `create*`/`getStatusPanel` hooks, then
     * `DiffUtil.installShowNotifyListener(getComponent(), ...)` — touches nothing beyond a bare
     * `JPanel`. This is deliberately **not** one of the three concrete text-viewer types
     * ([defaultEditorsOf]'s `when` matches on): those build real editor holders in their
     * constructors, which is exactly the machinery `ReviewDiffExtensionTest`'s [FakeChainContext]
     * KDoc already documents as unusable headless. A stub proves the *timing* (listener registered
     * now, handler installed only once `onInit()` fires); [testThePluginHandlerIsAppendedLastAndWins]
     * proves the *ordering* on a real `EditorEx` directly, and
     * [testThePluginHandlerEndsUpLastThroughTheRealOnViewerCreatedAndInitSequence] below proves both
     * together through the real `onViewerCreated` + `init()` sequence.
     *
     * [onInit] optionally installs a platform handler itself — exactly what
     * `TextDiffViewerUtil.EditorActionsPopup.install` does from the real `TwosideTextDiffViewer`'s own
     * `onInit()` — so a test can reproduce the platform's own half of the sequence, not just this
     * plugin's. Left disabled (`platformGroup == null`) for every test that only cares about this
     * plugin's timing.
     */
    private class StubDiffViewer(
        context: DiffContext,
        request: ContentDiffRequest,
        private val editorsForPlatformInstall: List<EditorEx> = emptyList(),
        private val platformGroup: ActionGroup? = null,
    ) : DiffViewerBase(context, request) {
        private val panel = JPanel()

        var installedPlatformHandler: ContextMenuPopupHandler.Simple? = null
            private set

        override fun getComponent(): JComponent = panel
        override fun getPreferredFocusedComponent(): JComponent? = null
        override fun performRediff(indicator: ProgressIndicator): Runnable = Runnable {}

        override fun onInit() {
            val group = platformGroup ?: return
            editorsForPlatformInstall.forEach { editor ->
                val handler = ContextMenuPopupHandler.Simple(group)
                editor.installPopupHandler(handler)
                installedPlatformHandler = handler
            }
        }
    }

    /** A [FrameDiffTool.DiffViewer] that is deliberately not a [DiffViewerBase], for the guard in
     * [ReviewDiffExtension.onViewerCreated] that has no `addListener` to defer through otherwise. */
    private class NotADiffViewerBase : FrameDiffTool.DiffViewer {
        override fun getComponent(): JComponent = JPanel()
        override fun getPreferredFocusedComponent(): JComponent? = null
        override fun init(): FrameDiffTool.ToolbarComponents = FrameDiffTool.ToolbarComponents()
        override fun dispose() = Unit
    }

    private fun reviewGroup(): ActionGroup =
        requireNotNull(ActionManager.getInstance().getAction("ReviewQueue.DiffPopup") as? ActionGroup) {
            "ReviewQueue.DiffPopup must be registered"
        }

    /**
     * The property that decides the whole bug: `EditorImpl.getPopupActionGroup` scans
     * `myPopupHandlers` from `size() - 1` downwards, so whichever handler was installed **last**
     * wins. This reproduces the platform's own sequence — its handler installed first, from
     * `TextDiffViewerUtil.EditorActionsPopup.install` — then runs this plugin's attachment step and
     * asserts the plugin's handler is both last and resolves to `ReviewQueue.DiffPopup`.
     */
    fun testThePluginHandlerIsAppendedLastAndWins() {
        val editor = createEditor()
        try {
            val platformGroup = requireNotNull(
                ActionManager.getInstance().getAction("Diff.EditorPopupMenu") as? ActionGroup,
            )
            editor.installPopupHandler(ContextMenuPopupHandler.Simple(platformGroup))

            val installed = ReviewDiffExtension.installHandler(editor, reviewGroup())

            val handlers = (editor as EditorImpl).popupHandlers
            assertSame(
                "the platform's handler was appended first and must not be the one scanned first",
                installed,
                handlers.last(),
            )
        } finally {
            releaseEditor(editor)
        }
    }

    /**
     * Pass-3 panel Minor 5, the highest-value one: every test above this one asserts on
     * `handlers.last()` — list *position* — never on what the platform's own popup-resolution code
     * does with that position. [testThePluginHandlerIsAppendedLastAndWins] would stay green even if a
     * future IDE switched `EditorImpl.getPopupActionGroup` to first-match-wins instead of scanning
     * from the end, because it never calls that method. This closes the gap by calling the *real*
     * `EditorImpl.getPopupActionGroup(EditorMouseEvent)` and asserting it actually resolves to this
     * plugin's group.
     *
     * Constructing an `EditorMouseEvent` and calling this method needs no Swing toolbar and no L&F —
     * confirmed by disassembling `EditorImpl.getPopupActionGroup` for IU-2026.2: for an
     * `EditorMouseEventArea.EDITING_AREA` event it is pure iteration over `myPopupHandlers` from
     * `size() - 1` downward, calling `ContextMenuPopupHandler.getActionGroup` on each entry that is
     * one, with no UI construction anywhere in the path. That is unlike `SimpleDiffViewer` or
     * `CacheDiffRequestChainProcessor` (see [FakeChainContext]'s KDoc), which is why this is reachable
     * headless where those are not.
     *
     * The method does not return the handler's group as-is, which is why this does not
     * `assertSame(group, result)` directly: disassembly of the same bytecode shows that for a
     * non-empty group that is not the *exact* `DefaultActionGroup` class — `ReviewDiffPopupGroup`
     * (registered under `ReviewQueue.DiffPopup` in `plugin.xml`) is a subclass, so it takes this
     * path — the result is wrapped in `EditorMousePopupActionGroup`, which extends
     * `ActionGroupWrapper`, before being returned. This unwraps via `ActionGroupWrapper.getDelegate()`
     * and asserts on that.
     */
    fun testEditorImplGetPopupActionGroupResolvesToTheReviewGroupOnTheRealDownwardScan() {
        val editor = createEditor()
        try {
            val platformGroup = requireNotNull(
                ActionManager.getInstance().getAction("Diff.EditorPopupMenu") as? ActionGroup,
            )
            editor.installPopupHandler(ContextMenuPopupHandler.Simple(platformGroup))

            val group = reviewGroup()
            ReviewDiffExtension.installHandler(editor, group)

            val mouseEvent = MouseEvent(
                editor.contentComponent,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                0,
                0,
                1,
                true,
            )
            val event = EditorMouseEvent(editor, mouseEvent, EditorMouseEventArea.EDITING_AREA)

            val result = (editor as EditorImpl).getPopupActionGroup(event)

            val delegate = (result as? ActionGroupWrapper)?.delegate ?: result
            assertSame(
                "the platform's own popup resolution — not merely list position — must resolve to " +
                    "this plugin's group",
                group,
                delegate,
            )
        } finally {
            releaseEditor(editor)
        }
    }

    /**
     * The split the wiring half exists for: distinguishes "installs at the right time" from
     * "installs at all". `onViewerCreated` must register a listener and touch no editor synchronously
     * — a synchronous install would run before the platform's own `installEditorListeners()` and lose
     * the downward scan exactly as the original `setContextMenuGroupId` call did. The handler must
     * appear only once the registered listener's `onInit()` fires, which [StubDiffViewer.init] does
     * for real via `DiffViewerBase.init()`'s `onInit()` → `fireEvent(EventType.INIT)` sequence.
     */
    fun testOnViewerCreatedDefersInstallUntilOnInit() {
        val editor = createEditor()
        try {
            val context = FakeContext().apply { putUserData(ReviewDiffKeys.REVIEW_DIFF, true) }
            val request = SimpleDiffRequest("t", emptyList<DiffContent>(), emptyList<String>())
            val viewer = StubDiffViewer(context, request)
            val extension = ReviewDiffExtension(editorsOf = { listOf(editor) })

            extension.onViewerCreated(viewer, context, request)

            assertEquals(
                "onViewerCreated must not install anything synchronously",
                emptyList<Any>(),
                (editor as EditorImpl).popupHandlers.filterIsInstance<ContextMenuPopupHandler.Simple>(),
            )

            viewer.init()

            assertEquals(
                "the handler must be installed once, exactly when onInit() fires",
                1,
                editor.popupHandlers.filterIsInstance<ContextMenuPopupHandler.Simple>().size,
            )
        } finally {
            releaseEditor(editor)
        }
    }

    /**
     * The negative half of the safety property from [testOnViewerCreatedDefersInstallUntilOnInit]:
     * a context that never carried the marker — a Git-log diff opened mid-pass, for instance — must
     * leave `getPopupHandlers()` exactly as the platform left it. `shouldDecorate` is asserted
     * elsewhere on its own; this proves the guard actually stops the attachment, not just the
     * predicate.
     */
    fun testAnUnmarkedContextLeavesThePopupHandlersUntouched() {
        val editor = createEditor()
        try {
            val platformGroup = requireNotNull(
                ActionManager.getInstance().getAction("Diff.EditorPopupMenu") as? ActionGroup,
            )
            editor.installPopupHandler(ContextMenuPopupHandler.Simple(platformGroup))
            val before = (editor as EditorImpl).popupHandlers.toList()

            val context = FakeContext()
            val request = SimpleDiffRequest("t", emptyList<DiffContent>(), emptyList<String>())
            val viewer = StubDiffViewer(context, request)
            val extension = ReviewDiffExtension(editorsOf = { listOf(editor) })

            extension.onViewerCreated(viewer, context, request)
            viewer.init()

            assertEquals(
                "an unmarked diff must be left exactly as the platform left it",
                before,
                editor.popupHandlers,
            )
        } finally {
            releaseEditor(editor)
        }
    }

    // --- Fix round 1, review round 1: the crux end-to-end, idempotency, and the untested guards. ---

    /**
     * The crux of the whole fix, proven for the first time through the *real* sequence rather than
     * its two halves separately: [StubDiffViewer.onInit] installs the platform's handler exactly as
     * `TextDiffViewerUtil.EditorActionsPopup.install` does, from the viewer's own `onInit()` — which
     * `DiffViewerBase.init()` calls (bytecode offset 60) *before* `fireEvent(EventType.INIT)`
     * (offset 107) drives this extension's deferred listener. A single `viewer.init()` call therefore
     * exercises both halves in their real order, and this asserts the plugin's handler — appended by
     * the listener, after the platform's own `onInit()` has already run — ends up last, which is what
     * `EditorImpl.getPopupActionGroup`'s downward scan picks.
     *
     * This test only proves anything because it can fail: reverting the implementation to install
     * synchronously from `onViewerCreated` (before `viewer.init()` runs the platform's `onInit()` at
     * all) was verified to flip the assertions below — the plugin's handler would be installed
     * *first* instead of last — then reverted back to the current, deferred implementation.
     */
    fun testThePluginHandlerEndsUpLastThroughTheRealOnViewerCreatedAndInitSequence() {
        val editor = createEditor()
        try {
            val platformGroup = requireNotNull(
                ActionManager.getInstance().getAction("Diff.EditorPopupMenu") as? ActionGroup,
            )
            val context = FakeContext().apply { putUserData(ReviewDiffKeys.REVIEW_DIFF, true) }
            val request = SimpleDiffRequest("t", emptyList<DiffContent>(), emptyList<String>())
            val viewer = StubDiffViewer(
                context,
                request,
                editorsForPlatformInstall = listOf(editor),
                platformGroup = platformGroup,
            )
            val extension = ReviewDiffExtension(editorsOf = { listOf(editor) })

            extension.onViewerCreated(viewer, context, request)
            viewer.init()

            val handlers = (editor as EditorImpl).popupHandlers.filterIsInstance<ContextMenuPopupHandler.Simple>()
            assertEquals(
                "exactly the platform's handler (installed from the viewer's own onInit) and this " +
                    "plugin's handler (installed from the deferred listener) must be present",
                2,
                handlers.size,
            )
            assertSame(
                "the platform's handler, installed first from the viewer's own onInit, must be first",
                viewer.installedPlatformHandler,
                handlers.first(),
            )
            assertNotSame(
                "the last handler in the downward scan must be this plugin's, not the platform's",
                viewer.installedPlatformHandler,
                handlers.last(),
            )
        } finally {
            releaseEditor(editor)
        }
    }

    /**
     * `installHandler` has no dedup and no removal counterpart of its own — the safety this test
     * checks lives entirely in the listener removing itself inside its own `onInit()` (see
     * [ReviewDiffExtension]'s class KDoc). No current call path re-fires `init()` on a live viewer,
     * but nothing should silently stack a second handler if a future change ever does.
     */
    fun testOnInitFiringTwiceInstallsExactlyOneHandler() {
        val editor = createEditor()
        try {
            val context = FakeContext().apply { putUserData(ReviewDiffKeys.REVIEW_DIFF, true) }
            val request = SimpleDiffRequest("t", emptyList<DiffContent>(), emptyList<String>())
            val viewer = StubDiffViewer(context, request)
            val extension = ReviewDiffExtension(editorsOf = { listOf(editor) })

            extension.onViewerCreated(viewer, context, request)

            viewer.init()
            viewer.init()

            assertEquals(
                "a second onInit() firing on the same viewer must not stack a second handler",
                1,
                (editor as EditorImpl).popupHandlers.filterIsInstance<ContextMenuPopupHandler.Simple>().size,
            )
        } finally {
            releaseEditor(editor)
        }
    }

    /**
     * The guard `onViewerCreated` falls back to when it is handed a [FrameDiffTool.DiffViewer] that
     * is not a [DiffViewerBase] — every concrete viewer this plugin's own diffs can produce extends
     * it, but the interface itself does not rule out a future or third-party viewer that does not.
     * There is no `addListener` to defer through in that case, so nothing must be installed.
     */
    fun testANonDiffViewerBaseInstallsNoHandler() {
        val editor = createEditor()
        try {
            val context = FakeContext().apply { putUserData(ReviewDiffKeys.REVIEW_DIFF, true) }
            val request = SimpleDiffRequest("t", emptyList<DiffContent>(), emptyList<String>())
            val viewer = NotADiffViewerBase()
            val extension = ReviewDiffExtension(editorsOf = { listOf(editor) })

            extension.onViewerCreated(viewer, context, request)

            assertEquals(
                "a viewer that is not a DiffViewerBase has no addListener to defer through; " +
                    "nothing must be installed",
                emptyList<Any>(),
                (editor as EditorImpl).popupHandlers.filterIsInstance<ContextMenuPopupHandler.Simple>(),
            )
        } finally {
            releaseEditor(editor)
        }
    }

    /**
     * The guard for `'ReviewQueue.DiffPopup'` failing to resolve to an [ActionGroup] — a
     * `plugin.xml` typo or a future rename of the group id, say. `ActionManager.replaceAction`
     * refuses a group-for-action swap outright (`IllegalStateException: cannot replace a group with
     * an action and vice versa`), so the real registration is unregistered and a plain [AnAction] is
     * registered under the same id for the duration of the test, then the original group is
     * registered back in the `finally` block so no other test observes the swap.
     */
    fun testAnUnresolvedActionGroupInstallsNoHandler() {
        val editor = createEditor()
        val manager = ActionManager.getInstance()
        val originalAction = requireNotNull(manager.getAction("ReviewQueue.DiffPopup"))
        try {
            manager.unregisterAction("ReviewQueue.DiffPopup")
            manager.registerAction(
                "ReviewQueue.DiffPopup",
                object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) = Unit
                },
            )

            val context = FakeContext().apply { putUserData(ReviewDiffKeys.REVIEW_DIFF, true) }
            val request = SimpleDiffRequest("t", emptyList<DiffContent>(), emptyList<String>())
            val viewer = StubDiffViewer(context, request)
            val extension = ReviewDiffExtension(editorsOf = { listOf(editor) })

            extension.onViewerCreated(viewer, context, request)
            viewer.init()

            assertEquals(
                "'ReviewQueue.DiffPopup' resolving to a plain AnAction, not an ActionGroup, " +
                    "must install nothing",
                emptyList<Any>(),
                (editor as EditorImpl).popupHandlers.filterIsInstance<ContextMenuPopupHandler.Simple>(),
            )
        } finally {
            // `ActionManager` is a process-global singleton, so a restore that throws would leave
            // 'ReviewQueue.DiffPopup' bound to the dummy action — or unbound — for every later test
            // in this JVM, and every test that resolves that id would then fail for a reason that
            // has nothing to do with it. Worse, a throw from a bare `finally` *replaces* the
            // assertion failure above, burying the real cause. So the restore cannot be allowed to
            // propagate: it is caught and reported, never rethrown, and `releaseEditor` sits in its
            // own `finally` so the editor is released whatever the registry does.
            try {
                manager.unregisterAction("ReviewQueue.DiffPopup")
                manager.registerAction("ReviewQueue.DiffPopup", originalAction)
            } catch (t: Throwable) {
                // warn, not error: `LoggedErrorProcessor` turns a logged error into a test failure,
                // which would mask the assertion failure this block exists to preserve.
                thisLogger().warn("Failed to restore the real 'ReviewQueue.DiffPopup' action", t)
            } finally {
                releaseEditor(editor)
            }
        }
    }

    /**
     * [defaultEditorsOf] is the real dispatch [ReviewDiffExtension]'s constructor defaults to. Its
     * three concrete text-viewer branches cannot be exercised headless — `SimpleDiffViewer`,
     * `OnesideTextDiffViewer` and `UnifiedDiffViewer` all build real editor holders in their
     * constructors (see [FakeChainContext]'s KDoc on why that machinery is unusable here). This
     * exercises the one branch that is reachable: a viewer matching none of the three.
     */
    fun testDefaultEditorsOfYieldsEmptyListForAnUnmatchedViewerType() {
        val context = FakeContext()
        val request = SimpleDiffRequest("t", emptyList<DiffContent>(), emptyList<String>())
        val viewer = StubDiffViewer(context, request)

        assertEquals(emptyList<EditorEx>(), defaultEditorsOf(viewer))
    }
}
