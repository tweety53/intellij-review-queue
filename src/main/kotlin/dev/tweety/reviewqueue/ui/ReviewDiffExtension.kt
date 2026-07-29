package dev.tweety.reviewqueue.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler

/**
 * Installs the review context menu on the editors of a review diff.
 *
 * `DiffExtension` is the only hook that reaches a diff viewer's editors, and it is **global**: it
 * fires for the Git log, local history, Compare Files and every other diff in the IDE. The guard
 * below is therefore the first thing that runs, and it is the whole safety property — without it
 * this plugin would remove Compare with Clipboard from diffs it never opened.
 *
 * **The guard reads [DiffContext], not [DiffRequest].** [EditorTabDiffPresenter] stamps
 * [ReviewDiffKeys.REVIEW_DIFF] on the `ChangeDiffRequestChain`, not on the individual request each
 * producer builds — `ChangeDiffRequestChain` extends `UserDataHolderBase` directly and has no code
 * path that copies its data onto the requests it produces, so `request.getUserData(REVIEW_DIFF)`
 * would always be null. The chain's data *does* reach `DiffContext`: `ChainDiffVirtualFile` opens a
 * chain through `CacheDiffRequestChainProcessor(project, chain)`, whose constructor passes `chain`
 * straight up to `DiffRequestProcessor`'s `UserDataHolder` parameter, which becomes
 * `DiffContextOnDataHolders`'s `myInitialContext` — `getUserData` on that class checks its own
 * (empty) holder first, then falls back to `myInitialContext`, i.e. the chain. Verified by
 * disassembling `intellij.platform.diff.impl` and `intellij.platform.vcs.impl` for IU-2026.2, and
 * proven in `ReviewDiffExtensionTest` by building a chain and producer the way the presenter does
 * and reading the marker back off a real `DiffContextOnDataHolders` wrapping that chain — the same
 * base class `DiffRequestProcessor`'s private `MyDiffContext` extends unchanged.
 *
 * **Attachment does not happen here, in `onViewerCreated` — it is deferred to a listener's
 * `onInit()`.** `EditorEx.setContextMenuGroupId` was the original mechanism (see the proposal's
 * *Fix round 1*) and does not work: it only writes a string consulted by the *default*
 * `EditorPopupHandler` at index 0 of
 * `EditorImpl.myPopupHandlers`, which is not what the platform's own diff menu goes through.
 * `TwosideTextDiffViewer.installEditorListeners()` builds `ContextMenuPopupHandler.Simple` from
 * `Diff.EditorPopupMenu` and appends it via `EditorEx.installPopupHandler` — and
 * `EditorImpl.getPopupActionGroup` scans `myPopupHandlers` from `size() - 1` **downwards**, so
 * whichever handler was installed *last* wins, regardless of what `setContextMenuGroupId` says.
 * `DiffRequestProcessor.createState()` calls `onViewerCreated` immediately after `createComponent()`,
 * and only afterwards does `DiffViewerBase.init()` call `onInit()` (bytecode offset 60) and then
 * `fireEvent(EventType.INIT)` (offset 107) — which is what runs `installEditorListeners()` and
 * appends the platform's handler. Installing a handler synchronously from `onViewerCreated` would
 * therefore always lose the downward scan to the platform's handler, installed afterwards — the
 * exact bug `setContextMenuGroupId` had. Registering a [DiffViewerListener] here and installing
 * `ContextMenuPopupHandler.Simple(ReviewQueue.DiffPopup)` from that listener's `onInit()` instead
 * means this plugin's handler is appended *after* the platform's, and wins the scan. Verified by
 * disassembling `intellij.platform.diff.impl` and `intellij.platform.ide.impl` for IU-2026.2, and
 * proven in `ReviewDiffExtensionTest` by reproducing both halves directly: the ordering property on
 * a real `EditorEx`, and the registration/deferral timing on a stub `DiffViewerBase`.
 *
 * **An install-once guard, not self-removal, is what makes a second `onInit()` firing safe.**
 * `onInit()` firing twice on the same live viewer has no current call path, and that is a verified
 * platform property rather than an assumption about this plugin's own callers:
 * `DiffRequestProcessor$DefaultState.init()` invokes `DiffViewer.init()` exactly once (bytecode
 * offset 43), and every navigation path — including Next/Previous Diff — runs `ViewerState.destroy()`
 * and then `createState()`, which builds a **new** viewer through `FrameDiffTool.createComponent()`
 * before calling `init()` on it. There is no viewer-reuse or re-init path to reach. Confirmed by
 * disassembling `intellij.platform.diff.impl` for IU-2026.2, to the same standard as every other
 * platform claim in this file. This plugin's own callers agree — both
 * `EditorTabDiffPresenter.show()` and `ReviewDiffOpener.open()` build a fresh viewer and a fresh
 * `ReviewDiffExtension`-registered listener every time — but nothing *enforced* that, and a future
 * "refresh in place" change would silently accumulate one more handler per re-init with no removal
 * counterpart. `DiffViewerBase` does expose a public `removeListener`, but calling it from *inside*
 * the listener's own `onInit()` throws `ConcurrentModificationException`: `fireEvent` iterates
 * `listeners` with a plain `Iterator`, not a copy (confirmed by disassembling
 * `intellij.platform.diff.impl` for IU-2026.2), so mutating that same list mid-iteration is unsafe.
 * A boolean captured by the listener instead makes the second firing a no-op without touching the
 * listener list at all.
 *
 * The platform instantiates this class from the `diff.DiffExtension` extension point with no
 * arguments (see `plugin.xml`), so its constructor is `@JvmOverloads` with the real dispatch
 * ([defaultEditorsOf]) as its default — [defaultEditorsOf] takes the class's place as the seam a
 * test replaces, in place of subclassing a production class purely to override one method. The same
 * pattern `RecordingToolWindows` uses at the platform boundary elsewhere in this codebase's tests.
 *
 * **`@JvmOverloads` leaves three constructors on the class, and that is safe here for a reason worth
 * stating rather than assuming.** A loader that enumerated constructors could reasonably balk at an
 * ambiguous choice, and if it did, this extension would silently never load — the same invisible
 * failure as the original `setContextMenuGroupId` bug, reached by a different route.
 * `ComponentManagerImpl.findConstructorAndInstantiateClass` does not enumerate: it resolves through
 * `findConstructorOrNull(lookup, aClass, emptyConstructorMethodType)`, a `MethodHandles` lookup by
 * **exact** signature, trying the empty constructor first and a `CoroutineScope` one second. Extra
 * constructors are therefore invisible to it, and the `@JvmOverloads`-generated no-arg constructor
 * is what it finds. Confirmed by disassembling `intellij.platform.ide` for IU-2026.2 and by `javap`
 * on this class's own compiled output, which carries a genuine `public ReviewDiffExtension()`.
 */
class ReviewDiffExtension @JvmOverloads constructor(
    private val editorsOf: (FrameDiffTool.DiffViewer) -> List<EditorEx> = ::defaultEditorsOf,
) : DiffExtension() {

    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest,
    ) {
        if (!shouldDecorate(context)) return

        // A viewer that is not a DiffViewerBase has no addListener to defer through. Every concrete
        // diff viewer this plugin's diffs can produce — the three text viewers editorsOf dispatches
        // on below, and the binary/image viewers whose editorsOf branch is empty anyway — extends
        // DiffViewerBase (verified by disassembling intellij.platform.diff.impl for IU-2026.2). This
        // branch exists for the type FrameDiffTool.DiffViewer itself does not rule out: a future or
        // third-party viewer implementing the interface directly. Log-and-skip rather than an
        // unchecked cast, the same reasoning ReviewDiffPopupGroup.platformTail's fallback uses.
        if (viewer !is DiffViewerBase) {
            thisLogger().warn(
                "Review Queue: diff viewer ${viewer.javaClass.name} is not a DiffViewerBase; " +
                    "the review context menu cannot be installed on its editors",
            )
            return
        }

        val group = ActionManager.getInstance().getAction(POPUP_GROUP_ID) as? ActionGroup ?: run {
            thisLogger().warn(
                "Review Queue: action group '$POPUP_GROUP_ID' did not resolve to an ActionGroup; " +
                    "the review context menu was not installed",
            )
            return
        }

        // Computed now, not inside onInit(): by the time onViewerCreated fires the viewer's editors
        // already exist (DiffRequestProcessor.createState() calls onViewerCreated right after
        // createComponent()) — only the popup handler's *installation* has to wait for onInit() to be
        // appended after the platform's. Capturing the list eagerly also keeps editorsOf's dispatch
        // on the viewer's concrete type in one place, unaffected by the deferred callback below.
        val editors = editorsOf(viewer)
        viewer.addListener(object : DiffViewerListener() {
            // Install-once guard against a second onInit() firing on this same viewer; see the
            // class KDoc ("An install-once guard, not self-removal, ...") for why a plain flag
            // rather than viewer.removeListener(this).
            private var installed = false

            override fun onInit() {
                if (installed) return
                installed = true
                editors.forEach { installHandler(it, group) }
            }
        })
    }

    companion object {
        private const val POPUP_GROUP_ID = "ReviewQueue.DiffPopup"

        /**
         * Deliberately keyed on data this plugin attached, not on whether a pass is running. A
         * session check would claim any diff opened mid-pass — open the Git log while reviewing and
         * that diff would get the review menu too.
         */
        internal fun shouldDecorate(context: DiffContext): Boolean =
            context.getUserData(ReviewDiffKeys.REVIEW_DIFF) == true

        /**
         * The one line that actually attaches the menu: a fresh `ContextMenuPopupHandler.Simple`
         * appended via `installPopupHandler`. Pulled out of the `onInit()` callback so
         * `ReviewDiffExtensionTest` can run this exact step against a real `EditorEx` that already
         * carries the platform's own handler, and assert the ordering property the whole fix depends
         * on — that this call's handler ends up last in `EditorImpl.myPopupHandlers`, which is what
         * `EditorImpl.getPopupActionGroup`'s downward scan picks. Returns the installed handler so the
         * test can assert on the exact instance rather than merely on list size.
         */
        internal fun installHandler(editor: EditorEx, group: ActionGroup): ContextMenuPopupHandler.Simple {
            val handler = ContextMenuPopupHandler.Simple(group)
            editor.installPopupHandler(handler)
            return handler
        }
    }
}

/**
 * `DiffViewerBase` carries no public editor accessor — the brief's snippet assumed one that does
 * not exist on IU-2026.2, confirmed by disassembling `intellij.platform.diff.impl`. The three
 * concrete text-viewer types below are the public surface that does: `SimpleDiffViewer` (the
 * plugin's actual viewer, since `ChangeDiffRequestProducer` renders as a two-side text diff)
 * extends `TwosideTextDiffViewer`, and the other two are the remaining diff tools the platform can
 * select for a two-file text change.
 *
 * **A binary or image change falls through to `emptyList()`, and that is inherent, not a gap in
 * this dispatch.** `TwosideBinaryDiffViewer`, `OnesideBinaryDiffViewer` and
 * `ThreesideBinaryDiffViewer` are generic over `BinaryEditorHolder`, which wraps a platform
 * `FileEditor` (an image viewer or `DumbFileEditor`, depending on file type) — never an `EditorEx`.
 * None of the three declares a `getEditors(): List<EditorEx>` the way the text viewers do; there is
 * no `EditorEx` for `installPopupHandler` to attach a handler to. Confirmed by disassembling all
 * three classes and `BinaryEditorHolder` in `intellij.platform.diff.impl` for IU-2026.2. This is why
 * the diff *toolbar* still offers Mark Reviewed on a binary file — `DiffUserDataKeys.CONTEXT_ACTIONS`
 * is built independently in `EditorTabDiffPresenter` and does not depend on there being a text
 * editor — while the *context menu* this extension installs cannot exist for that file. No further
 * branch belongs here for that case.
 *
 * **No viewer-type-agnostic alternative exists that is both sound and public.** The platform has no
 * common interface across `DiffViewer` implementations exposing `List<EditorEx>` — the concept
 * doesn't apply uniformly, since binary viewers hold no `EditorEx` at all to expose. The closest
 * generic surface, `DiffViewerBase`, was already ruled out above for having no such accessor at all
 * (not even an internal one under a different name, per the same disassembly). A dispatch over the
 * concrete public text-viewer types is therefore the correct shape, not a stand-in for a nicer API
 * that exists but wasn't used; a future IDE adding a new text-viewer type would need a new branch
 * here regardless of how this were written.
 *
 * A top-level `internal` function rather than a method on [ReviewDiffExtension]: it is the real
 * dispatch [ReviewDiffExtension]'s constructor defaults to, kept out of the class entirely so the
 * class itself stays `final` and requires no subclass — a test that needs a different dispatch (a
 * stub [com.intellij.diff.tools.util.base.DiffViewerBase] that is deliberately not one of the three
 * types matched below; see `ReviewDiffExtensionTest`'s KDoc on why a real `SimpleDiffViewer` cannot
 * be constructed headless) passes its own lambda to the constructor instead. `internal`, not
 * `private`, because `ReviewDiffExtensionTest` also calls this function directly to exercise the
 * one branch reachable headless — a viewer matching none of the three text-viewer types.
 */
internal fun defaultEditorsOf(viewer: FrameDiffTool.DiffViewer): List<EditorEx> = when (viewer) {
    is TwosideTextDiffViewer -> viewer.editors
    is OnesideTextDiffViewer -> viewer.editors
    is UnifiedDiffViewer -> viewer.editors
    else -> emptyList()
}.filterIsInstance<EditorEx>()
