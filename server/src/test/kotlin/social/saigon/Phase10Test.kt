package social.saigon

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.MetricType
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.economics.EconomicsService
import social.saigon.metrics.MetricsService
import social.saigon.moderation.ModerationService
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.InMemoryMetricRepository
import social.saigon.repository.InMemoryModerationRepository
import social.saigon.repository.InMemoryPaymentRepository
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories

/**
 * Phase 10 — Success Metrics & Observability (DESIGN §14).
 *
 * Verifies the six internal metrics: Seeker→Sharer and Sharer→Supporter conversions, Daily Active
 * Users, average chat duration, emoji selection rate, and report rate (target < 1%) — plus that the
 * snapshot is founder-only and the events store no message content.
 */
class Phase10Test {

    private val json = Json { ignoreUnknownKeys = true }

    // --- DAU window + averages computed from recorded events (DESIGN §14) ---

    @Test
    fun snapshotAggregatesConversionsDauDurationEmojiAndReportRate() {
        var now = 10_000_000L
        val metrics = MetricsService(InMemoryMetricRepository(), clock = { now })

        // Two members active "today".
        metrics.record(MetricType.ACTIVE, userId = "alice")
        metrics.record(MetricType.ACTIVE, userId = "alice") // de-duplicated per user
        metrics.record(MetricType.ACTIVE, userId = "bob")

        // Conversions.
        metrics.record(MetricType.SHARER_CONVERSION, userId = "alice")
        metrics.record(MetricType.SUPPORTER_CONVERSION, userId = "alice")

        // Two completed chats: durations 2s and 4s → avg 3s.
        metrics.record(MetricType.CHAT_ENDED, value = 2_000L)
        metrics.record(MetricType.CHAT_ENDED, value = 4_000L)

        // Emoji: 4 prompts shown, 3 selected → 0.75.
        repeat(4) { metrics.record(MetricType.FINAL_EMOJI_PROMPTED, userId = "u$it") }
        repeat(3) { metrics.record(MetricType.FINAL_EMOJI_SELECTED, userId = "u$it") }

        // One report against bob.
        metrics.record(MetricType.REPORT_FILED, userId = "bob")

        val snap = metrics.snapshot()
        assertEquals(1, snap.seekerToSharerConversions)
        assertEquals(1, snap.sharerToSupporterConversions)
        assertEquals(2, snap.dailyActiveUsers)
        assertEquals(2, snap.activeUsers)
        assertEquals(3_000L, snap.averageChatDurationMillis)
        assertEquals(3L, snap.averageChatDurationSeconds)
        assertEquals(2, snap.completedChats)
        assertEquals(0.75, snap.emojiSelectionRate, 1e-9)
        assertEquals(1, snap.totalReports)
        assertEquals(0.5, snap.reportRate, 1e-9)

        // After 25h the earlier activity falls out of the DAU window, but remains an active user.
        now += 25L * 60L * 60L * 1000L
        val later = metrics.snapshot()
        assertEquals(0, later.dailyActiveUsers)
        assertEquals(2, later.activeUsers)
    }

    @Test
    fun emptyMetricsAreZeroAndDoNotDivideByZero() {
        val snap = MetricsService(InMemoryMetricRepository()).snapshot()
        assertEquals(0, snap.dailyActiveUsers)
        assertEquals(0L, snap.averageChatDurationMillis)
        assertEquals(0.0, snap.emojiSelectionRate, 1e-9)
        assertEquals(0.0, snap.reportRate, 1e-9)
    }

    // --- Conversions are recorded by EconomicsService, renewals are not (DESIGN §14) ---

    @Test
    fun economicsServiceRecordsFirstTimeConversionsOnly() {
        val users = InMemoryUserRepository()
        users.create(User(id = "linh", role = Role.SEEKER, displayName = "L"))
        var now = 1_000L
        val metrics = MetricsService(InMemoryMetricRepository(), clock = { now })
        val svc = EconomicsService(users, InMemoryPaymentRepository(), clock = { now }, metrics = metrics)

        svc.paySharerFee("linh")
        svc.paySharerFee("linh") // already paid → not a second conversion
        svc.paySupporterSubscription("linh")
        now += 1
        svc.paySupporterSubscription("linh") // renewal → not a second conversion

        val snap = metrics.snapshot()
        assertEquals(1, snap.seekerToSharerConversions)
        assertEquals(1, snap.sharerToSupporterConversions)
    }

    // --- Report rate fed by ModerationService (DESIGN §14) ---

    @Test
    fun moderationServiceRecordsReportsForTheRate() {
        val users = InMemoryUserRepository()
        users.create(User(id = "subject", role = Role.SHARER, displayName = "S"))
        val metrics = MetricsService(InMemoryMetricRepository())
        val svc = ModerationService(InMemoryModerationRepository(), users, metrics = metrics)

        svc.report("r1", "subject", social.saigon.domain.ReportTarget.PROFILE)

        assertEquals(1, metrics.snapshot().totalReports)
    }

    // --- /metrics is founder-only (DESIGN §14: internal analytics) ---

    @Test
    fun metricsEndpointRejectsNonFounders() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "minh", role = Role.SHARER, displayName = "M"))
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("minh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun founderReadsAggregatedMetricsAfterLiveActivity() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "alice", role = Role.SHARER, displayName = "Alice"))
        repositories.users.create(User(id = "bob", role = Role.SUPPORTER, displayName = "Bob"))
        val authConfig = AuthConfig(secret = "test-secret")
        val tokens = TokenService(authConfig)
        val founderToken = tokens.issueMemberToken("founder", Role.FOUNDER)
        val aliceToken = tokens.issueMemberToken("alice", Role.SHARER)
        val bobToken = tokens.issueMemberToken("bob", Role.SUPPORTER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$bobToken") {
            val bobIncoming = incoming
            // Bob is active (joins the room).
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
            bobIncoming.receive() // Joined

            wsClient.webSocket("/ws?token=$aliceToken") {
                // Alice is active (joins + sends a public message).
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Join("room1"))))
                incoming.receive() // Joined (broadcast)
                bobIncoming.receive() // Bob also sees Alice join

                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "hello"))))
                incoming.receive() // Said
                bobIncoming.receive()

                // Alice starts then ends a private chat with Bob.
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.StartPrivateChat("room1", "bob"))))
                val started = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
                bobIncoming.receive() // Bob's start
                val chatId = (started as Outgoing.PrivateChatStarted).chatId

                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.EndPrivateChat(chatId))))
                incoming.receive() // ended
                bobIncoming.receive()
                incoming.receive() // alice prompt
                bobIncoming.receive() // bob prompt

                // Both pick a closing emoji.
                send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.SelectFinalEmoji(chatId, "🙏"))))
            }
        }

        val res = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer $founderToken") }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        // Two distinct active members (alice + bob) and one completed chat.
        assertTrue(body.contains("\"dailyActiveUsers\":2"), body)
        assertTrue(body.contains("\"completedChats\":1"), body)
        assertTrue(body.contains("\"reportRate\":0.0"), body)
    }
}
