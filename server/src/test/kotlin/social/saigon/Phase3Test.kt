package social.saigon

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.Intent
import social.saigon.domain.PlainText
import social.saigon.domain.Role
import social.saigon.domain.RoomCreate
import social.saigon.domain.User
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.InMemoryRoomRepository
import social.saigon.repository.Repositories
import social.saigon.room.RoomService

class Phase3Test {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Room model + founder-only creation (DESIGN §6) ---

    @Test
    fun roomServiceValidatesNameAndEmoji() {
        val svc = RoomService(InMemoryRoomRepository())

        val room = svc.create("founder", RoomCreate(name = "  Coffee Chat  ", emoji = " ☕ ", atmosphere = " cozy "))
        assertEquals("Coffee Chat", room.name)
        assertEquals("☕", room.emoji)
        assertEquals("cozy", room.atmosphere)
        assertEquals("founder", room.createdBy)

        // No leading '#' (DESIGN §6).
        assertFailsWith<IllegalArgumentException> { svc.create("founder", RoomCreate(name = "#nope", emoji = "x")) }
        // Name and emoji are required.
        assertFailsWith<IllegalArgumentException> { svc.create("founder", RoomCreate(name = "   ", emoji = "x")) }
        assertFailsWith<IllegalArgumentException> { svc.create("founder", RoomCreate(name = "ok", emoji = "  ")) }
    }

    // --- Plain-text-only enforcement (DESIGN §2, §6) ---

    @Test
    fun plainTextRejectsControlCharsBlankAndOverlong() {
        assertTrue(PlainText.isValid("Anyone know a good pho place in D1?"))
        assertFalse(PlainText.isValid("   "))
        assertFalse(PlainText.isValid("line1\nline2")) // newline is a control char
        assertFalse(PlainText.isValid("x".repeat(PlainText.MAX_LENGTH + 1)))
    }

    @Test
    fun founderOnlyRoomCreationAndLiveList() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val tokens = TokenService(authConfig)
        val founder = tokens.issueMemberToken("f1", Role.FOUNDER)
        val sharer = tokens.issueMemberToken("u1", Role.SHARER)
        application { module(authConfig = authConfig) }

        // Sharers cannot create rooms — founder only.
        val denied = client.post("/rooms") {
            bearerAuth(sharer)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Coffee Chat","emoji":"☕"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, denied.status)

        // Names must not start with '#'.
        val badName = client.post("/rooms") {
            bearerAuth(founder)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#bad","emoji":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badName.status)

        // Founder can create.
        val created = client.post("/rooms") {
            bearerAuth(founder)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Coffee Chat","emoji":"☕","atmosphere":"cozy"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)

        // The public room list reflects it (open viewing for all roles).
        val list = client.get("/rooms").bodyAsText()
        assertTrue(list.contains("Coffee Chat"))
        assertTrue(list.contains("cozy"))
        assertTrue(list.contains("\"occupants\":0"))
    }

    // --- Public chat: read-only Seekers, members can send (DESIGN §6) ---

    @Test
    fun seekerIsReadOnlyAndMemberCanSendPlainText() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val memberToken = TokenService(authConfig).issueMemberToken("u1", Role.SHARER)
        application { module(authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        // Seeker (no token) is read-only: a send attempt is rejected.
        wsClient.webSocket("/ws") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "hi"))))
            val reply = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(reply is Outgoing.Error)
        }

        // Member joins, sends plain text, and the broadcast is fanned back to the room.
        wsClient.webSocket("/ws?token=$memberToken") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            assertTrue(
                json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
                    is Outgoing.Joined,
            )

            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "hello Saigon"))))
            val said = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(said is Outgoing.Said)
            assertEquals("hello Saigon", (said as Outgoing.Said).text)
            assertEquals("u1", said.authorId)

            // Plain-text-only: control characters are rejected.
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "bad\nframe"))))
            val rejected = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(rejected is Outgoing.Error)
        }
    }

    @Test
    fun publicChatReadableByAnyoneAndOccupantsShowIntentsAndLanguages() = testApplication {
        val repositories = Repositories()
        repositories.users.create(
            User(
                id = "u1",
                role = Role.SHARER,
                displayName = "Real Name",
                intent = Intent.DEEP_TALK,
                languages = listOf("vi", "en"),
            ),
        )
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("u1", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$token") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            incoming.receive() // Joined
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "first message"))))
            incoming.receive() // Said

            // Anyone (incl. Seekers, no auth) can read a room's public chat.
            val messages = client.get("/rooms/room1/messages")
            assertEquals(HttpStatusCode.OK, messages.status)
            assertTrue(messages.bodyAsText().contains("first message"))

            // Occupant list shows intents + languages next to each person.
            val occupants = client.get("/rooms/room1/occupants")
            assertEquals(HttpStatusCode.OK, occupants.status)
            val body = occupants.bodyAsText()
            assertTrue(body.contains("DEEP_TALK"))
            assertTrue(body.contains("\"vi\""))
            assertTrue(body.contains("\"en\""))
        }
    }
}
