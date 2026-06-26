package social.saigon.metrics

import java.util.UUID
import social.saigon.domain.MetricEvent
import social.saigon.domain.MetricType
import social.saigon.domain.MetricsSink
import social.saigon.domain.MetricsSnapshot
import social.saigon.repository.MetricRepository

/**
 * Success metrics & observability (TASKS Phase 10, DESIGN §14).
 *
 * A single, founder-only analytics sink. Feature services emit content-free [MetricEvent]s through
 * the [MetricsSink] seam; this service stamps them with an id + timestamp, persists them, and
 * aggregates the six internal metrics from DESIGN §14:
 *
 * 1. Seeker → Sharer conversion
 * 2. Sharer → Supporter conversion
 * 3. Daily Active Users (DAU)
 * 4. Average chat duration
 * 5. Emoji selection rate
 * 6. Report rate (target < 1%)
 *
 * Nothing collected here is public or contains message content — it stores only the minimal facts
 * needed to compute the metrics. A [clock] seam keeps the DAU window deterministic in tests.
 */
class MetricsService(
    private val events: MetricRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : MetricsSink {

    override fun record(type: MetricType, userId: String?, value: Long?) {
        events.add(
            MetricEvent(
                id = UUID.randomUUID().toString(),
                type = type,
                userId = userId,
                createdAt = clock(),
                value = value,
            ),
        )
    }

    /** Aggregate the internal success metrics over all recorded events (DESIGN §14). */
    fun snapshot(): MetricsSnapshot {
        val all = events.all()
        val now = clock()

        val sharerConversions = all.count { it.type == MetricType.SHARER_CONVERSION }
        val supporterConversions = all.count { it.type == MetricType.SUPPORTER_CONVERSION }

        // DAU: distinct members active within the trailing 24h window.
        val activeEvents = all.filter { it.type == MetricType.ACTIVE && it.userId != null }
        val dau = activeEvents
            .filter { now - it.createdAt < DAY_MILLIS }
            .mapNotNull { it.userId }
            .toSet()
            .size
        val activeUsers = activeEvents.mapNotNull { it.userId }.toSet().size

        // Average chat duration over completed private chats.
        val durations = all.filter { it.type == MetricType.CHAT_ENDED }.mapNotNull { it.value }
        val completedChats = durations.size
        val avgDurationMillis = if (durations.isEmpty()) 0L else durations.sum() / durations.size

        // Emoji selection rate: selections ÷ prompts shown.
        val prompts = all.count { it.type == MetricType.FINAL_EMOJI_PROMPTED }
        val selections = all.count { it.type == MetricType.FINAL_EMOJI_SELECTED }
        val emojiRate = if (prompts == 0) 0.0 else selections.toDouble() / prompts

        // Report rate: reports filed ÷ distinct active members (target < 1%).
        val totalReports = all.count { it.type == MetricType.REPORT_FILED }
        val reportRate = if (activeUsers == 0) 0.0 else totalReports.toDouble() / activeUsers

        // Founder-initiated manual tier changes (comps, downgrades, demotions).
        val founderTierChanges = all.count { it.type == MetricType.TIER_CHANGE }

        return MetricsSnapshot(
            seekerToSharerConversions = sharerConversions,
            sharerToSupporterConversions = supporterConversions,
            dailyActiveUsers = dau,
            activeUsers = activeUsers,
            averageChatDurationMillis = avgDurationMillis,
            averageChatDurationSeconds = avgDurationMillis / 1000L,
            completedChats = completedChats,
            emojiSelectionRate = emojiRate,
            totalReports = totalReports,
            reportRate = reportRate,
            founderTierChanges = founderTierChanges,
        )
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
