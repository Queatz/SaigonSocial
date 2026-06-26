package social.saigon

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.privatechat.PrivateChatService
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.InMemoryPrivateChatRepository
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories

class Phase5Test {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Supporter-only availability + start gating (DESIGN §7) ---

    @Test
    fun onlySupportersAreAvailableAndStartRequiresAnAvailableTarget() {
        val users = InMemoryUserRepository()
        users.create(User(id = "sharer", role = Role.SHARER, displayName = "Sharer"))
        users.create(User(id = "supporter", role = Role.SUPPORTER, displayName = "Supporter"))
        users.create(User(id = "seeker-like", role = Role.SEEKER, displayName = "x"))
        val svc = PrivateChatService(InMemoryPrivateChatRepository(), users)

        // Only Supporters (and the Founder) are available for private chat.
        assertTrue(svc.isAvailable("supporter"))
        assertTrue(!svc.isAvailable("sharer"))
        assertTrue(!svc.isAvailable("unknown"))

        // A Sharer can start a chat with an available Supporter.
        val chat = svc.start("sharer", "supporter")
        assertEquals("sharer", chat.initiatorId)
        assertEquals("supporter", chat.targetId)

        // A Sharer is not privately messageable — starting against one fails.
        assertFailsWith<IllegalArgumentException> { svc.start("supporter", "sharer") }
        // Cannot chat with yourself; Seekers cannot start.
        assertFailsWith<IllegalArgumentException> { svc.start("supporter", "supporter") }
        assertFailsWith<IllegalArgumentException> { svc.start("seeker-like", "supporter") }
    }

    // --- Expired Supporter subscriptions are not available (DESIGN §3, §7) ---

    @Test
    fun expiredSupporterIsNotAvailable() {
        val users = InMemoryUserRepository()
        var now = 1_000L
        users.create(User(id = "active", role = Role.SUPPORTER, displayName = "A", supporterUntil = 2_000L))
        users.create(User(id = "expired", role = Role.SUPPORTER, displayName = "E", supporterUntil = 500L))
        val svc = PrivateChatService(InMemoryPrivateChatRepository(), users, clock = { now })

        assertTrue(svc.isAvailable("active"))
        assertTrue(!svc.isAvailable("expired"))
    }

    // --- Ephemeral history + end-by-either-party (DESIGN §7) ---

    @Test
    fun messagesAreEphemeralAndEitherPartyCanEnd() {
        val users = InMemoryUserRepository()
        users.create(User(id = "a", role = Role.SHARER, displayName = "A"))
        users.create(User(id = "b", role = Role.SUPPORTER, displayName = "B"))
        val repo = InMemoryPrivateChatRepository()
        val svc = PrivateChatService(repo, users)

        val chat = svc.start("a", "b")
        svc.post(chat.id, "a", "hello")
        svc.post(chat.id, "b", "hi there")
        assertEquals(2, repo.messages(chat.id).size)

        // Only participants may post; plain text is enforced.
        assertFailsWith<IllegalArgumentException> { svc.post(chat.id, "stranger", "hi") }
        assertFailsWith<IllegalArgumentException> { svc.post(chat.id, "a", "bad\nframe") }

        // Either party can end instantly; the target (B) ends here.
        val ended = svc.end(chat.id, "b")
        assertEquals(chat.id, ended?.id)

        // No history survives the chat (DESIGN §7).
        assertNull(svc.get(chat.id))
        assertTrue(repo.messages(chat.id).isEmpty())
        // Posting to an ended chat is rejected.
        assertFailsWith<IllegalArgumentException> { svc.post(chat.id, "a", "still there?") }
    }

    // --- "Available for chat" indicator surfaces in the room occupant list (DESIGN §7) ---

    @Test
    fun availableIndicatorShowsForSupportersInRoom() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "sup", role = Role.SUPPORTER, displayName = "Sup"))
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("sup", Role.SUPPORTER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$token") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            incoming.receive() // Joined

            val occupants = wsClient.get("/rooms/room1/occupants")
            assertEquals(HttpStatusCode.OK, occupants.status)
            assertTrue(occupants.bodyAsText().contains("\"available\":true"))
        }
    }

    // --- End-to-end private chat over WebSockets between two members (DESIGN §7) ---

    @Test
    fun privateChatStartsRelaysAndEndsOverWebSockets() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "alice", role = Role.SHARER, displayName = "Alice"))
        repositories.users.create(User(id = "bob", role = Role.SUPPORTER, displayName = "Bob"))
        val authConfig = AuthConfig(secret = "test-secret")
        val tokens = TokenService(authConfig)
        val aliceToken = tokens.issueMemberToken("alice", Role.SHARER)
        val bobToken = tokens.issueMemberToken("bob", Role.SUPPORTER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        // Bob connects first so he can receive frames routed to his user id.
        wsClient.webSocket("/ws?token=$bobToken") {
            val bobIncoming = incoming

            wsClient.webSocket("/ws?token=$aliceToken") {
                // Alice taps Bob's name in room1 to start a private chat.
                send(
                    Frame.Text(
                        json.encodeToString(Incoming.serializer(), Incoming.StartPrivateChat("room1", "bob")),
                    ),
                )

                val aliceStarted = json.decodeFromString(
                    Outgoing.serializer(),
                    (incoming.receive() as Frame.Text).readText(),
                )
                assertTrue(aliceStarted is Outgoing.PrivateChatStarted)
                aliceStarted as Outgoing.PrivateChatStarted
                assertEquals("bob", aliceStarted.peerId)
                val chatId = aliceStarted.chatId

                // Bob is notified of the same chat, with Alice as his peer.
                val bobStarted = json.decodeFromString(
                    Outgoing.serializer(),
                    (bobIncoming.receive() as Frame.Text).readText(),
                )
                assertTrue(bobStarted is Outgoing.PrivateChatStarted)
                assertEquals(chatId, (bobStarted as Outgoing.PrivateChatStarted).chatId)
                assertEquals("alice", bobStarted.peerId)

                // Alice sends a private message; both parties receive it.
                send(
                    Frame.Text(
                        json.encodeToString(Incoming.serializer(), Incoming.SayPrivate(chatId, "hey Bob")),
                    ),
                )
                val aliceSaw = json.decodeFromString(
                    Outgoing.serializer(),
                    (incoming.receive() as Frame.Text).readText(),
                )
                assertTrue(aliceSaw is Outgoing.PrivateMessageSaid)
                assertEquals("hey Bob", (aliceSaw as Outgoing.PrivateMessageSaid).text)
                val bobSaw = json.decodeFromString(
                    Outgoing.serializer(),
                    (bobIncoming.receive() as Frame.Text).readText(),
                )
                assertTrue(bobSaw is Outgoing.PrivateMessageSaid)
                assertEquals("hey Bob", (bobSaw as Outgoing.PrivateMessageSaid).text)

                // Either party can end instantly — Bob ends; both are notified.
                send(
                    Frame.Text(
                        json.encodeToString(Incoming.serializer(), Incoming.EndPrivateChat(chatId)),
                    ),
                )
                val aliceEnded = json.decodeFromString(
                    Outgoing.serializer(),
                    (incoming.receive() as Frame.Text).readText(),
                )
                assertTrue(aliceEnded is Outgoing.PrivateChatEnded)
                assertEquals(chatId, (aliceEnded as Outgoing.PrivateChatEnded).chatId)
                val bobEnded = json.decodeFromString(
                    Outgoing.serializer(),
                    (bobIncoming.receive() as Frame.Text).readText(),
                )
                assertTrue(bobEnded is Outgoing.PrivateChatEnded)
                assertEquals(chatId, (bobEnded as Outgoing.PrivateChatEnded).chatId)

                // Ending opens the closing-emoji ritual (DESIGN §8): both get a prompt next.
                val alicePrompt = json.decodeFromString(
                    Outgoing.serializer(),
                    (incoming.receive() as Frame.Text).readText(),
                )
                assertTrue(alicePrompt is Outgoing.FinalEmojiPrompt)
                val bobPrompt = json.decodeFromString(
                    Outgoing.serializer(),
                    (bobIncoming.receive() as Frame.Text).readText(),
                )
                assertTrue(bobPrompt is Outgoing.FinalEmojiPrompt)

                // After ending, the chat is gone — sending to it is rejected.
                send(
                    Frame.Text(
                        json.encodeToString(Incoming.serializer(), Incoming.SayPrivate(chatId, "still there?")),
                    ),
                )
                val rejected = json.decodeFromString(
                    Outgoing.serializer(),
                    (incoming.receive() as Frame.Text).readText(),
                )
                assertTrue(rejected is Outgoing.Error)
            }
        }
    }

    // --- A Seeker (no member id) cannot start a private chat (DESIGN §7) ---

    @Test
    fun seekerCannotStartPrivateChat() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        application { module(authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws") {
            send(
                Frame.Text(
                    json.encodeToString(Incoming.serializer(), Incoming.StartPrivateChat("room1", "someone")),
                ),
            )
            val reply = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(reply is Outgoing.Error)
        }
    }
}
