package social.saigon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import social.saigon.ai.LocalIdentityGenerator
import social.saigon.auth.AuthConfig
import social.saigon.auth.Claims
import social.saigon.auth.TokenService
import social.saigon.domain.Role
import social.saigon.realtime.ChatHub
import social.saigon.realtime.ClientSession
import social.saigon.realtime.Outgoing

class Phase0Test {

    private val tokenService = TokenService(AuthConfig(secret = "test-secret"))

    @Test
    fun roleCapabilityGates() {
        assertTrue(!Role.SEEKER.canChatPublicly)
        assertTrue(Role.SHARER.canChatPublicly)
        assertTrue(Role.SUPPORTER.isPrivatelyMessageable)
        assertTrue(!Role.SHARER.isPrivatelyMessageable)
        assertTrue(Role.FOUNDER.isAdmin)
    }

    @Test
    fun memberTokenRoundTrips() {
        val token = tokenService.issueMemberToken("u1", Role.SHARER)
        val decoded = tokenService.verifier.verify(token)
        assertEquals("u1", decoded.getClaim(Claims.USER_ID).asString())
        assertEquals("SHARER", decoded.getClaim(Claims.ROLE).asString())
    }

    @Test
    fun seekerTokenHasNoUserId() {
        val token = tokenService.issueSeekerToken("device-123")
        val decoded = tokenService.verifier.verify(token)
        assertEquals("SEEKER", decoded.getClaim(Claims.ROLE).asString())
        assertEquals("device-123", decoded.getClaim(Claims.DEVICE_ID).asString())
        assertTrue(decoded.getClaim(Claims.USER_ID).isNull || decoded.getClaim(Claims.USER_ID).isMissing)
    }

    @Test
    fun chatHubTracksPresenceAndBroadcasts() = runBlocking {
        val hub = ChatHub()
        val received = mutableListOf<Outgoing>()
        val a = session("a", received)
        val b = session("b", mutableListOf())
        hub.register(a)
        hub.register(b)

        assertEquals(1, hub.join("room1", "a"))
        assertEquals(2, hub.join("room1", "b"))
        hub.broadcast("room1", Outgoing.Said("room1", "a", "hi", 0))
        assertEquals(1, received.size)

        assertEquals(1, hub.leave("room1", "b"))
        hub.unregister("a")
        assertTrue(hub.isEmpty("room1"))
    }

    @Test
    fun identityGeneratorProducesNameAndAvatar() = runBlocking {
        val gen = LocalIdentityGenerator()
        val id = gen.generate("user@room")
        assertTrue(id.name.isNotBlank())
        assertNotNull(id.avatarUrl)
        assertTrue(id.avatarUrl.endsWith(".svg"))
    }

    private fun session(id: String, sink: MutableList<Outgoing>) = object : ClientSession {
        override val connectionId = id
        override suspend fun send(frame: Outgoing) {
            sink.add(frame)
        }
    }
}
