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
    /** The member's persistent display name, shown on the detail sheet alongside their role. */
    val displayName: String = "",
    val avatarUrl: String = "",
    val role: Role,
    val intents: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    /** The member's free-text introduction (their interests), shown on the detail sheet (DESIGN §4). */
    val interests: List<String> = emptyList(),
    /** Time frame(s) the member is often available offline, shown on the detail sheet (DESIGN §4). */
    val offlineSchedule: List<String> = emptyList(),
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
    /** True once the author (or the Founder) recalled the message; its [text] is then blank. */
    val recalled: Boolean = false,
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
    /** True once the author (or the Founder) recalled the reply; its [text] is then blank. */
    val recalled: Boolean = false,
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
    /**
     * Per-room overrides of the main profile facets (DESIGN §4, §5). A null field inherits the
     * value from the persistent profile; a non-null field (incl. empty list) is a room-scoped
     * customization Supporters/Founders set via the "Edit your room profile" dialog.
     */
    val interests: List<String>? = null,
    val intents: List<String>? = null,
    val languages: List<String>? = null,
    val offlineSchedule: List<String>? = null,
)

/**
 * Edit the caller's room-scoped identity (`PUT /rooms/{id}/identity`, DESIGN §4, §5). A non-blank
 * [name] renames the room identity; the remaining fields wholesale-replace the per-room overrides,
 * where a null clears the override so that facet inherits from the main profile again.
 */
@Serializable
data class RoomIdentityUpdate(
    val name: String? = null,
    val interests: List<String>? = null,
    val intents: List<String>? = null,
    val languages: List<String>? = null,
    val offlineSchedule: List<String>? = null,
)

/** The caller's editable persistent profile (`GET /profile/me`, DESIGN §4). */
@Serializable
data class ProfileResponse(
    val id: String,
    val role: Role,
    val displayName: String,
    val interests: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val offlineSchedule: List<String> = emptyList(),
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
    val intents: List<String>? = null,
    val languages: List<String>? = null,
    val offlineSchedule: List<String>? = null,
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
    val intents: List<String> = emptyList(),
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

    /**
     * Friendly conversation starters shown as tappable suggestions when a private chat has no
     * messages yet. Each is a short (≈3-5 word) opener that works whether the two people just met
     * or already know each other. A few are picked at random to break the ice.
     */
    val chatStarters: List<String> = listOf(
        "Hey, how's your day?",
        "What are you up to?",
        "How have you been?",
        "What's on your mind?",
        "Got any fun plans?",
        "How's everything going?",  
        "What made your day?",
        "Been anywhere lately?",
        "What are you into these days?",
        "Any good ideas lately?",
        "How's your week going?",
        "What's new with you?",
        "Working on anything?",
        "Seen anything lately?",
        "What's keeping you up?",
        "Any recommendations for me?",
        "How's life pampering you?",
        "What's your favorite room here?",
        "Coffee, tea, or water?",
        "What's the news lately?",
        "I bow to your transmission.",
        "I welcome your light-speed ping.",
        "I greet thee, fellow carbon-form.",
        "I bow, fellow solar wanderer.",
        "I humbly greet your quantum node.",
        "I welcome you, fellow dream-traveller.",
        "I formally accept your stream-of-consciousness.",
        "I honor to your orbital path, matey.",
        "I welcome your bio-luminescent field.",
        "I stand ready for you, cryptic being.",
        "I greet you, child of Old Earth.",
        "I bow to your neural fuel.",
        "I bow to you, gravity-bound vessel.",
        "I formally welcome your exo-consciousness.",
        "I welcome you, fellow star-seeder.",
        "I humbly accept your chrono-echo.",
        "I bow, honored by your shape of light.",
        "I welcome your terraformed heart.",
        "I greet you, fellow post-biological.",
        "I formally invite your holographics."
    )

    /**
     * The launch set of selectable "Here for" intent badges (DESIGN §4). Each is a free-text
     * label (emoji + short phrase); Supporters/Founders can add their own custom badges on top of
     * these, so this is just the default starting set rather than a closed enum.
     */
    val intents: List<String> = listOf(
        "💬 Just Chatting",
        "🧠 Deep Talk",
        "🫂 Heavy Topics",
        "🎯 Open to Collab",
        "🎲 Surprise Me",
        "☕ Casual",
    )

    /** Just the leading emoji of an intent [badge], for compact emoji-only display (DESIGN §6). */
    fun intentEmoji(badge: String): String = badge.substringBefore(' ')

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

    /**
     * Render a VND price stored as a whole integer, e.g. 50000 → "50.000đ" (dot thousands
     * separators, Vietnamese đồng suffix). A zero/negative price renders as "Free".
     */
    fun vnd(price: Int): String {
        if (price <= 0) return "Free"
        val digits = price.toString()
        val grouped = buildString {
            digits.forEachIndexed { index, c ->
                if (index > 0 && (digits.length - index) % 3 == 0) append('.')
                append(c)
            }
        }
        return "${grouped}đ"
    }
}

@Serializable
data class ThreadSummary(
    val roomId: String,
    val roomName: String,
    val roomEmoji: String,
    val parentMessageId: String,
    val parentMessageText: String,
    val parentMessageAuthorName: String,
    val replyCount: Int,
    val lastReplyText: String? = null,
    val lastReplyAuthorName: String? = null,
    val lastReplyAt: Long? = null,
)

/** A service as shown in the `/services` search and detail screens (`GET /services`). */
@Serializable
data class ServiceView(
    val id: String,
    val emoji: String,
    val name: String,
    val description: String = "",
    val roomId: String,
    /** The room the service lives in — always displayed wherever a service is shown. */
    val roomName: String,
    /** The room's emoji icon, shown alongside the room name. */
    val roomEmoji: String = "",
    val providerId: String,
    val providerName: String,
    val providerRole: Role = Role.SHARER,
    val providerLanguages: List<String> = emptyList(),
    val providerInterests: List<String> = emptyList(),
    /** Price in VND (whole integer, e.g. 50000 → 50.000đ). */
    val price: Int = 0,
    /** Whether the service is publicly listed (unlisted services are hidden from search). */
    val listed: Boolean = true,
    val createdAt: Long = 0L,
)

/** Create a service from a room (`POST /rooms/{id}/services`). */
@Serializable
data class ServiceCreate(
    val roomId: String,
    val emoji: String,
    val name: String,
    val description: String = "",
    /** Price in VND (whole integer). */
    val price: Int = 0,
)

/** Edit an existing service (`PUT /services/{id}`). */
@Serializable
data class ServiceUpdate(
    val emoji: String,
    val name: String,
    val description: String = "",
    /** Price in VND (whole integer). */
    val price: Int = 0,
)

/** Public feedback on a service (`GET /services/{id}/feedback`). */
@Serializable
data class FeedbackView(
    val id: String,
    val serviceId: String,
    val userId: String,
    val authorName: String,
    val authorRole: Role = Role.SHARER,
    val emoji: String,
    val message: String = "",
    val createdAt: Long = 0L,
)

/** Leave feedback on a service (`POST /services/{id}/feedback`). */
@Serializable
data class FeedbackCreate(val emoji: String, val message: String = "")

/** Contact a service provider with an opening message (`POST /services/{id}/contact`). */
@Serializable
data class ContactProviderRequest(val message: String)

/** The direct chat opened by contacting a provider, so the client can navigate straight to it. */
@Serializable
data class ContactProviderResponse(
    val roomId: String,
    val providerId: String,
    val providerName: String,
)

/** Lifecycle of an invite request. Serialized by name to match the server `InviteStatus`. */
@Serializable
enum class InviteStatus { PENDING, RESOLVED, DISMISSED }

/** Submit a "Request an Invite" from the sign-in screen (`POST /invite-requests`). */
@Serializable
data class InviteRequestCreate(
    val name: String,
    val zaloNumber: String,
    val description: String = "",
)

/** A pending invite request in the Founder's admin panel (`GET /founder/invite-requests`). */
@Serializable
data class InviteRequestView(
    val id: String,
    val name: String,
    val zaloNumber: String,
    val description: String = "",
    val status: InviteStatus = InviteStatus.PENDING,
    val createdAt: Long = 0L,
)
