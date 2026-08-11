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
import social.saigon.domain.User
import social.saigon.identity.IdentityService
import social.saigon.repository.InMemoryRoomIdentityRepository
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories
import social.saigon.repository.UserRepository

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

    private fun usersWith(displayName: String = "Real Name", id: String = "u1"): UserRepository =
        InMemoryUserRepository().also {
            it.create(User(id = id, role = Role.SHARER, displayName = displayName))
        }

    private fun service(
        generator: IdentityGenerator = SequentialIdentityGenerator(),
        users: UserRepository = usersWith(),
    ): IdentityService = IdentityService(InMemoryRoomIdentityRepository(), generator, users)

    @Test
    fun firstJoinStartsWithDisplayNameNotAGeneratedOne() = runBlocking {
        val gen = SequentialIdentityGenerator()
        val svc = service(gen, usersWith("Real Name"))

        // A new room profile starts as the member's main display name — never a random generated one.
        val first = svc.getOrCreate("u1", "room1")
        assertEquals("Real Name", first.name)
        assertTrue(first.avatarUrl.isBlank())
        assertEquals(0, gen.calls)

        // Subsequent joins return the same identity — no time-based/forced reset (DESIGN §5).
        val again = svc.getOrCreate("u1", "room1")
        assertEquals(first, again)
        assertEquals(0, gen.calls)
    }

    @Test
    fun identityIsIndependentPerRoom() = runBlocking {
        val svc = service()
        val coffee = svc.refresh("u1", "coffee")
        val deep = svc.refresh("u1", "deep")

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
    fun updateProfileStoresPerRoomOverridesAndCustomName() = runBlocking {
        val svc = service()
        svc.getOrCreate("u1", "room1")
        val skill = social.saigon.domain.Skill(emoji = "🎸", name = "Guitar", level = 4)
        val updated = svc.updateProfile(
            "u1",
            "room1",
            social.saigon.domain.RoomIdentityUpdate(
                name = "Coffee Nomad",
                interests = listOf("espresso", "vinyl"),
                introduction = "I host vinyl listening nights.",
                skills = listOf(skill),
                intents = listOf("☕ Casual"),
                languages = listOf("vi", "en"),
                offlineSchedule = listOf("weekends"),
            ),
        )
        assertEquals("Coffee Nomad", updated.name)
        assertEquals(listOf("espresso", "vinyl"), updated.interests)
        assertEquals("I host vinyl listening nights.", updated.introduction)
        assertEquals(listOf(skill), updated.skills)
        assertEquals(listOf("☕ Casual"), updated.intents)
        assertEquals(listOf("vi", "en"), updated.languages)
        assertEquals(listOf("weekends"), updated.offlineSchedule)
        // Persisted for subsequent fetches.
        assertEquals(updated, svc.getOrCreate("u1", "room1"))
    }

    @Test
    fun refreshKeepsPerRoomOverridesButRegeneratesName() = runBlocking {
        val svc = service()
        svc.getOrCreate("u1", "room1")
        svc.updateProfile(
            "u1",
            "room1",
            social.saigon.domain.RoomIdentityUpdate(interests = listOf("chess")),
        )
        val refreshed = svc.refresh("u1", "room1")
        // The custom override survives a name/avatar refresh (DESIGN §5).
        assertEquals(listOf("chess"), refreshed.interests)
        assertTrue(refreshed.name.isNotBlank())
    }

    @Test
    fun updateProfileNullClearsOverrideToInherit() = runBlocking {
        val svc = service()
        svc.getOrCreate("u1", "room1")
        svc.updateProfile(
            "u1",
            "room1",
            social.saigon.domain.RoomIdentityUpdate(
                interests = listOf("chess"),
                introduction = "Room intro",
                skills = listOf(social.saigon.domain.Skill(emoji = "♟️", name = "Chess", level = 5)),
            ),
        )
        // A null field clears the override so the facet inherits from the main profile again.
        val cleared = svc.updateProfile("u1", "room1", social.saigon.domain.RoomIdentityUpdate())
        assertEquals(null, cleared.interests)
        assertEquals(null, cleared.introduction)
        assertEquals(null, cleared.skills)
    }

    @Test
    fun updateProfileStoresRoomPhotosAdditively() = runBlocking {
        val svc = service()
        svc.getOrCreate("u1", "room1")
        val photo = social.saigon.domain.ProfilePhoto(url = "https://cdn.saigon.social/x.jpg", message = "hi")
        // A non-null photos list is stored for this room (issue: "Room profile photos").
        val withPhoto = svc.updateProfile(
            "u1",
            "room1",
            social.saigon.domain.RoomIdentityUpdate(photos = listOf(photo)),
        )
        assertEquals(listOf(photo), withPhoto.photos)

        // Photos are additive, not an inherit/override facet: a null photos list leaves the stored
        // photos intact even while another facet is updated.
        val kept = svc.updateProfile(
            "u1",
            "room1",
            social.saigon.domain.RoomIdentityUpdate(interests = listOf("chess")),
        )
        assertEquals(listOf(photo), kept.photos)

        // An explicit empty list wholesale-replaces (removes) the room photos.
        val cleared = svc.updateProfile(
            "u1",
            "room1",
            social.saigon.domain.RoomIdentityUpdate(photos = emptyList()),
        )
        assertTrue(cleared.photos.isEmpty())
    }

    @Test
    fun identityEndpointsStartWithDisplayNameAndRefresh() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val repositories = Repositories()
        repositories.users.create(User(id = "u1", role = Role.SHARER, displayName = "Real Name"))
        val token = TokenService(authConfig).issueMemberToken("u1", Role.SHARER)
        application {
            module(
                repositories = repositories,
                identityGenerator = SequentialIdentityGenerator(),
                authConfig = authConfig,
            )
        }

        val first = client.get("/rooms/room1/identity") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.bodyAsText()
        assertTrue(firstBody.contains("\"roomId\":\"room1\""))
        // New room profile name is the member's display name, not a random generated one.
        assertTrue(firstBody.contains("\"name\":\"Real Name\""), firstBody)

        // Stable on re-fetch.
        val second = client.get("/rooms/room1/identity") { bearerAuth(token) }
        assertEquals(firstBody, second.bodyAsText())

        // Refresh produces a different identity (anonymous name via the generator).
        val refreshed = client.post("/rooms/room1/identity/refresh") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        assertNotEquals(firstBody, refreshed.bodyAsText())
        assertTrue(refreshed.bodyAsText().contains("\"name\":\"Minh\""), refreshed.bodyAsText())
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
