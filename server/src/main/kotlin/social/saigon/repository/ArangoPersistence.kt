package social.saigon.repository

import com.arangodb.ArangoCollection
import com.arangodb.ArangoDB
import com.arangodb.ArangoDatabase
import com.arangodb.model.DocumentCreateOptions
import com.arangodb.model.OverwriteMode
import com.arangodb.util.RawJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import social.saigon.domain.InviteCode
import social.saigon.domain.Message
import social.saigon.domain.MetricEvent
import social.saigon.domain.Payment
import social.saigon.domain.Report
import social.saigon.domain.Room
import social.saigon.domain.RoomIdentity
import social.saigon.domain.RoomSearch
import social.saigon.domain.ThreadReply
import social.saigon.domain.User
import social.saigon.domain.Warning
import kotlinx.serialization.Serializable
import kotlin.jvm.java

/**
 * Real ArangoDB persistence (DESIGN §12, TASKS Phase 0).
 *
 * Documents are stored and read back as raw JSON ([RawJson]) produced by kotlinx-serialization, so
 * the wire format matches the rest of the platform exactly and the driver never needs a model of
 * the domain types. Each document carries an injected `_key` (the domain id); the matching
 * `ignoreUnknownKeys` JSON reader silently drops Arango's `_key`/`_id`/`_rev` metadata on read.
 *
 * Only durable collections are backed by Arango. Private chats and the post-chat final-emoji
 * sessions are intentionally ephemeral (DESIGN §7, §8) and stay in-memory.
 */
object ArangoSchemaExtra {
    const val ROOM_IDENTITIES = "RoomIdentities"
    const val REPORTS = "Reports"
    const val WARNINGS = "Warnings"
    const val MODERATION_FLAGS = "ModerationFlags"
    const val PAYMENTS = "Payments"
    const val INVITES = "Invites"

    val documentCollections: List<String> = listOf(
        ROOM_IDENTITIES, REPORTS, WARNINGS, MODERATION_FLAGS, PAYMENTS, INVITES,
    )
}

/** Connection settings for ArangoDB, sourced from the environment in production. */
data class ArangoConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val database: String,
) {
    companion object {
        /** The default fallbacks, kept in sync with the deploy scripts and the local compose file. */
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 8529
        const val DEFAULT_USER = "root"
        const val DEFAULT_PASSWORD = ""
        const val DEFAULT_DATABASE = "saigon_social"

        /**
         * Reads the Arango connection from the environment, applying the documented defaults for any
         * variable that is unset. ArangoDB is mandatory (DESIGN §12): the platform never falls back
         * to file/in-memory storage in production, so this always returns a usable config and the
         * caller fails loudly if the database is unreachable.
         */
        fun fromEnv(getenv: (String) -> String? = System::getenv): ArangoConfig = ArangoConfig(
            host = getenv("ARANGO_HOST")?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST,
            port = getenv("ARANGO_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
            user = getenv("ARANGO_USER")?.takeIf { it.isNotBlank() } ?: DEFAULT_USER,
            password = getenv("ARANGO_PASSWORD") ?: DEFAULT_PASSWORD,
            database = getenv("ARANGO_DB")?.takeIf { it.isNotBlank() } ?: DEFAULT_DATABASE,
        )
    }
}

/** Internal moderation flags document (DESIGN §10) — not a public domain type. */
@Serializable
private data class ModerationFlag(
    val userId: String,
    val banned: Boolean = false,
    val banReason: String? = null,
    val verified: Boolean = false,
)

/**
 * Connects to ArangoDB, creating the database and all collections if they do not yet exist, and
 * returns a ready-to-use [ArangoDatabase] handle.
 *
 * ArangoDB is mandatory (DESIGN §12), but it is commonly started moments before the server (the
 * local `docker compose up` and the deploy scripts both boot the container right before launching
 * the app). A freshly started container reports "Started" while the database is still initialising
 * and not yet accepting connections, which surfaces as the driver's "Cannot contact any host!".
 * So we retry for a bounded window before failing loudly — long enough to ride out first-boot
 * initialisation, short enough that a genuinely missing/misconfigured database still fails fast
 * (both locally and in deployment). The window is configurable via ARANGO_CONNECT_TIMEOUT_SECONDS.
 */
fun connectArango(
    config: ArangoConfig,
    timeoutSeconds: Long =
        System.getenv("ARANGO_CONNECT_TIMEOUT_SECONDS")?.toLongOrNull() ?: DEFAULT_CONNECT_TIMEOUT_SECONDS,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
): ArangoDatabase {
    val arango = ArangoDB.Builder()
        .host(config.host, config.port)
        .user(config.user)
        .password(config.password)
        .build()

    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
    var lastError: Exception? = null
    var attempt = 0
    while (true) {
        attempt++
        try {
            if (!arango.db(config.database).exists()) {
                arango.createDatabase(config.database)
            }
            val db = arango.db(config.database)
            (ArangoSchema.documentCollections + ArangoSchemaExtra.documentCollections).forEach { name ->
                val collection = db.collection(name)
                if (!collection.exists()) collection.create()
            }
            return db
        } catch (e: Exception) {
            lastError = e
            if (System.currentTimeMillis() >= deadline) break
            System.err.println(
                "ArangoDB not reachable yet at ${config.host}:${config.port} " +
                    "(attempt $attempt); retrying while it starts up…",
            )
            // Wait for the database to finish booting, but don't sleep past the deadline.
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            sleep(minOf(RETRY_DELAY_MS, remaining))
        }
    }

    // Fail loudly (both locally and in deployment) once the window elapses, rather than silently
    // degrading to volatile storage (DESIGN §12): the server must not start without its database.
    throw IllegalStateException(
        "Cannot reach ArangoDB at ${config.host}:${config.port} (db='${config.database}', " +
            "user='${config.user}') after ${timeoutSeconds}s. Start ArangoDB and check the ARANGO_* " +
            "environment variables. For local development run: docker compose up -d arango",
        lastError,
    )
}

/** Default window to wait for ArangoDB to become reachable before giving up (first boot is slow). */
const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 60L

/** Delay between connection attempts while waiting for ArangoDB to come up. */
private const val RETRY_DELAY_MS = 2000L

/** Builds an Arango-backed [Repositories] aggregate (ephemeral repos stay in-memory). */
fun arangoRepositories(db: ArangoDatabase): Repositories = Repositories(
    users = ArangoUserRepository(db),
    rooms = ArangoRoomRepository(db),
    identities = ArangoRoomIdentityRepository(db),
    roomSearches = ArangoRoomSearchRepository(db),
    messages = ArangoMessageRepository(db),
    threads = ArangoThreadRepository(db),
    privateChats = InMemoryPrivateChatRepository(),
    finalEmojis = InMemoryFinalEmojiRepository(),
    moderation = ArangoModerationRepository(db),
    payments = ArangoPaymentRepository(db),
    metrics = ArangoMetricRepository(db),
    invites = ArangoInviteCodeRepository(db),
)

/**
 * A tiny generic document store: serialize with [serializer], inject `_key`, upsert/read as
 * [RawJson]. AQL helpers cover the few filtered/distinct lookups the repositories need.
 */
internal class ArangoStore<T>(
    db: ArangoDatabase,
    private val collectionName: String,
    private val serializer: KSerializer<T>,
    private val keyOf: (T) -> String,
) {
    val database: ArangoDatabase = db
    private val collection: ArangoCollection = db.collection(collectionName)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val replace = DocumentCreateOptions().overwriteMode(OverwriteMode.replace)

    fun put(value: T): T {
        val obj = json.encodeToJsonElement(serializer, value).jsonObject
        val withKey = JsonObject(obj + ("_key" to JsonPrimitive(keyOf(value))))
        collection.insertDocument(RawJson.of(json.encodeToString(JsonObject.serializer(), withKey)), replace)
        return value
    }

    fun get(key: String): T? =
        collection.getDocument(key, RawJson::class.java)?.let { json.decodeFromString(serializer, it.get()) }

    fun delete(key: String) {
        runCatching { collection.deleteDocument(key) }
    }

    fun all(): List<T> = query("FOR d IN $collectionName RETURN d")

    fun query(aql: String, bindVars: Map<String, Any> = emptyMap()): List<T> =
        database.query(aql, RawJson::class.java, bindVars).asListRemaining()
            .map { json.decodeFromString(serializer, it.get()) }

    /** Run an AQL query whose projection returns plain strings (e.g. a single field). */
    fun queryStrings(aql: String, bindVars: Map<String, Any> = emptyMap()): List<String> =
        database.query(aql, RawJson::class.java, bindVars).asListRemaining()
            .map { json.decodeFromString(String.serializer(), it.get()) }
}

internal class ArangoUserRepository(db: ArangoDatabase) : UserRepository {
    private val store = ArangoStore(db, ArangoSchema.USERS, User.serializer()) { it.id }
    override fun create(user: User): User = store.put(user)
    override fun get(id: String): User? = store.get(id)
    override fun update(user: User): User = store.put(user)
    override fun all(): List<User> = store.all()
    override fun findByHandle(handle: String): User? =
        store.query(
            "FOR d IN ${ArangoSchema.USERS} FILTER LOWER(d.handle) == LOWER(@h) LIMIT 1 RETURN d",
            mapOf("h" to handle),
        ).firstOrNull()
    override fun delete(id: String) = store.delete(id)
}

internal class ArangoRoomRepository(db: ArangoDatabase) : RoomRepository {
    private val store = ArangoStore(db, ArangoSchema.ROOMS, Room.serializer()) { it.id }
    override fun create(room: Room): Room = store.put(room)
    override fun get(id: String): Room? = store.get(id)
    override fun all(): List<Room> = store.all()
    override fun delete(id: String) = store.delete(id)
}

internal class ArangoRoomIdentityRepository(db: ArangoDatabase) : RoomIdentityRepository {
    private val store = ArangoStore(db, ArangoSchemaExtra.ROOM_IDENTITIES, RoomIdentity.serializer()) {
        key(it.userId, it.roomId)
    }
    private fun key(userId: String, roomId: String) = "$userId@$roomId"
    override fun get(userId: String, roomId: String): RoomIdentity? = store.get(key(userId, roomId))
    override fun put(identity: RoomIdentity): RoomIdentity = store.put(identity)
}

internal class ArangoRoomSearchRepository(db: ArangoDatabase) : RoomSearchRepository {
    private val store = ArangoStore(db, ArangoSchema.ROOM_SEARCHES, RoomSearch.serializer()) { it.id }
    override fun add(search: RoomSearch): RoomSearch = store.put(search)
    override fun recent(limit: Int): List<RoomSearch> =
        store.query(
            "FOR d IN ${ArangoSchema.ROOM_SEARCHES} SORT d.createdAt DESC LIMIT @limit RETURN d",
            mapOf("limit" to limit),
        )
    override fun all(): List<RoomSearch> = store.all()
}

internal class ArangoMessageRepository(db: ArangoDatabase) : MessageRepository {
    private val store = ArangoStore(db, ArangoSchema.MESSAGES, Message.serializer()) { it.id }
    override fun add(message: Message): Message = store.put(message)
    override fun get(id: String): Message? = store.get(id)
    override fun byRoom(roomId: String): List<Message> =
        store.query(
            "FOR d IN ${ArangoSchema.MESSAGES} FILTER d.roomId == @roomId SORT d.createdAt RETURN d",
            mapOf("roomId" to roomId),
        )
    override fun page(roomId: String, before: Long?, limit: Int): List<Message> {
        // The database does the ordering and the paging: it returns the newest [limit] messages
        // older than [before] (newest→oldest). No application-level sorting happens here.
        val aql = if (before == null) {
            "FOR d IN ${ArangoSchema.MESSAGES} FILTER d.roomId == @roomId " +
                "SORT d.createdAt DESC LIMIT @limit RETURN d"
        } else {
            "FOR d IN ${ArangoSchema.MESSAGES} FILTER d.roomId == @roomId AND d.createdAt < @before " +
                "SORT d.createdAt DESC LIMIT @limit RETURN d"
        }
        val bindVars = if (before == null) {
            mapOf("roomId" to roomId, "limit" to limit)
        } else {
            mapOf("roomId" to roomId, "before" to before, "limit" to limit)
        }
        return store.query(aql, bindVars)
    }
}

internal class ArangoThreadRepository(db: ArangoDatabase) : ThreadRepository {
    private val store = ArangoStore(db, ArangoSchema.THREADS, ThreadReply.serializer()) { it.id }
    override fun add(reply: ThreadReply): ThreadReply = store.put(reply)
    override fun byParent(parentMessageId: String): List<ThreadReply> =
        store.query(
            "FOR d IN ${ArangoSchema.THREADS} FILTER d.parentMessageId == @p SORT d.createdAt RETURN d",
            mapOf("p" to parentMessageId),
        )
    override fun replyCount(parentMessageId: String): Int = byParent(parentMessageId).size
    override fun parents(): Set<String> =
        store.queryStrings("FOR d IN ${ArangoSchema.THREADS} RETURN DISTINCT d.parentMessageId").toSet()
    override fun clear(parentMessageIds: Collection<String>) {
        parentMessageIds.forEach { parent ->
            val keys = store.queryStrings(
                "FOR d IN ${ArangoSchema.THREADS} FILTER d.parentMessageId == @p RETURN d._key",
                mapOf("p" to parent),
            )
            keys.forEach { store.delete(it) }
        }
    }
}

internal class ArangoModerationRepository(db: ArangoDatabase) : ModerationRepository {
    private val reports = ArangoStore(db, ArangoSchemaExtra.REPORTS, Report.serializer()) { it.id }
    private val warningStore = ArangoStore(db, ArangoSchemaExtra.WARNINGS, Warning.serializer()) { it.id }
    private val flags = ArangoStore(db, ArangoSchemaExtra.MODERATION_FLAGS, ModerationFlag.serializer()) { it.userId }

    override fun addReport(report: Report): Report = reports.put(report)

    override fun reporters(subjectId: String): Set<String> =
        reports.queryStrings(
            "FOR d IN ${ArangoSchemaExtra.REPORTS} FILTER d.subjectId == @s RETURN d.reporterId",
            mapOf("s" to subjectId),
        ).toSet()

    override fun reportCount(subjectId: String): Int = reporters(subjectId).size

    override fun addWarning(warning: Warning): Warning = warningStore.put(warning)

    override fun warnings(subjectId: String): List<Warning> =
        warningStore.query(
            "FOR d IN ${ArangoSchemaExtra.WARNINGS} FILTER d.subjectId == @s SORT d.createdAt RETURN d",
            mapOf("s" to subjectId),
        )

    override fun ban(userId: String, reason: String?) {
        val existing = flags.get(userId)
        if (existing?.banned == true) return // idempotent: keep the first recorded reason
        flags.put((existing ?: ModerationFlag(userId)).copy(banned = true, banReason = reason))
    }

    override fun banReason(userId: String): String? = flags.get(userId)?.takeIf { it.banned }?.banReason

    override fun isBanned(userId: String): Boolean = flags.get(userId)?.banned ?: false

    override fun setVerified(userId: String, verified: Boolean) {
        val existing = flags.get(userId) ?: ModerationFlag(userId)
        flags.put(existing.copy(verified = verified))
    }

    override fun isVerified(userId: String): Boolean = flags.get(userId)?.verified ?: false
}

internal class ArangoPaymentRepository(db: ArangoDatabase) : PaymentRepository {
    private val store = ArangoStore(db, ArangoSchemaExtra.PAYMENTS, Payment.serializer()) { it.id }
    override fun add(payment: Payment): Payment = store.put(payment)
    override fun byUser(userId: String): List<Payment> =
        store.query(
            "FOR d IN ${ArangoSchemaExtra.PAYMENTS} FILTER d.userId == @u SORT d.createdAt RETURN d",
            mapOf("u" to userId),
        )
    override fun all(): List<Payment> = store.all()
}

internal class ArangoMetricRepository(db: ArangoDatabase) : MetricRepository {
    private val store = ArangoStore(db, ArangoSchema.METRICS, MetricEvent.serializer()) { it.id }
    override fun add(event: MetricEvent): MetricEvent = store.put(event)
    override fun all(): List<MetricEvent> = store.all()
}

internal class ArangoInviteCodeRepository(db: ArangoDatabase) : InviteCodeRepository {
    // Invite codes are matched case-insensitively (DESIGN §2), so the uppercased code is the _key.
    private val store = ArangoStore(db, ArangoSchemaExtra.INVITES, InviteCode.serializer()) { key(it.code) }
    private fun key(code: String) = code.uppercase()
    override fun create(invite: InviteCode): InviteCode = store.put(invite)
    override fun get(code: String): InviteCode? = store.get(key(code))
    override fun update(invite: InviteCode): InviteCode = store.put(invite)
    override fun all(): List<InviteCode> = store.all()
    override fun active(): List<InviteCode> =
        // The database does the filtering and ordering: only unredeemed codes, newest first.
        store.query(
            "FOR d IN ${ArangoSchemaExtra.INVITES} FILTER d.redeemedBy == null " +
                "SORT d.createdAt DESC RETURN d",
        )
    override fun delete(code: String) = store.delete(key(code))
}
