package social.saigon.repository

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import social.saigon.domain.FinalEmojiSession
import social.saigon.domain.InviteCode
import social.saigon.domain.Message
import social.saigon.domain.MetricEvent
import social.saigon.domain.Payment
import social.saigon.domain.PrivateChat
import social.saigon.domain.PrivateMessage
import social.saigon.domain.Report
import social.saigon.domain.Room
import social.saigon.domain.RoomIdentity
import social.saigon.domain.RoomSearch
import social.saigon.domain.ThreadReply
import social.saigon.domain.User
import social.saigon.domain.Warning

/**
 * Base data-model collections (DESIGN §12, TASKS Phase 0).
 *
 * The platform targets ArangoDB (multi-model: documents + graphs). These names define the
 * document collections and graph edge collections; [ArangoSchema] is consumed by the
 * persistence layer to provision the database. The default runtime repositories are in-memory
 * so the platform is fully testable without a running database.
 */
object ArangoSchema {
    const val USERS = "Users"
    const val ROOMS = "Rooms"
    const val MESSAGES = "Messages"
    const val THREADS = "Threads"
    const val EMOJIS = "Emojis"
    const val METRICS = "Metrics"
    const val ROOM_SEARCHES = "RoomSearches"

    /** Document collections provisioned on startup. */
    val documentCollections: List<String> =
        listOf(USERS, ROOMS, MESSAGES, THREADS, EMOJIS, METRICS, ROOM_SEARCHES)
}

interface UserRepository {
    fun create(user: User): User
    fun get(id: String): User?
    fun update(user: User): User
    fun all(): List<User>
    /** Look up a member by their unique, login-only handle (PASSWORD_AUTH.md §2C); null if none. */
    fun findByHandle(handle: String): User?
    /** Permanently remove a member by id (founder-only hard removal). Idempotent. */
    fun delete(id: String)
}

interface RoomRepository {
    fun create(room: Room): Room
    fun get(id: String): Room?
    fun all(): List<Room>
    /** Delete a room by id. Idempotent. */
    fun delete(id: String)
}

/**
 * Founder-issued invite codes (single-use account-creation credentials). The founder mints a code;
 * the recipient redeems it once to create their account.
 */
interface InviteCodeRepository {
    fun create(invite: InviteCode): InviteCode
    fun get(code: String): InviteCode?
    fun update(invite: InviteCode): InviteCode
    fun all(): List<InviteCode>
    fun delete(code: String)
}

interface RoomIdentityRepository {
    fun get(userId: String, roomId: String): RoomIdentity?
    fun put(identity: RoomIdentity): RoomIdentity
}

/**
 * Recorded room searches (DESIGN §6). Append-only: every search of the room list is stored so the
 * founder can understand what kinds of rooms people are looking for.
 */
interface RoomSearchRepository {
    fun add(search: RoomSearch): RoomSearch
    /** Recent searches, newest first, capped at [limit]. */
    fun recent(limit: Int): List<RoomSearch>
    fun all(): List<RoomSearch>
}

interface MessageRepository {
    fun add(message: Message): Message
    fun get(id: String): Message?
    fun byRoom(roomId: String): List<Message>

    /**
     * A single page of a room's messages for infinite scroll (20 per page). Returns up to [limit]
     * messages ordered newest→oldest; when [before] is non-null only messages strictly older than
     * that createdAt are considered (so the client can page upward through history). The ordering
     * and the limit are applied by the data store itself, not in application code.
     */
    fun page(roomId: String, before: Long?, limit: Int): List<Message>
}

/**
 * Private chats are ephemeral (DESIGN §7): no history is persisted once a chat ends. Ending a
 * chat drops both the session record and any messages exchanged within it.
 */
interface PrivateChatRepository {
    fun create(chat: PrivateChat): PrivateChat
    fun get(id: String): PrivateChat?
    /**
     * An open chat including both users (in either direction) that was started in [roomId], or null
     * if none is open. Chats are room-scoped (DESIGN §7), so the same pair can hold independent
     * conversations opened from different rooms.
     */
    fun findOpen(userA: String, userB: String, roomId: String): PrivateChat?
    /** Append an ephemeral message; the chat must still be open. */
    fun addMessage(message: PrivateMessage): PrivateMessage
    fun messages(chatId: String): List<PrivateMessage>
    /** End the chat and discard its history, returning the removed session (or null). */
    fun end(id: String): PrivateChat?
}

/**
 * Post-chat closing-emoji sessions (DESIGN §8). Ephemeral and tied to the ended chat; dropped once
 * both emojis are revealed (no follow-up nudges).
 */
interface FinalEmojiRepository {
    fun create(session: FinalEmojiSession): FinalEmojiSession
    fun get(chatId: String): FinalEmojiSession?
    fun update(session: FinalEmojiSession): FinalEmojiSession
    /** Drop the session, returning the removed value (or null). */
    fun remove(chatId: String): FinalEmojiSession?
}

/**
 * Moderation & safety state (DESIGN §10, TASKS Phase 7).
 *
 * Stores abuse reports, founder warnings, ban flags, and founder-verification flags. Reports are
 * counted by distinct reporter so two different people are required to trigger a ban.
 */
interface ModerationRepository {
    fun addReport(report: Report): Report
    /** Distinct reporter ids who have filed a report against [subjectId]. */
    fun reporters(subjectId: String): Set<String>
    fun reportCount(subjectId: String): Int
    fun addWarning(warning: Warning): Warning
    fun warnings(subjectId: String): List<Warning>
    /** Permanently ban [userId], recording an optional [reason]. Idempotent. */
    fun ban(userId: String, reason: String?)
    fun banReason(userId: String): String?
    fun isBanned(userId: String): Boolean
    fun setVerified(userId: String, verified: Boolean)
    fun isVerified(userId: String): Boolean
}

/**
 * Recorded platform payments (DESIGN §11, TASKS Phase 8): the one-time Sharer fee and Supporter
 * subscription periods. Append-only; there is no marketplace and no user-to-user transaction
 * ledger (the platform never takes a cut of money exchanged between members).
 */
interface PaymentRepository {
    fun add(payment: Payment): Payment
    fun byUser(userId: String): List<Payment>
    fun all(): List<Payment>
}

/**
 * Internal success-metric events (DESIGN §14, TASKS Phase 10). Append-only and content-free —
 * see [social.saigon.domain.MetricEvent]. Never exposed publicly; aggregated for the founder only.
 */
interface MetricRepository {
    fun add(event: MetricEvent): MetricEvent
    fun all(): List<MetricEvent>
}

/**
 * Threads are ephemeral (DESIGN §9): cleared when a room empties or after 24h of inactivity.
 */
interface ThreadRepository {
    fun add(reply: ThreadReply): ThreadReply
    fun byParent(parentMessageId: String): List<ThreadReply>
    fun replyCount(parentMessageId: String): Int
    /** All parent message ids that currently have at least one reply. */
    fun parents(): Set<String>
    /** Drop the threads hanging off the given parent message ids. */
    fun clear(parentMessageIds: Collection<String>)
}

class InMemoryUserRepository : UserRepository {
    private val store = ConcurrentHashMap<String, User>()
    override fun create(user: User): User = user.also { store[it.id] = it }
    override fun get(id: String): User? = store[id]
    override fun update(user: User): User = user.also { store[it.id] = it }
    override fun all(): List<User> = store.values.toList()
    override fun findByHandle(handle: String): User? =
        store.values.firstOrNull { it.handle != null && it.handle.equals(handle, ignoreCase = true) }
    override fun delete(id: String) { store.remove(id) }
}

class InMemoryRoomRepository : RoomRepository {
    private val store = ConcurrentHashMap<String, Room>()
    override fun create(room: Room): Room = room.also { store[it.id] = it }
    override fun get(id: String): Room? = store[id]
    override fun all(): List<Room> = store.values.toList()
    override fun delete(id: String) { store.remove(id) }
}

class InMemoryInviteCodeRepository : InviteCodeRepository {
    private val store = ConcurrentHashMap<String, InviteCode>()
    private fun key(code: String) = code.uppercase()
    override fun create(invite: InviteCode): InviteCode = invite.also { store[key(it.code)] = it }
    override fun get(code: String): InviteCode? = store[key(code)]
    override fun update(invite: InviteCode): InviteCode = invite.also { store[key(it.code)] = it }
    override fun all(): List<InviteCode> = store.values.toList()
    override fun delete(code: String) { store.remove(key(code)) }
}

class InMemoryRoomIdentityRepository : RoomIdentityRepository {
    private val store = ConcurrentHashMap<String, RoomIdentity>()
    private fun key(userId: String, roomId: String) = "$userId@$roomId"
    override fun get(userId: String, roomId: String): RoomIdentity? = store[key(userId, roomId)]
    override fun put(identity: RoomIdentity): RoomIdentity =
        identity.also { store[key(it.userId, it.roomId)] = it }
}

class InMemoryRoomSearchRepository : RoomSearchRepository {
    private val store = CopyOnWriteArrayList<RoomSearch>()
    override fun add(search: RoomSearch): RoomSearch = search.also { store.add(it) }
    override fun recent(limit: Int): List<RoomSearch> =
        store.sortedByDescending { it.createdAt }.take(limit)
    override fun all(): List<RoomSearch> = store.toList()
}

class InMemoryMessageRepository : MessageRepository {
    // CopyOnWriteArrayList + computeIfAbsent keep concurrent room writes safe under load (Phase 11).
    private val store = ConcurrentHashMap<String, MutableList<Message>>()
    private val byId = ConcurrentHashMap<String, Message>()
    override fun add(message: Message): Message = message.also {
        store.computeIfAbsent(it.roomId) { CopyOnWriteArrayList() }.add(it)
        byId[it.id] = it
    }
    override fun get(id: String): Message? = byId[id]
    override fun byRoom(roomId: String): List<Message> = store[roomId]?.toList() ?: emptyList()
    override fun page(roomId: String, before: Long?, limit: Int): List<Message> {
        // Mirror the store-level contract: newest→oldest, newest [limit] strictly older than [before].
        val all = (store[roomId]?.toList() ?: emptyList()).sortedByDescending { it.createdAt }
        val older = if (before == null) all else all.filter { it.createdAt < before }
        return older.take(limit)
    }
}

class InMemoryPrivateChatRepository : PrivateChatRepository {
    private val chats = ConcurrentHashMap<String, PrivateChat>()
    private val byChat = ConcurrentHashMap<String, MutableList<PrivateMessage>>()
    override fun create(chat: PrivateChat): PrivateChat = chat.also { chats[it.id] = it }
    override fun get(id: String): PrivateChat? = chats[id]
    override fun findOpen(userA: String, userB: String, roomId: String): PrivateChat? =
        chats.values.firstOrNull { it.roomId == roomId && it.includes(userA) && it.includes(userB) }
    override fun addMessage(message: PrivateMessage): PrivateMessage = message.also {
        byChat.getOrPut(it.chatId) { mutableListOf() }.add(it)
    }
    override fun messages(chatId: String): List<PrivateMessage> = byChat[chatId]?.toList() ?: emptyList()
    override fun end(id: String): PrivateChat? {
        // Ephemeral: drop the history along with the session (DESIGN §7).
        byChat.remove(id)
        return chats.remove(id)
    }
}

class InMemoryFinalEmojiRepository : FinalEmojiRepository {
    private val store = ConcurrentHashMap<String, FinalEmojiSession>()
    override fun create(session: FinalEmojiSession): FinalEmojiSession =
        session.also { store[it.chatId] = it }
    override fun get(chatId: String): FinalEmojiSession? = store[chatId]
    override fun update(session: FinalEmojiSession): FinalEmojiSession =
        session.also { store[it.chatId] = it }
    override fun remove(chatId: String): FinalEmojiSession? = store.remove(chatId)
}

class InMemoryModerationRepository : ModerationRepository {
    private val reports = ConcurrentHashMap<String, MutableList<Report>>()
    private val warningsBySubject = ConcurrentHashMap<String, MutableList<Warning>>()
    private val banned = ConcurrentHashMap<String, String>()
    private val verified = ConcurrentHashMap<String, Boolean>()

    // A sentinel for a ban with no recorded reason, so absence vs. null reason stay distinct.
    private val noReason = ""

    override fun addReport(report: Report): Report = report.also {
        reports.getOrPut(it.subjectId) { mutableListOf() }.add(it)
    }

    override fun reporters(subjectId: String): Set<String> =
        reports[subjectId]?.map { it.reporterId }?.toSet() ?: emptySet()

    override fun reportCount(subjectId: String): Int = reporters(subjectId).size

    override fun addWarning(warning: Warning): Warning = warning.also {
        warningsBySubject.getOrPut(it.subjectId) { mutableListOf() }.add(it)
    }

    override fun warnings(subjectId: String): List<Warning> =
        warningsBySubject[subjectId]?.toList() ?: emptyList()

    override fun ban(userId: String, reason: String?) {
        banned.putIfAbsent(userId, reason ?: noReason)
    }

    override fun banReason(userId: String): String? = banned[userId]?.takeIf { it != noReason }

    override fun isBanned(userId: String): Boolean = banned.containsKey(userId)

    override fun setVerified(userId: String, verified: Boolean) {
        this.verified[userId] = verified
    }

    override fun isVerified(userId: String): Boolean = verified[userId] ?: false
}

class InMemoryPaymentRepository : PaymentRepository {
    private val byUser = ConcurrentHashMap<String, MutableList<Payment>>()
    override fun add(payment: Payment): Payment = payment.also {
        byUser.getOrPut(it.userId) { mutableListOf() }.add(it)
    }
    override fun byUser(userId: String): List<Payment> = byUser[userId]?.toList() ?: emptyList()
    override fun all(): List<Payment> = byUser.values.flatten()
}

class InMemoryMetricRepository : MetricRepository {
    private val store = CopyOnWriteArrayList<MetricEvent>()
    override fun add(event: MetricEvent): MetricEvent = event.also { store.add(it) }
    override fun all(): List<MetricEvent> = store.toList()
}

class InMemoryThreadRepository : ThreadRepository {
    // CopyOnWriteArrayList + computeIfAbsent keep concurrent thread replies safe under load (Phase 11).
    private val store = ConcurrentHashMap<String, MutableList<ThreadReply>>()
    override fun add(reply: ThreadReply): ThreadReply = reply.also {
        store.computeIfAbsent(it.parentMessageId) { CopyOnWriteArrayList() }.add(it)
    }
    override fun byParent(parentMessageId: String): List<ThreadReply> =
        store[parentMessageId]?.toList() ?: emptyList()
    override fun replyCount(parentMessageId: String): Int = store[parentMessageId]?.size ?: 0
    override fun parents(): Set<String> = store.keys.toSet()
    override fun clear(parentMessageIds: Collection<String>) {
        parentMessageIds.forEach { store.remove(it) }
    }
}

/**
 * Aggregates all repositories so they can be injected together.
 */
class Repositories(
    val users: UserRepository = InMemoryUserRepository(),
    val rooms: RoomRepository = InMemoryRoomRepository(),
    val identities: RoomIdentityRepository = InMemoryRoomIdentityRepository(),
    val roomSearches: RoomSearchRepository = InMemoryRoomSearchRepository(),
    val messages: MessageRepository = InMemoryMessageRepository(),
    val threads: ThreadRepository = InMemoryThreadRepository(),
    val privateChats: PrivateChatRepository = InMemoryPrivateChatRepository(),
    val finalEmojis: FinalEmojiRepository = InMemoryFinalEmojiRepository(),
    val moderation: ModerationRepository = InMemoryModerationRepository(),
    val payments: PaymentRepository = InMemoryPaymentRepository(),
    val metrics: MetricRepository = InMemoryMetricRepository(),
    val invites: InviteCodeRepository = InMemoryInviteCodeRepository(),
)
