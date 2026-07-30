package dev.tweety.reviewqueue.ui

import com.intellij.openapi.client.ClientKind
import com.intellij.openapi.client.ClientSessionsManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus

/**
 * The project's client sessions, as [SessionRef]s.
 *
 * This exists to be replaced. `IdeLayoutControllerTest` runs against `RecordingToolWindowManager`,
 * a manager that always answers the way the controller expects — a fixture that cannot, by
 * construction, reproduce a `ClientId` defect. Swapping this service for one returning fabricated
 * sessions is what lets a test assert which session the sweep ran as, which is the defect KAN-18
 * describes stated as an assertion.
 *
 * `open` for that reason, and for that reason only.
 */
internal open class ReviewClientSessions(private val project: Project) {

    /** Every session of this project, guests included; [ReviewSessionTargeting] does the filtering. */
    open fun sessions(): List<SessionRef> =
        ClientSessionsManager.getProjectSessions(project, ClientKind.ALL)
            .map { SessionRef(it.clientId, it.type) }

    /**
     * The message bus to watch [session] on, or `null` when [session] has gone away since
     * [sessions] was read.
     *
     * **Deliberately the project's bus, not the session's.** `design.md` assumed a session-scoped
     * bus ("`ClientSession` extends `ComponentManager` and exposes `getMessageBus()`"), and on
     * IU-2026.2 that is not true: `ClientSession.messageBus` is `@Deprecated(level = ERROR)` with
     * the message "sessions don't have their own message bus", and its only implementation,
     * `ClientSessionImpl.getMessageBus()`, is `final` and throws `IllegalStateException` on every
     * call — `ClientSessionImpl.isMessageBusSupported()` returns `false`. There is no per-session
     * bus to subscribe to. Both facts were read with `javap -c` from
     * `intellij.platform.core.jar` / `intellij.platform.ide.impl.jar`.
     *
     * What survives from that design decision is the part that is actually load-bearing for the
     * re-show watch: the lookup below still fails when [session] is gone, so a watch is only ever
     * armed for a session that still exists. The narrowing to "only events from the swept layout"
     * does not, and the watch has to filter events itself instead of relying on the bus to do it.
     */
    open fun messageBus(session: SessionRef): MessageBus? =
        ClientSessionsManager.getProjectSession(project, session.clientId)?.project?.messageBus

    /**
     * Owns the production [ReviewClientSessions]. Final, and holding rather than being the thing it
     * holds, because the platform will not resolve [ReviewClientSessions] itself as a service:
     * `LightServiceInstanceSupportKt.isLightService` is `Modifier.isFinal(cls) &&
     * cls.isAnnotationPresent(Service)`, so a `@Service`-annotated **open** class is never
     * registered, and `ComponentManagerImpl.doGetService` then throws
     * `PluginException("Light service class … must be final")` on the very first `getInstance` call.
     * That was confirmed on IU-2026.2 both from the bytecode and by resolving it in a
     * `HeavyPlatformTestCase`. `ReviewClientSessions` has to stay open — being replaceable is its
     * entire purpose — so the annotation moves here instead.
     */
    @Service(Service.Level.PROJECT)
    private class Default(project: Project) {
        val instance: ReviewClientSessions = ReviewClientSessions(project)
    }

    companion object {
        /**
         * The [ReviewClientSessions] for [project]: whatever a test registered, else the real one.
         *
         * `getServiceIfCreated` rather than `service()` on the first branch on purpose. It is the
         * one lookup that answers "did someone register an instance under this key" without also
         * demanding that the platform be able to *construct* one — which for an open class it
         * cannot. `ServiceContainerUtil.replaceService(ReviewClientSessions::class.java, …)` puts a
         * created instance under exactly that key, so a test's fake wins here; in production nothing
         * ever does, the branch is null, and the real instance comes from [Default].
         */
        fun getInstance(project: Project): ReviewClientSessions =
            project.getServiceIfCreated(ReviewClientSessions::class.java)
                ?: project.service<Default>().instance
    }
}
