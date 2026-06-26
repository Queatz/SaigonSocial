package social.saigon.realtime

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import social.saigon.domain.Role

/**
 * Real-time transport envelopes for live chat over WebSockets (DESIGN §12, TASKS Phase 0).
 *
 * The protocol is intentionally small: clients send [Incoming] frames and receive [Outgoing]
 * frames. Heartbeats keep connections alive; the server tracks per-room presence so room
 * occupant counts (DESIGN §6) stay live.
 */
@Serializable
sealed interface Incoming {
    @Serializable
    data class Join(val roomId: String) : Incoming

    @Serializable
    data class Leave(val roomId: String) : Incoming

    @Serializable
    data class Say(val roomId: String, val text: String) : Incoming

    /**
     * Reply within a message's thread (DESIGN §9). [replyToId] nests this under an existing reply;
     * null is a top-level reply on the parent message.
     */
    @Serializable
    data class Reply(
        val roomId: String,
        val parentMessageId: String,
        val text: String,
        val replyToId: String? = null,
    ) : Incoming

    /**
     * Start a one-on-one private chat by tapping a Supporter's name in a room (DESIGN §7).
     * [roomId] scopes the room-scoped display names shown to each party.
     */
    @Serializable
    data class StartPrivateChat(val roomId: String, val targetUserId: String) : Incoming

    /** Send a plain-text message in an open private chat (DESIGN §7). */
    @Serializable
    data class SayPrivate(val chatId: String, val text: String) : Incoming

    /** End a private chat instantly — either party, no confirmation (DESIGN §7). */
    @Serializable
    data class EndPrivateChat(val chatId: String) : Incoming

    /**
     * Privately choose one closing emoji for the just-ended chat (DESIGN §8). The choice stays
     * hidden until both parties have selected, at which point both receive a [Outgoing.FinalEmojiRevealed].
     */
    @Serializable
    data class SelectFinalEmoji(val chatId: String, val emoji: String) : Incoming

    @Serializable
    data object Ping : Incoming
}

@Serializable
sealed interface Outgoing {
    @Serializable
    data class Joined(val roomId: String, val occupants: Int) : Outgoing

    @Serializable
    data class Left(val roomId: String, val occupants: Int) : Outgoing

    @Serializable
    data class Said(
        val roomId: String,
        val authorId: String,
        val text: String,
        val createdAt: Long,
        /** The author's room-scoped display name (DESIGN §5). Null for anonymous senders. */
        val authorName: String? = null,
        /** The persisted message id, so clients can tap it to open its thread (DESIGN §9). */
        val messageId: String? = null,
    ) : Outgoing

    /**
     * A reply broadcast to the room (DESIGN §9). [replyCount] carries the parent message's live
     * reply total so feeds can render the "↳ N replies" indicator without a refetch.
     */
    @Serializable
    data class Replied(
        val roomId: String,
        val parentMessageId: String,
        val replyId: String,
        val authorId: String,
        val text: String,
        val createdAt: Long,
        val replyCount: Int,
        val replyToId: String? = null,
        /** The author's room-scoped display name (DESIGN §5). Null for anonymous senders. */
        val authorName: String? = null,
    ) : Outgoing

    /**
     * A private chat has opened (DESIGN §7). Sent to both participants. [peerName] is the other
     * party's room-scoped display name, where known.
     */
    @Serializable
    data class PrivateChatStarted(
        val chatId: String,
        val peerId: String,
        val peerName: String? = null,
    ) : Outgoing

    /** A plain-text private message delivered to both participants (DESIGN §7). */
    @Serializable
    data class PrivateMessageSaid(
        val chatId: String,
        val authorId: String,
        val text: String,
        val createdAt: Long,
    ) : Outgoing

    /**
     * A private chat has ended (DESIGN §7). Sent to both participants, immediately followed by a
     * [FinalEmojiPrompt] opening the closing-emoji ritual (DESIGN §8).
     */
    @Serializable
    data class PrivateChatEnded(val chatId: String, val endedBy: String) : Outgoing

    /**
     * The shared closing-emoji selection screen (DESIGN §8): "One last emoji to sum it up?".
     * Sent to both participants when the chat ends. [options] is the defined emoji set.
     */
    @Serializable
    data class FinalEmojiPrompt(val chatId: String, val options: List<String>) : Outgoing

    /**
     * The closing-emoji reveal (DESIGN §8), sent to each participant once both have selected.
     * [youSent]/[theySent] are personalized per recipient and shown side-by-side. No follow-up
     * nudges are sent after this.
     */
    @Serializable
    data class FinalEmojiRevealed(
        val chatId: String,
        val youSent: String,
        val theySent: String,
    ) : Outgoing

    @Serializable
    data object Pong : Outgoing

    @Serializable
    data class Error(val reason: String) : Outgoing
}

/**
 * A single connected client session. Implemented by the WebSocket layer; abstracted here so the
 * hub logic is testable without a live socket.
 */
interface ClientSession {
    val connectionId: String

    /** The persistent user id behind this connection, or null for a Seeker/anonymous viewer. */
    val userId: String? get() = null

    /** The role this connection authenticated as. Defaults to the read-only Seeker. */
    val role: Role get() = Role.SEEKER

    suspend fun send(frame: Outgoing)
}

/**
 * Tracks room presence and fans messages out to connected sessions. Thread-safe.
 *
 * Connection lifecycle: [register] on connect, [join]/[leave] per room, [unregister] on
 * disconnect (which also removes the session from every room it occupied).
 */
class ChatHub {
    private val sessions = ConcurrentHashMap<String, ClientSession>()
    private val rooms = ConcurrentHashMap<String, MutableSet<String>>()

    fun register(session: ClientSession) {
        sessions[session.connectionId] = session
    }

    /**
     * Drops the connection from every room it occupied. Returns the set of rooms that became
     * empty as a result, so callers can trigger ephemeral thread cleanup (DESIGN §9).
     */
    fun unregister(connectionId: String): Set<String> {
        sessions.remove(connectionId)
        val emptied = mutableSetOf<String>()
        rooms.forEach { (roomId, members) ->
            if (members.remove(connectionId) && members.isEmpty()) emptied.add(roomId)
        }
        rooms.entries.removeIf { it.value.isEmpty() }
        return emptied
    }

    /** Returns the live occupant count after joining. */
    fun join(roomId: String, connectionId: String): Int {
        val members = rooms.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }
        members.add(connectionId)
        return members.size
    }

    /** Returns the live occupant count after leaving. Empties trigger ephemeral cleanup upstream. */
    fun leave(roomId: String, connectionId: String): Int {
        val members = rooms[roomId] ?: return 0
        members.remove(connectionId)
        val size = members.size
        if (size == 0) rooms.remove(roomId)
        return size
    }

    fun occupants(roomId: String): Int = rooms[roomId]?.size ?: 0

    fun isEmpty(roomId: String): Boolean = occupants(roomId) == 0

    /** The currently-connected sessions present in [roomId] (for building the occupant list). */
    fun sessionsIn(roomId: String): List<ClientSession> =
        rooms[roomId]?.mapNotNull { sessions[it] } ?: emptyList()

    /**
     * Every connected session belonging to [userId]. Used to route private chat frames to a
     * participant regardless of which room(s) they currently occupy (DESIGN §7).
     */
    fun sessionsForUser(userId: String): List<ClientSession> =
        sessions.values.filter { it.userId == userId }

    /** Deliver a frame to all of [userId]'s connected sessions. */
    suspend fun sendToUser(userId: String, frame: Outgoing) {
        sessionsForUser(userId).forEach { it.send(frame) }
    }

    /** Broadcasts a frame to every session currently in [roomId]. */
    suspend fun broadcast(roomId: String, frame: Outgoing) {
        val members = rooms[roomId]?.toList() ?: return
        for (id in members) {
            sessions[id]?.send(frame)
        }
    }
}
