package dev.tweety.reviewqueue.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.client.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The whole rule, exercised without a platform. `IdeLayoutControllerTest` cannot cover the
 * priority order — it runs one fixture at a time — so the ordering lives here, where every
 * combination is cheap.
 */
class ReviewSessionTargetingTest {

    private fun ref(name: String, type: ClientType) = SessionRef(ClientId(name), type)

    @Test
    fun `frontend wins over controller and local`() {
        val sessions = listOf(
            ref("local", ClientType.LOCAL),
            ref("controller", ClientType.CONTROLLER),
            ref("frontend", ClientType.FRONTEND),
        )

        assertEquals(ClientId("frontend"), ReviewSessionTargeting.hideTarget(sessions)?.clientId)
    }

    @Test
    fun `controller wins over local when there is no frontend`() {
        val sessions = listOf(ref("local", ClientType.LOCAL), ref("controller", ClientType.CONTROLLER))

        assertEquals(ClientId("controller"), ReviewSessionTargeting.hideTarget(sessions)?.clientId)
    }

    @Test
    fun `a plain local installation targets the local session`() {
        val sessions = listOf(ref("local", ClientType.LOCAL))

        assertEquals(ClientId("local"), ReviewSessionTargeting.hideTarget(sessions)?.clientId)
    }

    @Test
    fun `a guest is never the hide target`() {
        val sessions = listOf(ref("guest", ClientType.GUEST))

        assertNull(
            "a guest is another person; their layout is not ours to hide",
            ReviewSessionTargeting.hideTarget(sessions),
        )
    }

    @Test
    fun `no sessions means no target`() {
        assertNull(ReviewSessionTargeting.hideTarget(emptyList()))
    }

    @Test
    fun `restore targets every non-guest session`() {
        val sessions = listOf(
            ref("local", ClientType.LOCAL),
            ref("guest", ClientType.GUEST),
            ref("frontend", ClientType.FRONTEND),
        )

        assertEquals(
            listOf(ClientId("local"), ClientId("frontend")),
            ReviewSessionTargeting.restoreTargets(sessions).map { it.clientId },
        )
    }

    @Test
    fun `restore targets nothing when only a guest is present`() {
        assertEquals(
            emptyList<SessionRef>(),
            ReviewSessionTargeting.restoreTargets(listOf(ref("guest", ClientType.GUEST))),
        )
    }
}
