## Context

Full design: `docs/superpowers/specs/2026-07-29-kan-6-plugin-updates-design.md` (committed
`c515c42`). This file carries the parts an implementer needs at hand, plus the decision record.

Every platform API named below was verified against the actual IU-2026.2 artifacts under
`~/.gradle/caches/.../ideaIU-2026.2-aarch64/`, not from memory. One check changed the design:
`DiffUserDataKeysEx` has **no** `NOTIFICATIONS` key in this build — it has `BOTTOM_PANEL`, which is
the wrong edge — so the banner goes through `DiffUserDataKeys.NOTIFICATION_PROVIDERS` instead.

| API | Where it lives |
|-----|----------------|
| `DiffUserDataKeys.NOTIFICATION_PROVIDERS: Key<List<DiffNotificationProvider>>` | `lib/intellij.platform.diff.jar` |
| `DiffNotificationProvider.createNotification(FrameDiffTool.DiffViewer): JComponent` | `lib/intellij.platform.diff.jar` |
| `DiffExtension.EP_NAME`, `onViewerCreated(viewer, context, request)` | `lib/intellij.platform.diff.jar` |
| `EditorEx.setContextMenuGroupId(String)` / `getContextMenuGroupId()` | `lib/intellij.platform.ide.impl.jar` |
| group `Diff.EditorPopupMenu`, action `CompareClipboardWithSelection` | `PlatformActions.xml:452` |

## Goals / Non-Goals

**Goals.** A reviewed count on the diff; a pass that leaves the reviewer alone with the code;
renames as single entries in all three scopes; the review actions on right-click without *Compare
with Clipboard*; and the removal of dead code and stale documentation.

**Non-Goals.** No change to how marks are stored or addressed. No change to the tab-per-file
presenter design. No Distraction Free / Zen mode. No new scope types. No change to any diff this
plugin did not open.

## Approach

### Progress banner

`EditorTabDiffPresenter.show` already stamps `DiffUserDataKeys.CONTEXT_ACTIONS` on the chain it
builds per file; it gains `NOTIFICATION_PROVIDERS` beside it, plus the private marker key the diff
extension reads.

`createNotification(viewer)` runs once per viewer, so the returned component subscribes to
`ReviewQueueService.addListener` against the viewer's disposable and repaints itself. Without that
subscription the count would refresh only when the tab is replaced — which Mark Reviewed does and
**Toggle Reviewed does not**, leaving a stale number during exactly the gesture used to fix a
mis-mark.

`QueueSnapshot.reviewedCount` already exists (`ReviewQueueService.kt:136`). The banner reads it. A
second count computed elsewhere could disagree with the file-list popup's `N / M reviewed` title,
which reads the same snapshot.

### Focus mode

`IdeLayoutController.MANAGED_IDS` is replaced by a sweep over
`ToolWindowManager.getInstance(project).toolWindowIds` filtered on `isVisible`. Nothing else in the
controller changes, because its three existing properties are exactly what a dynamic set needs: it
records before hiding, it keeps unresolved ids on the record at restore, and it restores only what it
hid.

`LEGACY_IDS` pruning in `loadState` stays. It drops the deleted `Review Queue` id, which can never
resolve and would otherwise latch `hideForReview()`'s leftover branch permanently. Keying that prune
on the *current* managed set was already wrong and becomes impossible once the set is dynamic — the
existing comment in `IdeLayoutController.kt:35-43` says why, and it still holds.

### Rename detection

Commit Range switches to the rename-detecting `GitChangeUtils.getDiff` overload. Branch vs Base must
produce single rename entries; `getThreeDotDiffOrThrow` exposes no rename flag at the call site, so
which call delivers that is settled against a real repository fixture during implementation.

Staged is the substantial part. `git4idea.index.getStatus` reads `git status --porcelain=v2`, which
has no similarity pass — no combination of its three booleans yields rename entries. (The original
plan, `docs/superpowers/plans/2026-07-25-review-queue.md:29`, records the empirical session that
established this.) Staged is therefore re-resolved through a HEAD-vs-index diff. `StagedFilter` exists
solely to interpret that status call's index column — `GitReviewSource.kt:80` is its only use in
`src/main` — so it goes with it.

`ChangeMapper.toItem` already prefers `afterRevision`, so a rename keys to the new path with the new
content's hash. No model change.

**Accepted cost.** A mark recorded against the old delete+add pair goes stale once: the delete
entry's key leaves the queue, the add entry's key survives with its mark. A renamed file may be
re-offered on the first pass after upgrade. That is correct for content-addressed marks and is a
one-time cost at the upgrade boundary.

### Context menu

`ReviewQueue.DiffPopup` is a code-backed `DefaultActionGroup` registered in `plugin.xml`. Its
`getChildren` returns: the per-file actions (Mark Reviewed first), a separator, the session controls
from `ReviewSessionService.diffActions`, a separator, then `Diff.EditorPopupMenu`'s **live** children
with `CompareClipboardWithSelection` filtered out by id.

Composing the tail rather than enumerating it is load-bearing: `Diff.EditorPopupMenu` is contributed
to by `VcsActions.xml` (Annotate), `intellij.platform.collaborationTools` (review comments) and the
Ultimate customization layer. A hand-written replacement list would silently drop all of them, and
would keep dropping whatever a future IDE adds.

Installation is `EditorEx.setContextMenuGroupId("ReviewQueue.DiffPopup")` from a
`ReviewDiffExtension : DiffExtension`. That EP is **global** — it fires for Git log diffs, local
history, Compare Files, every diff in the IDE — so the extension returns immediately unless it finds
this plugin's marker key.

> **Superseded by Fix round 1** (`proposal.md`): `setContextMenuGroupId` does not work — it only
> feeds the default popup handler, which the platform's own diff menu never consults. The actual
> mechanism is a `DiffViewerListener` registered in `onViewerCreated` that calls
> `EditorEx.installPopupHandler` from `onInit()`. This section is kept as the as-designed record;
> see `proposal.md`'s **Fix round 1** for the corrected mechanism.

**The marker is read from the `DiffContext`, not the `DiffRequest`** — established empirically
during implementation against the IU-2026.2 jars, and the correction matters more than it looks.
`ChangeDiffRequestChain` **never copies its user data onto the requests it produces**, so the
obvious `request.getUserData(REVIEW_DIFF)` is always `null`: the guard would always be false and the
whole feature a no-op that every unit test still passes. The marker survives by a different route —
`ChainDiffVirtualFile` opens the chain through `CacheDiffRequestChainProcessor(project, chain)`,
whose constructor hands `chain` up as `DiffRequestProcessor`'s `UserDataHolder`; that becomes
`DiffContextOnDataHolders`' initial context, whose `getUserData` falls back to it. `shouldDecorate`
therefore takes the `DiffContext`.

### Cleanup

`core/ReviewCursor.kt` and `ReviewCursorTest.kt` are deleted in full: **no member of `ReviewCursor`
has a caller in `src/main`**. The two *Known, deliberate limitations* bullets leave `README.md`, and
the rename paragraph (`docs/manual-verification.md:278`) and checklist item 17 (`:691`) leave the
verification doc.

## Decisions

### Where the reviewed count is displayed

**ID:** progress-banner-placement
**Status:** active
**Chosen:** A banner above the diff, via `NOTIFICATION_PROVIDERS` — roomy, never truncated, and it
leaves the tab title's position/total meaning intact.
**Considered:** A label on the diff toolbar — competes for width with ten buttons and is squeezed on
a narrow window. The tab title — cheapest, but truncates once several tabs are open and would cost
the position/total distinction the title carries today.

### Which tool windows a pass hides

**ID:** hide-every-visible-window
**Status:** active
**Chosen:** Every visible tool window, swept dynamically — no list to maintain, and a new plugin's
window is covered for free.
**Considered:** A sweep with a keep list (Terminal, Run) — needs maintaining, and the exempted
windows are the ones most likely to distract. Distraction Free / Zen mode — maximum focus, but it
changes a global IDE setting that outlives the project and restores unreliably.

### What the context menu contains

**ID:** popup-menu-contents
**Status:** active
**Chosen:** Per-file actions, then the session controls, then the platform tail minus *Compare with
Clipboard* — the whole toolbar reachable from the pointer, with blame and the rest still available.
**Considered:** Only this plugin's actions — simplest, but loses Annotate and Copy inside the one
diff where a reviewer wants blame. Per-file actions plus the platform tail, without the session
controls — shorter menu, but Scope and End Review stay toolbar-only.

### How the diff extension scopes itself

**ID:** popup-scoping-marker
**Status:** active
**Chosen:** A private marker `Key<Boolean>` stamped on the chain by the presenter — only diffs this
plugin opened are touched, and the check is a field read with no platform-internal casts. Read off
the `DiffContext`; see the correction under *Context menu* for why the request does not carry it.
**Considered:** Asking `ReviewSessionService.isActive` — fewer moving parts, but it claims any diff
opened mid-pass, so opening the Git log during a review would give that diff the review menu too.
Marker plus `isActive` — strictly more correct for a review tab left open after End Review, at the
cost of another coupling; the marker alone was judged sufficient.

### How far rename detection goes

**ID:** renames-all-scopes
**Status:** active
**Chosen:** All three scopes, re-resolving Staged if required — Staged is the scope myflow Gate B
actually uses, so renames working only elsewhere would fix the case nobody hits.
**Considered:** Branch and Range only, leaving Staged documented as a limitation — smaller blast
radius, but it leaves the ticket's actual complaint in place. Adding an `old → new` path display in
the tab title and file list — more legible, but it means changing `ReviewKey`/`ReviewItem` for
presentation.

### Whether the cursor limitation is a fix or a deletion

**ID:** cursor-limitation-is-dead-code
**Status:** active
**Chosen:** A deletion. `ReviewCursor` has no callers in `src/main`; `ReviewSession.settleOn` is the
live path and settles forward, so the documented behaviour cannot occur.
**Considered:** Treating it as a live bug and designing a reproduction — rejected once the call graph
showed there is nothing to reproduce. Deleting plus adding a regression test pinning that a mid-pass
Refresh settles forward — deferred; `ReviewSessionServiceTest` already covers settle-forward.

## Risks / Trade-offs

**Rename detection is the only item that can grow.** Re-resolving Staged replaces the path every
myflow Gate B review depends on. Its blast radius exceeds the other three items combined. The other
three are additive and independently verifiable, so a problem here should not hold them back — the
tasks are ordered to make that separation real.

**Three of the four items are UI that cannot be verified headlessly.** This repository's sandbox has
no display; `runIde` there can only confirm the plugin loads. Whether the banner renders above the
toolbar, whether the sweep leaves a clean screen, and whether the popup opens with Mark Reviewed
first are Gate B checks for a human at a real IDE. `docs/manual-verification.md` gains a section per
item.

**`DiffExtension` is a global EP.** A bug in its guard clause affects every diff in the IDE, not just
this plugin's. The guard is the first statement in `onViewerCreated` and is covered by a test.

## Migration Plan

None required. No stored state changes shape: the layout record is a list of tool-window ids either
way, and review marks keep their existing key and hash format. The single user-visible discontinuity
is the one-time re-offer of renamed files described under *Rename detection*.

## Open Questions

None. The one unknown — which call gives Branch vs Base its rename entries — is an implementation
detail with a fixed acceptance test (`a rename produces one entry keyed to the new path`), resolved
against a repository fixture rather than decided here.
