package social.saigon.privatechat

import java.util.UUID
import social.saigon.domain.Capability
import social.saigon.domain.PlainText
import social.saigon.domain.PrivateChat
import social.saigon.domain.PrivateMessage
import social.saigon.domain.User
import social.saigon.domain.can
import social.saigon.repository.PrivateChatRepository
import social.saigon.repository.UserRepository

/**
 * One-on-one private chat (TASKS Phase 5, DESIGN §7).
 *
 * Only **Supporters** are available to be privately messaged — this creates the clear value
 * proposition for the Supporter fee (DESIGN §7). The service owns who may start a chat, who may
 * be messaged ("available for chat"), plain-text validation, the ephemeral message lifecycle, and
 * instant termination by either party (no confirmation prompt). No history survives the chat.
 *
 * A [clock] seam keeps timestamps deterministic in tests.
 */
class PrivateChatService(
    private val chats: PrivateChatRepository,
    private val users: UserRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Is [userId] currently available for a private chat? True only for an *active* Supporter
     * (or the Founder): the role must be privately messageable and any Supporter subscription
     * must not have expired (DESIGN §3, §7).
     */
    fun isAvailable(userId: String): Boolean =
        users.get(userId)?.let(::available) ?: false

    private fun available(user: User): Boolean {
        if (!user.role.can(Capability.BE_PRIVATELY_MESSAGED)) return false
        val until = user.supporterUntil ?: return true
        return until > clock()
    }

    /**
     * Start a private chat from [initiatorId] by tapping [targetId]'s name in [roomId] (DESIGN §7).
     * The chat is scoped to [roomId], so the same pair can hold separate conversations opened from
     * different rooms.
     *
     * @throws IllegalArgumentException if the initiator is unknown or may not start chats, the
     *   target is not an available Supporter, or the two are the same person.
     */
    fun start(initiatorId: String, targetId: String, roomId: String = ""): PrivateChat {
        require(initiatorId != targetId) { "Cannot start a direct chat with yourself" }
        val initiator = users.get(initiatorId)
            ?: throw IllegalArgumentException("Unknown user")
        require(initiator.role.can(Capability.START_PRIVATE_CHAT)) {
            "Role ${initiator.role} cannot start a direct chat"
        }
        val target = users.get(targetId)
            ?: throw IllegalArgumentException("Unknown user")
        require(available(target)) { "User is not available for direct chat" }

        return chats.create(
            PrivateChat(
                id = UUID.randomUUID().toString(),
                initiatorId = initiatorId,
                targetId = targetId,
                roomId = roomId,
                createdAt = clock(),
            ),
        )
    }

    /**
     * Open the one-on-one chat between [userId] and [peerId], reusing an already-open session if
     * one exists, otherwise starting a fresh one (DESIGN §7).
     *
     * The recipient of a chat opens it by tapping the *initiator's* name — but the initiator need
     * not be an available Supporter, so a plain [start] would wrongly reject them with "User is not
     * available for private chat." Reusing the existing session lets either party (re)open the chat
     * the initiator already created without re-running the availability gate.
     */
    fun openOrStart(userId: String, peerId: String, roomId: String = ""): PrivateChat {
        require(userId != peerId) { "Cannot start a direct chat with yourself" }
        chats.findOpen(userId, peerId, roomId)?.let { return it }
        return start(userId, peerId, roomId)
    }

    /** The open chat with [chatId], or null if it is unknown/ended. */
    fun get(chatId: String): PrivateChat? = chats.get(chatId)

    /**
     * The messages exchanged so far in the open chat [chatId], oldest→newest. Lets a participant
     * (re)opening the chat screen load the conversation so far for the still-open session — the
     * history is dropped only when the chat ends (DESIGN §7). Empty if the chat is unknown/ended.
     */
    fun messages(chatId: String): List<PrivateMessage> =
        chats.messages(chatId).sortedBy { it.createdAt }

    /**
     * Post an ephemeral, plain-text message to an open chat.
     *
     * @throws IllegalArgumentException if the chat is unknown/ended, the author is not a
     *   participant, or the text is not plain text.
     */
    fun post(chatId: String, authorId: String, text: String): PrivateMessage {
        val chat = chats.get(chatId)
            ?: throw IllegalArgumentException("Chat not found or already ended")
        require(chat.includes(authorId)) { "Not a participant in this chat" }
        val body = text.trim()
        require(PlainText.isValid(body)) { "Plain text only" }
        return chats.addMessage(
            PrivateMessage(
                id = UUID.randomUUID().toString(),
                chatId = chat.id,
                authorId = authorId,
                text = body,
                createdAt = clock(),
            ),
        )
    }

    /**
     * End the chat instantly on behalf of [byUserId] — no confirmation prompt (DESIGN §7). Either
     * party may end it. Returns the ended session (with both participants, so callers can notify
     * them) or null if it was already gone. History is discarded.
     *
     * @throws IllegalArgumentException if [byUserId] is not a participant of an open chat.
     */
    fun end(chatId: String, byUserId: String): PrivateChat? {
        val chat = chats.get(chatId) ?: return null
        require(chat.includes(byUserId)) { "Not a participant in this chat" }
        return chats.end(chatId)
    }
}
