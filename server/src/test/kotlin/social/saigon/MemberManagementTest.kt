package social.saigon

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.economics.EconomicsService
import social.saigon.metrics.MetricsService
import social.saigon.repository.InMemoryMetricRepository
import social.saigon.repository.InMemoryPaymentRepository
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories

/**
 * Founder member management (MEMBER_MANAGEMENT.md): the founder can list members and manually
 * change a member's tier (comp upgrade / downgrade / demote) without a payment, with each change
 * audited as a metric event.
 */
class MemberManagementTest {

    private fun users(vararg users: User): InMemoryUserRepository {
        val repo = InMemoryUserRepository()
        users.forEach { repo.create(it) }
        return repo
    }

    // --- setTier service semantics (no payment recorded; audited) ---

    @Test
    fun compsAMemberUpToSupporterWithoutAPayment() {
        val users = users(User(id = "linh", role = Role.SHARER, displayName = "L", sharerPaidAt = 1L))
        val payments = InMemoryPaymentRepository()
        val metrics = MetricsService(InMemoryMetricRepository(), clock = { 1_000L })
        val svc = EconomicsService(users, payments, clock = { 1_000L }, metrics = metrics)

        val outcome = svc.setTier("linh", Role.SUPPORTER)

        assertEquals(Role.SUPPORTER, outcome.user.role)
        assertEquals(Role.SUPPORTER, outcome.status.effectiveRole)
        assertTrue(outcome.status.supporterActive)
        // A comp is not a transaction: no Payment is recorded.
        assertTrue(payments.byUser("linh").isEmpty())
        // The change is audited.
        assertEquals(1, metrics.snapshot().founderTierChanges)
    }

    @Test
    fun downgradesAnActiveSupporterToSharer() {
        val users = users(
            User(
                id = "linh",
                role = Role.SUPPORTER,
                displayName = "L",
                sharerPaidAt = 1L,
                supporterUntil = Long.MAX_VALUE,
            ),
        )
        val svc = EconomicsService(users, InMemoryPaymentRepository(), clock = { 1_000L })

        val outcome = svc.setTier("linh", Role.SHARER)

        assertEquals(Role.SHARER, outcome.user.role)
        assertNull(outcome.user.supporterUntil)
        assertEquals(Role.SHARER, outcome.status.effectiveRole)
        assertFalse(outcome.status.supporterActive)
    }

    @Test
    fun demotesAMemberToSeekerForSoftRemoval() {
        val users = users(
            User(id = "linh", role = Role.SUPPORTER, displayName = "L", sharerPaidAt = 1L, supporterUntil = Long.MAX_VALUE),
        )
        val svc = EconomicsService(users, InMemoryPaymentRepository(), clock = { 1_000L })

        val outcome = svc.setTier("linh", Role.SEEKER)

        assertEquals(Role.SEEKER, outcome.user.role)
        assertNull(outcome.user.sharerPaidAt)
        assertNull(outcome.user.supporterUntil)
        assertEquals(Role.SEEKER, outcome.status.effectiveRole)
    }

    @Test
    fun refusesToChangeTheFoundersOwnTierOrPromoteToFounder() {
        val users = users(
            User(id = "founder", role = Role.FOUNDER, displayName = "F"),
            User(id = "linh", role = Role.SHARER, displayName = "L", sharerPaidAt = 1L),
        )
        val svc = EconomicsService(users, InMemoryPaymentRepository())

        assertFailsWith<IllegalArgumentException> { svc.setTier("founder", Role.SHARER) }
        assertFailsWith<IllegalArgumentException> { svc.setTier("linh", Role.FOUNDER) }
        assertFailsWith<IllegalArgumentException> { svc.setTier("ghost", Role.SHARER) }
    }

    // --- HTTP surface ---

    @Test
    fun founderListsMembersAndNonFounderIsForbidden() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "linh", role = Role.SHARER, displayName = "Linh", sharerPaidAt = 1L))
        val authConfig = AuthConfig(secret = "test-secret")
        val tokens = TokenService(authConfig)
        application { module(repositories = repositories, authConfig = authConfig) }

        val asFounder = client.get("/founder/members") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.issueMemberToken("founder", Role.FOUNDER)}")
        }
        assertEquals(HttpStatusCode.OK, asFounder.status)
        val body = asFounder.bodyAsText()
        assertTrue(body.contains("\"displayName\":\"Linh\""))
        // Seekers / the founder's own non-member rows aside, the founder is excluded only by role.
        assertTrue(body.contains("\"effectiveRole\""))

        // A Sharer may not read the directory.
        val asSharer = client.get("/founder/members") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.issueMemberToken("linh", Role.SHARER)}")
        }
        assertEquals(HttpStatusCode.Forbidden, asSharer.status)
    }

    @Test
    fun founderPatchesAMembersTierAndGetsAFreshToken() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "linh", role = Role.SHARER, displayName = "Linh", sharerPaidAt = 1L))
        val authConfig = AuthConfig(secret = "test-secret")
        val founderToken = TokenService(authConfig).issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.patch("/founder/members/linh/role") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"SUPPORTER"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"role\":\"SUPPORTER\""))
        assertTrue(body.contains("\"token\":"))
        assertEquals(Role.SUPPORTER, repositories.users.get("linh")!!.role)

        // Demotion to Seeker mints no member token (they re-authenticate as a Seeker by device).
        val demote = client.patch("/founder/members/linh/role") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"SEEKER"}""")
        }
        assertEquals(HttpStatusCode.OK, demote.status)
        // No member token is minted for a Seeker (the null default is omitted from the JSON).
        assertFalse(demote.bodyAsText().contains("\"token\":\""))
        assertEquals(Role.SEEKER, repositories.users.get("linh")!!.role)
    }

    @Test
    fun founderRemovesAMemberButNotThemselves() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "linh", role = Role.SHARER, displayName = "Linh", sharerPaidAt = 1L))
        val authConfig = AuthConfig(secret = "test-secret")
        val founderToken = TokenService(authConfig).issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.delete("/founder/members/linh") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertNull(repositories.users.get("linh"))

        // The founder cannot remove their own account, so the platform always keeps an admin.
        val self = client.delete("/founder/members/founder") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertEquals(HttpStatusCode.BadRequest, self.status)
        assertEquals(Role.FOUNDER, repositories.users.get("founder")!!.role)
    }

    @Test
    fun nonFounderCannotRemoveAMember() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "linh", role = Role.SHARER, displayName = "Linh", sharerPaidAt = 1L))
        repositories.users.create(User(id = "minh", role = Role.SHARER, displayName = "Minh", sharerPaidAt = 1L))
        val authConfig = AuthConfig(secret = "test-secret")
        val sharerToken = TokenService(authConfig).issueMemberToken("minh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.delete("/founder/members/linh") {
            header(HttpHeaders.Authorization, "Bearer $sharerToken")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        // The member is left untouched.
        assertEquals("Linh", repositories.users.get("linh")!!.displayName)
    }

    @Test
    fun founderResetsAMembersPasswordAndNonFounderCannot() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(
            User(
                id = "linh",
                role = Role.SHARER,
                displayName = "Linh",
                handle = "linh",
                sharerPaidAt = 1L,
            ),
        )
        val authConfig = AuthConfig(secret = "test-secret")
        val tokens = TokenService(authConfig)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.post("/founder/members/linh/password") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.issueMemberToken("founder", Role.FOUNDER)}")
            contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"brand-new-pass"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        // The member can now sign in with the founder-set password; the hash is never returned.
        assertFalse(res.bodyAsText().contains("passwordHash"))
        val signedIn = social.saigon.auth.PasswordService(repositories.users)
            .authenticate("linh", "brand-new-pass")
        assertEquals("linh", signedIn.id)

        // A non-founder may not reset anyone's password.
        val asSharer = client.post("/founder/members/linh/password") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.issueMemberToken("linh", Role.SHARER)}")
            contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"another-pass"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, asSharer.status)
    }

    @Test
    fun founderChangesAMembersNameButNotTheirOwn() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "Founder"))
        repositories.users.create(User(id = "linh", role = Role.SHARER, displayName = "Linh", sharerPaidAt = 1L))
        val authConfig = AuthConfig(secret = "test-secret")
        val founderToken = TokenService(authConfig).issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.patch("/founder/members/linh/name") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"  Linh Nguyen  "}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        // The name is trimmed and persisted.
        assertEquals("Linh Nguyen", repositories.users.get("linh")!!.displayName)

        // The founder changes their own name from their own profile, not here.
        val self = client.patch("/founder/members/founder/name") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"New Founder"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, self.status)
        assertEquals("Founder", repositories.users.get("founder")!!.displayName)

        // A blank name is rejected.
        val blank = client.patch("/founder/members/linh/name") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals("Linh Nguyen", repositories.users.get("linh")!!.displayName)
    }

    @Test
    fun nonFounderCannotChangeAMembersName() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "linh", role = Role.SHARER, displayName = "Linh", sharerPaidAt = 1L))
        repositories.users.create(User(id = "minh", role = Role.SHARER, displayName = "Minh", sharerPaidAt = 1L))
        val authConfig = AuthConfig(secret = "test-secret")
        val sharerToken = TokenService(authConfig).issueMemberToken("minh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.patch("/founder/members/linh/name") {
            header(HttpHeaders.Authorization, "Bearer $sharerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Hacked"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        // The member's name is left untouched.
        assertEquals("Linh", repositories.users.get("linh")!!.displayName)
    }

    @Test
    fun founderCannotPatchTheirOwnTier() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        val authConfig = AuthConfig(secret = "test-secret")
        val founderToken = TokenService(authConfig).issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.patch("/founder/members/founder/role") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"SHARER"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(Role.FOUNDER, repositories.users.get("founder")!!.role)
    }
}
