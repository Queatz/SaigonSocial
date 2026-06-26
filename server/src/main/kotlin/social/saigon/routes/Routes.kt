package social.saigon.routes

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import social.saigon.ai.IdentityGenerator
import social.saigon.auth.AUTH_JWT
import social.saigon.auth.CallerPrincipal
import social.saigon.auth.Claims
import social.saigon.auth.TokenService
import social.saigon.auth.toCaller
import social.saigon.domain.Capability
import social.saigon.domain.CapabilityMatrix
import social.saigon.domain.EconomicStatus
import social.saigon.domain.EmojiOptions
import social.saigon.domain.ForbiddenException
import social.saigon.domain.Intent
import social.saigon.domain.LaunchConfig
import social.saigon.domain.Message
import social.saigon.domain.ModerationStatus
import social.saigon.domain.PlainText
import social.saigon.domain.Pricing
import social.saigon.domain.ProfileUpdate
import social.saigon.domain.ReportTarget
import social.saigon.domain.Role
import social.saigon.domain.RoomCreate
import social.saigon.domain.Tier
import social.saigon.domain.User
import social.saigon.domain.WarningReason
import social.saigon.economics.EconomicsService
import social.saigon.finalemoji.FinalEmojiService
import social.saigon.identity.IdentityService
import social.saigon.metrics.MetricsService
import social.saigon.domain.MetricType
import social.saigon.moderation.ModerationService
import social.saigon.privatechat.PrivateChatService
import social.saigon.profile.ProfileService
import social.saigon.realtime.ChatHub
import social.saigon.realtime.ClientSession
import social.saigon.realtime.Incoming
import social.saigon.realtime.Outgoing
import social.saigon.repository.FOUNDER_ID
import social.saigon.repository.Repositories
import social.saigon.room.RoomService
import social.saigon.thread.ThreadService

@Serializable
data class SeekerTokenRequest(val deviceId: String)

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class OnboardRequest(
    val displayName: String,
    val role: Role = Role.SHARER,
)

@Serializable
data class OnboardResponse(val userId: String, val token: String)

/**
 * Phase 0 HTTP + WebSocket surface. Higher phases hang their routes off this same wiring.
 */
fun Application.registerRoutes(
    repositories: Repositories,
    chatHub: ChatHub,
    tokenService: TokenService,
    identityGenerator: IdentityGenerator,
) {
    val json = Json { ignoreUnknownKeys = true }
    val profiles = ProfileService(repositories.users)
    val identities = IdentityService(repositories.identities, identityGenerator)
    val roomService = RoomService(repositories.rooms)
    val threadService = ThreadService(repositories.threads, repositories.messages)
    val privateChatService = PrivateChatService(repositories.privateChats, repositories.users)
    val finalEmojiService = FinalEmojiService(repositories.finalEmojis, profiles)
    val metricsService = MetricsService(repositories.metrics)
    val moderationService = ModerationService(repositories.moderation, repositories.users, metrics = metricsService)
    val economicsService = EconomicsService(repositories.users, repositories.payments, metrics = metricsService)

    routing {
        // Seeker access: no persistence, token bound to a device id (DESIGN §3, TASKS Phase 0).
        post("/auth/seeker") {
            val req = call.receive<SeekerTokenRequest>()
            require(req.deviceId.isNotBlank()) { "deviceId required" }
            call.respond(TokenResponse(tokenService.issueSeekerToken(req.deviceId)))
        }

        // Founder bootstrap (DESIGN §2): Saigon.Social is founder-managed with no open sign-up, so
        // there is no pre-baked founder token. The very first person to arrive — when no member
        // account exists yet — claims the Founder account by signing in with a blank token. Once a
        // founder (or any member) exists this is closed forever; everyone else is onboarded by the
        // founder.
        post("/auth/bootstrap") {
            val hasMembers = repositories.users.all().any { it.role != Role.SEEKER }
            if (hasMembers) {
                throw ForbiddenException(
                    "Saigon.Social already has a founder. Ask them for an access token to join.",
                )
            }
            val founder = repositories.users.create(
                User(id = FOUNDER_ID, role = Role.FOUNDER, displayName = "Founder"),
            )
            call.respond(
                OnboardResponse(founder.id, tokenService.issueMemberToken(founder.id, founder.role)),
            )
        }

        // Public pricing table (DESIGN §3, §11). Open so a Seeker can see the cost of joining before
        // committing. The platform takes no cut of user-to-user transactions.
        get("/economics/pricing") {
            call.respond(
                PricingResponse(
                    sharerOneTimeVnd = Pricing.SHARER_ONE_TIME_VND,
                    supporterPeriodVnd = Pricing.SUPPORTER_PERIOD_VND,
                    supporterPeriodDays = Pricing.SUPPORTER_PERIOD_DAYS,
                    platformCutBps = Pricing.PLATFORM_CUT_BPS,
                ),
            )
        }

        // Public launch-hardening guarantees (TASKS Phase 11): single-city scope, web-only (no
        // push), and the privacy promises (no history, no receipts, no "last seen").
        get("/launch") {
            call.respond(LaunchConfig.guarantees())
        }

        // Public room list with live occupant counts (DESIGN §6). Open to all roles for viewing.
        get("/rooms") {
            val rooms = roomService.list().map { room ->
                RoomSummary(
                    id = room.id,
                    name = room.name,
                    emoji = room.emoji,
                    atmosphere = room.atmosphere,
                    occupants = chatHub.occupants(room.id),
                )
            }
            call.respond(rooms)
        }

        // Open viewing for all roles (incl. Seekers): read a room's public chat (DESIGN §6).
        // Each message carries its live reply-count for the "↳ N replies" indicator (DESIGN §9).
        get("/rooms/{roomId}/messages") {
            val roomId = call.requireRoomId()
            val feed = repositories.messages.byRoom(roomId).map { message ->
                FeedMessage(
                    id = message.id,
                    roomId = message.roomId,
                    authorId = message.authorId,
                    text = message.text,
                    createdAt = message.createdAt,
                    replyCount = threadService.replyCount(message.id),
                )
            }
            call.respond(feed)
        }

        // Open viewing for all roles (incl. Seekers): read a message's ephemeral thread (DESIGN §9, §11).
        get("/rooms/{roomId}/messages/{messageId}/thread") {
            call.requireRoomId()
            val messageId = call.parameters["messageId"].orEmpty()
            require(messageId.isNotBlank()) { "messageId required" }
            call.respond(threadService.thread(messageId))
        }

        // Who's in the room, with their intents + languages and room-scoped identity (DESIGN §6).
        get("/rooms/{roomId}/occupants") {
            val roomId = call.requireRoomId()
            val occupants = chatHub.sessionsIn(roomId)
                .mapNotNull { it.userId }
                .distinct()
                .mapNotNull { userId ->
                    val user = repositories.users.get(userId) ?: return@mapNotNull null
                    val identity = identities.getOrCreate(userId, roomId)
                    OccupantView(
                        name = identity.name,
                        avatarUrl = identity.avatarUrl,
                        role = user.role,
                        intent = user.intent,
                        languages = user.languages,
                        // "Available for chat" indicator for active Supporters (DESIGN §7).
                        available = privateChatService.isAvailable(userId),
                    )
                }
            call.respond(occupants)
        }

        authenticate(AUTH_JWT) {
            // --- Phase 1: User Types & Profiles (DESIGN §3, §4) ---

            // The caller's own persistent profile. Seekers have no profile and are rejected.
            get("/profile/me") {
                val user = profiles.get(requireMember(call.caller()))
                call.respond(user.toProfileResponse())
            }

            // Update editable profile fields (interests/intent/languages/schedule).
            put("/profile/me") {
                val userId = requireMember(call.caller())
                val update = call.receive<ProfileUpdate>()
                call.respond(profiles.update(userId, update).toProfileResponse())
            }

            // Private emoji tally — visible only to the user themself (DESIGN §4, §8).
            get("/profile/me/emoji-stats") {
                val userId = requireMember(call.caller())
                call.respond(EmojiStatsResponse(profiles.emojiStats(userId)))
            }

            // Capabilities granted to the caller's role (capability matrix; DESIGN §3, §11).
            get("/profile/me/capabilities") {
                val caller = call.caller()
                call.respond(CapabilitiesResponse(caller.role, CapabilityMatrix.capabilities(caller.role).toList()))
            }

            // Free-text interest auto-suggest (DESIGN §4).
            get("/profile/interests/suggest") {
                requireMember(call.caller())
                val query = call.request.queryParameters["q"].orEmpty()
                call.respond(InterestSuggestResponse(profiles.suggestInterests(query)))
            }

            // --- Phase 2: Per-Room Identity (DESIGN §5) ---

            // The caller's anonymous identity in a room. Generated via Qwen AI on first join and
            // kept stable until the user refreshes it (no time-based resets).
            get("/rooms/{roomId}/identity") {
                val userId = requireMember(call.caller())
                val roomId = call.requireRoomId()
                call.respond(identities.getOrCreate(userId, roomId))
            }

            // "Refresh my name & avatar in this room" — instant, user-controlled regeneration.
            post("/rooms/{roomId}/identity/refresh") {
                val userId = requireMember(call.caller())
                val roomId = call.requireRoomId()
                call.respond(identities.refresh(userId, roomId))
            }

            // Founder-only room creation/management (DESIGN §6).
            post("/rooms") {
                val caller = call.caller()
                require(caller.role.isAdmin) { "Founder only" }
                val founderId = caller.userId ?: throw IllegalArgumentException("Founder only")
                val req = call.receive<RoomCreate>()
                call.respond(roomService.create(founderId, req))
            }

            // Founder-only onboarding: no open sign-up, no OTP (DESIGN §2, §3).
            post("/founder/onboard") {
                val caller = call.principal<JWTPrincipal>()!!.toCaller()
                require(caller.role.isAdmin) { "Founder only" }
                val req = call.receive<OnboardRequest>()
                require(req.role != Role.SEEKER) { "Cannot onboard a Seeker" }
                val user = repositories.users.create(
                    User(
                        id = UUID.randomUUID().toString(),
                        role = req.role,
                        displayName = req.displayName,
                    ),
                )
                // The founder collects the fee in person, so onboarding settles the tier's payment
                // status: every onboarded member has paid the one-time Sharer fee, and a Supporter
                // additionally starts a subscription period with a real expiry (DESIGN §3, §11).
                economicsService.paySharerFee(user.id)
                if (req.role == Role.SUPPORTER) economicsService.paySupporterSubscription(user.id)
                call.respond(OnboardResponse(user.id, tokenService.issueMemberToken(user.id, user.role)))
            }

            // --- Phase 8: Economics & Onboarding (DESIGN §3, §11) ---

            // The caller's own economic standing (tier, payment status, subscription expiry).
            get("/economics/me") {
                val userId = requireMember(call.caller())
                call.respond(economicsService.status(userId))
            }

            // Settle the one-time Sharer join fee (100,000 VND).
            post("/economics/sharer-fee") {
                val userId = requireMember(call.caller())
                val req = call.receive<PayRequest>()
                val outcome = economicsService.paySharerFee(userId, req.amountVnd ?: Pricing.SHARER_ONE_TIME_VND)
                call.respond(outcome.status)
            }

            // Purchase or renew a Supporter subscription (50,000 VND / 3 months). Promotes the member
            // to Supporter, so a fresh token carrying the new role is returned.
            post("/economics/supporter-subscription") {
                val userId = requireMember(call.caller())
                val req = call.receive<PayRequest>()
                val outcome = economicsService.paySupporterSubscription(
                    userId,
                    req.amountVnd ?: Pricing.SUPPORTER_PERIOD_VND,
                )
                call.respond(
                    SubscriptionResponse(
                        status = outcome.status,
                        token = tokenService.issueMemberToken(outcome.user.id, outcome.user.role),
                    ),
                )
            }

            // --- Phase 7: Moderation & Safety (DESIGN §10) ---

            // Report button on every profile and chat: any member may file a report; two distinct
            // reporters permanently ban the subject (no appeals).
            post("/moderation/report") {
                val reporterId = requireMember(call.caller())
                val req = call.receive<ReportRequest>()
                require(req.subjectId.isNotBlank()) { "subjectId required" }
                val outcome = moderationService.report(
                    reporterId = reporterId,
                    subjectId = req.subjectId,
                    target = req.target,
                    contextId = req.contextId,
                    reason = req.reason,
                )
                call.respond(
                    ReportResponse(
                        reportId = outcome.report.id,
                        reportCount = outcome.reportCount,
                        subjectBanned = outcome.subjectBanned,
                    ),
                )
            }

            // Founder-only unsolicited-sales (and other) warnings: warning → second warning → ban.
            post("/moderation/warn") {
                val founderId = requireFounder(call.caller())
                val req = call.receive<WarnRequest>()
                require(req.subjectId.isNotBlank()) { "subjectId required" }
                val outcome = moderationService.warn(
                    founderId = founderId,
                    subjectId = req.subjectId,
                    reason = req.reason,
                    note = req.note,
                )
                call.respond(
                    WarnResponse(
                        warningId = outcome.warning.id,
                        warningCount = outcome.warningCount,
                        subjectBanned = outcome.subjectBanned,
                    ),
                )
            }

            // Founder-only manual verification (no bots / no automated sign-up).
            post("/moderation/verify") {
                requireFounder(call.caller())
                val req = call.receive<VerifyRequest>()
                require(req.userId.isNotBlank()) { "userId required" }
                moderationService.verify(req.userId)
                call.respond(moderationService.status(req.userId))
            }

            // Founder-only view of a user's aggregated moderation state.
            get("/moderation/status/{userId}") {
                requireFounder(call.caller())
                val userId = call.parameters["userId"].orEmpty()
                require(userId.isNotBlank()) { "userId required" }
                call.respond(moderationService.status(userId))
            }

            // --- Phase 10: Success Metrics & Observability (DESIGN §14) ---

            // Founder-only aggregated success metrics. Internal use only; never exposed publicly,
            // and the underlying events store no message content (DESIGN §14).
            get("/metrics") {
                requireFounder(call.caller())
                call.respond(metricsService.snapshot())
            }
        }

        // Live chat transport (DESIGN §12). Connection lifecycle + heartbeat handled here.
        // Viewing is open to everyone; the optional `?token=` identifies members so the server can
        // enforce who may send (Seekers are read-only) and attach a room-scoped author name.
        webSocket("/ws") {
            val connectionId = UUID.randomUUID().toString()
            val caller = call.request.queryParameters["token"]?.let { tokenService.resolve(it) }
            val session = object : ClientSession {
                override val connectionId = connectionId
                override val userId = caller?.userId
                override val role = caller?.role ?: Role.SEEKER
                override suspend fun send(frame: Outgoing) {
                    outgoing.send(Frame.Text(json.encodeToString(Outgoing.serializer(), frame)))
                }
            }
            chatHub.register(session)
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val incomingFrame = runCatching {
                        json.decodeFromString(Incoming.serializer(), frame.readText())
                    }.getOrNull() ?: run {
                        session.send(Outgoing.Error("Malformed frame"))
                        continue
                    }
                    handleIncoming(incomingFrame, session, chatHub, repositories, identities, threadService, privateChatService, finalEmojiService, moderationService, metricsService)
                }
            } finally {
                // Room emptied on disconnect → drop its ephemeral threads (DESIGN §9).
                chatHub.unregister(connectionId).forEach { threadService.clearForRoom(it) }
            }
        }
    }
}

@Serializable
data class RoomSummary(
    val id: String,
    val name: String,
    val emoji: String,
    val atmosphere: String,
    val occupants: Int,
)

/** A person currently in a room, with intents + languages shown next to them (DESIGN §6). */
@Serializable
data class OccupantView(
    val name: String,
    val avatarUrl: String,
    val role: Role,
    val intent: Intent?,
    val languages: List<String>,
    /** "Available for chat" indicator for active Supporters (DESIGN §7). */
    val available: Boolean = false,
)

/** Public-facing view of a user's own persistent profile (DESIGN §4). */
@Serializable
data class ProfileResponse(
    val id: String,
    val role: Role,
    val displayName: String,
    val interests: List<String>,
    val intent: Intent?,
    val languages: List<String>,
    val offlineSchedule: List<String>,
    val supporterUntil: Long?,
    val capabilities: List<Capability>,
)

/**
 * A main-feed message enriched with its live thread reply-count for the "↳ N replies"
 * indicator (DESIGN §9). [replyCount] is 0 when the message has no thread yet.
 */
@Serializable
data class FeedMessage(
    val id: String,
    val roomId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
    val replyCount: Int,
)

/** Public pricing table for the economics screen (DESIGN §3, §11). */
@Serializable
data class PricingResponse(
    val sharerOneTimeVnd: Long,
    val supporterPeriodVnd: Long,
    val supporterPeriodDays: Long,
    /** Always 0 — the platform takes no cut of user-to-user transactions. */
    val platformCutBps: Int,
)

/** Body for a platform-fee payment (DESIGN §11). Amount defaults to the tier's fee when omitted. */
@Serializable
data class PayRequest(val amountVnd: Long? = null)

/** Response to a Supporter subscription: the new standing plus a token carrying the upgraded role. */
@Serializable
data class SubscriptionResponse(
    val status: EconomicStatus,
    val token: String,
)

@Serializable
data class EmojiStatsResponse(val stats: Map<String, Int>)

@Serializable
data class CapabilitiesResponse(val role: Role, val capabilities: List<Capability>)

@Serializable
data class InterestSuggestResponse(val suggestions: List<String>)

/** Body for filing an abuse report on a profile or chat (DESIGN §10). */
@Serializable
data class ReportRequest(
    val subjectId: String,
    val target: ReportTarget = ReportTarget.PROFILE,
    val contextId: String? = null,
    val reason: String? = null,
)

@Serializable
data class ReportResponse(
    val reportId: String,
    val reportCount: Int,
    val subjectBanned: Boolean,
)

/** Body for a founder-issued warning (DESIGN §10). */
@Serializable
data class WarnRequest(
    val subjectId: String,
    val reason: WarningReason = WarningReason.UNSOLICITED_SALES,
    val note: String? = null,
)

@Serializable
data class WarnResponse(
    val warningId: String,
    val warningCount: Int,
    val subjectBanned: Boolean,
)

/** Body for founder-only manual verification (DESIGN §10). */
@Serializable
data class VerifyRequest(val userId: String)

private fun ApplicationCall.caller(): CallerPrincipal =
    principal<JWTPrincipal>()!!.toCaller()

/** Resolve the `{roomId}` path parameter, rejecting blanks. */
private fun ApplicationCall.requireRoomId(): String {
    val roomId = parameters["roomId"].orEmpty()
    require(roomId.isNotBlank()) { "roomId required" }
    return roomId
}

/** Resolve the caller's persistent user id, rejecting Seekers (who have no profile). */
private fun requireMember(caller: CallerPrincipal): String =
    caller.userId ?: throw IllegalArgumentException("Members only")

/** Resolve the caller's user id, rejecting anyone who is not the Founder (DESIGN §10). */
private fun requireFounder(caller: CallerPrincipal): String {
    if (!caller.role.isAdmin) throw ForbiddenException("Founder only")
    return caller.userId ?: throw ForbiddenException("Founder only")
}

private fun User.toProfileResponse(): ProfileResponse = ProfileResponse(
    id = id,
    role = role,
    displayName = displayName,
    interests = interests,
    intent = intent,
    languages = languages,
    offlineSchedule = offlineSchedule,
    supporterUntil = supporterUntil,
    capabilities = CapabilityMatrix.capabilities(role).toList(),
)

private suspend fun handleIncoming(
    frame: Incoming,
    session: ClientSession,
    chatHub: ChatHub,
    repositories: Repositories,
    identities: IdentityService,
    threadService: ThreadService,
    privateChats: PrivateChatService,
    finalEmojis: FinalEmojiService,
    moderation: ModerationService,
    metrics: MetricsService,
) {
    when (frame) {
        is Incoming.Ping -> session.send(Outgoing.Pong)

        is Incoming.Join -> {
            val occupants = chatHub.join(frame.roomId, session.connectionId)
            // A member joining a room is an activity signal (DESIGN §14 — DAU).
            session.userId?.let { metrics.record(MetricType.ACTIVE, userId = it) }
            chatHub.broadcast(frame.roomId, Outgoing.Joined(frame.roomId, occupants))
        }

        is Incoming.Leave -> {
            val occupants = chatHub.leave(frame.roomId, session.connectionId)
            chatHub.broadcast(frame.roomId, Outgoing.Left(frame.roomId, occupants))
            // Ephemeral threads disappear when the room empties (DESIGN §9).
            if (occupants == 0) threadService.clearForRoom(frame.roomId)
        }

        is Incoming.Reply -> {
            // Only Sharers/Supporters/Founder may reply in threads; Seekers are read-only (DESIGN §6, §9).
            if (!session.role.canChatPublicly) {
                session.send(Outgoing.Error("Read-only: only members can reply"))
                return
            }
            // Banned accounts are silenced everywhere (DESIGN §10).
            if (session.userId?.let(moderation::isBanned) == true) {
                session.send(Outgoing.Error("Account banned"))
                return
            }
            val authorId = session.userId ?: session.connectionId
            val authorName = session.userId?.let { identities.getOrCreate(it, frame.roomId).name }
            val reply = try {
                threadService.reply(
                    parentMessageId = frame.parentMessageId,
                    authorId = authorId,
                    text = frame.text,
                    replyToId = frame.replyToId,
                )
            } catch (e: IllegalArgumentException) {
                session.send(Outgoing.Error(e.message ?: "Invalid reply"))
                return
            }
            chatHub.broadcast(
                frame.roomId,
                Outgoing.Replied(
                    roomId = frame.roomId,
                    parentMessageId = reply.parentMessageId,
                    replyId = reply.id,
                    authorId = reply.authorId,
                    text = reply.text,
                    createdAt = reply.createdAt,
                    replyCount = threadService.replyCount(reply.parentMessageId),
                    replyToId = reply.replyToId,
                    authorName = authorName,
                ),
            )
        }

        is Incoming.Say -> {
            // Only Sharers/Supporters/Founder may send; Seekers are read-only (DESIGN §6).
            if (!session.role.canChatPublicly) {
                session.send(Outgoing.Error("Read-only: only members can send messages"))
                return
            }
            // Banned accounts are silenced everywhere (DESIGN §10).
            if (session.userId?.let(moderation::isBanned) == true) {
                session.send(Outgoing.Error("Account banned"))
                return
            }
            val text = frame.text.trim()
            // Plain-text-only: no images/voice/video (DESIGN §2, §6).
            if (!PlainText.isValid(text)) {
                session.send(Outgoing.Error("Plain text only"))
                return
            }
            // Members post under their room-scoped identity; fall back to the connection id.
            val authorId = session.userId ?: session.connectionId
            val authorName = session.userId?.let { identities.getOrCreate(it, frame.roomId).name }
            val message = repositories.messages.add(
                Message(
                    id = UUID.randomUUID().toString(),
                    roomId = frame.roomId,
                    authorId = authorId,
                    text = text,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            // Sending a public message is an activity signal (DESIGN §14 — DAU).
            session.userId?.let { metrics.record(MetricType.ACTIVE, userId = it) }
            chatHub.broadcast(
                frame.roomId,
                Outgoing.Said(message.roomId, message.authorId, message.text, message.createdAt, authorName, message.id),
            )
        }

        is Incoming.StartPrivateChat -> {
            // Private chat is initiated by a member tapping a Supporter's name (DESIGN §7).
            val initiatorId = session.userId
            if (initiatorId == null) {
                session.send(Outgoing.Error("Members only"))
                return
            }
            // Banned accounts cannot start private chats (DESIGN §10).
            if (moderation.isBanned(initiatorId)) {
                session.send(Outgoing.Error("Account banned"))
                return
            }
            val chat = try {
                privateChats.start(initiatorId, frame.targetUserId)
            } catch (e: IllegalArgumentException) {
                session.send(Outgoing.Error(e.message ?: "Cannot start private chat"))
                return
            }
            // Each party sees the other's room-scoped display name (DESIGN §5, §7).
            val initiatorName = identities.getOrCreate(initiatorId, frame.roomId).name
            val targetName = identities.getOrCreate(frame.targetUserId, frame.roomId).name
            chatHub.sendToUser(initiatorId, Outgoing.PrivateChatStarted(chat.id, frame.targetUserId, targetName))
            chatHub.sendToUser(frame.targetUserId, Outgoing.PrivateChatStarted(chat.id, initiatorId, initiatorName))
        }

        is Incoming.SayPrivate -> {
            val authorId = session.userId
            if (authorId == null) {
                session.send(Outgoing.Error("Members only"))
                return
            }
            // Banned accounts are silenced everywhere (DESIGN §10).
            if (moderation.isBanned(authorId)) {
                session.send(Outgoing.Error("Account banned"))
                return
            }
            val chat = privateChats.get(frame.chatId)
            val message = try {
                privateChats.post(frame.chatId, authorId, frame.text)
            } catch (e: IllegalArgumentException) {
                session.send(Outgoing.Error(e.message ?: "Cannot send message"))
                return
            }
            // Deliver to both participants — no typing indicators, no read receipts (DESIGN §7).
            val said = Outgoing.PrivateMessageSaid(message.chatId, message.authorId, message.text, message.createdAt)
            chat?.participants?.forEach { chatHub.sendToUser(it, said) }
        }

        is Incoming.EndPrivateChat -> {
            val byUserId = session.userId
            if (byUserId == null) {
                session.send(Outgoing.Error("Members only"))
                return
            }
            // Either party can end instantly; history is discarded (DESIGN §7).
            val ended = try {
                privateChats.end(frame.chatId, byUserId)
            } catch (e: IllegalArgumentException) {
                session.send(Outgoing.Error(e.message ?: "Cannot end private chat"))
                return
            } ?: return
            val notice = Outgoing.PrivateChatEnded(ended.id, byUserId)
            ended.participants.forEach { chatHub.sendToUser(it, notice) }
            // Record the completed chat's duration for the average-chat-duration metric (DESIGN §14).
            metrics.record(MetricType.CHAT_ENDED, value = System.currentTimeMillis() - ended.createdAt)
            // Trigger the shared closing-emoji selection screen for both parties (DESIGN §8).
            finalEmojis.open(ended.id, ended.participants)
            val prompt = Outgoing.FinalEmojiPrompt(ended.id, EmojiOptions.all)
            // Both participants are shown the prompt — the denominator of the emoji selection rate.
            ended.participants.forEach {
                chatHub.sendToUser(it, prompt)
                metrics.record(MetricType.FINAL_EMOJI_PROMPTED, userId = it)
            }
        }

        is Incoming.SelectFinalEmoji -> {
            val userId = session.userId
            if (userId == null) {
                session.send(Outgoing.Error("Members only"))
                return
            }
            // Each party selects privately; nothing is revealed until both have chosen (DESIGN §8).
            val outcome = try {
                finalEmojis.select(frame.chatId, userId, frame.emoji)
            } catch (e: IllegalArgumentException) {
                session.send(Outgoing.Error(e.message ?: "Cannot select emoji"))
                return
            }
            // A participant picked a closing emoji — the numerator of the selection rate (DESIGN §14).
            metrics.record(MetricType.FINAL_EMOJI_SELECTED, userId = userId)
            when (outcome) {
                // Waiting on the peer — stay silent (no read receipts/nudges; DESIGN §7, §8).
                is FinalEmojiService.Outcome.Waiting -> Unit
                // Both chose — reveal each party's personalized side-by-side view.
                is FinalEmojiService.Outcome.Revealed -> outcome.reveals.forEach { (uid, reveal) ->
                    chatHub.sendToUser(
                        uid,
                        Outgoing.FinalEmojiRevealed(frame.chatId, reveal.youSent, reveal.theySent),
                    )
                }
            }
        }
    }
}
