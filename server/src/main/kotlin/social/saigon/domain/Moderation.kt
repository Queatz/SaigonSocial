package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * Community health & abuse prevention (TASKS Phase 7, DESIGN §10).
 *
 * The moderation model is intentionally lean and unforgiving, matching the product's "trust-first"
 * stance: a report button lives on every profile and chat, two reports against the same person are
 * a permanent ban (no appeals), and unsolicited sales escalate warning → second warning → ban.
 */

/** What a report is filed against (DESIGN §10). A report can target a profile or a chat. */
@Serializable
enum class ReportTarget {
    /** Reporting a person's profile. */
    PROFILE,

    /** Reporting behaviour inside a chat (public room or private). */
    CHAT,
}

/**
 * An abuse report filed by one member against another (DESIGN §10).
 *
 * Reports are counted by *distinct reporter*; reaching [ModerationPolicy.REPORTS_TO_BAN] distinct
 * reporters permanently bans the subject.
 */
@Serializable
data class Report(
    val id: String,
    val reporterId: String,
    val subjectId: String,
    val target: ReportTarget,
    /** Optional context id (e.g. the room/chat id the report was filed from). */
    val contextId: String? = null,
    /** Optional free-text reason. */
    val reason: String? = null,
    val createdAt: Long,
)

/** Why a founder issued a moderation warning (DESIGN §10). */
@Serializable
enum class WarningReason {
    /** Mentioning services/products without being asked (DESIGN §10). */
    UNSOLICITED_SALES,

    /** Any other founder-judged violation. */
    OTHER,
}

/**
 * A founder-issued warning (DESIGN §10). The first is a caution; the second is a ban.
 */
@Serializable
data class Warning(
    val id: String,
    val subjectId: String,
    /** The founder who issued the warning. */
    val issuedBy: String,
    val reason: WarningReason = WarningReason.UNSOLICITED_SALES,
    /** Optional free-text note. */
    val note: String? = null,
    val createdAt: Long,
)

/**
 * Aggregated moderation state for a single user (DESIGN §10).
 */
@Serializable
data class ModerationStatus(
    val userId: String,
    val banned: Boolean = false,
    val banReason: String? = null,
    /** Number of *distinct* reporters who have reported this user. */
    val reportCount: Int = 0,
    /** Number of founder warnings issued against this user. */
    val warningCount: Int = 0,
    /** Founder has manually verified this member (no bots / no automated sign-up). */
    val verified: Boolean = false,
)

/**
 * The moderation thresholds (DESIGN §10). Kept here as a single source of truth.
 */
object ModerationPolicy {
    /** Two reports = permanent ban, no appeals (DESIGN §10). */
    const val REPORTS_TO_BAN = 2

    /** Warning → second warning → ban (DESIGN §10). */
    const val WARNINGS_TO_BAN = 2

    const val BAN_REASON_REPORTS = "Two reports — permanent ban"
    const val BAN_REASON_SALES = "Unsolicited sales — second warning"
}

/**
 * Thrown when an authenticated caller is not permitted to perform an action (e.g. a banned account,
 * or a non-founder attempting a founder-only operation). Mapped to HTTP 403 Forbidden.
 */
class ForbiddenException(message: String) : RuntimeException(message)
