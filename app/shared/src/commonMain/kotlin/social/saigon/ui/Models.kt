package social.saigon.ui

import kotlinx.serialization.Serializable

/**
 * Client-side mirrors of the backend's JSON contract (DESIGN §12).
 *
 * These are the exact shapes the Ktor [SaigonApi] reads from / writes to the live server, so the
 * Compose Web client renders real, persisted data — there is no in-memory sample content. Only the
 * fixed option sets defined by the PRD itself (intents, languages, closing emojis) live here as
 * constants; everything else is fetched from the backend.
 */

/** User roles (DESIGN §3). Mirrors the server `Role` enum by name. */
@Serializable
enum class Role(val label: String) {
    SEEKER("Seeker"),
    SHARER("Sharer"),
    SUPPORTER("Supporter"),
    FOUNDER("Founder"),
}

/** Primary intent badge (DESIGN §4). Serialized by name to match the server `Intent` enum. */
@Serializable
enum class Intent(val badge: String) {
    JUST_CHATTING("💬 Just Chatting"),
    DEEP_TALK("🧠 Deep Talk"),
    HEAVY_TOPICS("🫂 Heavy Topics"),
    OPEN_TO_COLLAB("🎯 Open to Collab"),
    SURPRISE_ME("🎲 Surprise Me"),
    CASUAL("☕ Casual"),
}

/** A public room as shown in the room list (`GET /rooms`, DESIGN §6). */
@Serializable
data class RoomSummary(
    val id: String,
    val name: String,
    val emoji: String,
    val atmosphere: String,
    val occupants: Int,
)

/** A person currently in a room with intent + languages (`GET /rooms/{id}/occupants`, DESIGN §6). */
@Serializable
data class OccupantView(
    val name: String,
    val avatarUrl: String = "",
    val role: Role,
    val intent: Intent? = null,
    val languages: List<String> = emptyList(),
    /** "Available for chat" indicator for active Supporters (DESIGN §7). */
    val available: Boolean = false,
)

/** A public-chat message with its live reply-count (`GET /rooms/{id}/messages`, DESIGN §9). */
@Serializable
data class FeedMessage(
    val id: String,
    val roomId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
    val replyCount: Int = 0,
)

/** A reply inside a message's ephemeral thread (`GET .../thread`, DESIGN §9). */
@Serializable
data class ThreadReply(
    val id: String,
    val parentMessageId: String,
    val replyToId: String? = null,
    val authorId: String,
    val text: String,
    val createdAt: Long,
)

/** The caller's room-scoped anonymous identity (`GET /rooms/{id}/identity`, DESIGN §5). */
@Serializable
data class RoomIdentity(
    val userId: String,
    val roomId: String,
    val name: String,
    val avatarUrl: String = "",
)

/** The caller's editable persistent profile (`GET /profile/me`, DESIGN §4). */
@Serializable
data class ProfileResponse(
    val id: String,
    val role: Role,
    val displayName: String,
    val interests: List<String> = emptyList(),
    val intent: Intent? = null,
    val languages: List<String> = emptyList(),
    val offlineSchedule: List<String> = emptyList(),
    val supporterUntil: Long? = null,
    val capabilities: List<String> = emptyList(),
)

/** Editable profile fields (`PUT /profile/me`, DESIGN §4). Null fields are left unchanged. */
@Serializable
data class ProfileUpdate(
    val interests: List<String>? = null,
    val intent: Intent? = null,
    val languages: List<String>? = null,
    val offlineSchedule: List<String>? = null,
)

@Serializable
data class SeekerTokenRequest(val deviceId: String)

@Serializable
data class TokenResponse(val token: String)

/** Founder-only request to onboard a new member, minting their access token (DESIGN §2, §3). */
@Serializable
data class OnboardRequest(val displayName: String, val role: Role = Role.SHARER)

/** The new member's id and the access token they sign in with (`POST /founder/onboard`). */
@Serializable
data class OnboardResponse(val userId: String, val token: String)

/** A single private-chat message in the current session (DESIGN §7). Ephemeral; never persisted. */
data class PrivateMessage(
    val author: String,
    val text: String,
    val mine: Boolean,
)

/**
 * Fixed option sets defined by the PRD itself (DESIGN §4, §8) — not sample data. They are the
 * canonical choices the UI offers; the selected values are sent to / read from the backend.
 */
object UiConstants {
    /** Closing-emoji options (DESIGN §8). */
    val emojiOptions: List<String> = listOf("😊", "💡", "😂", "🙏", "🤝", "❤️", "✨", "🔥")

    /** Selectable intents (DESIGN §4). */
    val intents: List<Intent> = Intent.entries.toList()

    /** Supported language codes (DESIGN §4). */
    val supportedLanguages: List<String> = listOf("vi", "en", "ko", "zh", "ja", "fr")
}
