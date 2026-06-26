package social.saigon.economics

import java.util.UUID
import social.saigon.domain.CapabilityMatrix
import social.saigon.domain.EconomicStatus
import social.saigon.domain.MetricType
import social.saigon.domain.MetricsSink
import social.saigon.domain.Payment
import social.saigon.domain.Pricing
import social.saigon.domain.Role
import social.saigon.domain.Tier
import social.saigon.domain.User
import social.saigon.repository.PaymentRepository
import social.saigon.repository.UserRepository

/**
 * Economics & onboarding (TASKS Phase 8, DESIGN §3, §11).
 *
 * Owns the platform's money rules:
 * - The **one-time Sharer fee** (100,000 VND) settles a member's join payment.
 * - The **Supporter subscription** (50,000 VND / 3 months) makes a member available for 1-on-1
 *   chats; it is time-boxed and renews by extending from the later of *now* or the current expiry.
 * - **Tier capabilities are tied to payment status**: an unpaid would-be Sharer keeps only
 *   Seeker-level capabilities, and an expired Supporter is treated as a Sharer ([effectiveRole]).
 * - The platform takes **no cut** of user-to-user transactions — there is no marketplace logic, so
 *   the only money this service ever records is the platform fees above.
 *
 * The founder still personally onboards every member (no open sign-up); this service records the
 * payments and activates/refreshes the resulting tier. A [clock] seam keeps timestamps
 * deterministic in tests.
 */
class EconomicsService(
    private val users: UserRepository,
    private val payments: PaymentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val metrics: MetricsSink = MetricsSink.None,
) {
    /** Result of recording a payment: the receipt plus the member's refreshed standing. */
    data class PaymentOutcome(
        val payment: Payment,
        val user: User,
        val status: EconomicStatus,
    )

    /**
     * Settle the one-time Sharer join fee for [userId] (DESIGN §3, §11).
     *
     * @throws IllegalArgumentException if the user is unknown, the Founder (never billed), or the
     *   [amountVnd] does not match [Pricing.SHARER_ONE_TIME_VND].
     */
    fun paySharerFee(userId: String, amountVnd: Long = Pricing.SHARER_ONE_TIME_VND): PaymentOutcome {
        val user = requireUser(userId)
        require(user.role != Role.FOUNDER) { "Founder is not billed" }
        require(amountVnd == Pricing.SHARER_ONE_TIME_VND) {
            "Sharer fee is ${Pricing.SHARER_ONE_TIME_VND} VND"
        }
        val now = clock()
        // First-time settlement of the one-time fee is a Seeker → Sharer conversion (DESIGN §14).
        val firstTime = user.sharerPaidAt == null
        // A paid member is at least a Sharer; never downgrade an existing Supporter.
        val role = if (user.role == Role.SEEKER) Role.SHARER else user.role
        val updated = users.update(user.copy(role = role, sharerPaidAt = user.sharerPaidAt ?: now))
        if (firstTime) metrics.record(MetricType.SHARER_CONVERSION, userId = userId)
        val payment = payments.add(
            Payment(
                id = UUID.randomUUID().toString(),
                userId = userId,
                tier = Tier.SHARER,
                amountVnd = amountVnd,
                createdAt = now,
            ),
        )
        return PaymentOutcome(payment, updated, statusOf(updated))
    }

    /**
     * Purchase or renew a Supporter subscription period for [userId] (DESIGN §3, §7, §11).
     *
     * Requires the member to have settled the one-time Sharer fee first. The new period extends
     * from the later of *now* or any unexpired existing subscription, so renewals never lose time.
     * The member's role is promoted to [Role.SUPPORTER].
     *
     * @throws IllegalArgumentException if the user is unknown, the Founder, has not paid the Sharer
     *   fee, or the [amountVnd] does not match [Pricing.SUPPORTER_PERIOD_VND].
     */
    fun paySupporterSubscription(
        userId: String,
        amountVnd: Long = Pricing.SUPPORTER_PERIOD_VND,
    ): PaymentOutcome {
        val user = requireUser(userId)
        require(user.role != Role.FOUNDER) { "Founder is not billed" }
        require(amountVnd == Pricing.SUPPORTER_PERIOD_VND) {
            "Supporter subscription is ${Pricing.SUPPORTER_PERIOD_VND} VND"
        }
        require(user.sharerPaidAt != null) { "Must pay the Sharer fee before subscribing" }
        val now = clock()
        // Extend from the later of now or the current (unexpired) expiry so renewals stack.
        val base = maxOf(now, user.supporterUntil ?: now)
        val periodEnd = base + Pricing.SUPPORTER_PERIOD_MILLIS
        // First promotion to Supporter is a Sharer → Supporter conversion; renewals don't count.
        val firstTime = user.role != Role.SUPPORTER
        val updated = users.update(user.copy(role = Role.SUPPORTER, supporterUntil = periodEnd))
        if (firstTime) metrics.record(MetricType.SUPPORTER_CONVERSION, userId = userId)
        val payment = payments.add(
            Payment(
                id = UUID.randomUUID().toString(),
                userId = userId,
                tier = Tier.SUPPORTER,
                amountVnd = amountVnd,
                createdAt = now,
                periodEndsAt = periodEnd,
            ),
        )
        return PaymentOutcome(payment, updated, statusOf(updated))
    }

    /** The member's aggregated economic standing (DESIGN §3, §11). */
    fun status(userId: String): EconomicStatus = statusOf(requireUser(userId))

    /**
     * The role actually in force for [user] once payment status is applied (DESIGN §3, §11):
     * - an expired Supporter falls back to Sharer;
     * - a member who has not settled the one-time Sharer fee falls back to Seeker;
     * - the Founder is always the Founder.
     */
    fun effectiveRole(user: User): Role = when (user.role) {
        Role.FOUNDER -> Role.FOUNDER
        Role.SEEKER -> Role.SEEKER
        Role.SUPPORTER -> if (supporterActive(user)) Role.SUPPORTER else sharerOrSeeker(user)
        Role.SHARER -> sharerOrSeeker(user)
    }

    private fun sharerOrSeeker(user: User): Role =
        if (user.sharerPaidAt != null) Role.SHARER else Role.SEEKER

    private fun supporterActive(user: User): Boolean {
        val until = user.supporterUntil ?: return false
        return until > clock()
    }

    private fun statusOf(user: User): EconomicStatus {
        val effective = effectiveRole(user)
        return EconomicStatus(
            userId = user.id,
            role = user.role,
            effectiveRole = effective,
            sharerPaid = user.sharerPaidAt != null,
            supporterUntil = user.supporterUntil,
            supporterActive = supporterActive(user),
            capabilities = CapabilityMatrix.capabilities(effective).toList(),
        )
    }

    private fun requireUser(userId: String): User =
        users.get(userId) ?: throw IllegalArgumentException("Unknown user")
}
