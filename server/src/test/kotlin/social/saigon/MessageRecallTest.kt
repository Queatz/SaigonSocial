package social.saigon

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.Message
import social.saigon.domain.Role
import social.saigon.domain.ThreadReply
import social.saigon.domain.User
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.Repositories

/**
 * Recalling a public-chat message (issue: "Ability to recall messages"). Only the message's own
 * author or the Founder may recall it; recalling clears the text and flags it recalled, and never
 * touches the message's thread.
 */
class MessageRecallTest {

    private fun seed(): Repositories {
        val repositories = Repositories()
        repositories.users.create(User(id = "author", role = Role.SHARER, displayName = "Author"))
        repositories.users.create(User(id = "other", role = Role.SHARER, displayName = "Other"))
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "Founder"))
        repositories.messages.add(
            Message(
                id = "m1",
                roomId = "room1",
                authorId = "author",
                text = "secret message",
                createdAt = 1_000L,
                authorName = "Curious Otter",
            ),
        )
        // A reply hangs off the message so we can prove the thread survives a recall.
        repositories.threads.add(
            ThreadReply(
                id = "r1",
                parentMessageId = "m1",
                authorId = "other",
                text = "a reply",
                createdAt = 1_100L,
                authorName = "Replier",
            ),
        )
        return repositories
    }

    @Test
    fun authorCanRecallTheirOwnMessageWithoutAffectingTheThread() = testApplication {
        val repositories = seed()
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("author", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val recall = client.post("/rooms/room1/messages/m1/recall") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, recall.status)
        val body = recall.bodyAsText()
        assertTrue(body.contains("\"recalled\":true"))
        assertTrue(body.contains("\"text\":\"\""))

        // The stored message is cleared + flagged.
        val stored = repositories.messages.get("m1")!!
        assertTrue(stored.recalled)
        assertEquals("", stored.text)

        // The thread is untouched: its single reply is still there and readable.
        val thread = client.get("/rooms/room1/messages/m1/thread")
        assertEquals(HttpStatusCode.OK, thread.status)
        assertTrue(thread.bodyAsText().contains("a reply"))

        // The feed now shows the recalled flag.
        val feed = client.get("/rooms/room1/messages")
        assertTrue(feed.bodyAsText().contains("\"recalled\":true"))
    }

    @Test
    fun anotherMemberCannotRecallSomeoneElsesMessage() = testApplication {
        val repositories = seed()
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("other", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val recall = client.post("/rooms/room1/messages/m1/recall") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, recall.status)
        // The message is left intact.
        val stored = repositories.messages.get("m1")!!
        assertFalse(stored.recalled)
        assertEquals("secret message", stored.text)
    }

    @Test
    fun recallIsBroadcastToEveryoneInTheRoom() = testApplication {
        val repositories = seed()
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("author", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val json = Json { ignoreUnknownKeys = true }
        val wsClient = createClient { install(ClientWebSockets) }

        // A bystander is sitting in the room over the live socket; recalling a message must reach
        // them immediately, the same way new replies do (DESIGN §6, §9).
        wsClient.webSocket("/ws") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            incoming.receive() // Joined

            val recall = client.post("/rooms/room1/messages/m1/recall") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, recall.status)

            val frame = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(frame is Outgoing.Recalled)
            frame as Outgoing.Recalled
            assertEquals("room1", frame.roomId)
            assertEquals("m1", frame.messageId)
        }
    }

    @Test
    fun founderCanRecallAnyMessage() = testApplication {
        val repositories = seed()
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val recall = client.post("/rooms/room1/messages/m1/recall") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, recall.status)
        assertTrue(repositories.messages.get("m1")!!.recalled)
    }
}
