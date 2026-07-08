package social.saigon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import social.saigon.ai.GeneratedIdentity
import social.saigon.ai.IdentityGenerator
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.ProfilePhoto
import social.saigon.domain.Role
import social.saigon.domain.RoomIdentityUpdate
import social.saigon.domain.User
import social.saigon.identity.IdentityService
import social.saigon.profile.ProfileService
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories

/**
 * Profile photos (issue: "Profile and room profile photos"): uploading a new photo makes it the
 * main one and rotates the previous main photo into the "previous photos" list; re-saving the same
 * URL just updates its caption; previous photos can be deleted individually.
 */
class ProfilePhotoTest {

    private fun service(): Pair<ProfileService, InMemoryUserRepository> {
        val repo = InMemoryUserRepository()
        return ProfileService(repo) to repo
    }

    private fun sharer(repo: InMemoryUserRepository, id: String = "u1"): User =
        repo.create(User(id = id, role = Role.SHARER, displayName = "Real Name"))

    @Test
    fun newPhotoBecomesMainAndRotatesThePreviousOneIntoHistory() {
        val (svc, repo) = service()
        sharer(repo)

        val first = svc.setPhoto("u1", "https://content.local/content/a.jpg", "First!")
        assertEquals("https://content.local/content/a.jpg", first.photo?.url)
        assertEquals("First!", first.photo?.message)
        assertTrue(first.previousPhotos.isEmpty())

        val second = svc.setPhoto("u1", "https://content.local/content/b.jpg", "Second!")
        assertEquals("https://content.local/content/b.jpg", second.photo?.url)
        // The old main photo moved to the head of the previous photos, caption intact.
        assertEquals(listOf("https://content.local/content/a.jpg"), second.previousPhotos.map { it.url })
        assertEquals("First!", second.previousPhotos.single().message)
    }

    @Test
    fun savingTheSameUrlOnlyUpdatesTheCaption() {
        val (svc, repo) = service()
        sharer(repo)
        svc.setPhoto("u1", "https://content.local/content/a.jpg", "Old caption")
        val updated = svc.setPhoto("u1", "https://content.local/content/a.jpg", "New caption")
        assertEquals("New caption", updated.photo?.message)
        // No rotation happened.
        assertTrue(updated.previousPhotos.isEmpty())
    }

    @Test
    fun previousPhotosCanBeDeletedIndividually() {
        val (svc, repo) = service()
        sharer(repo)
        svc.setPhoto("u1", "https://content.local/content/a.jpg", "")
        svc.setPhoto("u1", "https://content.local/content/b.jpg", "")
        svc.setPhoto("u1", "https://content.local/content/c.jpg", "")

        val afterDelete = svc.deletePhoto("u1", "https://content.local/content/a.jpg")
        assertEquals(listOf("https://content.local/content/b.jpg"), afterDelete.previousPhotos.map { it.url })
        // The main photo is untouched.
        assertEquals("https://content.local/content/c.jpg", afterDelete.photo?.url)
    }

    @Test
    fun deletingAnUnknownOrCurrentPhotoIsRejected() {
        val (svc, repo) = service()
        sharer(repo)
        svc.setPhoto("u1", "https://content.local/content/a.jpg", "")
        // The current main photo is not deletable — only replaceable.
        assertFailsWith<IllegalArgumentException> {
            svc.deletePhoto("u1", "https://content.local/content/a.jpg")
        }
        assertFailsWith<IllegalArgumentException> {
            svc.deletePhoto("u1", "https://content.local/content/nope.jpg")
        }
    }

    @Test
    fun blankPhotoUrlIsRejected() {
        val (svc, repo) = service()
        sharer(repo)
        assertFailsWith<IllegalArgumentException> { svc.setPhoto("u1", "  ", "caption") }
    }

    @Test
    fun photoEndpointsRoundTripOverHttp() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val repositories = Repositories()
        repositories.users.create(User(id = "u1", role = Role.SHARER, displayName = "Real Name"))
        val token = TokenService(authConfig).issueMemberToken("u1", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Set the first photo with a caption.
        val set = client.post("/profile/photo") {
            bearerAuth(token)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://content.local/content/a.jpg","message":"Hello!"}""")
        }
        assertEquals(HttpStatusCode.OK, set.status)
        assertTrue(set.bodyAsText().contains("\"url\":\"https://content.local/content/a.jpg\""))
        assertTrue(set.bodyAsText().contains("\"message\":\"Hello!\""))

        // A second upload rotates the first into previousPhotos.
        client.post("/profile/photo") {
            bearerAuth(token)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://content.local/content/b.jpg","message":""}""")
        }
        val me = client.get("/profile/me") { bearerAuth(token) }.bodyAsText()
        assertTrue(me.contains("\"url\":\"https://content.local/content/b.jpg\""))
        assertTrue(me.contains("\"url\":\"https://content.local/content/a.jpg\""))

        // Delete the previous photo by URL.
        val delete = client.delete("/profile/photos") {
            bearerAuth(token)
            parameter("url", "https://content.local/content/a.jpg")
        }
        assertEquals(HttpStatusCode.OK, delete.status)
        val after = client.get("/profile/me") { bearerAuth(token) }.bodyAsText()
        assertFalse(after.contains("a.jpg"))

        // Photo changes require a signed-in member.
        val anonymous = client.post("/profile/photo") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://content.local/content/c.jpg"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
    }

    @Test
    fun userStartsWithoutAnyPhoto() {
        val (svc, repo) = service()
        val user = sharer(repo)
        assertNull(user.photo)
        assertTrue(user.previousPhotos.isEmpty())
        assertNull(svc.get("u1").photo)
    }

    /** A trivial generator: the room-first photo test never refreshes an identity, so this is unused. */
    private class NoopIdentityGenerator : IdentityGenerator {
        override suspend fun generate(seed: String) = GeneratedIdentity(name = "", avatarUrl = "")
    }

    @Test
    fun occupantPhotoPrefersTheRoomProfilePhotoThenFallsBackToTheMainProfile() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val repositories = Repositories()
        // u1 has both a main photo and a room-scoped photo; u2 has only a main photo.
        repositories.users.create(User(id = "u1", role = Role.SHARER, displayName = "One"))
        repositories.users.create(User(id = "u2", role = Role.SHARER, displayName = "Two"))
        ProfileService(repositories.users).setPhoto("u1", "https://content.local/content/main1.jpg", "")
        ProfileService(repositories.users).setPhoto("u2", "https://content.local/content/main2.jpg", "")
        // Store a room-scoped photo for u1 in room1 via the same identity repo the server uses.
        val identities = IdentityService(repositories.identities, NoopIdentityGenerator())
        runBlocking {
            identities.updateProfile(
                "u1",
                "room1",
                RoomIdentityUpdate(
                    photos = listOf(ProfilePhoto(url = "https://content.local/content/room1.jpg", message = "")),
                ),
            )
        }
        application { module(repositories = repositories, authConfig = authConfig) }

        // u1: the room photo wins over the main profile photo (issue: room-first, else fall back).
        val one = client.get("/rooms/room1/occupants/u1").bodyAsText()
        assertTrue(one.contains("\"photoUrl\":\"https://content.local/content/room1.jpg\""), one)
        assertFalse(one.contains("\"photoUrl\":\"https://content.local/content/main1.jpg\""), one)

        // u2: no room photo, so the avatar falls back to the main profile photo.
        val two = client.get("/rooms/room1/occupants/u2").bodyAsText()
        assertTrue(two.contains("\"photoUrl\":\"https://content.local/content/main2.jpg\""), two)
    }
}
