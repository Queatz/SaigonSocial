package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * The post-chat closing ritual (TASKS Phase 6, DESIGN §8).
 *
 * When **either** party ends a private chat, both see a shared emoji-selection screen and each
 * privately picks one emoji from [EmojiOptions]. Selections stay hidden until **both** have chosen;
 * only then are the two emojis revealed side-by-side. The session is ephemeral and tied to the
 * (now-ended) chat by [chatId].
 */
@Serializable
data class FinalEmojiSession(
    /** The id of the private chat that just ended. */
    val chatId: String,
    /** The two participants of the ended chat. */
    val participants: List<String>,
    /** Each participant's privately-chosen emoji, keyed by user id. Hidden until both selected. */
    val selections: Map<String, String> = emptyMap(),
) {
    fun includes(userId: String): Boolean = userId in participants

    /** The other participant relative to [userId]. */
    fun peerOf(userId: String): String = participants.first { it != userId }

    /** Both parties have made their private selection — ready to reveal. */
    val isComplete: Boolean get() = participants.isNotEmpty() && participants.all { it in selections }
}

/**
 * What [userId] sees on the reveal screen (DESIGN §8): their own emoji and their peer's, shown
 * side by side.
 */
@Serializable
data class FinalEmojiReveal(
    val youSent: String,
    val theySent: String,
)
