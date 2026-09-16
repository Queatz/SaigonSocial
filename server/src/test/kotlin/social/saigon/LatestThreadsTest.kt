package social.saigon

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import social.saigon.auth.AuthConfig
import social.saigon.domain.Message
import social.saigon.domain.Role
import social.saigon.domain.Room
import social.saigon.domain.User
import social.saigon.repository.Repositories
import social.saigon.routes.FeedMessage

/**
 * Home-screen "Latest threads": the newest public-chat messages across every community, opened
 * as threads via `/community/{id}/thread/{id}`.
 */
class LatestThreadsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun latestThreadsReturnsFiveNewestAcrossCommunities() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "u1", role = Role.SHARER, displayName = "Alice"))
        repositories.rooms.create(
            Room(id = "cafe", name = "Cafe Chat", emoji = "☕", createdBy = "u1"),
        )
        repositories.rooms.create(
            Room(id = "pho", name = "Pho Lovers", emoji = "🍜", createdBy = "u1"),
        )
        // Six messages so the endpoint has to cap at 5 and drop the oldest.
        repositories.messages.add(
            Message("m1", "cafe", "u1", "cafe 1", createdAt = 1_000L, authorName = "Alice"),
        )
        repositories.messages.add(
            Message("m2", "cafe", "u1", "cafe 2", createdAt = 2_000L, authorName = "Alice"),
        )
        repositories.messages.add(
            Message("m3", "cafe", "u1", "cafe 3", createdAt = 3_000L, authorName = "Alice"),
        )
        repositories.messages.add(
            Message("m4", "cafe", "u1", "cafe 4", createdAt = 4_000L, authorName = "Alice"),
        )
        repositories.messages.add(
            Message("p1", "pho", "u1", "pho 1", createdAt = 5_000L, authorName = "Alice"),
        )
        repositories.messages.add(
            Message("p2", "pho", "u1", "pho 2", createdAt = 6_000L, authorName = "Alice"),
        )
        application { module(repositories = repositories, authConfig = AuthConfig(secret = "test-secret")) }

        val response = client.get("/threads/latest")
        assertEquals(HttpStatusCode.OK, response.status)
        val threads = json.decodeFromString<List<FeedMessage>>(response.bodyAsText())
        assertEquals(5, threads.size)
        assertEquals(listOf("pho 2", "pho 1", "cafe 4", "cafe 3", "cafe 2"), threads.map { it.text })
        assertEquals("p2", threads.first().id)
        assertEquals("pho", threads.first().roomId)
        assertEquals("Pho Lovers", threads.first().roomName)
        assertEquals("🍜", threads.first().roomEmoji)
        assertEquals("Cafe Chat", threads.last().roomName)
        assertTrue(threads.none { it.text == "cafe 1" })
    }

    @Test
    fun latestThreadsIsPublicAndEmptyWithoutMessages() = testApplication {
        val repositories = Repositories()
        application { module(repositories = repositories, authConfig = AuthConfig(secret = "test-secret")) }

        val response = client.get("/threads/latest")
        assertEquals(HttpStatusCode.OK, response.status)
        val threads = json.decodeFromString<List<FeedMessage>>(response.bodyAsText())
        assertEquals(emptyList(), threads)
    }
}
