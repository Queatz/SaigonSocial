package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * Founder-supplied fields for creating a room (TASKS Phase 3, DESIGN §6).
 *
 * The id and `createdBy` are assigned server-side; only the founder may create rooms.
 */
@Serializable
data class RoomCreate(
    /** Single string, no '#' prefix. */
    val name: String,
    /** Primary emoji displayed before the name. */
    val emoji: String,
    /** Free-text atmosphere/vibe. */
    val atmosphere: String = "",
)

/**
 * Plain-text-only enforcement for public chat and threads (DESIGN §2, §6).
 *
 * The platform is deliberately text-first: no images, voice notes, or video. Since the transport
 * only carries text frames, "no media" is enforced by rejecting anything that is not a single
 * block of human-readable plain text — control characters (which smuggle binary/rich payloads) are
 * rejected, and an upper length bound keeps messages conversational.
 */
object PlainText {
    /** Upper bound on a single public-chat message / thread reply. */
    const val MAX_LENGTH = 2_000

    /**
     * Returns true if [text] is acceptable plain text: non-blank, within [MAX_LENGTH], and free of
     * control characters (tabs/newlines included — public chat messages are single-line).
     */
    fun isValid(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_LENGTH) return false
        return text.none { it.isISOControl() }
    }
}
