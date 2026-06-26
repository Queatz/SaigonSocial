package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * A one-on-one private chat session (TASKS Phase 5, DESIGN §7).
 *
 * Private chats are **Supporter-only on the receiving side** and intentionally minimal: no typing
 * indicators, no read receipts, no "last seen." They are **ephemeral** — no history is persisted
 * once the chat ends. Either participant can end the chat instantly, with no confirmation prompt.
 */
@Serializable
data class PrivateChat(
    val id: String,
    /** The member who tapped a Supporter's name to start the chat. */
    val initiatorId: String,
    /** The available Supporter being messaged. */
    val targetId: String,
    val createdAt: Long,
) {
    /** The two participants of this chat. */
    val participants: List<String> get() = listOf(initiatorId, targetId)

    fun includes(userId: String): Boolean = userId == initiatorId || userId == targetId

    /** The other participant relative to [userId]. */
    fun peerOf(userId: String): String = if (userId == initiatorId) targetId else initiatorId
}

/**
 * A single ephemeral message within a [PrivateChat] (DESIGN §7). Plain text only; never persisted
 * beyond the lifetime of the chat.
 */
@Serializable
data class PrivateMessage(
    val id: String,
    val chatId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
)
