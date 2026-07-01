package social.saigon.ui

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    /**
     * Public room list with live occupant counts (DESIGN §6). An optional [query] filters the rooms
     * on the backend (by name/atmosphere); non-blank queries are recorded server-side so the founder
     * can see what kinds of rooms people are looking for.
     */
    suspend fun rooms(query: String? = null): List<RoomSummary> =
        client.get("$baseUrl/rooms") {
            HttpRequestAuth(this)
            query?.trim('.', ',')?.trim()?.takeIf { it.isNotBlank() }?.let { url.parameters.append("q", it) }
        }.body()

    /**
     * A page of a room's public chat feed, each message carrying its reply-count (DESIGN §6, §9).
     * Infinite scroll pages by 20 — pass [before] (the oldest loaded message's createdAt) to fetch
     * the previous page of older messages. The database orders and pages the feed newest→oldest
     * (see MessageRepository.page); we only reverse the page here so the UI keeps its oldest→newest
     * chronological order. No client-side sorting happens.
     */
    suspend fun messages(roomId: String, before: Long? = null, limit: Int = 20): List<FeedMessage> =
        client.get("$baseUrl/rooms/$roomId/messages") {
            HttpRequestAuth(this)
            url.parameters.append("limit", limit.toString())
            before?.let { url.parameters.append("before", it.toString()) }
        }.body<List<FeedMessage>>().reversed()

    /**
     * Create a new room (Supporters + Founder; DESIGN §6). Requires a member token. An optional
     * [id] sets the room's URL; when blank the server assigns one.
     */
    suspend fun createRoom(name: String, emoji: String, atmosphere: String, id: String? = null) {
        val response = client.post("$baseUrl/rooms") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(RoomCreate(name, emoji, atmosphere, id?.takeIf { it.isNotBlank() }))
        }
        // Surface the server's validation message (e.g. a taken room URL) so the UI can show it
        // rather than silently dropping the failure.
        if (!response.status.isSuccess()) {
            throw RoomCreateException(
                response.bodyAsTextSafe().takeIf { it.isNotBlank() }
                    ?: "Could not create the room. Please try again.",
            )
        }
    }

    /** Founder-only: rename/edit a room (DESIGN §6). */
    suspend fun updateRoom(roomId: String, name: String, emoji: String, atmosphere: String): Unit =
        client.put("$baseUrl/rooms/$roomId") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(RoomUpdate(name, emoji, atmosphere))
        }.let { }

    /** Founder-only: delete a room (DESIGN §6). */
    suspend fun deleteRoom(roomId: String): Unit =
        client.delete("$baseUrl/rooms/$roomId") { HttpRequestAuth(this) }.let { }

    /** Who is currently in a room, with intents + languages (DESIGN §6). */
    suspend fun occupants(roomId: String): List<OccupantView> =
        client.get("$baseUrl/rooms/$roomId/occupants") { HttpRequestAuth(this) }.body()

    /**
     * A single member's room-scoped occupant view by persistent user id (DESIGN §5, §6). Used to
     * open the detail sheet from a message's profile icon even when the author is no longer one of
     * the live occupants. Returns null when the member can't be found (e.g. 404).
     */
    suspend fun occupant(roomId: String, userId: String): OccupantView? {
        val response = client.get("$baseUrl/rooms/$roomId/occupants/$userId") { HttpRequestAuth(this) }
        return if (response.status.isSuccess()) response.body() else null
    }

    /** A message's ephemeral thread (DESIGN §9). */
    suspend fun thread(roomId: String, messageId: String): List<ThreadReply> =
        client.get("$baseUrl/rooms/$roomId/messages/$messageId/thread") { HttpRequestAuth(this) }.body()

    /**
     * Recall a public-chat message (DESIGN §6, §9): the server clears its text and flags it as
     * recalled, so it renders a "message recalled" notice instead of its content. Only the author or
     * the Founder may do this; the server rejects anyone else with a friendly [InvalidTokenException].
     * The message's thread is left untouched. Returns the refreshed, recalled message.
     */
    suspend fun recallMessage(roomId: String, messageId: String): FeedMessage =
        client.post("$baseUrl/rooms/$roomId/messages/$messageId/recall") { HttpRequestAuth(this) }
            .decodeMember()

    /**
     * Recall a thread reply (DESIGN §6, §9): like [recallMessage] but scoped to a single reply
     * inside [messageId]'s thread. The server clears its text and flags it recalled so it renders a
     * "message recalled" notice instead of its content. Only the reply's author or the Founder may
     * do this; anyone else is rejected with a friendly [InvalidTokenException]. The rest of the
     * thread is left untouched. Returns the refreshed, recalled reply.
     */
    suspend fun recallReply(roomId: String, messageId: String, replyId: String): ThreadReply =
        client.post("$baseUrl/rooms/$roomId/messages/$messageId/replies/$replyId/recall") {
            HttpRequestAuth(this)
        }.decodeMember()

    /**
     * The caller's persistent profile (DESIGN §4). Requires a member token. A 401 or 400 from this
     * route means the saved token is no longer usable (expired/revoked, or no longer maps to a
     * member), so we clear it locally and surface a [SessionExpiredException] telling the UI to sign
     * the user out. Any other failure is surfaced as-is and must NOT clear the token.
     */
    suspend fun profile(): ProfileResponse {
        val response = client.get("$baseUrl/profile/me") { HttpRequestAuth(this) }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.BadRequest) {
            token = null
            throw SessionExpiredException("Your session has expired. Please sign in again.")
        }
        return response.decodeMember()
    }

    /**
     * The caller's private emoji tally, aggregated from received final-emoji reactions (DESIGN §4,
     * §8). Visible only to the user themself. Requires a member token.
     */
    suspend fun emojiStats(): Map<String, Int> =
        client.get("$baseUrl/profile/me/emoji-stats") { HttpRequestAuth(this) }
            .decodeMember<EmojiStatsResponse>().stats

    /**
     * Active threads the user has started or replied to (ensures state restoration across reloads).
     */
    suspend fun myThreads(): List<ThreadSummary> =
        client.get("$baseUrl/profile/me/threads") { HttpRequestAuth(this) }
            .decodeMember()

    /**
     * Dismiss a thread reply, preventing it from showing up in active thread replies until another reply comes in.
     */
    suspend fun dismissThread(parentMessageId: String): Unit {
        client.post("$baseUrl/profile/me/threads/$parentMessageId/dismiss") { HttpRequestAuth(this) }
    }

    /** Update editable profile fields (DESIGN §4). Requires a member token. */
    suspend fun updateProfile(update: ProfileUpdate): ProfileResponse =
        client.put("$baseUrl/profile/me") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(update)
        }.decodeMember()

    /**
     * Set or change the durable sign-in password (PASSWORD_AUTH.md §2B). Requires a member token;
     * [currentPassword] is required only when one is already set. Returns the refreshed profile.
     */
    suspend fun setPassword(newPassword: String, currentPassword: String?): ProfileResponse =
        client.post("$baseUrl/profile/password") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(SetPasswordRequest(newPassword, currentPassword))
        }.decodeMember()

    /**
     * Sign in with a handle + password set earlier (PASSWORD_AUTH.md §2C). The server mints a fresh
     * member JWT, so this also renews an expired token. Bad credentials surface as a friendly
     * [InvalidTokenException].
     */
    suspend fun login(identifier: String, password: String): OnboardResponse {
        val response = client.post("$baseUrl/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(identifier, password))
        }
        // A failed password sign-in is a credentials problem, not a bad access token — surface the
        // right message instead of the generic "that token is invalid" copy.
        when {
            response.status == HttpStatusCode.Unauthorized ->
                throw InvalidTokenException("Invalid username and password.")
            !response.status.isSuccess() ->
                throw InvalidTokenException(
                    response.bodyAsTextSafe().takeIf { it.isNotBlank() }
                        ?: "Could not sign in. Please try again.",
                )
        }
        return response.body()
    }

    /**
     * Create an account by redeeming a founder-issued invite code, choosing a username + password
     * (replaces pasting a raw access token). The server mints a member JWT on success.
     */
    suspend fun redeemInvite(code: String, username: String, password: String): OnboardResponse {
        val response = client.post("$baseUrl/auth/redeem") {
            contentType(ContentType.Application.Json)
            setBody(RedeemRequest(code, username, password))
        }
        if (!response.status.isSuccess()) {
            throw InvalidTokenException(
                response.bodyAsTextSafe().takeIf { it.isNotBlank() }
                    ?: "Could not create your account. Check your invite code and try again.",
            )
        }
        return response.body()
    }

    // --- Services, feedback, and invite requests ---

    /**
     * Public: search services by name (case-insensitive partial match). Blank [query] lists all.
     * Optionally scope to a single [providerId] (their "Providing"/"Services provided" lists) and/or
     * a single [roomId].
     */
    suspend fun services(
        query: String? = null,
        providerId: String? = null,
        roomId: String? = null,
    ): List<ServiceView> =
        client.get("$baseUrl/services") {
            HttpRequestAuth(this)
            query?.trim()?.takeIf { it.isNotBlank() }?.let { url.parameters.append("q", it) }
            providerId?.takeIf { it.isNotBlank() }?.let { url.parameters.append("provider", it) }
            roomId?.takeIf { it.isNotBlank() }?.let { url.parameters.append("room", it) }
        }.body()

    /** Public: a single service's full detail, or null when it can't be found. */
    suspend fun service(id: String): ServiceView? {
        val response = client.get("$baseUrl/services/$id") { HttpRequestAuth(this) }
        return if (response.status.isSuccess()) response.body() else null
    }

    /** Public: the feedback left on a service (aggregated by emoji in the UI). */
    suspend fun serviceFeedback(serviceId: String): List<FeedbackView> =
        client.get("$baseUrl/services/$serviceId/feedback") { HttpRequestAuth(this) }.body()

    /** Create a service from a room (Supporters only, server-validated). Requires a member token. */
    suspend fun createService(
        roomId: String,
        emoji: String,
        name: String,
        description: String,
        price: Int = 0,
    ): ServiceView =
        client.post("$baseUrl/rooms/$roomId/services") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(ServiceCreate(roomId, emoji, name, description, price))
        }.decodeMember()

    /** The caller's own services across all rooms, including unlisted ones. Requires a member token. */
    suspend fun myServices(): List<ServiceView> =
        client.get("$baseUrl/services/mine") { HttpRequestAuth(this) }.decodeMember()

    /** Edit a service (provider or Founder). Requires a member token. */
    suspend fun updateService(
        serviceId: String,
        emoji: String,
        name: String,
        description: String,
        price: Int,
    ): ServiceView =
        client.put("$baseUrl/services/$serviceId") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(ServiceUpdate(emoji, name, description, price))
        }.decodeMember()

    /** Unlist a service (make it invisible in the public search). Requires a member token. */
    suspend fun unlistService(serviceId: String): ServiceView =
        client.post("$baseUrl/services/$serviceId/unlist") { HttpRequestAuth(this) }.decodeMember()

    /** Relist a previously unlisted service. Requires a member token. */
    suspend fun relistService(serviceId: String): ServiceView =
        client.post("$baseUrl/services/$serviceId/relist") { HttpRequestAuth(this) }.decodeMember()

    /** Delete a service (provider or Founder). Requires a member token. */
    suspend fun deleteService(serviceId: String): Unit =
        client.delete("$baseUrl/services/$serviceId") { HttpRequestAuth(this) }.let { }

    /** Leave public feedback on a service. Requires a member token. */
    suspend fun addFeedback(serviceId: String, emoji: String, message: String): FeedbackView =
        client.post("$baseUrl/services/$serviceId/feedback") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(FeedbackCreate(emoji, message))
        }.decodeMember()

    /** Delete feedback (provider, author, or Founder). Requires a member token. */
    suspend fun deleteFeedback(feedbackId: String): Unit =
        client.delete("$baseUrl/feedback/$feedbackId") { HttpRequestAuth(this) }.let { }

    /**
     * Contact a service provider with an opening [message]: opens (or reuses) a direct chat scoped
     * to the service's room, posts the message, and returns the chat coordinates so the client can
     * navigate straight into the conversation (DESIGN §7). Requires a member token.
     */
    suspend fun contactProvider(serviceId: String, message: String): ContactProviderResponse =
        client.post("$baseUrl/services/$serviceId/contact") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(ContactProviderRequest(message))
        }.decodeMember()

    /** Public: submit a "Request an Invite" from the sign-in screen (no account is created). */
    suspend fun requestInvite(name: String, zaloNumber: String, description: String) {
        val response = client.post("$baseUrl/invite-requests") {
            contentType(ContentType.Application.Json)
            setBody(InviteRequestCreate(name, zaloNumber, description))
        }
        if (!response.status.isSuccess()) {
            throw InvalidTokenException(
                response.bodyAsTextSafe().takeIf { it.isNotBlank() }
                    ?: "Could not submit your request. Please try again.",
            )
        }
    }

    /** Founder-only: the pending invite requests (resolved/dismissed are never listed). */
    suspend fun inviteRequests(): List<InviteRequestView> =
        client.get("$baseUrl/founder/invite-requests") { HttpRequestAuth(this) }.decodeMember()

    /** Founder-only: mark an invite request resolved. */
    suspend fun resolveInviteRequest(id: String): Unit =
        client.post("$baseUrl/founder/invite-requests/$id/resolve") { HttpRequestAuth(this) }.let { }

    /** Founder-only: dismiss an invite request. */
    suspend fun dismissInviteRequest(id: String): Unit =
        client.post("$baseUrl/founder/invite-requests/$id/dismiss") { HttpRequestAuth(this) }.let { }

    /** Founder-only: mint a single-use invite code for a new member of the given [role]. */
    suspend fun createInvite(role: Role): InviteCodeResponse =
        client.post("$baseUrl/founder/invites") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(InviteCreateRequest(role))
        }.decodeMember()

    /** Change the caller's login username to [username], rejecting one already taken. */
    suspend fun changeUsername(username: String): ProfileResponse {
        val response = client.put("$baseUrl/profile/username") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(ChangeUsernameRequest(username))
        }
        if (!response.status.isSuccess()) {
            throw InvalidTokenException(
                response.bodyAsTextSafe().takeIf { it.isNotBlank() }
                    ?: "Could not change your username. Please try again.",
            )
        }
        return response.body()
    }

    /** The caller's room-scoped identity, generated on first join (DESIGN §5). Requires a token. */
    suspend fun identity(roomId: String): RoomIdentity =
        client.get("$baseUrl/rooms/$roomId/identity") { HttpRequestAuth(this) }.decodeMember()

    /** "Refresh my name & avatar in this room" (DESIGN §5). Requires a member token. */
    suspend fun refreshIdentity(roomId: String): RoomIdentity =
        client.post("$baseUrl/rooms/$roomId/identity/refresh") { HttpRequestAuth(this) }.decodeMember()

    /**
     * Save the caller's room-scoped profile edits (DESIGN §4, §5): a custom name plus per-room
     * overrides of interests/intents/languages/availability/providing. A Supporter/Founder perk;
     * the server rejects other roles. Requires a member token.
     */
    suspend fun updateRoomIdentity(roomId: String, update: RoomIdentityUpdate): RoomIdentity =
        client.put("$baseUrl/rooms/$roomId/identity") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(update)
        }.decodeMember()

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
     * Whether the one-time Founder bootstrap is still available (no member account exists yet). The
     * onboarding screen uses this to show the "Claim the Founder account" affordance only when it
     * would actually succeed. Any failure is treated as "not available" so the button stays hidden.
     */
    suspend fun bootstrapAvailable(): Boolean =
        runCatching {
            client.get("$baseUrl/auth/bootstrap").body<BootstrapStatusResponse>().available
        }.getOrDefault(false)

    /** Founder-only: every minted invite code, newest first, flagged as redeemed or not. */
    suspend fun invites(): List<InviteCodeView> =
        client.get("$baseUrl/founder/invites") { HttpRequestAuth(this) }.decodeMember()

    /** Founder-only: delete an unused invite code. */
    suspend fun deleteInvite(code: String): Unit =
        client.delete("$baseUrl/founder/invites/$code") { HttpRequestAuth(this) }.let { }

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

    /** Founder-only: the member directory with each member's tier and subscription expiry. */
    suspend fun members(): List<MemberView> =
        client.get("$baseUrl/founder/members") { HttpRequestAuth(this) }.decodeMember()

    /**
     * Founder-only: the most recent room searches, newest first (DESIGN §6), so the founder can
     * understand what kinds of rooms people are looking for. [limit] caps the result (1–500).
     */
    suspend fun roomSearches(limit: Int = 100): List<RoomSearchView> =
        client.get("$baseUrl/founder/room-searches") {
            HttpRequestAuth(this)
            url.parameters.append("limit", limit.toString())
        }.decodeMember()

    /**
     * Founder-only: the most recent service searches, newest first, so the founder can see what
     * services people are looking for. [limit] caps the result (1–500).
     */
    suspend fun serviceSearches(limit: Int = 100): List<RoomSearchView> =
        client.get("$baseUrl/founder/service-searches") {
            HttpRequestAuth(this)
            url.parameters.append("limit", limit.toString())
        }.decodeMember()

    /**
     * Founder-only: change a member's tier (comp to Supporter, downgrade to Sharer, or demote to
     * Seeker). Returns the refreshed member and a fresh token they can re-authenticate with.
     */
    suspend fun setMemberRole(userId: String, role: Role): MemberTierChangeResponse =
        client.patch("$baseUrl/founder/members/$userId/role") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(SetTierRequest(role))
        }.decodeMember()

    /**
     * Founder-only: permanently remove a member's account (a hard removal, distinct from demoting
     * to Seeker). The Founder's own account cannot be removed; the server rejects that with 4xx.
     */
    suspend fun removeMember(userId: String) {
        val response = client.delete("$baseUrl/founder/members/$userId") { HttpRequestAuth(this) }
        if (!response.status.isSuccess()) {
            throw InvalidTokenException(
                response.bodyAsTextSafe().takeIf { it.isNotBlank() }
                    ?: "Could not remove that member.",
            )
        }
    }

    /**
     * Founder-only: reset a member's sign-in password on their behalf (e.g. when they're locked
     * out). Returns the refreshed member view; the password hash is never exposed.
     */
    suspend fun resetMemberPassword(userId: String, newPassword: String): MemberView =
        client.post("$baseUrl/founder/members/$userId/password") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(newPassword))
        }.decodeMember()

    /**
     * Founder-only: change a member's display name on their behalf — it's how the Founder ties an
     * account to the verified person IRL. Returns the refreshed member view.
     */
    suspend fun setMemberName(userId: String, displayName: String): MemberView =
        client.patch("$baseUrl/founder/members/$userId/name") {
            HttpRequestAuth(this)
            contentType(ContentType.Application.Json)
            setBody(SetNameRequest(displayName))
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

    /**
     * Open a live one-on-one private chat with [targetUserId] by tapping their name in [roomId]
     * (DESIGN §7). The caller drives the session via [PrivateChatChannel.start] (a long-running
     * suspend) and must close it when done. The returned channel also carries the closing-emoji
     * ritual (DESIGN §8) once the chat ends.
     */
    suspend fun openPrivateChat(roomId: String, targetUserId: String): PrivateChatChannel {
        val wsUrl = buildString {
            append(baseUrl.replaceFirst("http", "ws"))
            append("/ws")
            token?.let { append("?token=$it") }
        }
        val session = client.webSocketSession(wsUrl)
        // The hub routes private frames by user id, so we still need to join the room first so the
        // server has a live session for this connection.
        session.send(Frame.Text(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", JOIN_TYPE)
            put("roomId", roomId)
        })))
        return PrivateChatChannel(session, roomId, targetUserId, json)
    }

    /**
     * Open an always-on notifications channel for the signed-in member (DESIGN §7). Unlike the
     * room/private sockets it never joins a room — it exists only so the server has a live session
     * to route `PrivateChatStarted` to. This lets a member learn that someone opened a chat with
     * them no matter which screen they are on (room list, profile, …), instead of only while they
     * happen to be sitting inside a room. Anonymous viewers carry no user id, so they get nothing.
     */
    suspend fun openNotifications(): NotificationsChannel {
        val wsUrl = buildString {
            append(baseUrl.replaceFirst("http", "ws"))
            append("/ws")
            token?.let { append("?token=$it") }
        }
        val session = client.webSocketSession(wsUrl)
        return NotificationsChannel(session, json)
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
        const val REPLIED_TYPE = "social.saigon.realtime.Outgoing.Replied"
        const val RECALLED_TYPE = "social.saigon.realtime.Outgoing.Recalled"
        const val JOINED_TYPE = "social.saigon.realtime.Outgoing.Joined"
        const val LEFT_TYPE = "social.saigon.realtime.Outgoing.Left"

        // Private-chat + final-emoji protocol (DESIGN §7, §8).
        const val START_PRIVATE_TYPE = "social.saigon.realtime.Incoming.StartPrivateChat"
        const val SAY_PRIVATE_TYPE = "social.saigon.realtime.Incoming.SayPrivate"
        const val END_PRIVATE_TYPE = "social.saigon.realtime.Incoming.EndPrivateChat"
        const val SELECT_FINAL_EMOJI_TYPE = "social.saigon.realtime.Incoming.SelectFinalEmoji"
        const val PRIVATE_STARTED_TYPE = "social.saigon.realtime.Outgoing.PrivateChatStarted"
        const val PRIVATE_SAID_TYPE = "social.saigon.realtime.Outgoing.PrivateMessageSaid"
        const val PRIVATE_ENDED_TYPE = "social.saigon.realtime.Outgoing.PrivateChatEnded"
        const val FINAL_EMOJI_PROMPT_TYPE = "social.saigon.realtime.Outgoing.FinalEmojiPrompt"
        const val FINAL_EMOJI_REVEALED_TYPE = "social.saigon.realtime.Outgoing.FinalEmojiRevealed"
        const val ERROR_TYPE = "social.saigon.realtime.Outgoing.Error"
        const val THREAD_REPLY_RECEIVED_TYPE = "social.saigon.realtime.Outgoing.MessageReplyReceived"
    }
}

/**
 * A user-facing authentication failure (bad/expired access token, or a forbidden action). Carries a
 * friendly, already-localized message suitable for display, instead of the raw server status text.
 */
class InvalidTokenException(message: String) : Exception(message)

/**
 * The saved access token was rejected by `/profile/me` (401/400), so the session can no longer be
 * trusted. Callers should clear any persisted token and route the user back to the sign-in screen.
 */
class SessionExpiredException(message: String) : Exception(message)

/**
 * Room creation was rejected by the server (e.g. the chosen room URL is already taken or malformed).
 * Carries the server's user-facing message so the create-room dialog can show it.
 */
class RoomCreateException(message: String) : Exception(message)

/** A live thread-reply broadcast over the WebSocket (`Outgoing.Replied`), carrying the parent's
 * fresh reply-count so the feed can update its "↳ N replies" indicator, plus the full reply payload
 * so an open thread sheet can append it live to every client in the room (DESIGN §9). */
data class ReplyEvent(
    val parentMessageId: String,
    val replyId: String,
    val replyCount: Int,
    val authorId: String = "",
    val authorName: String? = null,
    val text: String = "",
    val createdAt: Long = 0L,
    val replyToId: String? = null,
)

/** A live recall broadcast over the WebSocket (`Outgoing.Recalled`): the named message — or, when
 * [replyId] is set, the named reply inside [messageId]'s thread — has been recalled, so its content
 * should be replaced by the "message recalled" notice on every client in the room right away
 * (DESIGN §6, §9). A message recall leaves its thread unaffected; a reply recall leaves the rest of
 * the thread unaffected. */
data class RecallEvent(
    val messageId: String,
    /** The recalled reply's id when this is a thread-reply recall; null for a main-feed message. */
    val replyId: String? = null,
)

/** A live, room-scoped chat message broadcast over the WebSocket (`Outgoing.Said`). */
data class ChatMessage(
    val messageId: String,
    val authorId: String,
    val authorName: String?,
    val text: String,
    val createdAt: Long,
)

/**
 * Run a WebSocket consume loop, treating a dropped connection (lost internet, server restart, the
 * page navigating away) as a normal end-of-stream instead of letting the resulting
 * `WebSocketException` reach the coroutine exception handler and crash the app. Coroutine
 * cancellation is still honored so callers can tear the channel down from `onDispose`.
 */
private suspend fun consumeUntilClosed(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Socket interrupted; the UI's DisposableEffect closes/reopens the channel as needed.
    }
}

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
    /**
     * Consume incoming frames, surfacing each broadcast public message to [onMessage] and each
     * thread reply to [onReply] (so the feed's "↳ N replies" indicator updates live, DESIGN §9).
     * [onPrivateChatStarted] fires when another member opens a one-on-one chat with the caller while
     * they are in this room, so the UI can notify them and list the new direct chat (DESIGN §7).
     */
    suspend fun start(
        onMessage: (ChatMessage) -> Unit,
        onReply: (ReplyEvent) -> Unit = {},
        /**
         * Fires when a message in this room is recalled (DESIGN §6, §9), so every client can flip the
         * message to its "message recalled" notice live, the same way [onReply] updates the feed.
         */
        onRecalled: (RecallEvent) -> Unit = {},
        onPrivateChatStarted: (peerId: String, peerName: String?, roomId: String?) -> Unit = { _, _, _ -> },
        /**
         * Fires whenever someone joins or leaves the room (DESIGN §6), so the UI can refresh its
         * live "In the room" occupant list. Carries the server's de-duplicated head-count.
         */
        onPresenceChanged: (occupants: Int) -> Unit = {},
    ) {
        consumeUntilClosed {
            session.incoming.consumeAsFlow().filterIsInstance<Frame.Text>().collect { frame ->
            val obj = runCatching { json.parseToJsonElement(frame.readText()) as? JsonObject }.getOrNull() ?: return@collect
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                SaigonApi.SAID_TYPE -> onMessage(
                    ChatMessage(
                        messageId = obj["messageId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        authorId = obj["authorId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        authorName = obj["authorName"]?.jsonPrimitive?.contentOrNull,
                        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    ),
                )
                SaigonApi.REPLIED_TYPE -> onReply(
                    ReplyEvent(
                        parentMessageId = obj["parentMessageId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        replyId = obj["replyId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        replyCount = obj["replyCount"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                        authorId = obj["authorId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        authorName = obj["authorName"]?.jsonPrimitive?.contentOrNull,
                        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                        replyToId = obj["replyToId"]?.jsonPrimitive?.contentOrNull,
                    ),
                )
                SaigonApi.RECALLED_TYPE -> onRecalled(
                    RecallEvent(
                        messageId = obj["messageId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        replyId = obj["replyId"]?.jsonPrimitive?.contentOrNull,
                    ),
                )
                SaigonApi.PRIVATE_STARTED_TYPE -> onPrivateChatStarted(
                    obj["peerId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    obj["peerName"]?.jsonPrimitive?.contentOrNull,
                    obj["roomId"]?.jsonPrimitive?.contentOrNull,
                )
                SaigonApi.JOINED_TYPE, SaigonApi.LEFT_TYPE -> onPresenceChanged(
                    obj["occupants"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                )
            }
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

/**
 * Notice that another member opened a one-on-one chat with the signed-in user (`Outgoing
 * .PrivateChatStarted`). [roomId] is where the chat was started, so the recipient can open it with
 * the right room context (DESIGN §7).
 */
data class ChatStartedNotice(
    val peerId: String,
    val peerName: String?,
    val roomId: String?,
)

/**
 * Notice that someone replied in a thread started by or participated in by the user.
 */
data class ThreadReplyNotice(
    val roomId: String,
    val roomName: String,
    val roomEmoji: String,
    val parentMessageId: String,
    val parentMessageText: String,
    val parentMessageAuthorName: String,
    val replyText: String,
    val replyAuthorName: String,
    val replyCreatedAt: Long,
    val replyCount: Int,
)

/**
 * An always-on, room-less WebSocket whose only job is to surface "someone started a chat with you"
 * notices to the signed-in member regardless of which screen they are on (DESIGN §7). The server
 * routes `PrivateChatStarted` by user id, so simply having this connection open is enough — no room
 * join required.
 */
class NotificationsChannel internal constructor(
    private val session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    private val json: Json,
) {
    /** Consume server frames, surfacing each chat-started notice to [onChatStarted] and thread reply
     * to [onThreadReply]. Suspends until the socket closes. */
    suspend fun start(
        onChatStarted: (ChatStartedNotice) -> Unit,
        onThreadReply: (ThreadReplyNotice) -> Unit = {},
    ) {
        consumeUntilClosed {
            session.incoming.consumeAsFlow().filterIsInstance<Frame.Text>().collect { frame ->
                val obj = runCatching { json.parseToJsonElement(frame.readText()) as? JsonObject }.getOrNull() ?: return@collect
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    SaigonApi.PRIVATE_STARTED_TYPE -> {
                        onChatStarted(
                            ChatStartedNotice(
                                peerId = obj["peerId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                peerName = obj["peerName"]?.jsonPrimitive?.contentOrNull,
                                roomId = obj["roomId"]?.jsonPrimitive?.contentOrNull,
                            )
                        )
                    }
                    SaigonApi.THREAD_REPLY_RECEIVED_TYPE -> {
                        onThreadReply(
                            ThreadReplyNotice(
                                roomId = obj["roomId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                roomName = obj["roomName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                roomEmoji = obj["roomEmoji"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                parentMessageId = obj["parentMessageId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                parentMessageText = obj["parentMessageText"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                parentMessageAuthorName = obj["parentMessageAuthorName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                replyText = obj["replyText"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                replyAuthorName = obj["replyAuthorName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                replyCreatedAt = obj["replyCreatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                                replyCount = obj["replyCount"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun close() {
        session.close()
    }
}

/** A plain-text private message delivered over the WebSocket (`Outgoing.PrivateMessageSaid`). */
data class PrivateChatMessage(
    val chatId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
)

/**
 * A live one-on-one private chat WebSocket (DESIGN §7) that also carries the closing-emoji ritual
 * (DESIGN §8). Opening it sends `StartPrivateChat`; the server answers with `PrivateChatStarted`
 * (delivering the real chat id + peer name), then relays each side's `PrivateMessageSaid`. Ending
 * the chat triggers the shared final-emoji prompt and, once both sides pick, the reveal.
 *
 * All peer data is real — nothing is fabricated client-side. Nothing is revealed until the peer
 * actually selects an emoji.
 */
class PrivateChatChannel internal constructor(
    private val session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    private val roomId: String,
    private val targetUserId: String,
    private val json: Json,
) {
    /** The server-assigned chat id, known once [Callbacks.onStarted] fires. */
    var chatId: String? = null
        private set

    /** Live event sinks for the private-chat + final-emoji flow. */
    class Callbacks(
        val onStarted: (chatId: String, peerName: String?) -> Unit = { _, _ -> },
        val onMessage: (PrivateChatMessage) -> Unit = {},
        val onEnded: (endedBy: String) -> Unit = {},
        val onFinalPrompt: (options: List<String>) -> Unit = {},
        val onRevealed: (youSent: String, theySent: String) -> Unit = { _, _ -> },
        val onError: (reason: String) -> Unit = {},
    )

    /**
     * Send the initial `StartPrivateChat` and then consume server frames, dispatching each to the
     * matching [callbacks] sink. Suspends until the socket closes.
     */
    suspend fun start(callbacks: Callbacks) {
        session.send(Frame.Text(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", SaigonApi.START_PRIVATE_TYPE)
            put("roomId", roomId)
            put("targetUserId", targetUserId)
        })))
        consumeUntilClosed {
            session.incoming.consumeAsFlow().filterIsInstance<Frame.Text>().collect { frame ->
            val obj = runCatching { json.parseToJsonElement(frame.readText()) as? JsonObject }.getOrNull() ?: return@collect
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                SaigonApi.PRIVATE_STARTED_TYPE -> {
                    chatId = obj["chatId"]?.jsonPrimitive?.contentOrNull
                    callbacks.onStarted(
                        chatId.orEmpty(),
                        obj["peerName"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                SaigonApi.PRIVATE_SAID_TYPE -> callbacks.onMessage(
                    PrivateChatMessage(
                        chatId = obj["chatId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        authorId = obj["authorId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    ),
                )
                SaigonApi.PRIVATE_ENDED_TYPE -> callbacks.onEnded(
                    obj["endedBy"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                SaigonApi.FINAL_EMOJI_PROMPT_TYPE -> callbacks.onFinalPrompt(
                    (obj["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                )
                SaigonApi.FINAL_EMOJI_REVEALED_TYPE -> callbacks.onRevealed(
                    obj["youSent"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    obj["theySent"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                SaigonApi.ERROR_TYPE -> callbacks.onError(
                    obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }
        }
    }

    /** Send a plain-text private message (DESIGN §7). No-op until the chat has started. */
    suspend fun say(text: String) {
        val id = chatId ?: return
        session.send(Frame.Text(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", SaigonApi.SAY_PRIVATE_TYPE)
            put("chatId", id)
            put("text", text)
        })))
    }

    /** End the private chat instantly (DESIGN §7); the server then opens the final-emoji prompt. */
    suspend fun end() {
        val id = chatId ?: return
        session.send(Frame.Text(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", SaigonApi.END_PRIVATE_TYPE)
            put("chatId", id)
        })))
    }

    /** Privately choose the closing emoji (DESIGN §8); stays hidden until the peer also picks. */
    suspend fun selectFinalEmoji(emoji: String) {
        val id = chatId ?: return
        session.send(Frame.Text(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", SaigonApi.SELECT_FINAL_EMOJI_TYPE)
            put("chatId", id)
            put("emoji", emoji)
        })))
    }

    suspend fun close() {
        session.close()
    }
}
