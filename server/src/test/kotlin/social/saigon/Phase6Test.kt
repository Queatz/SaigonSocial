package social.saigon

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import social.saigon.domain.EmojiOptions
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.finalemoji.FinalEmojiService
import social.saigon.profile.ProfileService
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.InMemoryFinalEmojiRepository
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories

class Phase6Test {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Selections stay private until both choose; reveal is side-by-side (DESIGN §8) ---

    @Test
    fun bothMustSelectBeforeRevealAndTalliesUpdate() {
        val users = InMemoryUserRepository()
        users.create(User(id = "a", role = Role.SHARER, displayName = "A"))
        users.create(User(id = "b", role = Role.SUPPORTER, displayName = "B"))
        val profiles = ProfileService(users)
        val svc = FinalEmojiService(InMemoryFinalEmojiRepository(), profiles)

        svc.open("chat1", listOf("a", "b"))

        // First party selects → still waiting, nothing revealed.
        val first = svc.select("chat1", "a", "🙏")
        assertTrue(first is FinalEmojiService.Outcome.Waiting)
        // No emoji has been tallied yet (selections private until both choose).
        assertTrue(profiles.emojiStats("a").isEmpty())
        assertTrue(profiles.emojiStats("b").isEmpty())

        // Second party selects → reveal to both, personalized.
        val second = svc.select("chat1", "b", "😊")
        assertTrue(second is FinalEmojiService.Outcome.Revealed)
        second as FinalEmojiService.Outcome.Revealed
        assertEquals("🙏", second.reveals.getValue("a").youSent)
        assertEquals("😊", second.reveals.getValue("a").theySent)
        assertEquals("😊", second.reveals.getValue("b").youSent)
        assertEquals("🙏", second.reveals.getValue("b").theySent)

        // Each received emoji (the peer's) is added to the recipient's private tally (DESIGN §8).
        assertEquals(1, profiles.emojiStats("a")["😊"])
        assertEquals(1, profiles.emojiStats("b")["🙏"])

        // No follow-up: the session is gone, so further selection is rejected (no nudges).
        assertNull(svc.get("chat1"))
        assertFailsWith<IllegalArgumentException> { svc.select("chat1", "a", "🔥") }
    }

    // --- Validation: only participants, only defined emojis (DESIGN §8) ---

    @Test
    fun selectionValidatesParticipantAndEmoji() {
        val users = InMemoryUserRepository()
        users.create(User(id = "a", role = Role.SHARER, displayName = "A"))
        users.create(User(id = "b", role = Role.SUPPORTER, displayName = "B"))
        val svc = FinalEmojiService(InMemoryFinalEmojiRepository(), ProfileService(users))
        svc.open("chat1", listOf("a", "b"))

        // Unknown session.
        assertFailsWith<IllegalArgumentException> { svc.select("nope", "a", "🙏") }
        // Non-participant.
        assertFailsWith<IllegalArgumentException> { svc.select("chat1", "stranger", "🙏") }
        // Emoji outside the defined option set.
        assertFailsWith<IllegalArgumentException> { svc.select("chat1", "a", "🍕") }
        // The defined set is exactly DESIGN §8.
        assertEquals(listOf("😊", "💡", "😂", "🙏", "🤝", "❤️", "✨", "🔥"), EmojiOptions.all)
    }

    // --- End-to-end: ending a chat prompts both, then reveals once both select (DESIGN §8) ---

    @Test
    fun endingChatPromptsAndRevealsOverWebSockets() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "alice", role = Role.SHARER, displayName = "Alice"))
        repositories.users.create(User(id = "bob", role = Role.SUPPORTER, displayName = "Bob"))
        val authConfig = AuthConfig(secret = "test-secret")
        val tokens = TokenService(authConfig)
        val aliceToken = tokens.issueMemberToken("alice", Role.SHARER)
        val bobToken = tokens.issueMemberToken("bob", Role.SUPPORTER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$bobToken") {
            val bobSession = this

            wsClient.webSocket("/ws?token=$aliceToken") {
                // Alice starts a private chat with Bob.
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.StartPrivateChat("room1", "bob"))))
                val started = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
                assertTrue(started is Outgoing.PrivateChatStarted)
                val chatId = (started as Outgoing.PrivateChatStarted).chatId
                // Bob's PrivateChatStarted.
                bobSession.incoming.receive()

                // Alice ends the chat.
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.EndPrivateChat(chatId))))

                // Alice receives PrivateChatEnded then a FinalEmojiPrompt.
                val aliceEnded = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
                assertTrue(aliceEnded is Outgoing.PrivateChatEnded)
                val alicePrompt = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
                assertTrue(alicePrompt is Outgoing.FinalEmojiPrompt)
                assertEquals(EmojiOptions.all, (alicePrompt as Outgoing.FinalEmojiPrompt).options)

                // Bob likewise: PrivateChatEnded then FinalEmojiPrompt.
                val bobEnded = json.decodeFromString(Outgoing.serializer(), (bobSession.incoming.receive() as Frame.Text).readText())
                assertTrue(bobEnded is Outgoing.PrivateChatEnded)
                val bobPrompt = json.decodeFromString(Outgoing.serializer(), (bobSession.incoming.receive() as Frame.Text).readText())
                assertTrue(bobPrompt is Outgoing.FinalEmojiPrompt)

                // Alice selects first — no reveal yet (selection stays private).
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.SelectFinalEmoji(chatId, "🙏"))))

                // Bob selects from his own socket — both now receive the reveal, personalized.
                bobSession.send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.SelectFinalEmoji(chatId, "😊"))))

                // Alice's reveal: she sent 🙏, Bob sent 😊.
                val aliceReveal = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
                assertTrue(aliceReveal is Outgoing.FinalEmojiRevealed)
                aliceReveal as Outgoing.FinalEmojiRevealed
                assertEquals("🙏", aliceReveal.youSent)
                assertEquals("😊", aliceReveal.theySent)

                // Bob's reveal: mirror image.
                val bobReveal = json.decodeFromString(Outgoing.serializer(), (bobSession.incoming.receive() as Frame.Text).readText())
                assertTrue(bobReveal is Outgoing.FinalEmojiRevealed)
                bobReveal as Outgoing.FinalEmojiRevealed
                assertEquals("😊", bobReveal.youSent)
                assertEquals("🙏", bobReveal.theySent)

                // Received emojis land in each user's private tally (DESIGN §8).
                assertEquals(1, ProfileService(repositories.users).emojiStats("alice")["😊"])
                assertEquals(1, ProfileService(repositories.users).emojiStats("bob")["🙏"])
            }
        }
    }

    // --- A Seeker (no member id) cannot select a final emoji (DESIGN §8) ---

    @Test
    fun seekerCannotSelectFinalEmoji() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        application { module(authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.SelectFinalEmoji("chat1", "🙏"))))
            val reply = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(reply is Outgoing.Error)
        }
    }
}
