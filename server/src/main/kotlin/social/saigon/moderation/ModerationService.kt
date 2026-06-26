package social.saigon.moderation

import java.util.UUID
import social.saigon.domain.MetricType
import social.saigon.domain.MetricsSink
import social.saigon.domain.ModerationPolicy
import social.saigon.domain.ModerationStatus
import social.saigon.domain.Report
import social.saigon.domain.ReportTarget
import social.saigon.domain.Warning
import social.saigon.domain.WarningReason
import social.saigon.repository.ModerationRepository
import social.saigon.repository.UserRepository

/**
 * Moderation & safety (TASKS Phase 7, DESIGN §10).
 *
 * Owns the platform's community-health rules:
 * - A report button lives on every profile and chat; **two distinct reporters** permanently ban the
 *   subject (no appeals).
 * - Unsolicited sales escalate **warning → second warning → ban**, issued by the founder.
 * - The founder manually verifies members (no bots / no automated sign-up) via [verify].
 *
 * Bans are permanent; [isBanned] is consulted by the chat transport and member routes to silence a
 * banned account everywhere.
 *
 * A [clock] seam keeps timestamps deterministic in tests.
 */
class ModerationService(
    private val moderation: ModerationRepository,
    private val users: UserRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val metrics: MetricsSink = MetricsSink.None,
) {
    /** Result of filing a report. */
    data class ReportOutcome(
        val report: Report,
        /** Distinct reporters now on record against the subject. */
        val reportCount: Int,
        /** Whether this report tipped the subject into (or already had them in) a ban. */
        val subjectBanned: Boolean,
    )

    /** Result of issuing a warning. */
    data class WarnOutcome(
        val warning: Warning,
        val warningCount: Int,
        val subjectBanned: Boolean,
    )

    fun isBanned(userId: String): Boolean = moderation.isBanned(userId)

    fun isVerified(userId: String): Boolean = moderation.isVerified(userId)

    /**
     * File an abuse report from [reporterId] against [subjectId] (DESIGN §10).
     *
     * Reports are deduplicated per reporter, so the same person reporting twice still counts once.
     * Reaching [ModerationPolicy.REPORTS_TO_BAN] distinct reporters permanently bans the subject.
     *
     * @throws IllegalArgumentException if a user reports themself or the subject is unknown.
     */
    fun report(
        reporterId: String,
        subjectId: String,
        target: ReportTarget,
        contextId: String? = null,
        reason: String? = null,
    ): ReportOutcome {
        require(reporterId != subjectId) { "Cannot report yourself" }
        require(users.get(subjectId) != null) { "Unknown user" }

        val report = moderation.addReport(
            Report(
                id = UUID.randomUUID().toString(),
                reporterId = reporterId,
                subjectId = subjectId,
                target = target,
                contextId = contextId,
                reason = reason,
                createdAt = clock(),
            ),
        )

        // Feeds the community-health report rate (DESIGN §14); no message content is recorded.
        metrics.record(MetricType.REPORT_FILED, userId = subjectId)

        val reportCount = moderation.reportCount(subjectId)
        if (reportCount >= ModerationPolicy.REPORTS_TO_BAN) {
            moderation.ban(subjectId, ModerationPolicy.BAN_REASON_REPORTS)
        }
        return ReportOutcome(report, reportCount, moderation.isBanned(subjectId))
    }

    /**
     * Issue a founder warning against [subjectId] (DESIGN §10).
     *
     * The first warning is a caution; the second (or beyond) is a permanent ban. Used primarily for
     * the unsolicited-sales workflow, but [reason] allows other founder-judged violations.
     *
     * @throws IllegalArgumentException if the subject is unknown.
     */
    fun warn(
        founderId: String,
        subjectId: String,
        reason: WarningReason = WarningReason.UNSOLICITED_SALES,
        note: String? = null,
    ): WarnOutcome {
        require(users.get(subjectId) != null) { "Unknown user" }

        val warning = moderation.addWarning(
            Warning(
                id = UUID.randomUUID().toString(),
                subjectId = subjectId,
                issuedBy = founderId,
                reason = reason,
                note = note,
                createdAt = clock(),
            ),
        )

        val warningCount = moderation.warnings(subjectId).size
        if (warningCount >= ModerationPolicy.WARNINGS_TO_BAN) {
            moderation.ban(subjectId, ModerationPolicy.BAN_REASON_SALES)
        }
        return WarnOutcome(warning, warningCount, moderation.isBanned(subjectId))
    }

    /**
     * Founder-only manual verification (DESIGN §10): mark [userId] as a vouched-for human. There is
     * no automated sign-up, so this is the only path to a verified flag.
     */
    fun verify(userId: String) {
        require(users.get(userId) != null) { "Unknown user" }
        moderation.setVerified(userId, true)
    }

    /** The aggregated moderation state for [userId] (DESIGN §10). */
    fun status(userId: String): ModerationStatus = ModerationStatus(
        userId = userId,
        banned = moderation.isBanned(userId),
        banReason = moderation.banReason(userId),
        reportCount = moderation.reportCount(userId),
        warningCount = moderation.warnings(userId).size,
        verified = moderation.isVerified(userId),
    )
}
