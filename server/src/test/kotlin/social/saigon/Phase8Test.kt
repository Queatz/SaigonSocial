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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import social.saigon.auth.AuthConfig
import social.saigon.auth.TokenService
import social.saigon.domain.Capability
import social.saigon.domain.Pricing
import social.saigon.domain.Role
import social.saigon.domain.Tier
import social.saigon.domain.User
import social.saigon.economics.EconomicsService
import social.saigon.repository.InMemoryPaymentRepository
import social.saigon.repository.InMemoryUserRepository
import social.saigon.repository.Repositories

class Phase8Test {

    private fun users(vararg users: User): InMemoryUserRepository {
        val repo = InMemoryUserRepository()
        users.forEach { repo.create(it) }
        return repo
    }

    // --- Pricing & "no platform cut" (DESIGN §3, §11) ---

    @Test
    fun pricingMatchesTheDesignAndTakesNoCut() {
        assertEquals(100_000L, Pricing.SHARER_ONE_TIME_VND)
        assertEquals(50_000L, Pricing.SUPPORTER_PERIOD_VND)
        assertEquals(90L, Pricing.SUPPORTER_PERIOD_DAYS)
        // No marketplace logic: the platform never takes a cut of user-to-user money.
        assertEquals(0, Pricing.PLATFORM_CUT_BPS)
        assertEquals(100_000L, Pricing.feeFor(Tier.SHARER))
        assertEquals(50_000L, Pricing.feeFor(Tier.SUPPORTER))
    }

    // --- Sharer one-time fee flow (DESIGN §3, §11) ---

    @Test
    fun sharerFeeSettlesPaymentAndGrantsSharerCapabilities() {
        val users = users(User(id = "minh", role = Role.SHARER, displayName = "M"))
        val payments = InMemoryPaymentRepository()
        val svc = EconomicsService(users, payments, clock = { 1_000L })

        // Tier capabilities are tied to payment status: unpaid → only Seeker-level access.
        val before = svc.status("minh")
        assertFalse(before.sharerPaid)
        assertEquals(Role.SEEKER, before.effectiveRole)
        assertFalse(Capability.CHAT_PUBLICLY in before.capabilities)

        val outcome = svc.paySharerFee("minh")
        assertEquals(Tier.SHARER, outcome.payment.tier)
        assertEquals(100_000L, outcome.payment.amountVnd)
        assertEquals(1_000L, outcome.user.sharerPaidAt)
        assertTrue(outcome.status.sharerPaid)
        assertEquals(Role.SHARER, outcome.status.effectiveRole)
        assertTrue(Capability.CHAT_PUBLICLY in outcome.status.capabilities)
        assertEquals(1, payments.byUser("minh").size)
    }

    @Test
    fun sharerFeeRejectsWrongAmountAndFounder() {
        val users = users(
            User(id = "minh", role = Role.SHARER, displayName = "M"),
            User(id = "founder", role = Role.FOUNDER, displayName = "F"),
        )
        val svc = EconomicsService(users, InMemoryPaymentRepository())

        assertFailsWith<IllegalArgumentException> { svc.paySharerFee("minh", amountVnd = 50_000L) }
        assertFailsWith<IllegalArgumentException> { svc.paySharerFee("founder") }
        assertFailsWith<IllegalArgumentException> { svc.paySharerFee("ghost") }
    }

    // --- Supporter subscription + expiry handling (DESIGN §3, §7, §11) ---

    @Test
    fun supporterSubscriptionRequiresSharerFeeThenActivatesAndExpires() {
        val users = users(User(id = "linh", role = Role.SHARER, displayName = "L"))
        val payments = InMemoryPaymentRepository()
        var now = 1_000L
        val svc = EconomicsService(users, payments, clock = { now })

        // Cannot subscribe before settling the one-time Sharer fee.
        assertFailsWith<IllegalArgumentException> { svc.paySupporterSubscription("linh") }

        svc.paySharerFee("linh")
        val sub = svc.paySupporterSubscription("linh")
        val expectedEnd = 1_000L + Pricing.SUPPORTER_PERIOD_MILLIS
        assertEquals(Role.SUPPORTER, sub.user.role)
        assertEquals(expectedEnd, sub.user.supporterUntil)
        assertEquals(expectedEnd, sub.payment.periodEndsAt)
        assertTrue(sub.status.supporterActive)
        assertEquals(Role.SUPPORTER, sub.status.effectiveRole)
        assertTrue(Capability.BE_PRIVATELY_MESSAGED in sub.status.capabilities)

        // Wrong amount is rejected.
        assertFailsWith<IllegalArgumentException> { svc.paySupporterSubscription("linh", amountVnd = 100_000L) }

        // Expiry handling: once the period lapses, the member falls back to an (active) Sharer.
        now = expectedEnd + 1
        val expired = svc.status("linh")
        assertFalse(expired.supporterActive)
        assertEquals(Role.SHARER, expired.effectiveRole)
        assertFalse(Capability.BE_PRIVATELY_MESSAGED in expired.capabilities)
    }

    @Test
    fun renewalExtendsFromTheCurrentExpiryWithoutLosingTime() {
        val users = users(User(id = "linh", role = Role.SHARER, displayName = "L"))
        var now = 1_000L
        val svc = EconomicsService(users, InMemoryPaymentRepository(), clock = { now })
        svc.paySharerFee("linh")

        val first = svc.paySupporterSubscription("linh")
        val firstEnd = first.user.supporterUntil!!

        // Renew while still active → the new period stacks on top of the remaining time.
        now += Pricing.SUPPORTER_PERIOD_MILLIS / 2
        val renewed = svc.paySupporterSubscription("linh")
        assertEquals(firstEnd + Pricing.SUPPORTER_PERIOD_MILLIS, renewed.user.supporterUntil)
    }

    // --- HTTP surface (DESIGN §3, §11) ---

    @Test
    fun pricingEndpointIsPublic() = testApplication {
        application { module(repositories = Repositories(), authConfig = AuthConfig(secret = "test-secret")) }
        val res = client.get("/economics/pricing")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"sharerOneTimeVnd\":100000"))
        assertTrue(body.contains("\"supporterPeriodVnd\":50000"))
        assertTrue(body.contains("\"platformCutBps\":0"))
    }

    @Test
    fun memberCanPayFeeThenSubscribeOverHttp() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "minh", role = Role.SHARER, displayName = "M"))
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("minh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val fee = client.post("/economics/sharer-fee") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, fee.status)
        assertTrue(fee.bodyAsText().contains("\"sharerPaid\":true"))

        val sub = client.post("/economics/supporter-subscription") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, sub.status)
        val subBody = sub.bodyAsText()
        assertTrue(subBody.contains("\"supporterActive\":true"))
        assertTrue(subBody.contains("\"token\":"))
        assertEquals(Role.SUPPORTER, repositories.users.get("minh")!!.role)

        val me = client.get("/economics/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"effectiveRole\":\"SUPPORTER\""))
    }

    // --- Founder onboarding settles the tier's payment status (DESIGN §3, §11) ---

    @Test
    fun founderOnboardingASupporterStartsASubscriptionPeriod() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        val authConfig = AuthConfig(secret = "test-secret")
        val founderToken = TokenService(authConfig).issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.post("/founder/onboard") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Linh","role":"SUPPORTER"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        // The newly onboarded Supporter has a paid Sharer fee and a real subscription expiry.
        val supporter = repositories.users.all().first { it.role == Role.SUPPORTER }
        assertNotNull(supporter.sharerPaidAt)
        val until = supporter.supporterUntil
        assertNotNull(until)
        assertTrue(until > System.currentTimeMillis())
    }
}
