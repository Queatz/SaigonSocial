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
import social.saigon.domain.Room
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

    @Test
    fun roomSearchFiltersOnBackendAndRecordsForFounder() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val founder = TokenService(authConfig).issueMemberToken("f1", Role.FOUNDER)
        val repositories = Repositories()
        repositories.rooms.create(Room("r1", "Coffee Lovers", "☕", "cozy spot", "f1"))
        repositories.rooms.create(Room("r2", "Tech Talk", "💻", "geeky vibes", "f1"))
        application { module(repositories = repositories, authConfig = authConfig) }

        // The backend filters by name, case-insensitively.
        val byName = client.get("/rooms?q=coffee").bodyAsText()
        assertTrue(byName.contains("Coffee Lovers"))
        assertFalse(byName.contains("Tech Talk"))

        // …and by atmosphere.
        val byAtmosphere = client.get("/rooms?q=geeky").bodyAsText()
        assertTrue(byAtmosphere.contains("Tech Talk"))
        assertFalse(byAtmosphere.contains("Coffee Lovers"))

        // A blank query returns every room and is NOT recorded as a search.
        val all = client.get("/rooms").bodyAsText()
        assertTrue(all.contains("Coffee Lovers") && all.contains("Tech Talk"))

        // The non-blank searches were persisted for the founder to review.
        val searches = client.get("/founder/room-searches") { bearerAuth(founder) }.bodyAsText()
        assertTrue(searches.contains("coffee"), "recent searches should include 'coffee'")
        assertTrue(searches.contains("geeky"), "recent searches should include 'geeky'")
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
    fun messagesFeedIsSortedNewestToOldest() = testApplication {
        val repositories = Repositories()
        // Seed three messages out of chronological order to prove the endpoint sorts them.
        repositories.messages.add(
            social.saigon.domain.Message("m2", "room1", "u1", "second", createdAt = 2_000L),
        )
        repositories.messages.add(
            social.saigon.domain.Message("m1", "room1", "u1", "first", createdAt = 1_000L),
        )
        repositories.messages.add(
            social.saigon.domain.Message("m3", "room1", "u1", "third", createdAt = 3_000L),
        )
        val authConfig = AuthConfig(secret = "test-secret")
        application { module(repositories = repositories, authConfig = authConfig) }

        // The wire response is ordered newest→oldest (highest createdAt first).
        val body = client.get("/rooms/room1/messages").bodyAsText()
        val firstIdx = body.indexOf("\"createdAt\":3000")
        val midIdx = body.indexOf("\"createdAt\":2000")
        val lastIdx = body.indexOf("\"createdAt\":1000")
        assertTrue(firstIdx in 0 until midIdx, "newest message must come first")
        assertTrue(midIdx in 0 until lastIdx, "middle message must come before the oldest")
    }

    @Test
    fun messagesEndpointPagesWithLimitAndBeforeCursor() = testApplication {
        val repositories = Repositories()
        // Five messages spanning createdAt 1..5; the database applies the limit + cursor.
        (1..5).forEach { i ->
            repositories.messages.add(
                social.saigon.domain.Message("m$i", "room1", "u1", "msg$i", createdAt = i * 1_000L),
            )
        }
        val authConfig = AuthConfig(secret = "test-secret")
        application { module(repositories = repositories, authConfig = authConfig) }

        // First page: newest two (createdAt 5000, 4000) in newest→oldest order.
        val first = client.get("/rooms/room1/messages?limit=2").bodyAsText()
        assertTrue(first.contains("\"createdAt\":5000"), "first page must include the newest message")
        assertTrue(first.contains("\"createdAt\":4000"), "first page must include the second-newest message")
        assertFalse(first.contains("\"createdAt\":3000"), "first page must be limited to two messages")
        assertTrue(
            first.indexOf("\"createdAt\":5000") < first.indexOf("\"createdAt\":4000"),
            "page must be ordered newest→oldest",
        )

        // Paging upward with the cursor of the oldest message on the first page (4000) yields the
        // next-older two (3000, 2000) — proving the cursor flows API → backend → DB.
        val second = client.get("/rooms/room1/messages?limit=2&before=4000").bodyAsText()
        assertTrue(second.contains("\"createdAt\":3000"), "second page must continue from the cursor")
        assertTrue(second.contains("\"createdAt\":2000"), "second page must include the next-older message")
        assertFalse(second.contains("\"createdAt\":4000"), "the cursor message must be excluded")
        assertFalse(second.contains("\"createdAt\":5000"), "newer messages must not reappear")
    }

    @Test
    fun publicChatReadableByAnyoneAndOccupantsShowIntentsAndLanguages() = testApplication {
        val repositories = Repositories()
        repositories.users.create(
            User(
                id = "u1",
                role = Role.SHARER,
                displayName = "Real Name",
                intents = listOf(Intent.DEEP_TALK),
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
