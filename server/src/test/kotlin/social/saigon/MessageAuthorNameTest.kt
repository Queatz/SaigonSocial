package social.saigon

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import social.saigon.auth.AuthConfig
import social.saigon.domain.Message
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.repository.Repositories

/**
 * Messages store the author's room-local name at post time and display *that* (round-2 issue), so a
 * later identity refresh never rewrites the name shown on a past post.
 */
class MessageAuthorNameTest {

    @Test
    fun feedShowsTheNameStoredOnTheMessageNotALiveLookup() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "u1", role = Role.SHARER, displayName = "Real Name"))
        // A message whose author-name was captured at post time.
        repositories.messages.add(
            Message(
                id = "m1",
                roomId = "room1",
                authorId = "u1",
                text = "pho place in D1?",
                createdAt = 1_000L,
                authorName = "Curious Otter",
            ),
        )
        application { module(repositories = repositories, authConfig = AuthConfig(secret = "test-secret")) }

        val feed = client.get("/rooms/room1/messages")
        assertEquals(HttpStatusCode.OK, feed.status)
        val body = feed.bodyAsText()
        // The stored name is shown; the raw id is never surfaced as the author name.
        assertTrue(body.contains("\"authorName\":\"Curious Otter\""))
    }
}
