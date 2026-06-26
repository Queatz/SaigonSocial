package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * Economics & onboarding (TASKS Phase 8, DESIGN §3, §11).
 *
 * The model is intentionally lean and matches the product's "economic transparency" promise:
 * a one-time fee to join as a Sharer, an optional time-boxed Supporter subscription for 1-on-1
 * availability, and **no platform cut on any user-to-user transactions** (there is no marketplace
 * logic — money that changes hands between members is between them).
 */

/** A paid platform tier (DESIGN §11). Founder is an admin role and is never billed. */
@Serializable
enum class Tier {
    /** One-time join fee. */
    SHARER,

    /** Recurring availability subscription. */
    SUPPORTER,
}

/**
 * The pricing table (DESIGN §3, §11). Kept here as a single source of truth.
 *
 * Amounts are in Vietnamese đồng (VND). [PLATFORM_CUT_BPS] is fixed at zero: Saigon.Social takes
 * **no cut** of any transaction between users — these prices are the *only* money the platform
 * ever handles.
 */
object Pricing {
    /** One-time fee to join as a Sharer (DESIGN §3, §11). */
    const val SHARER_ONE_TIME_VND = 100_000L

    /** Supporter subscription fee, charged per [SUPPORTER_PERIOD_DAYS] window (DESIGN §3, §11). */
    const val SUPPORTER_PERIOD_VND = 50_000L

    /** Length of a single Supporter subscription period: 3 months (DESIGN §3, §11). */
    const val SUPPORTER_PERIOD_DAYS = 90L

    /** Supporter subscription period in milliseconds. */
    const val SUPPORTER_PERIOD_MILLIS = SUPPORTER_PERIOD_DAYS * 24L * 60L * 60L * 1000L

    /**
     * The platform's cut of user-to-user transactions, in basis points. Always zero — there is no
     * marketplace and no transaction-fee logic (DESIGN §11).
     */
    const val PLATFORM_CUT_BPS = 0

    /** The fee charged for a given [tier]. */
    fun feeFor(tier: Tier): Long = when (tier) {
        Tier.SHARER -> SHARER_ONE_TIME_VND
        Tier.SUPPORTER -> SUPPORTER_PERIOD_VND
    }
}

/**
 * A recorded platform payment (DESIGN §11). Either the one-time Sharer fee or a single Supporter
 * subscription period. Never includes a cut of user-to-user money.
 */
@Serializable
data class Payment(
    val id: String,
    val userId: String,
    val tier: Tier,
    /** Amount paid, in VND. Must equal [Pricing.feeFor]. */
    val amountVnd: Long,
    val createdAt: Long,
    /**
     * For a Supporter subscription, the epoch millis the period this payment funds expires;
     * null for the one-time Sharer fee.
     */
    val periodEndsAt: Long? = null,
)

/**
 * A member's aggregated economic standing (TASKS Phase 8, DESIGN §3, §11).
 *
 * [effectiveRole] reflects payment status: an expired Supporter is treated as a Sharer, and an
 * unpaid would-be Sharer keeps only Seeker-level capabilities until the one-time fee settles. The
 * [capabilities] are derived from [effectiveRole] so the UI and routes share a single answer to
 * "what can this person do right now?".
 */
@Serializable
data class EconomicStatus(
    val userId: String,
    /** The account's nominal role on record. */
    val role: Role,
    /** The role actually in force once payment status is applied. */
    val effectiveRole: Role,
    /** Whether the one-time Sharer fee has settled. */
    val sharerPaid: Boolean,
    /** Epoch millis the Supporter subscription expires, or null if not a Supporter. */
    val supporterUntil: Long?,
    /** Whether the Supporter subscription is currently active (not expired). */
    val supporterActive: Boolean,
    /** Capabilities currently in force (derived from [effectiveRole]). */
    val capabilities: List<Capability>,
)
