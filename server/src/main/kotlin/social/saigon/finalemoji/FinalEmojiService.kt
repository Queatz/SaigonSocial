package social.saigon.finalemoji

import social.saigon.domain.EmojiOptions
import social.saigon.domain.FinalEmojiReveal
import social.saigon.domain.FinalEmojiSession
import social.saigon.profile.ProfileService
import social.saigon.repository.FinalEmojiRepository

/**
 * The post-chat closing-emoji ritual (TASKS Phase 6, DESIGN §8).
 *
 * When either party ends a private chat, a shared selection session is [open]ed for both
 * participants. Each privately [select]s one emoji from [EmojiOptions]; selections stay hidden
 * until **both** have chosen. On completion the two emojis are revealed side-by-side and each
 * received emoji is added to the recipient's private tally via [ProfileService]. The session is
 * then dropped — there are no follow-up nudges.
 */
class FinalEmojiService(
    private val sessions: FinalEmojiRepository,
    private val profiles: ProfileService,
) {
    /**
     * The outcome of a [select] call: either still [Waiting] on the other party, or [Revealed]
     * with each participant's personalized view of the two emojis.
     */
    sealed interface Outcome {
        /** This participant has chosen; the peer has not yet. Selection stays private. */
        data object Waiting : Outcome

        /** Both have chosen — reveal each participant's view, keyed by user id. */
        data class Revealed(val reveals: Map<String, FinalEmojiReveal>) : Outcome
    }

    /**
     * Open the shared selection screen for a chat that just ended (DESIGN §8). Idempotent: an
     * existing session for the same chat is returned unchanged.
     */
    fun open(chatId: String, participants: List<String>): FinalEmojiSession {
        sessions.get(chatId)?.let { return it }
        require(participants.size == 2) { "A final-emoji session needs exactly two participants" }
        return sessions.create(FinalEmojiSession(chatId = chatId, participants = participants))
    }

    /** The pending session for [chatId], or null once revealed/unknown. */
    fun get(chatId: String): FinalEmojiSession? = sessions.get(chatId)

    /**
     * Record [userId]'s private emoji choice. When both participants have selected, reveals the
     * pair, updates each user's private tally, and drops the session.
     *
     * @throws IllegalArgumentException if the session is unknown, [userId] is not a participant,
     *   or [emoji] is not one of the defined options.
     */
    fun select(chatId: String, userId: String, emoji: String): Outcome {
        val session = sessions.get(chatId)
            ?: throw IllegalArgumentException("No closing-emoji session for this chat")
        require(session.includes(userId)) { "Not a participant in this chat" }
        require(EmojiOptions.isValid(emoji)) { "Unknown emoji" }

        val updated = session.copy(selections = session.selections + (userId to emoji))
        if (!updated.isComplete) {
            sessions.update(updated)
            return Outcome.Waiting
        }

        // Both have chosen — reveal, tally, and discard (no follow-up nudges; DESIGN §8).
        sessions.remove(chatId)
        val reveals = updated.participants.associateWith { participant ->
            val peer = updated.peerOf(participant)
            val mine = updated.selections.getValue(participant)
            val theirs = updated.selections.getValue(peer)
            // The emoji a user *receives* is the one their peer sent (DESIGN §8).
            profiles.recordReceivedEmoji(participant, theirs)
            FinalEmojiReveal(youSent = mine, theySent = theirs)
        }
        return Outcome.Revealed(reveals)
    }
}
