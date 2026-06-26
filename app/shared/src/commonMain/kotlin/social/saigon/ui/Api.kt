package social.saigon.ui

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The live backend client (DESIGN §12). Talks to the Ktor server over HTTP for the durable,
 * ArangoDB-backed reads/writes (rooms, messages, threads, occupants, profile, identity) and over a
 * WebSocket for real-time public chat. There is no in-memory sample data: every screen renders
 * what the server actually persists.
 */
class SaigonApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** A member JWT enables sending and member-only reads; null browses as an anonymous viewer. */
    var token: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
    }

    private fun HttpRequestAuth(builder: io.ktor.client.request.HttpRequestBuilder) {
        token?.let { builder.header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /**
     * Decode a member-only response, turning the server's 401/403 into a friendly
     * [InvalidTokenException] instead of letting the raw status text or a JSON-parse failure bubble
     * up to the UI.
     */
    private suspend inline fun <reified T> HttpResponse.decodeMember(): T {
        when {
            status == HttpStatusCode.Unauthorized ->
                throw InvalidTokenException(
                    "That access token isn't valid. Check it and try again, or ask the founder for a new one.",
                )
            status == HttpStatusCode.Forbidden ->
                throw InvalidTokenException(
                    bodyAsTextSafe().takeIf { it.isNotBlank() }
                        ?: "Your account isn't allowed to do that.",
                )
            !status.isSuccess() ->
                throw InvalidTokenException("Something went wrong (${status.value}). Please try again.")
        }
        return body()
    }

    private suspend fun HttpResponse.bodyAsTextSafe(): String =
        runCatching { bodyAsText() }.getOrDefault("")

    /** Public room list with live occupant counts (DESIGN §6). */
    suspend fun rooms(): List<RoomSummary> =
        client.get("$baseUrl/rooms") { HttpRequestAuth(this) }.body()

    /** A room's public chat feed, each message carrying its reply-count (DESIGN §6, §9). */
    suspend fun messages(roomId: String): List<FeedMessage> =
        client.get("$baseUrl/rooms/$roomId/messages") { HttpRequestAuth(this) }.body()

    /** Who is currently in a room, with intents + languages (DESIGN §6). */
    suspend fun occupants(roomId: String): List<OccupantView> =
        client.get("$baseUrl/rooms/$roomId/occupants") { HttpRequestAuth(this) }.body()

    /** A message's ephemeral thread (DESIGN §9). */
    suspend fun thread(roomId: String, messageId: String): List<ThreadReply> =
        client.get("$baseUrl/rooms/$roomId/messages/$messageId/thread") { HttpRequestAuth(this) }.body()

    /** The caller's persistent profile (DESIGN §4). Requires a member token. */
    suspend fun profile(): ProfileResponse =
        client.get("$baseUrl/profile/me") { HttpRequestAuth(this) }.decodeMember()

    /** Update editable profile fields (DESIGN §4). Requires a member token. */
    suspend fun updateProfile(update: ProfileUpdate): ProfileResponse =
        client.put("$baseUrl/profile/me") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(update)
        }.decodeMember()

    /** The caller's room-scoped identity, generated on first join (DESIGN §5). Requires a token. */
    suspend fun identity(roomId: String): RoomIdentity =
        client.get("$baseUrl/rooms/$roomId/identity") { HttpRequestAuth(this) }.decodeMember()

    /** "Refresh my name & avatar in this room" (DESIGN §5). Requires a member token. */
    suspend fun refreshIdentity(roomId: String): RoomIdentity =
        client.post("$baseUrl/rooms/$roomId/identity/refresh") { HttpRequestAuth(this) }.decodeMember()

    /** Obtain a read-only Seeker token bound to a device id (DESIGN §3). */
    suspend fun seekerToken(deviceId: String): String =
        client.post("$baseUrl/auth/seeker") {
            contentType(ContentType.Application.Json)
            setBody(SeekerTokenRequest(deviceId))
        }.body<TokenResponse>().token

    /**
     * Founder bootstrap (DESIGN §2): claim the Founder account with a blank access token. Only
     * succeeds when no member account exists yet; otherwise the server returns 403 and this throws a
     * friendly [InvalidTokenException].
     */
    suspend fun bootstrapFounder(): OnboardResponse =
        client.post("$baseUrl/auth/bootstrap").decodeMember()

    /**
     * Founder-only: onboard a new member, minting the access token they will sign in with
     * (DESIGN §2, §3). Requires the caller's token to be the founder's.
     */
    suspend fun onboard(displayName: String, role: Role): OnboardResponse =
        client.post("$baseUrl/founder/onboard") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(OnboardRequest(displayName, role))
        }.decodeMember()

    /**
     * Open a live public-chat channel for a room (DESIGN §6, §12) and join it. The caller is
     * responsible for collecting broadcasts via [RoomChannel.start] (a long-running suspend) in its
     * own coroutine, and for closing the channel when leaving the room.
     */
    suspend fun openRoom(roomId: String): RoomChannel {
        val wsUrl = buildString {
            append(baseUrl.replaceFirst("http", "ws"))
            append("/ws")
            token?.let { append("?token=$it") }
        }
        val session = client.webSocketSession(wsUrl)
        session.send(Frame.Text(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", JOIN_TYPE)
            put("roomId", roomId)
        })))
        return RoomChannel(session, roomId, json)
    }

    fun close() {
        client.close()
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:9090"
        private const val JOIN_TYPE = "social.saigon.realtime.Incoming.Join"
        const val SAY_TYPE = "social.saigon.realtime.Incoming.Say"
        const val REPLY_TYPE = "social.saigon.realtime.Incoming.Reply"
        const val SAID_TYPE = "social.saigon.realtime.Outgoing.Said"
    }
}

/**
 * A user-facing authentication failure (bad/expired access token, or a forbidden action). Carries a
 * friendly, already-localized message suitable for display, instead of the raw server status text.
 */
class InvalidTokenException(message: String) : Exception(message)

/** A live, room-scoped chat message broadcast over the WebSocket (`Outgoing.Said`). */
data class ChatMessage(
    val messageId: String,
    val authorId: String,
    val authorName: String?,
    val text: String,
    val createdAt: Long,
)

/**
 * An open public-chat WebSocket for a single room. Reads server broadcasts and lets members send
 * plain-text messages (DESIGN §6). Close it when leaving the room — emptying a room drops its
 * ephemeral threads server-side (DESIGN §9).
 */
class RoomChannel internal constructor(
    private val session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    private val roomId: String,
    private val json: Json,
) {
    /** Consume incoming frames, surfacing each broadcast public message to [onMessage]. */
    suspend fun start(onMessage: (ChatMessage) -> Unit) {
        session.incoming.consumeAsFlow().filterIsInstance<Frame.Text>().collect { frame ->
            val obj = runCatching { json.parseToJsonElement(frame.readText()) as? JsonObject }.getOrNull() ?: return@collect
            if (obj["type"]?.jsonPrimitive?.contentOrNull == SaigonApi.SAID_TYPE) {
                onMessage(
                    ChatMessage(
                        messageId = obj["messageId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        authorId = obj["authorId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        authorName = obj["authorName"]?.jsonPrimitive?.contentOrNull,
                        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    ),
                )
            }
        }
    }

    /** Send a plain-text public message (DESIGN §6). Requires the channel's token to be a member. */
    suspend fun say(text: String) {
        session.send(Frame.Text(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", SaigonApi.SAY_TYPE)
            put("roomId", roomId)
            put("text", text)
        })))
    }

    /** Reply within a message's thread (DESIGN §9). Requires the channel's token to be a member. */
    suspend fun reply(parentMessageId: String, text: String) {
        session.send(Frame.Text(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", SaigonApi.REPLY_TYPE)
            put("roomId", roomId)
            put("parentMessageId", parentMessageId)
            put("text", text)
        })))
    }

    suspend fun close() {
        session.close()
    }
}
