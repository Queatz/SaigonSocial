package social.saigon

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import social.saigon.domain.ReportTarget
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.domain.WarningReason
import social.saigon.moderation.ModerationService
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.InMemoryModerationRepository
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories

class Phase7Test {

    private val json = Json { ignoreUnknownKeys = true }

    private fun users(vararg users: User): InMemoryUserRepository {
        val repo = InMemoryUserRepository()
        users.forEach { repo.create(it) }
        return repo
    }

    // --- Two reports = permanent ban, counted by distinct reporter (DESIGN §10) ---

    @Test
    fun twoDistinctReportersPermanentlyBanTheSubject() {
        val users = users(
            User(id = "alice", role = Role.SHARER, displayName = "A"),
            User(id = "bob", role = Role.SHARER, displayName = "B"),
            User(id = "carol", role = Role.SHARER, displayName = "C"),
        )
        val svc = ModerationService(InMemoryModerationRepository(), users)

        val first = svc.report("alice", "carol", ReportTarget.PROFILE)
        assertEquals(1, first.reportCount)
        assertFalse(first.subjectBanned)
        assertFalse(svc.isBanned("carol"))

        val second = svc.report("bob", "carol", ReportTarget.CHAT, contextId = "room1")
        assertEquals(2, second.reportCount)
        assertTrue(second.subjectBanned)
        assertTrue(svc.isBanned("carol"))
    }

    @Test
    fun sameReporterReportingTwiceDoesNotBan() {
        val users = users(
            User(id = "alice", role = Role.SHARER, displayName = "A"),
            User(id = "carol", role = Role.SHARER, displayName = "C"),
        )
        val svc = ModerationService(InMemoryModerationRepository(), users)

        svc.report("alice", "carol", ReportTarget.PROFILE)
        val again = svc.report("alice", "carol", ReportTarget.PROFILE)

        // Still a single distinct reporter — no ban.
        assertEquals(1, again.reportCount)
        assertFalse(again.subjectBanned)
        assertFalse(svc.isBanned("carol"))
    }

    @Test
    fun reportingYourselfOrUnknownUserIsRejected() {
        val users = users(User(id = "alice", role = Role.SHARER, displayName = "A"))
        val svc = ModerationService(InMemoryModerationRepository(), users)

        assertFailsWith<IllegalArgumentException> { svc.report("alice", "alice", ReportTarget.PROFILE) }
        assertFailsWith<IllegalArgumentException> { svc.report("alice", "ghost", ReportTarget.PROFILE) }
    }

    // --- Unsolicited-sales: warning → second warning → ban (DESIGN §10) ---

    @Test
    fun firstWarningCautionsSecondWarningBans() {
        val users = users(
            User(id = "founder", role = Role.FOUNDER, displayName = "F"),
            User(id = "seller", role = Role.SHARER, displayName = "S"),
        )
        val svc = ModerationService(InMemoryModerationRepository(), users)

        val first = svc.warn("founder", "seller", WarningReason.UNSOLICITED_SALES)
        assertEquals(1, first.warningCount)
        assertFalse(first.subjectBanned)
        assertFalse(svc.isBanned("seller"))

        val second = svc.warn("founder", "seller", WarningReason.UNSOLICITED_SALES)
        assertEquals(2, second.warningCount)
        assertTrue(second.subjectBanned)
        assertTrue(svc.isBanned("seller"))
    }

    // --- Founder-only manual verification (DESIGN §10) ---

    @Test
    fun founderVerificationSetsVerifiedFlag() {
        val users = users(User(id = "newbie", role = Role.SHARER, displayName = "N"))
        val svc = ModerationService(InMemoryModerationRepository(), users)

        assertFalse(svc.isVerified("newbie"))
        svc.verify("newbie")
        assertTrue(svc.isVerified("newbie"))
        assertTrue(svc.status("newbie").verified)
        assertFailsWith<IllegalArgumentException> { svc.verify("ghost") }
    }

    // --- A banned member is silenced on the public chat transport (DESIGN §10) ---

    @Test
    fun bannedMemberCannotSendPublicMessages() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "alice", role = Role.SHARER, displayName = "A"))
        repositories.users.create(User(id = "bob", role = Role.SHARER, displayName = "B"))
        repositories.users.create(User(id = "troll", role = Role.SHARER, displayName = "T"))
        // Two reports against the troll → permanent ban.
        val svc = ModerationService(repositories.moderation, repositories.users)
        svc.report("alice", "troll", ReportTarget.PROFILE)
        svc.report("bob", "troll", ReportTarget.PROFILE)

        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("troll", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws?token=$token") {
            send(Frame.Text(json.encodeToString(Incoming.serializer(), Incoming.Say("room1", "hello"))))
            val reply = json.decodeFromString(Outgoing.serializer(), (incoming.receive() as Frame.Text).readText())
            assertTrue(reply is Outgoing.Error)
            assertEquals("Account banned", (reply as Outgoing.Error).reason)
        }
    }

    // --- HTTP: reporting twice over the wire bans; founder-only routes are gated (DESIGN §10) ---

    @Test
    fun reportRouteBansAndFounderRoutesAreGated() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "alice", role = Role.SHARER, displayName = "A"))
        repositories.users.create(User(id = "bob", role = Role.SHARER, displayName = "B"))
        repositories.users.create(User(id = "troll", role = Role.SHARER, displayName = "T"))
        val authConfig = AuthConfig(secret = "test-secret")
        val tokens = TokenService(authConfig)
        val founderToken = tokens.issueMemberToken("founder", Role.FOUNDER)
        val aliceToken = tokens.issueMemberToken("alice", Role.SHARER)
        val bobToken = tokens.issueMemberToken("bob", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        suspend fun report(token: String) = client.post("/moderation/report") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId":"troll","target":"PROFILE"}""")
        }

        val first = report(aliceToken)
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.bodyAsText().contains("\"subjectBanned\":false"))

        val second = report(bobToken)
        assertEquals(HttpStatusCode.OK, second.status)
        assertTrue(second.bodyAsText().contains("\"subjectBanned\":true"))

        // A non-founder cannot issue warnings — Forbidden (DESIGN §10).
        val warnByMember = client.post("/moderation/warn") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"subjectId":"bob"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, warnByMember.status)

        // The founder can verify a member.
        val verify = client.post("/moderation/verify") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice"}""")
        }
        assertEquals(HttpStatusCode.OK, verify.status)
        assertTrue(verify.bodyAsText().contains("\"verified\":true"))

        // Founder can read a user's moderation status; the troll is shown banned.
        val status = client.get("/moderation/status/troll") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertEquals(HttpStatusCode.OK, status.status)
        assertTrue(status.bodyAsText().contains("\"banned\":true"))
    }
}
