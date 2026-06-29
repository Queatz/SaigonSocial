package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * User roles and their capability gates (DESIGN §3).
 * Capabilities are derived here so the rest of the platform has a single source of truth.
 */
@Serializable
enum class Role {
    /** Temporary visitor. Read-only. No persistence. */
    SEEKER,

    /** Verified member. Can participate in rooms and public chat. */
    SHARER,

    /** Sharer who pays to be available for 1-on-1 chats. */
    SUPPORTER,

    /** The founder. Full admin. */
    FOUNDER;

    /** Can send messages in public chat / threads. */
    val canChatPublicly: Boolean
        get() = this != SEEKER

    /** Can be contacted in a private 1-on-1 chat. */
    val isPrivatelyMessageable: Boolean
        get() = this == SUPPORTER

    /** Founder-only administrative actions (onboarding, room creation, moderation). */
    val isAdmin: Boolean
        get() = this == FOUNDER
}

/**
 * Primary intent badge (DESIGN §4). Multiple may be selected per profile.
 */
@Serializable
enum class Intent(val badge: String) {
    JUST_CHATTING("💬 Just Chatting"),
    DEEP_TALK("🧠 Deep Talk"),
    HEAVY_TOPICS("🫂 Heavy Topics"),
    OPEN_TO_COLLAB("🎯 Open to Collab"),
    SURPRISE_ME("🎲 Surprise Me"),
    CASUAL("☕ Casual"),
}

/**
 * Persistent user profile (DESIGN §4). Tied to the real person behind room-specific identities.
 */
@Serializable
data class User(
    val id: String,
    val role: Role,
    /** Real display name — for founder reference only, never shown publicly. */
    val displayName: String,
    /**
     * Unique, login-only handle used purely as the username for password sign-in
     * (PASSWORD_AUTH.md §2C). Distinct from [displayName], which stays free-text and private.
     * Null only for legacy rows created before handles existed.
     */
    val handle: String? = null,
    /** Argon2/bcrypt/PBKDF2 hash of the chosen password; null until the member sets one. */
    val passwordHash: String? = null,
    /** Unix epoch millis the password was last set/changed (for audit + "rotate password" UX). */
    val passwordSetAt: Long? = null,
    /** Free-text interest tags. Max 5. */
    val interests: List<String> = emptyList(),
    /** Primary intent badges the user selected (DESIGN §4). Multiple choice. */
    val intents: List<Intent> = emptyList(),
    /** Language codes the user speaks (e.g. "vi", "en", "ko", "zh"). */
    val languages: List<String> = emptyList(),
    /** Optional availability windows. */
    val offlineSchedule: List<String> = emptyList(),
    /** Services the Supporter offers to others, shown on their occupant detail sheet. */
    val providingServices: List<String> = emptyList(),
    /** Private emoji tally aggregated from received reactions. Visible only to the user. */
    val emojiStats: Map<String, Int> = emptyMap(),
    /** Unix epoch millis a Supporter subscription expires; null for non-supporters. */
    val supporterUntil: Long? = null,
    /**
     * Unix epoch millis the one-time Sharer fee was settled; null until paid (TASKS Phase 8,
     * DESIGN §3, §11). Tier capabilities are tied to this payment status.
     */
    val sharerPaidAt: Long? = null,
) {
    companion object {
        const val MAX_INTERESTS = 5
    }
}

/**
 * A public, city-based social space (DESIGN §6). Founder-created.
 */
@Serializable
data class Room(
    val id: String,
    /** Single string, no '#' prefix. */
    val name: String,
    /** Primary emoji displayed before the name. */
    val emoji: String,
    /** Free-text atmosphere/vibe set by the founder. */
    val atmosphere: String = "",
    val createdBy: String,
)

/**
 * A recorded room search (DESIGN §6). Persisted whenever a member or visitor searches the room
 * list, so the founder can understand what kinds of rooms people are looking for.
 */
@Serializable
data class RoomSearch(
    val id: String,
    /** The raw search text the user typed, trimmed. */
    val query: String,
    val createdAt: Long,
    /** The searcher's persistent user id when signed in; null for anonymous viewers. */
    val userId: String? = null,
)

/**
 * Room-scoped, user-controlled anonymous identity (DESIGN §5).
 * Unique per (user, room) pair; regenerated on demand.
 */
@Serializable
data class RoomIdentity(
    val userId: String,
    val roomId: String,
    val name: String,
    val avatarUrl: String,
)

/**
 * A public chat message in a room (DESIGN §6). Plain text only.
 */
@Serializable
data class Message(
    val id: String,
    val roomId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
    /**
     * The author's room-scoped display name captured at post time (DESIGN §5). Stored on the
     * message so a later identity refresh never rewrites the names on past posts. Empty for legacy
     * rows, in which case the name is resolved live.
     */
    val authorName: String = "",
)

/**
 * A reply within a message's ephemeral thread (DESIGN §9).
 */
@Serializable
data class ThreadReply(
    val id: String,
    /** The root message this thread hangs off. */
    val parentMessageId: String,
    /** Optional reply this reply is nested under. */
    val replyToId: String? = null,
    val authorId: String,
    val text: String,
    val createdAt: Long,
    /**
     * The author's room-scoped display name captured at post time (DESIGN §5). Stored on the
     * reply so a later identity refresh never rewrites the names on past replies. Empty for legacy
     * rows, in which case the name is resolved live.
     */
    val authorName: String = "",
)
