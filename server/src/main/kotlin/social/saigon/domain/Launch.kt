package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * Launch hardening (TASKS Phase 11): a single, machine-checkable source of truth for the platform's
 * cross-cutting non-functional guarantees, so they can be asserted in tests and surfaced on a
 * health endpoint rather than living only in prose.
 *
 * These mirror the product's core promises (DESIGN §2, §7, §12):
 * - **Privacy by default** — no chat history stored, no read receipts, no "last seen".
 * - **Web-only** — no push notifications path exists.
 * - **One city** — Saigon only; the app is place-based, not global.
 */
object LaunchConfig {
    /** The single launch city. The app is place-based, not global (DESIGN §2). */
    const val LAUNCH_CITY = "Ho Chi Minh City (Saigon)"

    /** Single-city scope: the platform is intentionally not multi-city. */
    const val SINGLE_CITY = true

    /** Private chat history is never persisted beyond the chat's lifetime (DESIGN §7, §12). */
    const val STORES_CHAT_HISTORY = false

    /** No read receipts are emitted (DESIGN §7). */
    const val READ_RECEIPTS = false

    /** No "last seen" / presence timestamps are exposed (DESIGN §7). */
    const val LAST_SEEN = false

    /** No typing indicators are emitted (DESIGN §7). */
    const val TYPING_INDICATORS = false

    /** Web-only: there is no push-notification delivery path (DESIGN §12). */
    const val PUSH_NOTIFICATIONS = false

    /** A serializable view for a public health/launch-info endpoint. */
    fun guarantees(): LaunchGuarantees = LaunchGuarantees(
        launchCity = LAUNCH_CITY,
        singleCity = SINGLE_CITY,
        storesChatHistory = STORES_CHAT_HISTORY,
        readReceipts = READ_RECEIPTS,
        lastSeen = LAST_SEEN,
        typingIndicators = TYPING_INDICATORS,
        pushNotifications = PUSH_NOTIFICATIONS,
    )
}

/** Public, read-only view of the launch-hardening guarantees (TASKS Phase 11). */
@Serializable
data class LaunchGuarantees(
    val launchCity: String,
    val singleCity: Boolean,
    val storesChatHistory: Boolean,
    val readReceipts: Boolean,
    val lastSeen: Boolean,
    val typingIndicators: Boolean,
    val pushNotifications: Boolean,
)
