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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.Message
import social.saigon.domain.Role
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.InMemoryMessageRepository
import social.saigon.repository.InMemoryThreadRepository
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
        // Plain-text-only is enforced on replies, too.
        assertFailsWith<IllegalArgumentException> { svc.reply("m1", "u2", "bad\nframe") }
        // A nested reply must target an existing reply in the same thread.
        assertFailsWith<IllegalArgumentException> { svc.reply("m1", "u2", "hi", replyToId = "nope") }
    }

    // --- Ephemeral threads: clear on room empty or after 24h inactivity (DESIGN §9) ---

    @Test
    fun threadsClearWhenRoomEmpties() {
        val messages = InMemoryMessageRepository()
        val threads = InMemoryThreadRepository()
        val svc = ThreadService(threads, messages)
        messages.add(Message("m1", "room1", "u1", "hello", 0))
        svc.reply("m1", "u2", "hi")
        assertEquals(1, svc.replyCount("m1"))

        svc.clearForRoom("room1")
        assertEquals(0, svc.replyCount("m1"))
        assertTrue(svc.thread("m1").isEmpty())
    }

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
}
