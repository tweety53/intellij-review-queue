package dev.tweety.reviewqueue.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.client.ClientType

/** One client session of the project, reduced to the two facts this plugin needs about it. */
internal data class SessionRef(val clientId: ClientId, val type: ClientType)

/**
 * Which client session a review pass acts on.
 *
 * Under a split or remote-dev IDE the project has several sessions, and `ToolWindowManager`'s
 * `getToolWindow`/`hideToolWindow` both address whichever one the current `ClientId` names. Running
 * with no client id in scope addresses the local session — which has no screen — so the reviewer's
 * windows are never told to hide. That is KAN-18.
 *
 * No built-in `ClientKind` expresses either rule below: `ClientKind.OWNER` is `LOCAL ∪ CONTROLLER`
 * and excludes `FRONTEND`, and `ClientKind.REMOTE` is `CONTROLLER ∪ GUEST`. Hence the hand-written
 * filters.
 */
internal object ReviewSessionTargeting {

    /**
     * The one session whose layout the reviewer is looking at, or `null` when there is none.
     *
     * Exactly one, because visibility must be measured in the same layout that is mutated: taking
     * the union across sessions would hide a window that is visible in one and closed in another,
     * and a later restore would reopen it where the user had closed it.
     */
    fun hideTarget(sessions: List<SessionRef>): SessionRef? =
        sessions.firstOrNull { it.type == ClientType.FRONTEND }
            ?: sessions.firstOrNull { it.type == ClientType.CONTROLLER }
            ?: sessions.firstOrNull { it.type == ClientType.LOCAL }

    /**
     * Every session that could be showing the reviewer's layout.
     *
     * Broader than [hideTarget] on purpose. Restoring is bounded by the record — it only ever shows
     * ids a pass already hid — so breadth cannot open a window the sweep did not hide. It is what
     * makes the quit-mid-pass path work when [ReviewLayoutRestorer] runs before the frontend session
     * has attached.
     */
    fun restoreTargets(sessions: List<SessionRef>): List<SessionRef> =
        sessions.filter { it.type != ClientType.GUEST }
}
