package social.saigon

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.Message
import social.saigon.domain.Role
import social.saigon.domain.Room
import social.saigon.domain.User
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.InMemoryMessageRepository
import social.saigon.repository.InMemoryThreadRepository
import social.saigon.repository.Repositories
import social.saigon.thread.ThreadService

class Phase4Test {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Thread data model: parent message + nested replies, displayed linearly (DESIGN §9) ---

    @Test
    fun repliesNestAndAreReturnedLinearlyInTimeOrder() {
        val messages = InMemoryMessageRepository()
        val threads = InMemoryThreadRepository()
        var now = 1_000L
        val svc = ThreadService(threads, messages, clock = { now })
        val parent = messages.add(Message("m1", "room1", "u1", "pho place in D1?", now))

        now = 1_100L
        val first = svc.reply(parent.id, "u2", "Pho Hung at 241 Nguyễn Trãi")
        now = 1_200L
        // A nested reply (reply to a reply) still belongs to the same parent thread.
        val nested = svc.reply(parent.id, "u3", "+1 to that", replyToId = first.id)

        val thread = svc.thread(parent.id)
        assertEquals(listOf(first.id, nested.id), thread.map { it.id })
        assertEquals(parent.id, nested.parentMessageId)
        assertEquals(first.id, nested.replyToId)
        assertEquals(2, svc.replyCount(parent.id))
    }

    @Test
    fun replyValidatesParentPlainTextAndNestingTarget() {
        val messages = InMemoryMessageRepository()
        val threads = InMemoryThreadRepository()
        val svc = ThreadService(threads, messages)
        messages.add(Message("m1", "room1", "u1", "hello", 0))

        // Unknown parent message is rejected.
        assertFailsWith<IllegalArgumentException> { svc.reply("missing", "u2", "hi") }
        // Newlines are allowed so replies can span multiple lines.
        val multiline = svc.reply("m1", "u2", "line one\nline two")
        assertEquals("line one\nline two", multiline.text)
        // Other control characters (binary/rich payloads) are still rejected.
        assertFailsWith<IllegalArgumentException> { svc.reply("m1", "u2", "bad\u0000frame") }
        // A nested reply must target an existing reply in the same thread.
        assertFailsWith<IllegalArgumentException> { svc.reply("m1", "u2", "hi", replyToId = "nope") }
    }

    // --- Ephemeral threads: clear after 24h inactivity (DESIGN §9) ---

    @Test
    fun inactiveThreadsAreSweptAfter24h() {
        val messages = InMemoryMessageRepository()
        val threads = InMemoryThreadRepository()
        var now = 0L
        val svc = ThreadService(threads, messages, clock = { now })
        messages.add(Message("stale", "room1", "u1", "old", now))
        messages.add(Message("fresh", "room1", "u1", "new", now))
        svc.reply("stale", "u2", "old reply")

        // Advance just past 24h, then add activity to the fresh thread.
        now = ThreadService.INACTIVITY_WINDOW_MS + 1
        svc.reply("fresh", "u2", "new reply")

        assertEquals(1, svc.sweepInactive())
        assertEquals(0, svc.replyCount("stale"))
        assertEquals(1, svc.replyCount("fresh"))
    }

    // --- Reply over WebSockets: capability gate, broadcast, reply-count indicator (DESIGN §9) ---

    @Test
    fun seekerCannotReplyButMemberCanAndCountIsBroadcast() = testApplication {
        val authConfig = AuthConfig(secret = "test-secret")
        val memberToken = TokenService(authConfig).issueMemberToken("u1", Role.SHARER)
        application { module(authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        var parentId: String? = null

        wsClient.webSocket("/ws?token=$memberToken") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            incoming.receive() // Joined

            // Post a parent message; the broadcast now carries its message id (tappable).
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "pho place?"))))
            val said = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(said is Outgoing.Said)
            parentId = (said as Outgoing.Said).messageId
            assertTrue(parentId != null)

            // Member replies in the thread; the room receives a Replied with the live reply-count.
            send(
                Frame.Text(
                    json.encodeToString(Incoming.serializer(), Incoming.Reply("room1", parentId!!, "Pho Hung is solid")),
                ),
            )
            val replied = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(replied is Outgoing.Replied)
            replied as Outgoing.Replied
            assertEquals(parentId, replied.parentMessageId)
            assertEquals("Pho Hung is solid", replied.text)
            assertEquals(1, replied.replyCount)

            // While the room is occupied, the feed exposes the reply-count indicator and the
            // thread is readable by anyone (Seekers included).
            val feed = wsClient.get("/rooms/room1/messages")
            assertEquals(HttpStatusCode.OK, feed.status)
            assertTrue(feed.bodyAsText().contains("\"replyCount\":1"))

            val thread = wsClient.get("/rooms/room1/messages/$parentId/thread")
            assertEquals(HttpStatusCode.OK, thread.status)
            assertTrue(thread.bodyAsText().contains("Pho Hung is solid"))

            // Seeker (no token) is read-only: a reply attempt is rejected.
            wsClient.webSocket("/ws") {
                send(
                    Frame.Text(
                        json.encodeToString(Incoming.serializer(), Incoming.Reply("room1", parentId!!, "me too")),
                    ),
                )
                val reply = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
                assertTrue(reply is Outgoing.Error)
            }
        }
    }

    @Test
    fun threadRepliesNotificationsAndEndpoint() = testApplication {
        val repositories = Repositories()
        val u1 = User(id = "u1", role = Role.SHARER, displayName = "Nam")
        val u2 = User(id = "u2", role = Role.SHARER, displayName = "Alex")
        repositories.users.create(u1)
        repositories.users.create(u2)
        repositories.rooms.create(Room("room1", "District 1 Room", "🏙️", "vibe", "u1"))

        val authConfig = AuthConfig(secret = "test-secret")
        val tokenService = TokenService(authConfig)
        val u1Token = tokenService.issueMemberToken("u1", Role.SHARER)
        val u2Token = tokenService.issueMemberToken("u2", Role.SHARER)

        application { module(repositories = repositories, authConfig = authConfig) }

        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$u1Token") {
            val u1Incoming = incoming
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            u1Incoming.receive()

            // Post a parent message
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "anyone here?"))))
            val saidFrame = u1Incoming.receive() as Frame.Text
            val said = json.decodeFromString(Outgoing.serializer(), saidFrame.readText()) as Outgoing.Said
            val parentId = said.messageId!!

            // Now u2 joins and replies
            wsClient.webSocket("/ws?token=$u2Token") {
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
                incoming.receive()

                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Reply("room1", parentId, "yes u2 is here"))))
                // u2 should get their Replied broadcast response
                val u2RepliedFrame = incoming.receive() as Frame.Text
                val u2Replied = json.decodeFromString(Outgoing.serializer(), u2RepliedFrame.readText())
                assertTrue(u2Replied is Outgoing.Replied)
            }

            // u1 receives Said response, Replied broadcast, and MessageReplyReceived notification
            val frames = (1..3).map {
                val f = u1Incoming.receive() as Frame.Text
                json.decodeFromString(Outgoing.serializer(), f.readText())
            }
            val hasNotification = frames.any { it is Outgoing.MessageReplyReceived }
            assertTrue(hasNotification, "Should have received MessageReplyReceived notification")

            val notification = frames.first { it is Outgoing.MessageReplyReceived } as Outgoing.MessageReplyReceived
            assertEquals("yes u2 is here", notification.replyText)
            assertEquals(repositories.identities.get("u2", "room1")?.name, notification.replyAuthorName)

            // Now test the REST endpoint /profile/me/threads for u1
            val response = wsClient.get("/profile/me/threads") {
                header(HttpHeaders.Authorization, "Bearer $u1Token")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("yes u2 is here"))
        }
    }

    @Test
    fun dismissAndReactivateThreadReplies() = testApplication {
        val repositories = Repositories()
        val u1 = User(id = "u1", role = Role.SHARER, displayName = "Nam")
        val u2 = User(id = "u2", role = Role.SHARER, displayName = "Alex")
        repositories.users.create(u1)
        repositories.users.create(u2)
        repositories.rooms.create(Room("room1", "District 1 Room", "🏙️", "vibe", "u1"))

        val authConfig = AuthConfig(secret = "test-secret")
        val tokenService = TokenService(authConfig)
        val u1Token = tokenService.issueMemberToken("u1", Role.SHARER)
        val u2Token = tokenService.issueMemberToken("u2", Role.SHARER)

        application { module(repositories = repositories, authConfig = authConfig) }

        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$u1Token") {
            val u1Incoming = incoming
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            u1Incoming.receive()

            // Post a parent message
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "anyone here?"))))
            val saidFrame = u1Incoming.receive() as Frame.Text
            val said = json.decodeFromString(Outgoing.serializer(), saidFrame.readText()) as Outgoing.Said
            val parentId = said.messageId!!

            // Now u2 joins and replies
            wsClient.webSocket("/ws?token=$u2Token") {
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
                incoming.receive()

                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Reply("room1", parentId, "yes u2 is here"))))
                // u2 should get their Replied broadcast response
                val u2RepliedFrame = incoming.receive() as Frame.Text
                val u2Replied = json.decodeFromString(Outgoing.serializer(), u2RepliedFrame.readText())
                assertTrue(u2Replied is Outgoing.Replied)
            }

            // Consume frames for u1
            (1..3).forEach { u1Incoming.receive() }

            // 1. Thread summary should list "yes u2 is here" for u1
            val listResponse = wsClient.get("/profile/me/threads") {
                header(HttpHeaders.Authorization, "Bearer $u1Token")
            }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            assertTrue(listResponse.bodyAsText().contains("yes u2 is here"))

            // 2. Dismiss the thread for u1
            val dismissResponse = wsClient.post("/profile/me/threads/$parentId/dismiss") {
                header(HttpHeaders.Authorization, "Bearer $u1Token")
            }
            assertEquals(HttpStatusCode.OK, dismissResponse.status)

            // 3. Thread summary should be empty for u1 now
            val listResponseAfterDismiss = wsClient.get("/profile/me/threads") {
                header(HttpHeaders.Authorization, "Bearer $u1Token")
            }
            assertEquals(HttpStatusCode.OK, listResponseAfterDismiss.status)
            assertFalse(listResponseAfterDismiss.bodyAsText().contains("yes u2 is here"))

            // 4. u2 replies again to the same thread
            wsClient.webSocket("/ws?token=$u2Token") {
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
                incoming.receive()

                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Reply("room1", parentId, "another reply from u2"))))
                val u2RepliedFrame = incoming.receive() as Frame.Text
                val u2Replied = json.decodeFromString(Outgoing.serializer(), u2RepliedFrame.readText())
                assertTrue(u2Replied is Outgoing.Replied)
            }

            // 5. Thread summary should show up again for u1 (reactivated!)
            val listResponseAfterNewReply = wsClient.get("/profile/me/threads") {
                header(HttpHeaders.Authorization, "Bearer $u1Token")
            }
            assertEquals(HttpStatusCode.OK, listResponseAfterNewReply.status)
            assertTrue(listResponseAfterNewReply.bodyAsText().contains("another reply from u2"))
        }
    }

    @Test
    fun ownRepliesDoNotTriggerThreadEntry() = testApplication {
        val repositories = Repositories()
        val u1 = User(id = "u1", role = Role.SHARER, displayName = "Nam")
        val u2 = User(id = "u2", role = Role.SHARER, displayName = "Alex")
        repositories.users.create(u1)
        repositories.users.create(u2)
        repositories.rooms.create(Room("room1", "District 1 Room", "🏙️", "vibe", "u1"))

        val authConfig = AuthConfig(secret = "test-secret")
        val tokenService = TokenService(authConfig)
        val u1Token = tokenService.issueMemberToken("u1", Role.SHARER)
        val u2Token = tokenService.issueMemberToken("u2", Role.SHARER)

        application { module(repositories = repositories, authConfig = authConfig) }

        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$u1Token") {
            val u1Incoming = incoming
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            u1Incoming.receive()

            // u1 starts a thread parent
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "my own thread"))))
            val saidFrame = u1Incoming.receive() as Frame.Text
            val said = json.decodeFromString(Outgoing.serializer(), saidFrame.readText()) as Outgoing.Said
            val parentId = said.messageId!!

            // u1 replies to their own thread — this must NOT create an entry for u1.
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Reply("room1", parentId, "talking to myself"))))
            u1Incoming.receive() // Replied broadcast

            val ownOnly = wsClient.get("/profile/me/threads") {
                header(HttpHeaders.Authorization, "Bearer $u1Token")
            }
            assertEquals(HttpStatusCode.OK, ownOnly.status)
            assertFalse(
                ownOnly.bodyAsText().contains("my own thread"),
                "A thread with only the user's own posts must not appear",
            )

            // u2 replies to u1's initial message — the thread should now appear for u1.
            wsClient.webSocket("/ws?token=$u2Token") {
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
                incoming.receive()
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Reply("room1", parentId, "u2 chimes in"))))
                val f = incoming.receive() as Frame.Text
                assertTrue(json.decodeFromString(Outgoing.serializer(), f.readText()) is Outgoing.Replied)
            }

            val afterOther = wsClient.get("/profile/me/threads") {
                header(HttpHeaders.Authorization, "Bearer $u1Token")
            }
            assertEquals(HttpStatusCode.OK, afterOther.status)
            assertTrue(
                afterOther.bodyAsText().contains("u2 chimes in"),
                "A thread should appear once another user replies",
            )
        }
    }
}
