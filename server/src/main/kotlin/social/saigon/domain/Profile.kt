package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * Capabilities gated per role (TASKS Phase 1, DESIGN §3, §11).
 *
 * [Role] exposes a few convenience flags, but the full matrix lives here as a single source of
 * truth so route handlers and the UI can reason about "who can do what" uniformly.
 */
@Serializable
enum class Capability {
    /** Browse rooms and read public chat + threads. Every role can. */
    VIEW_ROOMS,

    /** Send messages in public chat and reply in threads. */
    CHAT_PUBLICLY,

    /** Maintain a persistent profile (interests, intent, languages, schedule). */
    EDIT_PROFILE,

    /** Be available for and receive 1-on-1 private chats. */
    BE_PRIVATELY_MESSAGED,

    /** Start a private chat with an available Supporter. */
    START_PRIVATE_CHAT,

    /** Founder administration: onboarding, room creation, moderation. */
    ADMINISTER,
}

/**
 * The role → capability matrix (DESIGN §3, §11).
 *
 * - Seekers are read-only and have no persistence.
 * - Sharers get a persistent profile and public chat, but cannot be privately messaged.
 * - Supporters add private-chat availability.
 * - Founder can do everything, plus administration.
 */
object CapabilityMatrix {
    private val matrix: Map<Role, Set<Capability>> = mapOf(
        Role.SEEKER to setOf(Capability.VIEW_ROOMS),
        Role.SHARER to setOf(
            Capability.VIEW_ROOMS,
            Capability.CHAT_PUBLICLY,
            Capability.EDIT_PROFILE,
            Capability.START_PRIVATE_CHAT,
        ),
        Role.SUPPORTER to setOf(
            Capability.VIEW_ROOMS,
            Capability.CHAT_PUBLICLY,
            Capability.EDIT_PROFILE,
            Capability.START_PRIVATE_CHAT,
            Capability.BE_PRIVATELY_MESSAGED,
        ),
        Role.FOUNDER to setOf(
            Capability.VIEW_ROOMS,
            Capability.CHAT_PUBLICLY,
            Capability.EDIT_PROFILE,
            Capability.START_PRIVATE_CHAT,
            Capability.BE_PRIVATELY_MESSAGED,
            Capability.ADMINISTER,
        ),
    )

    fun capabilities(role: Role): Set<Capability> = matrix[role] ?: emptySet()

    fun allows(role: Role, capability: Capability): Boolean = capability in capabilities(role)
}

/** Convenience: does this role hold the given capability? */
fun Role.can(capability: Capability): Boolean = CapabilityMatrix.allows(this, capability)

/**
 * The closing-ritual emoji set (DESIGN §8). Received emojis feed a user's private tally.
 */
object EmojiOptions {
    val all: List<String> = listOf("😊", "💡", "😂", "🙏", "🤝", "❤️", "✨", "🔥")

    fun isValid(emoji: String): Boolean = emoji in all
}

/**
 * Languages a member can declare on their profile (DESIGN §4, §6).
 *
 * The launch set is intentionally small and Saigon-centric; it is open to extension but validated
 * so the room-list UI can render predictable language tags.
 */
object Languages {
    val supported: List<String> = listOf("vi", "en", "ko", "zh", "ja", "fr")

    fun isSupported(code: String): Boolean = code in supported
}

/**
 * A validated change-set for the persistent profile (TASKS Phase 1, DESIGN §4).
 *
 * Note: the real [User.displayName] and [Role] are founder-managed and are deliberately *not*
 * editable through this DTO. Null fields are left unchanged.
 */
@Serializable
data class ProfileUpdate(
    val interests: List<String>? = null,
    val intent: Intent? = null,
    val languages: List<String>? = null,
    val offlineSchedule: List<String>? = null,
)
