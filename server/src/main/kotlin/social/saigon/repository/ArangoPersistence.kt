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
import social.saigon.domain.Message
import social.saigon.domain.MetricEvent
import social.saigon.domain.Payment
import social.saigon.domain.Report
import social.saigon.domain.Room
import social.saigon.domain.RoomIdentity
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

    val documentCollections: List<String> = listOf(
        ROOM_IDENTITIES, REPORTS, WARNINGS, MODERATION_FLAGS, PAYMENTS,
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
        /**
         * Reads the Arango connection from the environment. Returns null when no host is configured,
         * so the platform transparently falls back to in-memory repositories (e.g. in tests).
         */
        fun fromEnv(getenv: (String) -> String? = System::getenv): ArangoConfig? {
            val host = getenv("ARANGO_HOST")?.takeIf { it.isNotBlank() } ?: return null
            return ArangoConfig(
                host = host,
                port = getenv("ARANGO_PORT")?.toIntOrNull() ?: 8529,
                user = getenv("ARANGO_USER") ?: "root",
                password = getenv("ARANGO_PASSWORD") ?: "",
                database = getenv("ARANGO_DB") ?: "saigon_social",
            )
        }
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
 */
fun connectArango(config: ArangoConfig): ArangoDatabase {
    val arango = ArangoDB.Builder()
        .host(config.host, config.port)
        .user(config.user)
        .password(config.password)
        .build()
    if (!arango.db(config.database).exists()) {
        arango.createDatabase(config.database)
    }
    val db = arango.db(config.database)
    (ArangoSchema.documentCollections + ArangoSchemaExtra.documentCollections).forEach { name ->
        val collection = db.collection(name)
        if (!collection.exists()) collection.create()
    }
    return db
}

/** Builds an Arango-backed [Repositories] aggregate (ephemeral repos stay in-memory). */
fun arangoRepositories(db: ArangoDatabase): Repositories = Repositories(
    users = ArangoUserRepository(db),
    rooms = ArangoRoomRepository(db),
    identities = ArangoRoomIdentityRepository(db),
    messages = ArangoMessageRepository(db),
    threads = ArangoThreadRepository(db),
    privateChats = InMemoryPrivateChatRepository(),
    finalEmojis = InMemoryFinalEmojiRepository(),
    moderation = ArangoModerationRepository(db),
    payments = ArangoPaymentRepository(db),
    metrics = ArangoMetricRepository(db),
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
}

internal class ArangoRoomRepository(db: ArangoDatabase) : RoomRepository {
    private val store = ArangoStore(db, ArangoSchema.ROOMS, Room.serializer()) { it.id }
    override fun create(room: Room): Room = store.put(room)
    override fun get(id: String): Room? = store.get(id)
    override fun all(): List<Room> = store.all()
}

internal class ArangoRoomIdentityRepository(db: ArangoDatabase) : RoomIdentityRepository {
    private val store = ArangoStore(db, ArangoSchemaExtra.ROOM_IDENTITIES, RoomIdentity.serializer()) {
        key(it.userId, it.roomId)
    }
    private fun key(userId: String, roomId: String) = "$userId@$roomId"
    override fun get(userId: String, roomId: String): RoomIdentity? = store.get(key(userId, roomId))
    override fun put(identity: RoomIdentity): RoomIdentity = store.put(identity)
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
    override fun clearForRoom(roomId: String, messageIds: Collection<String>) = clear(messageIds)
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
