package social.saigon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import social.saigon.ai.GeneratedIdentity
import social.saigon.ai.IdentityGenerator
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.Role
import social.saigon.identity.IdentityService
import social.saigon.repository.InMemoryRoomIdentityRepository

class Phase2Test {

    /** Deterministic, monotonic generator so refreshes are observably distinct in tests. */
    private class SequentialIdentityGenerator : IdentityGenerator {
        private val names = listOf("Minh", "Linh", "Alex", "Nam", "Mai")
        var calls = 0
            private set

        override suspend fun generate(seed: String): GeneratedIdentity {
            val n = calls++
            return GeneratedIdentity(
                name = names[n % names.size],
                avatarUrl = "https://avatars.saigon.social/sketch/$seed-$n.svg",
            )
        }
    }

    private fun service(generator: IdentityGenerator = SequentialIdentityGenerator()): IdentityService =
        IdentityService(InMemoryRoomIdentityRepository(), generator)

    @Test
    fun generatesIdentityOnFirstJoinAndKeepsItStable() = runBlocking {
        val gen = SequentialIdentityGenerator()
        val svc = service(gen)

        val first = svc.getOrCreate("u1", "room1")
        assertTrue(first.name.isNotBlank())
        assertTrue(first.avatarUrl.endsWith(".svg"))
        assertEquals(1, gen.calls)

        // Subsequent joins return the same identity — no time-based/forced reset (DESIGN §5).
        val again = svc.getOrCreate("u1", "room1")
        assertEquals(first, again)
        assertEquals(1, gen.calls)
    }

    @Test
    fun identityIsIndependentPerRoom() = runBlocking {
        val svc = service()
        val coffee = svc.getOrCreate("u1", "coffee")
        val deep = svc.getOrCreate("u1", "deep")

        assertEquals("coffee", coffee.roomId)
        assertEquals("deep", deep.roomId)
        // Same user, different rooms → independent identities.
        assertNotEquals(coffee.avatarUrl, deep.avatarUrl)
    }

    @Test
    fun refreshRegeneratesInstantly() = runBlocking {
        val svc = service()
        val before = svc.getOrCreate("u1", "room1")
        val after = svc.refresh("u1", "room1")

        assertNotEquals(before.avatarUrl, after.avatarUrl)
        // The refreshed identity is now the stable one for this room.
        assertEquals(after, svc.getOrCreate("u1", "room1"))
    }

    @Test
    fun identityEndpointsGenerateAndRefresh() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("u1", Role.SHARER)
        application {
            module(
                identityGenerator = SequentialIdentityGenerator(),
                authConfig = authConfig,
            )
        }

        val first = client.get("/rooms/room1/identity") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.bodyAsText()
        assertTrue(firstBody.contains("\"roomId\":\"room1\""))

        // Stable on re-fetch.
        val second = client.get("/rooms/room1/identity") { bearerAuth(token) }
        assertEquals(firstBody, second.bodyAsText())

        // Refresh produces a different identity.
        val refreshed = client.post("/rooms/room1/identity/refresh") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        assertNotEquals(firstBody, refreshed.bodyAsText())
    }

    @Test
    fun seekerCannotClaimRoomIdentity() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val seekerToken = TokenService(authConfig).issueSeekerToken("device-1")
        application {
            module(authConfig = authConfig)
        }

        val response = client.get("/rooms/room1/identity") { bearerAuth(seekerToken) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
