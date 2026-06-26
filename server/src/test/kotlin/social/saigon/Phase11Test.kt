package social.saigon

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.LaunchConfig
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.privatechat.PrivateChatService
import social.saigon.realtime.ChatHub
import social.saigon.realtime.ClientSession
import social.saigon.realtime.Outgoing
import social.saigon.repository.InMemoryMessageRepository
import social.saigon.repository.InMemoryPrivateChatRepository
import social.saigon.repository.InMemoryThreadRepository
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories
import social.saigon.thread.ThreadService

/**
 * Phase 11 — Launch Hardening.
 *
 * Cross-cutting non-functional checks: privacy guarantees (no history/receipts/last-seen), web-only
 * (no push), single-city scope, founder-auth + payment security, and WebSocket room / thread
 * behaviour under concurrency.
 */
class Phase11Test {

    // --- Privacy guarantees end-to-end (DESIGN §7, §12) ---

    @Test
    fun privateChatLeavesNoHistoryAndExposesNoReceiptsOrLastSeen() {
        val users = InMemoryUserRepository()
        users.create(User(id = "a", role = Role.SHARER, displayName = "A"))
        users.create(User(id = "b", role = Role.SUPPORTER, displayName = "B"))
        val repo = InMemoryPrivateChatRepository()
        val svc = PrivateChatService(repo, users)

        val chat = svc.start("a", "b")
        svc.post(chat.id, "a", "secret")
        svc.post(chat.id, "b", "also secret")
        assertEquals(2, repo.messages(chat.id).size)

        svc.end(chat.id, "a")
        // No history survives the chat.
        assertNull(svc.get(chat.id))
        assertTrue(repo.messages(chat.id).isEmpty())

        // The transport protocol exposes no read-receipt / typing / "last seen" signals.
        val frames = Outgoing::class.sealedSubclasses.mapNotNull { it.simpleName?.lowercase() }
        assertTrue(frames.isNotEmpty())
        assertFalse(frames.any { it.contains("receipt") || it.contains("typing") || it.contains("seen") || it.contains("read") })
    }

    @Test
    fun launchGuaranteesAreEncodedAndServedPublicly() = testApplication {
        // The guarantees are a single machine-checkable source of truth.
        assertFalse(LaunchConfig.STORES_CHAT_HISTORY)
        assertFalse(LaunchConfig.READ_RECEIPTS)
        assertFalse(LaunchConfig.LAST_SEEN)
        assertFalse(LaunchConfig.TYPING_INDICATORS)
        // Web-only: no push-notification path exists.
        assertFalse(LaunchConfig.PUSH_NOTIFICATIONS)
        // One city: Saigon only.
        assertTrue(LaunchConfig.SINGLE_CITY)
        assertTrue(LaunchConfig.LAUNCH_CITY.contains("Saigon"))

        application { module(authConfig = AuthConfig(secret = "test-secret")) }
        val res = client.get("/launch")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"pushNotifications\":false"), body)
        assertTrue(body.contains("\"storesChatHistory\":false"), body)
        assertTrue(body.contains("\"readReceipts\":false"), body)
        assertTrue(body.contains("\"singleCity\":true"), body)
        assertTrue(body.contains("Saigon"), body)
    }

    // --- Security: founder auth + payment flows (DESIGN §3, §10, §11) ---

    @Test
    fun founderOnlyRoutesRejectNonFounders() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "minh", role = Role.SHARER, displayName = "M"))
        repositories.users.create(User(id = "victim", role = Role.SHARER, displayName = "V"))
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("minh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // A non-founder cannot create rooms, warn, verify, read moderation status, or read metrics.
        val room = client.post("/rooms") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Coffee","emoji":"☕"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, room.status) // require(isAdmin) → IllegalArgument

        val warn = client.post("/moderation/warn") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId":"victim"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, warn.status)

        val verify = client.post("/moderation/verify") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"victim"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, verify.status)

        val metrics = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, metrics.status)
    }

    @Test
    fun paymentEndpointsRequireAuthenticationAndRejectTamperedAmounts() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "minh", role = Role.SHARER, displayName = "M"))
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("minh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Unauthenticated payment attempts are rejected.
        val anon = client.post("/economics/sharer-fee") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, anon.status)

        // A tampered amount is rejected (payment integrity).
        val tampered = client.post("/economics/sharer-fee") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"amountVnd":1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, tampered.status)
    }

    // --- Load / concurrency: WebSocket rooms and threads (TASKS Phase 11) ---

    @Test
    fun chatHubHandlesConcurrentJoinsAndLeaves() = runBlocking {
        val hub = ChatHub()
        val n = 500
        val ids = (0 until n).map { UUID.randomUUID().toString() }
        ids.forEach { id ->
            hub.register(object : ClientSession {
                override val connectionId = id
                override suspend fun send(frame: Outgoing) = Unit
            })
        }

        // Concurrently join the same room from many connections.
        ids.map { id -> async(Dispatchers.Default) { hub.join("room1", id) } }.awaitAll()
        assertEquals(n, hub.occupants("room1"))

        // Concurrently disconnect everyone; the room must end up empty.
        ids.map { id -> async(Dispatchers.Default) { hub.unregister(id) } }.awaitAll()
        assertEquals(0, hub.occupants("room1"))
        assertTrue(hub.isEmpty("room1"))
    }

    @Test
    fun threadServiceCountsAllConcurrentReplies() = runBlocking {
        val messages = InMemoryMessageRepository()
        val parent = messages.add(
            social.saigon.domain.Message(
                id = "parent",
                roomId = "room1",
                authorId = "minh",
                text = "Any good pho in D1?",
                createdAt = 0L,
            ),
        )
        val threads = ThreadService(InMemoryThreadRepository(), messages)

        val n = 500
        (0 until n).map { i ->
            async(Dispatchers.Default) { threads.reply(parent.id, "author-$i", "reply $i") }
        }.awaitAll()

        // No replies are lost under concurrent writes (Phase 11 hardening).
        assertEquals(n, threads.replyCount(parent.id))
        assertEquals(n, threads.thread(parent.id).size)
    }
}
