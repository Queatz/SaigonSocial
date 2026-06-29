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
    CASUAL("☕ Casual");

    /** Just the leading emoji of the [badge], for compact emoji-only display (DESIGN §6). */
    val emoji: String get() = badge.substringBefore(' ')
}

/** A public room as shown in the room list (`GET /rooms`, DESIGN §6). */
@Serializable
data class RoomSummary(
    val id: String,
    val name: String,
    val emoji: String,
    val atmosphere: String,
    val occupants: Int,
    /** Text of the room's most recent message, loaded with the list; null if the room has none. */
    val lastMessageText: String? = null,
    /** The most recent message's room-scoped sender name; null if the room has no messages. */
    val lastMessageSender: String? = null,
    /** Unix epoch millis of the most recent message; null if the room has no messages. */
    val lastMessageAt: Long? = null,
)

/** A person currently in a room with intent + languages (`GET /rooms/{id}/occupants`, DESIGN §6). */
@Serializable
data class OccupantView(
    /** The occupant's persistent user id, used to start a private chat (DESIGN §7). */
    val userId: String = "",
    val name: String,
    val avatarUrl: String = "",
    val role: Role,
    val intents: List<Intent> = emptyList(),
    val languages: List<String> = emptyList(),
    /** The member's free-text introduction (their interests), shown on the detail sheet (DESIGN §4). */
    val interests: List<String> = emptyList(),
    /** Time frame(s) the member is often available offline, shown on the detail sheet (DESIGN §4). */
    val offlineSchedule: List<String> = emptyList(),
    /** Services a Supporter offers, shown under "Providing" on the detail sheet. */
    val providingServices: List<String> = emptyList(),
    /** "Available for chat" indicator for active Supporters (DESIGN §7). */
    val available: Boolean = false,
)

/** A public-chat message with its live reply-count (`GET /rooms/{id}/messages`, DESIGN §9). */
@Serializable
data class FeedMessage(
    val id: String,
    val roomId: String,
    val authorId: String,
    /** The author's room-scoped display name (DESIGN §5). Falls back to the id for legacy rows. */
    val authorName: String = "",
    val text: String,
    val createdAt: Long,
    val replyCount: Int = 0,
) {
    /** The name to render in the feed — the room-scoped name when present, else the raw id. */
    val displayAuthor: String get() = authorName.ifBlank { authorId }
}

/** A reply inside a message's ephemeral thread (`GET .../thread`, DESIGN §9). */
@Serializable
data class ThreadReply(
    val id: String,
    val parentMessageId: String,
    val replyToId: String? = null,
    val authorId: String,
    /** The author's room-scoped display name (DESIGN §5). Falls back to the id for legacy rows. */
    val authorName: String = "",
    val text: String,
    val createdAt: Long,
) {
    /** The name to render in the thread — the room-scoped name when present, else the raw id. */
    val displayAuthor: String get() = authorName.ifBlank { authorId }
}

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
    val intents: List<Intent> = emptyList(),
    val languages: List<String> = emptyList(),
    val offlineSchedule: List<String> = emptyList(),
    val providingServices: List<String> = emptyList(),
    val supporterUntil: Long? = null,
    val capabilities: List<String> = emptyList(),
    /** The login-only handle used as the username for password sign-in (PASSWORD_AUTH.md §2C). */
    val handle: String? = null,
    /** True once the member has set a durable sign-in password (PASSWORD_AUTH.md §2B). */
    val passwordSet: Boolean = false,
)

/** The caller's private emoji tally (`GET /profile/me/emoji-stats`, DESIGN §4, §8). Visible only
 * to the user themself. */
@Serializable
data class EmojiStatsResponse(val stats: Map<String, Int> = emptyMap())

/** Editable profile fields (`PUT /profile/me`, DESIGN §4). Null fields are left unchanged. */
@Serializable
data class ProfileUpdate(
    val interests: List<String>? = null,
    val intents: List<Intent>? = null,
    val languages: List<String>? = null,
    val offlineSchedule: List<String>? = null,
    val providingServices: List<String>? = null,
)

/** Founder/Supporter request to create a new room (`POST /rooms`, DESIGN §6). */
@Serializable
data class RoomCreate(
    val name: String,
    val emoji: String,
    val atmosphere: String = "",
    /** Optional caller-chosen room URL (id); blank/null lets the server assign one. */
    val id: String? = null,
)

/** Founder request to rename/edit a room (`PUT /rooms/{id}`, DESIGN §6). Null fields unchanged. */
@Serializable
data class RoomUpdate(
    val name: String? = null,
    val emoji: String? = null,
    val atmosphere: String? = null,
)

/** Sign in with a previously-set password (`POST /auth/login`, PASSWORD_AUTH.md §2C). */
@Serializable
data class LoginRequest(val identifier: String, val password: String)

/**
 * Set or change the durable sign-in password (`POST /profile/password`, PASSWORD_AUTH.md §2B).
 * [currentPassword] is required only when one is already set.
 */
@Serializable
data class SetPasswordRequest(val newPassword: String, val currentPassword: String? = null)

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

/** A member in the Founder's directory (`GET /founder/members`). */
@Serializable
data class MemberView(
    val id: String,
    val displayName: String,
    /** The account's nominal role on record. */
    val role: Role,
    /** The role actually in force once payment status is applied. */
    val effectiveRole: Role = role,
    val sharerPaidAt: Long? = null,
    /** Epoch millis the Supporter subscription expires; null for non-Supporters. */
    val supporterUntil: Long? = null,
    /** The member's login username (handle). */
    val handle: String? = null,
    /** The member's chosen intents (DESIGN §4). */
    val intents: List<Intent> = emptyList(),
    /** Language codes the member speaks. */
    val languages: List<String> = emptyList(),
    /** The member's free-text introduction (formerly "interests"). */
    val interests: List<String> = emptyList(),
)

/** A recorded room search shown in the Founder's "Room searches" list (`GET /founder/room-searches`). */
@Serializable
data class RoomSearchView(
    /** The raw search text the user typed, trimmed. */
    val query: String,
    /** Unix epoch millis the search was recorded. */
    val createdAt: Long,
)

/** Founder request to mint a single-use invite code for a new member. */
@Serializable
data class InviteCreateRequest(val role: Role = Role.SHARER)

/** A minted invite code handed to a prospective member. */
@Serializable
data class InviteCodeResponse(val code: String, val role: Role)

/** Whether the one-time Founder bootstrap is still available (no member account exists yet). */
@Serializable
data class BootstrapStatusResponse(val available: Boolean)

/** A founder-visible invite code, including whether it has already been redeemed. */
@Serializable
data class InviteCodeView(
    val code: String,
    val role: Role,
    val createdAt: Long,
    val redeemed: Boolean,
)

/** Redeem an invite code to create an account by choosing a username + password. */
@Serializable
data class RedeemRequest(val code: String, val username: String, val password: String)

/** Change the caller's login username (handle). */
@Serializable
data class ChangeUsernameRequest(val username: String)

/** Founder-only request to change a member's tier (`PATCH /founder/members/{id}/role`). */
@Serializable
data class SetTierRequest(val role: Role)

/** Founder-only request to reset a member's password (`POST /founder/members/{id}/password`). */
@Serializable
data class ResetPasswordRequest(val newPassword: String)

/** Founder-only request to change a member's display name (`PATCH /founder/members/{id}/name`). */
@Serializable
data class SetNameRequest(val displayName: String)

/** Result of a tier change: the refreshed member plus a fresh token (null when demoted to Seeker). */
@Serializable
data class MemberTierChangeResponse(val member: MemberView, val token: String? = null)

/** A single private-chat message in the current session (DESIGN §7). Ephemeral; never persisted. */
data class PrivateMessage(
    val author: String,
    val text: String,
    val mine: Boolean,
)

/**
 * An open one-on-one direct chat the user can return to (DESIGN §7). Client-only navigation state:
 * private chats are ephemeral and never persisted, so this list lives only for the current session.
 * It backs the "Direct chats" screen and the in-room "someone started a chat with you" notification.
 */
data class DirectChat(
    val roomId: String,
    val targetUserId: String,
    val peerName: String,
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

    /** Full name + flag emoji for each supported language code, for selectable language buttons. */
    val languageNames: Map<String, String> = mapOf(
        "vi" to "🇻🇳 Tiếng Việt",
        "en" to "🇬🇧 English",
        "ko" to "🇰🇷 한국어",
        "zh" to "🇨🇳 中文",
        "ja" to "🇯🇵 日本語",
        "fr" to "🇫🇷 Français",
    )

    /** The full name + flag for [code], falling back to the bare code if unknown. */
    fun languageLabel(code: String): String = languageNames[code] ?: code

    /** Just the flag emoji for [code] (the leading token of its label), falling back to the code. */
    fun languageEmoji(code: String): String = languageNames[code]?.substringBefore(' ') ?: code
}
