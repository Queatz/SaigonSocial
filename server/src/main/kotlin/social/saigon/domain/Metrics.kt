package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * Success metrics & observability (TASKS Phase 10, DESIGN §14).
 *
 * Saigon.Social tracks a deliberately small, **internal-only** set of funnel and health signals.
 * Nothing here is public: the snapshot is founder-gated, and the events store no message content —
 * only the minimal facts needed to compute the metrics in DESIGN §14.
 */
@Serializable
enum class MetricType {
    /** A member settled the one-time Sharer fee for the first time (Seeker → Sharer). */
    SHARER_CONVERSION,

    /** A member started a Supporter subscription for the first time (Sharer → Supporter). */
    SUPPORTER_CONVERSION,

    /** A member was active (joined a room or sent a public message). Feeds Daily Active Users. */
    ACTIVE,

    /** A private chat ended; [MetricEvent.value] carries its duration in milliseconds. */
    CHAT_ENDED,

    /** The closing-emoji screen was shown to a participant (denominator of the selection rate). */
    FINAL_EMOJI_PROMPTED,

    /** A participant picked a closing emoji (numerator of the selection rate). */
    FINAL_EMOJI_SELECTED,

    /** An abuse report was filed (feeds the report rate, target < 1%). */
    REPORT_FILED,
}

/**
 * A single recorded analytics event (DESIGN §14). Intentionally content-free: it captures the
 * event [type], an optional [userId], a timestamp, and an optional numeric [value] (e.g. a chat's
 * duration in millis for [MetricType.CHAT_ENDED]).
 */
@Serializable
data class MetricEvent(
    val id: String,
    val type: MetricType,
    val userId: String? = null,
    val createdAt: Long,
    val value: Long? = null,
)

/**
 * A lightweight sink the platform's services emit analytics events into (TASKS Phase 10).
 *
 * Keeping the seam this small means feature services depend only on this interface, not on the
 * metrics implementation. The default [None] is a no-op so existing call sites and tests that do
 * not care about analytics are unaffected.
 */
interface MetricsSink {
    fun record(type: MetricType, userId: String? = null, value: Long? = null)

    /** A no-op sink used as the default when no analytics collection is wired in. */
    object None : MetricsSink {
        override fun record(type: MetricType, userId: String?, value: Long?) = Unit
    }
}

/**
 * The aggregated internal success metrics (DESIGN §14, TASKS Phase 10).
 *
 * Conversions are first-time counts; [dailyActiveUsers] counts distinct active members in the last
 * 24 hours; [emojiSelectionRate] and [reportRate] are ratios in `[0, 1]`. [reportRate] targets
 * below 1% (`0.01`) for a healthy community.
 */
@Serializable
data class MetricsSnapshot(
    val seekerToSharerConversions: Int,
    val sharerToSupporterConversions: Int,
    val dailyActiveUsers: Int,
    /** Distinct members ever active (denominator for the report rate). */
    val activeUsers: Int,
    val averageChatDurationMillis: Long,
    val averageChatDurationSeconds: Long,
    val completedChats: Int,
    /** Closing emojis selected ÷ closing-emoji prompts shown, in `[0, 1]`. */
    val emojiSelectionRate: Double,
    val totalReports: Int,
    /** Reports filed ÷ distinct active members, in `[0, 1]`. Target < 0.01 (DESIGN §14). */
    val reportRate: Double,
)
