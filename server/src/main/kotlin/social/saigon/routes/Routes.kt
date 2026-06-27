package social.saigon.routes

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
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
import social.saigon.auth.PasswordService
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
import social.saigon.domain.RoomUpdate
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

/** Sign in with the durable password credential (PASSWORD_AUTH.md §2C). */
@Serializable
data class LoginRequest(val identifier: String, val password: String)

/** Founder request to mint a single-use invite code for a new member. */
@Serializable
data class InviteCreateRequest(val role: Role = Role.SHARER)

/** A minted invite code handed to a prospective member. */
@Serializable
data class InviteCodeResponse(val code: String, val role: Role)

/** Whether the one-time Founder bootstrap is still available (no member account exists yet). */
@Serializable
data class BootstrapStatusResponse(val available: Boolean)

/** A founder-visible invite code, including whether it has already been redeemed. */
@Serializable
data class InviteCodeView(
    val code: String,
    val role: Role,
    val createdAt: Long,
    val redeemed: Boolean,
)

/** Redeem an invite code to create an account by choosing a username + password. */
@Serializable
data class RedeemRequest(val code: String, val username: String, val password: String)

/** Change the caller's login username (handle). */
@Serializable
data class ChangeUsernameRequest(val username: String)

/**
 * Set or change the caller's password (PASSWORD_AUTH.md §2B). [currentPassword] is required only
 * when one is already set.
 */
@Serializable
data class SetPasswordRequest(val newPassword: String, val currentPassword: String? = null)

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
    val passwordService = PasswordService(repositories.users)

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
            // Let the data store assign the id (DESIGN §12): we never hand-pick the founder's id,
            // just like every onboarded member gets a DB-assigned id. A login-only handle is set so
            // the founder can later set a password and sign back in with it (PASSWORD_AUTH.md §2D).
            val handle = uniqueHandle(repositories, "founder")
            val founder = repositories.users.create(
                User(
                    id = UUID.randomUUID().toString(),
                    role = Role.FOUNDER,
                    displayName = "Founder",
                    handle = handle,
                    // Never leave the founder without a password (issue 5): default to "founder",
                    // which they can change later. Hashed directly (platform-issued default).
                    passwordHash = social.saigon.auth.PasswordHasher.hash("founder"),
                    passwordSetAt = System.currentTimeMillis(),
                ),
            )
            call.respond(
                OnboardResponse(founder.id, tokenService.issueMemberToken(founder.id, founder.role)),
            )
        }

        // Public probe for the onboarding screen: is the Founder seat still unclaimed? The
        // "Claim the Founder account" affordance is shown only when no member account exists yet,
        // mirroring the gate POST /auth/bootstrap enforces.
        get("/auth/bootstrap") {
            val hasMembers = repositories.users.all().any { it.role != Role.SEEKER }
            call.respond(BootstrapStatusResponse(available = !hasMembers))
        }

        // Password sign-in (PASSWORD_AUTH.md §2C): re-enter with a handle + password the member set
        // earlier, instead of re-pasting a token. On success the server mints a *fresh* member JWT,
        // neatly renewing an expired token. Failures return a generic 401 (no user enumeration).
        post("/auth/login") {
            val req = call.receive<LoginRequest>()
            val user = passwordService.authenticate(req.identifier, req.password)
            call.respond(OnboardResponse(user.id, tokenService.issueMemberToken(user.id, user.role)))
        }

        // Redeem a founder-issued invite code to create an account (replaces sharing a raw access
        // token). The recipient chooses a username + password; the code is single-use.
        post("/auth/redeem") {
            val req = call.receive<RedeemRequest>()
            val code = req.code.trim()
            val username = req.username.trim()
            require(code.isNotBlank()) { "An invite code is required." }
            require(username.isNotBlank()) { "A username is required." }
            require(req.password.length >= social.saigon.auth.PasswordHasher.MIN_LENGTH) {
                "Password must be at least ${social.saigon.auth.PasswordHasher.MIN_LENGTH} characters."
            }
            val invite = repositories.invites.get(code)
                ?: throw IllegalArgumentException("That invite code is invalid.")
            require(!invite.redeemed) { "That invite code has already been used." }
            require(repositories.users.findByHandle(username) == null) {
                "That username is taken. Please choose another."
            }
            val user = repositories.users.create(
                User(
                    id = UUID.randomUUID().toString(),
                    role = invite.role,
                    displayName = username,
                    handle = username,
                ),
            )
            passwordService.setPassword(user.id, req.password, null)
            economicsService.paySharerFee(user.id)
            if (invite.role == Role.SUPPORTER) economicsService.paySupporterSubscription(user.id)
            repositories.invites.update(
                invite.copy(redeemedBy = user.id, redeemedAt = System.currentTimeMillis()),
            )
            call.respond(OnboardResponse(user.id, tokenService.issueMemberToken(user.id, user.role)))
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
        // An optional `q` filters rooms by name/atmosphere on the backend; each non-blank query is
        // recorded so the founder can understand what kinds of rooms people are looking for (§6).
        get("/rooms") {
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            val matches = roomService.list().filter { room ->
                query.isBlank() ||
                    room.name.contains(query, ignoreCase = true) ||
                    room.atmosphere.contains(query, ignoreCase = true)
            }
            if (query.isNotBlank()) {
                repositories.roomSearches.add(
                    social.saigon.domain.RoomSearch(
                        id = UUID.randomUUID().toString(),
                        query = query,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            // Sorted by most people active, descending (DESIGN §6).
            val rooms = matches.map { room ->
                RoomSummary(
                    id = room.id,
                    name = room.name,
                    emoji = room.emoji,
                    atmosphere = room.atmosphere,
                    occupants = chatHub.occupants(room.id),
                )
            }.sortedByDescending { it.occupants }
            call.respond(rooms)
        }

        // Open viewing for all roles (incl. Seekers): read a room's public chat (DESIGN §6).
        // Each message carries its live reply-count for the "↳ N replies" indicator (DESIGN §9).
        get("/rooms/{roomId}/messages") {
            val roomId = call.requireRoomId()
            // Infinite scroll: page by 20, paging upward through history with the `before` cursor.
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val feed = repositories.messages.page(roomId, before, limit).map { message ->
                FeedMessage(
                    id = message.id,
                    roomId = message.roomId,
                    authorId = message.authorId,
                    authorName = message.authorName.ifBlank {
                        authorName(message.authorId, roomId, repositories, identities)
                    },
                    text = message.text,
                    createdAt = message.createdAt,
                    replyCount = threadService.replyCount(message.id),
                )
            }
            // Ordering and paging are done by the database (see MessageRepository.page): the feed is
            // already newest→oldest, so it is sent as-is. The client flips each page back to
            // oldest→newest for display (see SaigonApi.messages).
            call.respond(feed)
        }

        // Open viewing for all roles (incl. Seekers): read a message's ephemeral thread (DESIGN §9, §11).
        get("/rooms/{roomId}/messages/{messageId}/thread") {
            val roomId = call.requireRoomId()
            val messageId = call.parameters["messageId"].orEmpty()
            require(messageId.isNotBlank()) { "messageId required" }
            val replies = threadService.thread(messageId).map { reply ->
                ThreadReplyView(
                    id = reply.id,
                    parentMessageId = reply.parentMessageId,
                    replyToId = reply.replyToId,
                    authorId = reply.authorId,
                    authorName = reply.authorName.ifBlank {
                        authorName(reply.authorId, roomId, repositories, identities)
                    },
                    text = reply.text,
                    createdAt = reply.createdAt,
                )
            }
            call.respond(replies)
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
                        userId = userId,
                        name = identity.name,
                        avatarUrl = identity.avatarUrl,
                        role = user.role,
                        intents = user.intents,
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

            // Change the caller's login username (handle), rejecting one already taken.
            put("/profile/username") {
                val userId = requireMember(call.caller())
                val req = call.receive<ChangeUsernameRequest>()
                val username = req.username.trim()
                require(username.isNotBlank()) { "A username is required." }
                val existing = repositories.users.findByHandle(username)
                require(existing == null || existing.id == userId) {
                    "That username is taken. Please choose another."
                }
                val user = profiles.get(userId)
                call.respond(repositories.users.update(user.copy(handle = username)).toProfileResponse())
            }

            // Set or change the durable sign-in password (PASSWORD_AUTH.md §2B). Gated by the JWT
            // auth: the caller must already be signed in with a valid token. The returned profile
            // reflects the now-set password without ever exposing the hash.
            post("/profile/password") {
                val userId = requireMember(call.caller())
                val req = call.receive<SetPasswordRequest>()
                val updated = passwordService.setPassword(userId, req.newPassword, req.currentPassword)
                call.respond(updated.toProfileResponse())
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
                // Supporters and the Founder may create rooms (DESIGN §6).
                require(caller.role.isAdmin || caller.role == Role.SUPPORTER) {
                    "Only Supporters and the Founder can create rooms"
                }
                val creatorId = caller.userId ?: throw IllegalArgumentException("Members only")
                val req = call.receive<RoomCreate>()
                call.respond(roomService.create(creatorId, req))
            }

            // Founder-only room rename/edit (DESIGN §6).
            put("/rooms/{roomId}") {
                val caller = call.caller()
                require(caller.role.isAdmin) { "Founder only" }
                val roomId = call.requireRoomId()
                val req = call.receive<RoomUpdate>()
                call.respond(roomService.update(roomId, req))
            }

            // Founder-only room deletion (DESIGN §6).
            delete("/rooms/{roomId}") {
                val caller = call.caller()
                require(caller.role.isAdmin) { "Founder only" }
                val roomId = call.requireRoomId()
                roomService.delete(roomId)
                call.respond(mapOf("deleted" to roomId))
            }

            // Founder-only onboarding: no open sign-up, no OTP (DESIGN §2, §3).
            post("/founder/onboard") {
                val caller = call.principal<JWTPrincipal>()!!.toCaller()
                require(caller.role.isAdmin) { "Founder only" }
                val req = call.receive<OnboardRequest>()
                require(req.role != Role.SEEKER) { "Cannot onboard a Seeker" }
                // A login-only handle the member can pair with a password later (PASSWORD_AUTH.md
                // §2C); derived from their display name, kept unique.
                val handle = uniqueHandle(repositories, req.displayName)
                val user = repositories.users.create(
                    User(
                        id = UUID.randomUUID().toString(),
                        role = req.role,
                        displayName = req.displayName,
                        handle = handle,
                        // Never leave an account without a password: seed a default (the handle) the
                        // member can change later from their profile. Hashed directly because these
                        // are platform-issued defaults, not user-typed (so no strength gate).
                        passwordHash = social.saigon.auth.PasswordHasher.hash(handle),
                        passwordSetAt = System.currentTimeMillis(),
                    ),
                )
                // The founder collects the fee in person, so onboarding settles the tier's payment
                // status: every onboarded member has paid the one-time Sharer fee, and a Supporter
                // additionally starts a subscription period with a real expiry (DESIGN §3, §11).
                economicsService.paySharerFee(user.id)
                if (req.role == Role.SUPPORTER) economicsService.paySupporterSubscription(user.id)
                call.respond(OnboardResponse(user.id, tokenService.issueMemberToken(user.id, user.role)))
            }

            // Founder-only member directory (MEMBER_MANAGEMENT.md A): who has been onboarded, each
            // member's tier, payment status, and subscription expiry. Reuses UserRepository.all();
            // Seekers are excluded since they have no persistent account.
            get("/founder/members") {
                requireFounder(call.caller())
                val members = repositories.users.all()
                    .filter { it.role != Role.SEEKER }
                    .sortedBy { it.displayName.lowercase() }
                    .map { it.toMemberView(economicsService) }
                call.respond(members)
            }

            // Founder-only: recent room searches (DESIGN §6), newest first, so the founder can
            // understand what kinds of rooms people are looking for. `limit` caps the result (1–500).
            get("/founder/room-searches") {
                requireFounder(call.caller())
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val searches = repositories.roomSearches.recent(limit)
                    .map { RoomSearchView(it.query, it.createdAt) }
                call.respond(searches)
            }

            // Founder-only: mint a single-use invite code for a new member (replaces sharing a raw
            // access token). The recipient redeems it via POST /auth/redeem to create their account.
            post("/founder/invites") {
                val founderId = requireFounder(call.caller())
                val req = call.receive<InviteCreateRequest>()
                require(req.role != Role.SEEKER) { "Cannot invite a Seeker" }
                require(req.role != Role.FOUNDER) { "Cannot invite a Founder" }
                val code = generateInviteCode(repositories)
                repositories.invites.create(
                    social.saigon.domain.InviteCode(
                        code = code,
                        role = req.role,
                        createdBy = founderId,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                call.respond(InviteCodeResponse(code, req.role))
            }

            // Founder-only: list every invite code that has been minted, newest first, flagging
            // which have already been redeemed so the UI can offer to delete the unused ones.
            get("/founder/invites") {
                requireFounder(call.caller())
                val invites = repositories.invites.all()
                    .sortedByDescending { it.createdAt }
                    .map { InviteCodeView(it.code, it.role, it.createdAt, it.redeemed) }
                call.respond(invites)
            }

            // Founder-only: delete an invite code that has *not* yet been used. Already-redeemed
            // codes are kept as an audit trail of who joined with which code.
            delete("/founder/invites/{code}") {
                requireFounder(call.caller())
                val code = call.parameters["code"].orEmpty().trim()
                require(code.isNotBlank()) { "code required" }
                val invite = repositories.invites.get(code)
                    ?: throw IllegalArgumentException("That invite code does not exist.")
                require(!invite.redeemed) { "That invite code has already been used." }
                repositories.invites.delete(invite.code)
                call.respond(mapOf("deleted" to invite.code))
            }

            // Founder-only manual tier change (MEMBER_MANAGEMENT.md B): comp a member up to Supporter,
            // downgrade to Sharer, or demote to Seeker — no payment is recorded. Because the JWT
            // carries the role, a fresh token reflecting the new tier is returned so the member can
            // re-authenticate (a non-member Seeker gets no token). The Founder's own role is fixed.
            patch("/founder/members/{id}/role") {
                val founderId = requireFounder(call.caller())
                val userId = call.parameters["id"].orEmpty()
                require(userId.isNotBlank()) { "id required" }
                require(userId != founderId) { "The Founder's role cannot be changed." }
                val req = call.receive<SetTierRequest>()
                val outcome = economicsService.setTier(userId, req.role)
                val token = outcome.user.role
                    .takeIf { it != Role.SEEKER }
                    ?.let { tokenService.issueMemberToken(outcome.user.id, it) }
                call.respond(
                    MemberTierChangeResponse(
                        member = outcome.user.toMemberView(economicsService),
                        token = token,
                    ),
                )
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
                chatHub.unregister(connectionId)
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
    /** The occupant's persistent user id, used to start a private chat (DESIGN §7). */
    val userId: String = "",
    val name: String,
    val avatarUrl: String,
    val role: Role,
    val intents: List<Intent> = emptyList(),
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
    val intents: List<Intent> = emptyList(),
    val languages: List<String>,
    val offlineSchedule: List<String>,
    val supporterUntil: Long?,
    val capabilities: List<Capability>,
    /** The login-only handle used as the username for password sign-in (PASSWORD_AUTH.md §2C). */
    val handle: String? = null,
    /** True once the member has set a durable sign-in password (PASSWORD_AUTH.md §2B). */
    val passwordSet: Boolean = false,
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
    /** The author's room-scoped display name (DESIGN §5), so the feed never shows a raw id. */
    val authorName: String,
    val text: String,
    val createdAt: Long,
    val replyCount: Int,
)

/** A thread reply enriched with the author's room-scoped display name (DESIGN §5, §9). */
@Serializable
data class ThreadReplyView(
    val id: String,
    val parentMessageId: String,
    val replyToId: String? = null,
    val authorId: String,
    val authorName: String,
    val text: String,
    val createdAt: Long,
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

/** A member as shown in the Founder's directory (MEMBER_MANAGEMENT.md A). */
@Serializable
data class MemberView(
    val id: String,
    val displayName: String,
    /** The account's nominal role on record. */
    val role: Role,
    /** The role actually in force once payment status is applied. */
    val effectiveRole: Role,
    val sharerPaidAt: Long?,
    val supporterUntil: Long?,
    val status: EconomicStatus,
    /** The member's login username (handle), for the founder's reference. */
    val handle: String? = null,
    /** The member's chosen intents (DESIGN §4). */
    val intents: List<Intent> = emptyList(),
    /** Language codes the member speaks. */
    val languages: List<String> = emptyList(),
    /** The member's free-text introduction (formerly "interests"). */
    val introduction: List<String> = emptyList(),
)

/** Body for a founder-only manual tier change (MEMBER_MANAGEMENT.md B). */
@Serializable
data class SetTierRequest(val role: Role)

/**
 * Result of a founder-initiated tier change: the member's refreshed directory view plus a fresh
 * access token carrying the new role (null when the member was demoted to Seeker).
 */
@Serializable
data class MemberTierChangeResponse(val member: MemberView, val token: String? = null)

/** A recorded room search, surfaced to the founder so they can see what people look for (§6). */
@Serializable
data class RoomSearchView(val query: String, val createdAt: Long)

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

private fun User.toMemberView(economics: EconomicsService): MemberView = MemberView(
    id = id,
    displayName = displayName,
    role = role,
    effectiveRole = economics.effectiveRole(this),
    sharerPaidAt = sharerPaidAt,
    supporterUntil = supporterUntil,
    status = economics.status(id),
    handle = handle,
    intents = intents,
    languages = languages,
    introduction = interests,
)

private fun User.toProfileResponse(): ProfileResponse = ProfileResponse(
    id = id,
    role = role,
    displayName = displayName,
    interests = interests,
    intents = intents,
    languages = languages,
    offlineSchedule = offlineSchedule,
    supporterUntil = supporterUntil,
    capabilities = CapabilityMatrix.capabilities(role).toList(),
    handle = handle,
    // Never expose the hash itself — only whether a password exists (PASSWORD_AUTH.md §2E).
    passwordSet = passwordHash != null,
)

/**
 * Resolve a message/reply author's room-scoped display name (DESIGN §5) so the public feed and
 * thread sheets never surface a raw user id. Anonymous (non-member) authors keep their id.
 */
private suspend fun authorName(
    authorId: String,
    roomId: String,
    repositories: Repositories,
    identities: IdentityService,
): String =
    if (repositories.users.get(authorId) != null) {
        identities.getOrCreate(authorId, roomId).name
    } else {
        authorId
    }

/**
 * Generate a short, human-friendly, single-use invite code (e.g. "K7P2QM9X"). Uses an
 * ambiguity-free alphabet (no 0/O/1/I/L) and retries until the code is free.
 */
private fun generateInviteCode(repositories: Repositories): String {
    val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    fun once(): String = (1..8).map { alphabet.random() }.joinToString("")
    var code = once()
    while (repositories.invites.get(code) != null) code = once()
    return code
}

/**
 * Derive a unique, login-only handle from a display name (PASSWORD_AUTH.md §2C). Slugs the name to
 * lowercase ASCII alphanumerics, falling back to "member", then appends a numeric suffix until the
 * handle is free across all persisted users.
 */
private fun uniqueHandle(repositories: Repositories, displayName: String): String {
    val base = displayName.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), "-")
        .ifBlank { "member" }
    if (repositories.users.findByHandle(base) == null) return base
    var suffix = 2
    while (repositories.users.findByHandle("$base$suffix") != null) suffix++
    return "$base$suffix"
}

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
            chatHub.join(frame.roomId, session.connectionId)
            // A member joining a room is an activity signal (DESIGN §14 — DAU).
            session.userId?.let { metrics.record(MetricType.ACTIVE, userId = it) }
            // Broadcast the de-duplicated head-count so one member never shows up twice.
            chatHub.broadcast(frame.roomId, Outgoing.Joined(frame.roomId, chatHub.occupants(frame.roomId)))
        }

        is Incoming.Leave -> {
            chatHub.leave(frame.roomId, session.connectionId)
            chatHub.broadcast(frame.roomId, Outgoing.Left(frame.roomId, chatHub.occupants(frame.roomId)))
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
                    authorName = authorName ?: authorId,
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
                    authorName = authorName ?: authorId,
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
                // Reuse an already-open chat if one exists, so the *recipient* can open the chat the
                // initiator created by tapping the initiator's name — even though the initiator may
                // not be an available Supporter (DESIGN §7).
                privateChats.openOrStart(initiatorId, frame.targetUserId, frame.roomId)
            } catch (e: IllegalArgumentException) {
                session.send(Outgoing.Error(e.message ?: "Cannot start private chat"))
                return
            }
            // Each party sees the other's room-scoped display name (DESIGN §5, §7).
            val initiatorName = identities.getOrCreate(initiatorId, frame.roomId).name
            val targetName = identities.getOrCreate(frame.targetUserId, frame.roomId).name
            chatHub.sendToUser(initiatorId, Outgoing.PrivateChatStarted(chat.id, frame.targetUserId, targetName, frame.roomId))
            chatHub.sendToUser(frame.targetUserId, Outgoing.PrivateChatStarted(chat.id, initiatorId, initiatorName, frame.roomId))
            // Replay the conversation so far to whoever just opened the screen, so (re)opening an
            // already-open chat shows its history rather than a blank window (DESIGN §7). The
            // history lives only while the chat is open; it is discarded once the chat ends.
            privateChats.messages(chat.id).forEach { message ->
                session.send(Outgoing.PrivateMessageSaid(message.chatId, message.authorId, message.text, message.createdAt))
            }
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
