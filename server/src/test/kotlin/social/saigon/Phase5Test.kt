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

    // --- Recipient can open the chat the initiator created, even against a Sharer (DESIGN §7) ---

    @Test
    fun recipientCanOpenExistingChatWithUnavailableInitiator() {
        val users = InMemoryUserRepository()
        users.create(User(id = "sharer", role = Role.SHARER, displayName = "Sharer"))
        users.create(User(id = "supporter", role = Role.SUPPORTER, displayName = "Supporter"))
        val svc = PrivateChatService(InMemoryPrivateChatRepository(), users)

        // The Sharer taps the Supporter and creates the chat.
        val created = svc.openOrStart("sharer", "supporter")

        // The Supporter (recipient) opens the chat by tapping the Sharer's name. Although a plain
        // start would reject the Sharer as "not available," openOrStart reuses the existing chat.
        val reopened = svc.openOrStart("supporter", "sharer")
        assertEquals(created.id, reopened.id)

        // Sanity: a plain start against the Sharer still fails (availability gate intact).
        assertFailsWith<IllegalArgumentException> { svc.start("supporter", "sharer") }
    }

    // --- Chats started from different rooms are independent (DESIGN §7) ---

    @Test
    fun chatsAreScopedToTheRoomTheyWereStartedIn() {
        val users = InMemoryUserRepository()
        users.create(User(id = "sharer", role = Role.SHARER, displayName = "Sharer"))
        users.create(User(id = "supporter", role = Role.SUPPORTER, displayName = "Supporter"))
        val repo = InMemoryPrivateChatRepository()
        val svc = PrivateChatService(repo, users)

        // The same pair opens a chat from two different rooms: each gets its own session.
        val roomOne = svc.openOrStart("sharer", "supporter", "room1")
        val roomTwo = svc.openOrStart("sharer", "supporter", "room2")
        assertTrue(roomOne.id != roomTwo.id)
        assertEquals("room1", roomOne.roomId)
        assertEquals("room2", roomTwo.roomId)

        // Reopening within the same room reuses that room's session (no third chat is created).
        assertEquals(roomOne.id, svc.openOrStart("supporter", "sharer", "room1").id)
        assertEquals(roomTwo.id, svc.openOrStart("supporter", "sharer", "room2").id)

        // Messages stay confined to their own room's chat — histories never bleed together.
        svc.post(roomOne.id, "sharer", "from room one")
        svc.post(roomTwo.id, "sharer", "from room two")
        assertEquals(listOf("from room one"), svc.messages(roomOne.id).map { it.text })
        assertEquals(listOf("from room two"), svc.messages(roomTwo.id).map { it.text })
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
        // Newlines are allowed so messages can span lines; other control chars are still rejected.
        svc.post(chat.id, "a", "line one\nline two")
        assertFailsWith<IllegalArgumentException> { svc.post(chat.id, "a", "bad\u0000frame") }

        // Either party can end instantly; the target (B) ends here.
        val ended = svc.end(chat.id, "b")
        assertEquals(chat.id, ended?.id)

        // No history survives the chat (DESIGN §7).
        assertNull(svc.get(chat.id))
        assertTrue(repo.messages(chat.id).isEmpty())
        // Posting to an ended chat is rejected.
        assertFailsWith<IllegalArgumentException> { svc.post(chat.id, "a", "still there?") }
    }

    // --- History of an open chat can be loaded when (re)opening the screen (DESIGN §7) ---

    @Test
    fun openChatExposesItsMessagesUntilItEnds() {
        val users = InMemoryUserRepository()
        users.create(User(id = "a", role = Role.SHARER, displayName = "A"))
        users.create(User(id = "b", role = Role.SUPPORTER, displayName = "B"))
        val repo = InMemoryPrivateChatRepository()
        var now = 1_000L
        val svc = PrivateChatService(repo, users, clock = { now })

        val chat = svc.start("a", "b")
        svc.post(chat.id, "a", "hello")
        now = 2_000L
        svc.post(chat.id, "b", "hi there")

        // The conversation so far is exposed oldest -> newest so a (re)opened screen loads history.
        val history = svc.messages(chat.id)
        assertEquals(listOf("hello", "hi there"), history.map { it.text })

        // Once the chat ends the history is discarded (ephemeral).
        svc.end(chat.id, "a")
        assertTrue(svc.messages(chat.id).isEmpty())
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

    // --- One member with two live connections must appear only once in the room (DESIGN §6) ---

    @Test
    fun memberWithMultipleConnectionsAppearsOnceInOccupants() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "sup", role = Role.SUPPORTER, displayName = "Sup"))
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("sup", Role.SUPPORTER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        // The same member joins room1 from two connections at once (a reconnect that raced the old
        // socket's close, or a second tab / the private-chat socket which also joins the room).
        wsClient.webSocket("/ws?token=$token") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            incoming.receive() // Joined

            wsClient.webSocket("/ws?token=$token") {
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
                incoming.receive() // Joined

                val body = wsClient.get("/rooms/room1/occupants").bodyAsText()
                val entries = Regex("\"userId\"").findAll(body).count()
                assertEquals(1, entries, "one member must appear exactly once: $body")
            }
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

    // --- (Re)opening an open chat replays its history over the socket (DESIGN §7) ---

    @Test
    fun openingAnExistingPrivateChatLoadsHistory() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "alice", role = Role.SHARER, displayName = "Alice"))
        repositories.users.create(User(id = "bob", role = Role.SUPPORTER, displayName = "Bob"))
        val authConfig = AuthConfig(secret = "test-secret")
        val tokens = TokenService(authConfig)
        val aliceToken = tokens.issueMemberToken("alice", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$aliceToken") {
            // Alice opens a chat with Bob and sends a message into it.
            send(
                Frame.Text(
                    json.encodeToString(Incoming.serializer(), Incoming.StartPrivateChat("room1", "bob")),
                ),
            )
            val started = json.decodeFromString(
                Outgoing.serializer(),
                (incoming.receive() as Frame.Text).readText(),
            )
            val chatId = (started as Outgoing.PrivateChatStarted).chatId
            send(
                Frame.Text(
                    json.encodeToString(Incoming.serializer(), Incoming.SayPrivate(chatId, "hello Bob")),
                ),
            )
            val echo = json.decodeFromString(
                Outgoing.serializer(),
                (incoming.receive() as Frame.Text).readText(),
            )
            assertEquals("hello Bob", (echo as Outgoing.PrivateMessageSaid).text)

            // Reopening the chat screen replays the conversation so far: the started notice is
            // followed by the existing message, so the screen loads history instead of a blank.
            send(
                Frame.Text(
                    json.encodeToString(Incoming.serializer(), Incoming.StartPrivateChat("room1", "bob")),
                ),
            )
            val reStarted = json.decodeFromString(
                Outgoing.serializer(),
                (incoming.receive() as Frame.Text).readText(),
            )
            assertTrue(reStarted is Outgoing.PrivateChatStarted)
            assertEquals(chatId, (reStarted as Outgoing.PrivateChatStarted).chatId)
            val replayed = json.decodeFromString(
                Outgoing.serializer(),
                (incoming.receive() as Frame.Text).readText(),
            )
            assertTrue(replayed is Outgoing.PrivateMessageSaid)
            assertEquals("hello Bob", (replayed as Outgoing.PrivateMessageSaid).text)
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
