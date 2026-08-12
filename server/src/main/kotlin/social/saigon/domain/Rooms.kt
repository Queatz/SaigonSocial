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
    /**
     * Optional caller-chosen room URL (id). When blank, the server assigns a random id. When
     * provided it must be URL-safe (lowercase letters, digits and hyphens) and not already taken.
     */
    val id: String? = null,
    /** Room photos (issue: "Room photos will show up visibly in the room lists..."). */
    val photos: List<ProfilePhoto> = emptyList(),
    /** Markdown-supported community description (issue: "Two room upgrades"). */
    val description: String = "",
)

/**
 * Founder-supplied fields for renaming/editing an existing room (DESIGN §6). Null fields are left
 * unchanged.
 */
@Serializable
data class RoomUpdate(
    val name: String? = null,
    val emoji: String? = null,
    val atmosphere: String? = null,
    val photos: List<ProfilePhoto>? = null,
    /** Markdown-supported community description; null leaves the existing value unchanged. */
    val description: String? = null,
)

/**
 * Plain-text-only enforcement for public chat and threads (DESIGN §2, §6).
 *
 * The platform is deliberately text-first: no images, voice notes, or video. Since the transport
 * only carries text frames, "no media" is enforced by rejecting anything that is not human-readable
 * plain text — control characters (which smuggle binary/rich payloads) are rejected, and an upper
 * length bound keeps messages conversational. Newlines are allowed so messages can span lines.
 */
object PlainText {
    /** Upper bound on a single public-chat message / thread reply. */
    const val MAX_LENGTH = 2_000

    /**
     * Returns true if [text] is acceptable plain text: non-blank, within [MAX_LENGTH], and free of
     * control characters — except newlines (`\n`/`\r`), which are permitted so messages can span
     * multiple lines.
     */
    fun isValid(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_LENGTH) return false
        return text.none { it.isISOControl() && it != '\n' && it != '\r' }
    }
}
