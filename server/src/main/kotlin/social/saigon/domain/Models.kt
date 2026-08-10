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
 * A profile photo hosted on the content server (issue: "Profile and room profile photos").
 * [url] is the content server's public URL for the file; [message] is the user's own caption for
 * the photo, shown alongside it.
 */
@Serializable
data class ProfilePhoto(
    val url: String,
    val message: String = "",
    val createdAt: Long = 0L,
)

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
    /**
     * Primary intent badges the user selected (DESIGN §4). Multiple choice. Each badge is a
     * free-text label (usually an emoji + short phrase, e.g. "💬 Just Chatting"). Supporters and
     * Founders can create their own custom badges in addition to the launch set.
     */
    val intents: List<String> = emptyList(),
    /** Language codes the user speaks (e.g. "vi", "en", "ko", "zh"). */
    val languages: List<String> = emptyList(),
    /** Optional availability windows. */
    val offlineSchedule: List<String> = emptyList(),
    /** The member's current (main) profile photo; null until they upload one. */
    val photo: ProfilePhoto? = null,
    /**
     * Previous profile photos, newest first. Uploading a new photo makes it the main one and moves
     * the old main photo to the head of this list; entries can be deleted individually.
     */
    val previousPhotos: List<ProfilePhoto> = emptyList(),
    /** Ids of the [Service]s this Supporter provides. Each service is created from a room. */
    val services: List<String> = emptyList(),
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
    /** Creation time (epoch millis), so the room list can show the newest rooms first. */
    val createdAt: Long = 0L,
    /** Room photos (issue: "Room photos will show up visibly in the room lists..."). */
    val photos: List<ProfilePhoto> = emptyList(),
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
    /**
     * Per-room overrides of the user's persistent profile facets (DESIGN §4, §5). A null field
     * means "inherit the value from the main [User] profile"; a non-null field (including an empty
     * list) is a deliberate room-scoped customization. Supporters/Founders tailor these per room.
     */
    val interests: List<String>? = null,
    val intents: List<String>? = null,
    val languages: List<String>? = null,
    val offlineSchedule: List<String>? = null,
    /**
     * Room-scoped profile photos (issue: "Room profile photos"). Added from the room profile form,
     * these belong only to this (user, room) pair and show up ahead of the user's main-profile
     * photos in the room-profile sheet's photo list. Unlike the override facets above, photos are
     * additive rather than an inherit/override toggle, so an empty list simply means "none yet".
     */
    val photos: List<ProfilePhoto> = emptyList(),
)

/**
 * A change-set for a caller's room-scoped identity (DESIGN §5). [name], when non-blank, renames the
 * room identity. The remaining fields are per-room overrides: a null field clears the override so
 * the value inherits from the main [User] profile, while a non-null field pins a room-scoped value.
 */
@Serializable
data class RoomIdentityUpdate(
    val name: String? = null,
    val interests: List<String>? = null,
    val intents: List<String>? = null,
    val languages: List<String>? = null,
    val offlineSchedule: List<String>? = null,
    /**
     * The room-scoped photos to store (issue: "Room profile photos"). A null list leaves the
     * existing room photos untouched; a non-null list wholesale-replaces them. This differs from
     * the override facets, where a null field clears the override to inherit the main profile.
     */
    val photos: List<ProfilePhoto>? = null,
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
    /**
     * Whether the message has been recalled by its author (or the Founder). A recalled message has
     * its [text] cleared and is shown in the UI with a "message recalled" notice rather than its
     * original content. Recalling a message does not affect its thread (DESIGN §9).
     */
    val recalled: Boolean = false,
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
    /**
     * Whether the reply has been recalled by its author (or the Founder). A recalled reply has its
     * [text] cleared and is shown in the UI with a "message recalled" notice rather than its
     * original content. Recalling a reply does not affect the rest of the thread (DESIGN §9).
     */
    val recalled: Boolean = false,
)

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

/**
 * A top-level service a Supporter provides. A service always belongs to a room: it is created from
 * a room, and every UI that shows a service also shows the room it lives in.
 */
@Serializable
data class Service(
    val id: String,
    /** The [User] (a Supporter) who provides the service. */
    val providerId: String,
    /** The [Room] the service was created from and belongs to. */
    val roomId: String,
    /** A single emoji icon for the service. */
    val emoji: String,
    val name: String,
    val description: String = "",
    /** Price in Vietnamese đồng (VND), stored as a whole integer (e.g. 50000 → 50.000đ). */
    val price: Int = 0,
    /**
     * Semicolon-separated items that are included in the service. Rendered as a bullet list on the
     * service detail screen ("What's included").
     */
    val included: String = "",
    /**
     * Semicolon-separated items that are not included in the service. Rendered as a small box with
     * comma-separated text on the service detail screen ("What's not included").
     */
    val notIncluded: String = "",
    /**
     * Whether the service is publicly listed. Unlisting hides it from the public search and the
     * provider's room profile without deleting it; the provider can relist it later.
     */
    val listed: Boolean = true,
    /**
     * Photos of the service the provider uploaded (issue: "upload service photos"), newest first.
     * Shown on the service page and as the background of the service cards/rows. Only the provider
     * (or a Founder) can add or delete them.
     */
    val photos: List<ProfilePhoto> = emptyList(),
    val createdAt: Long = 0L,
)

/**
 * A recorded service search. Persisted whenever someone searches the service list by name, so the
 * founder can understand what kinds of services people are looking for (mirrors [RoomSearch]).
 */
@Serializable
data class ServiceSearch(
    val id: String,
    /** The raw search text the user typed, trimmed. */
    val query: String,
    val createdAt: Long,
    /** The searcher's persistent user id when signed in; null for anonymous viewers. */
    val userId: String? = null,
)

/**
 * Public feedback left on a [Service]. Anyone may leave feedback; it is visible to everyone. Only
 * the service provider, the author of the feedback, or a Founder may delete it (hard delete).
 */
@Serializable
data class Feedback(
    val id: String,
    val serviceId: String,
    /** The [User] who wrote the feedback. */
    val userId: String,
    /** A single emoji summarising the feedback, aggregated in the UI. */
    val emoji: String,
    val message: String = "",
    val createdAt: Long = 0L,
)

/** Lifecycle of a [InviteRequest] handled manually by the Founder. */
@Serializable
enum class InviteStatus { PENDING, RESOLVED, DISMISSED }

/**
 * A "Request an Invite" submission from the sign-in screen. Stored for the Founder to review; no
 * account is created automatically. The Founder resolves or dismisses each request manually.
 */
@Serializable
data class InviteRequest(
    val id: String,
    val name: String,
    val zaloNumber: String,
    val description: String = "",
    val status: InviteStatus = InviteStatus.PENDING,
    val createdAt: Long = 0L,
)
