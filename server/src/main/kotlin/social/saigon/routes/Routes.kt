package social.saigon.routes

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
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
import io.ktor.http.HttpStatusCode
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import social.saigon.ai.IdentityGenerator
import social.saigon.auth.AUTH_JWT
import social.saigon.auth.CallerPrincipal
import social.saigon.auth.PasswordService
import social.saigon.auth.TokenService
import social.saigon.auth.toCaller
import social.saigon.domain.Capability
import social.saigon.domain.CapabilityMatrix
import social.saigon.domain.EconomicStatus
import social.saigon.domain.EmojiOptions
import social.saigon.domain.Feedback
import social.saigon.domain.ForbiddenException
import social.saigon.domain.InviteRequest
import social.saigon.domain.InviteStatus
import social.saigon.domain.LaunchConfig
import social.saigon.domain.Message
import social.saigon.domain.PlainText
import social.saigon.domain.Pricing
import social.saigon.domain.ProfilePhoto
import social.saigon.domain.ProfileUpdate
import social.saigon.domain.ReportTarget
import social.saigon.domain.Role
import social.saigon.domain.RoomCreate
import social.saigon.domain.RoomIdentityUpdate
import social.saigon.domain.RoomMembership
import social.saigon.domain.RoomUpdate
import social.saigon.domain.Service
import social.saigon.domain.ServiceActivity
import social.saigon.domain.ServiceDestination
import social.saigon.domain.Skill
import social.saigon.domain.ThreadSummary
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
 * Set the caller's profile photo (issue: "Profile and community profile photos").
 * [url] is a content-server URL from a prior upload; [message] is the user's caption for it.
 */
@Serializable
data class SetProfilePhotoRequest(val url: String, val message: String = "")

/**
 * Set or change the caller's password (PASSWORD_AUTH.md §2B). [currentPassword] is required only
 * when one is already set.
 */
@Serializable
data class SetPasswordRequest(val newPassword: String, val currentPassword: String? = null)

/** Create a service from a room. The provider is always the authenticated caller. */
@Serializable
data class ServiceCreate(
    val roomId: String,
    val emoji: String,
    val name: String,
    val description: String = "",
    /** Price in VND (whole integer). */
    val price: Int = 0,
    /** Semicolon-separated "What's included" items. */
    val included: String = "",
    /** Semicolon-separated "What's not included" items. */
    val notIncluded: String = "",
    /** Semicolon-separated "What to bring" items. */
    val toBring: String = "",
    /** Semicolon-separated "What not to bring" items. */
    val notToBring: String = "",
    /** Ordered itinerary stops ("Where you'll go"). */
    val destinations: List<ServiceDestination> = emptyList(),
    /** Activities ("What you'll do"), Markdown body supported. */
    val activities: List<ServiceActivity> = emptyList(),
    /** Free-text cancellation policy; blank falls back to the default 24-hour promise. */
    val cancellationPolicy: String = Service.DEFAULT_CANCELLATION_POLICY,
)

/** Edit an existing service (provider or Founder). */
@Serializable
data class ServiceUpdate(
    val emoji: String,
    val name: String,
    val description: String = "",
    /** Price in VND (whole integer). */
    val price: Int = 0,
    /** Semicolon-separated "What's included" items. */
    val included: String = "",
    /** Semicolon-separated "What's not included" items. */
    val notIncluded: String = "",
    /** Semicolon-separated "What to bring" items. */
    val toBring: String = "",
    /** Semicolon-separated "What not to bring" items. */
    val notToBring: String = "",
    /** Ordered itinerary stops ("Where you'll go"). */
    val destinations: List<ServiceDestination> = emptyList(),
    /** Activities ("What you'll do"), Markdown body supported. */
    val activities: List<ServiceActivity> = emptyList(),
    /** Free-text cancellation policy. */
    val cancellationPolicy: String = Service.DEFAULT_CANCELLATION_POLICY,
)

/**
 * Add a photo to a service (issue: "upload adventure photos"). [url] is a content-server URL from a
 * prior upload; [message] is the provider's optional caption for it.
 */
@Serializable
data class AddServicePhotoRequest(val url: String, val message: String = "")

/**
 * Update an existing photo in a list (previous profile photos or service photos). [originalUrl]
 * identifies the entry to edit; [url]/[message] are the replacement image and caption.
 */
@Serializable
data class UpdatePhotoRequest(
    val originalUrl: String,
    val url: String,
    val message: String = "",
)

/** A service enriched with its room name and provider details for list + detail screens. */
@Serializable
data class ServiceView(
    val id: String,
    val emoji: String,
    val name: String,
    val description: String,
    val roomId: String,
    val roomName: String,
    val roomEmoji: String,
    val providerId: String,
    val providerName: String,
    val providerRole: Role,
    val providerLanguages: List<String> = emptyList(),
    val providerInterests: List<String> = emptyList(),
    /** Price in VND (whole integer, e.g. 50000 → 50.000đ). */
    val price: Int = 0,
    /** Semicolon-separated "What's included" items. */
    val included: String = "",
    /** Semicolon-separated "What's not included" items. */
    val notIncluded: String = "",
    /** Semicolon-separated "What to bring" items. */
    val toBring: String = "",
    /** Semicolon-separated "What not to bring" items. */
    val notToBring: String = "",
    /** Ordered itinerary stops ("Where you'll go"). */
    val destinations: List<ServiceDestination> = emptyList(),
    /** Activities ("What you'll do"), Markdown body supported. */
    val activities: List<ServiceActivity> = emptyList(),
    /** Free-text cancellation policy. */
    val cancellationPolicy: String = Service.DEFAULT_CANCELLATION_POLICY,
    /** Whether the service is publicly listed (unlisted services are hidden from search). */
    val listed: Boolean = true,
    /** The service's uploaded photos (newest first), shown on the detail screen + as card backgrounds. */
    val photos: List<ProfilePhoto> = emptyList(),
    val createdAt: Long,
)

/** Public feedback on a service, with the author's display name + role (for the badge). */
@Serializable
data class FeedbackView(
    val id: String,
    val serviceId: String,
    val userId: String,
    val authorName: String,
    val authorRole: Role,
    val emoji: String,
    val message: String,
    val createdAt: Long,
)

/** Add feedback to a service. */
@Serializable
data class FeedbackCreate(val emoji: String, val message: String = "")

/** Submit a "Request an Invite" from the sign-in screen (public, no account is created). */
@Serializable
data class InviteRequestCreate(
    val name: String,
    val zaloNumber: String,
    val description: String = "",
)

/** Founder-facing view of a pending invite request. */
@Serializable
data class InviteRequestView(
    val id: String,
    val name: String,
    val zaloNumber: String,
    val description: String,
    val status: InviteStatus,
    val createdAt: Long,
)

/** Contact a service provider with an opening message. */
@Serializable
data class ContactProviderRequest(val message: String)

/** The direct chat opened by contacting a provider, so the client can navigate straight to it. */
@Serializable
data class ContactProviderResponse(
    val roomId: String,
    val providerId: String,
    val providerName: String,
)

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
    val identities = IdentityService(repositories.identities, identityGenerator, repositories.users)
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
            // Newest rooms first (sorted by creation time, descending), so a freshly created room
            // shows at the top of the list rather than the bottom. The latest message (if any) is
            // loaded together with each room so the home list can show "time ago" + a preview line
            // without a second round-trip.
            val rooms = matches.sortedByDescending { it.createdAt }.map { room ->
                val last = repositories.messages.page(room.id, null, 1).firstOrNull()
                RoomSummary(
                    id = room.id,
                    name = room.name,
                    emoji = room.emoji,
                    atmosphere = room.atmosphere,
                    occupants = chatHub.occupants(room.id),
                    lastMessageText = last?.text,
                    lastMessageSender = last?.let {
                        it.authorName.ifBlank { authorName(it.authorId, room.id, repositories, identities) }
                    },
                    lastMessageAt = last?.createdAt,
                    photos = room.photos,
                    description = room.description,
                )
            }
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
                    // The room-first avatar photo (room profile latest, else main profile).
                    authorPhotoUrl = roomPhotoUrl(message.authorId, roomId, repositories, identities),
                    text = message.text,
                    createdAt = message.createdAt,
                    replyCount = threadService.replyCount(message.id),
                    recalled = message.recalled,
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
                    // The room-first avatar photo (room profile latest, else main profile).
                    authorPhotoUrl = roomPhotoUrl(reply.authorId, roomId, repositories, identities),
                    text = reply.text,
                    createdAt = reply.createdAt,
                    recalled = reply.recalled,
                )
            }
            call.respond(replies)
        }

        // Who's in the room: live online sessions plus durable joined members (issue: "Join / Leave
        // a community"). Joined members stay listed even while offline; online presence is still
        // tracked separately so the UI can mark who is currently present.
        get("/rooms/{roomId}/occupants") {
            val roomId = call.requireRoomId()
            val onlineIds = chatHub.sessionsIn(roomId)
                .mapNotNull { it.userId }
                .distinct()
                .toSet()
            val memberIds = repositories.memberships.byRoom(roomId).map { it.userId }.toSet()
            val occupants = (onlineIds + memberIds).mapNotNull { userId ->
                toOccupantView(
                    userId = userId,
                    roomId = roomId,
                    repositories = repositories,
                    identities = identities,
                    privateChatService = privateChatService,
                    online = userId in onlineIds,
                    member = userId in memberIds,
                )
            }
            call.respond(occupants)
        }

        // A single member's room-scoped occupant view by persistent user id (DESIGN §5, §6). Open
        // to all roles, mirroring the occupants list. Used to open the detail sheet from a message's
        // profile icon even when the author is no longer one of the live occupants — message authors
        // outlive their connection, but their room identity and public profile facets do not.
        get("/rooms/{roomId}/occupants/{userId}") {
            val roomId = call.requireRoomId()
            val userId = call.parameters["userId"].orEmpty()
            require(userId.isNotBlank()) { "userId required" }
            if (repositories.users.get(userId) == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such member"))
                return@get
            }
            val online = chatHub.sessionsIn(roomId).any { it.userId == userId }
            val member = repositories.memberships.get(userId, roomId) != null
            call.respond(
                toOccupantView(
                    userId = userId,
                    roomId = roomId,
                    repositories = repositories,
                    identities = identities,
                    privateChatService = privateChatService,
                    online = online,
                    member = member,
                )!!,
            )
        }

        // --- Services (top-level entity) ---

        // Public: browse/search services by name (case-insensitive partial match). Each result
        // carries the room name + provider details so the search cards can render everything.
        get("/services") {
            val q = call.request.queryParameters["q"].orEmpty().trim().lowercase()
            // Optional filters so callers can list a single provider's services (their room profile
            // "Hosting" section, or their profile-wide "Adventures hosted" screen), optionally
            // scoped to one room.
            val provider = call.request.queryParameters["provider"].orEmpty().trim()
            val room = call.request.queryParameters["room"].orEmpty().trim()
            // Record every non-blank by-name search so the founder can see what people look for
            // (mirrors the room-search log). Provider/room-scoped listing calls carry no `q`.
            if (q.isNotBlank()) {
                repositories.serviceSearches.add(
                    social.saigon.domain.ServiceSearch(
                        id = UUID.randomUUID().toString(),
                        query = q,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            val services = repositories.services.all()
                // Unlisted services are invisible to the public search + room-profile listings.
                .filter { it.listed }
                .filter { q.isEmpty() || it.name.lowercase().contains(q) }
                .filter { provider.isEmpty() || it.providerId == provider }
                .filter { room.isEmpty() || it.roomId == room }
                .sortedByDescending { it.createdAt }
                .map { it.toServiceView(repositories) }
            call.respond(services)
        }

        // Public: a single service's full detail.
        get("/services/{id}") {
            val id = call.parameters["id"].orEmpty()
            val service = repositories.services.get(id)
                ?: run { call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such adventure")); return@get }
            call.respond(service.toServiceView(repositories))
        }

        // Public: feedback for a service (aggregated client-side by emoji).
        get("/services/{id}/feedback") {
            val id = call.parameters["id"].orEmpty()
            val feedback = repositories.feedback.byService(id).map { it.toFeedbackView(repositories) }
            call.respond(feedback)
        }

        // Public: submit a "Request an Invite" (DESIGN — no account is created automatically).
        post("/invite-requests") {
            val req = call.receive<InviteRequestCreate>()
            val name = req.name.trim()
            val zalo = req.zaloNumber.trim()
            require(name.isNotBlank()) { "A full name is required." }
            require(zalo.isNotBlank()) { "A Zalo number is required." }
            repositories.inviteRequests.create(
                InviteRequest(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    zaloNumber = zalo,
                    description = req.description.trim(),
                    status = InviteStatus.PENDING,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
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

            // Set a new profile photo — or update the current one's caption (issue: "Profile and
            // community profile photos"). A new URL becomes the main photo and rotates the previous
            // main photo into the "previous photos" list.
            post("/profile/photo") {
                val userId = requireMember(call.caller())
                val req = call.receive<SetProfilePhotoRequest>()
                call.respond(profiles.setPhoto(userId, req.url, req.message).toProfileResponse())
            }

            // Update one of the caller's *previous* profile photos (replace image and/or caption).
            put("/profile/photos") {
                val userId = requireMember(call.caller())
                val req = call.receive<UpdatePhotoRequest>()
                call.respond(
                    profiles.updatePreviousPhoto(
                        userId,
                        req.originalUrl,
                        req.url,
                        req.message,
                    ).toProfileResponse(),
                )
            }

            // Delete one of the caller's *previous* profile photos by its URL (`?url=`).
            delete("/profile/photos") {
                val userId = requireMember(call.caller())
                val url = call.request.queryParameters["url"].orEmpty()
                require(url.isNotBlank()) { "url required" }
                call.respond(profiles.deletePhoto(userId, url).toProfileResponse())
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

            // Thread replies active for the user (those they started or replied to).
            get("/profile/me/threads") {
                val selfUserId = requireMember(call.caller())
                val parentIds = repositories.threads.parents()
                val relevantThreads = parentIds.mapNotNull { parentId ->
                    val parent = repositories.messages.get(parentId) ?: return@mapNotNull null
                    val replies = repositories.threads.byParent(parentId).sortedBy { it.createdAt }
                    val userStarted = parent.authorId == selfUserId
                    // My own replies never trigger an entry. A thread is only relevant when another
                    // user has replied either to my initial message (I started the thread) or after
                    // my own first reply.
                    val myFirstReplyAt = replies
                        .filter { it.authorId == selfUserId }
                        .minOfOrNull { it.createdAt }
                    val hasTriggeringReply = replies.any { reply ->
                        reply.authorId != selfUserId && reply.authorId.isNotBlank() &&
                            (userStarted || (myFirstReplyAt != null && reply.createdAt > myFirstReplyAt))
                    }
                    if (!hasTriggeringReply) return@mapNotNull null
                    if (repositories.threads.isDismissed(selfUserId, parentId)) return@mapNotNull null
                    val room = repositories.rooms.get(parent.roomId) ?: return@mapNotNull null
                    val lastReply = replies.lastOrNull()
                    ThreadSummary(
                        roomId = room.id,
                        roomName = room.name,
                        roomEmoji = room.emoji,
                        parentMessageId = parent.id,
                        parentMessageText = parent.text,
                        parentMessageAuthorName = parent.authorName.ifBlank {
                            authorName(parent.authorId, room.id, repositories, identities)
                        },
                        replyCount = replies.size,
                        lastReplyText = lastReply?.text,
                        lastReplyAuthorName = lastReply?.authorName?.ifBlank {
                            lastReply?.authorId?.let { authorName(it, room.id, repositories, identities) }
                        },
                        lastReplyAt = lastReply?.createdAt
                    )
                }.sortedByDescending { it.lastReplyAt ?: 0L }
                call.respond(relevantThreads)
            }

            post("/profile/me/threads/{parentMessageId}/dismiss") {
                val selfUserId = requireMember(call.caller())
                val parentMessageId = call.parameters["parentMessageId"].orEmpty()
                repositories.threads.dismiss(selfUserId, parentMessageId)
                call.respond(HttpStatusCode.OK)
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

            // The caller's room-scoped identity. A new profile starts with their main display name
            // (not a random one); Qwen AI is only used when they explicitly refresh. Kept stable
            // until the user refreshes it (no time-based resets).
            get("/rooms/{roomId}/identity") {
                val userId = requireMember(call.caller())
                val roomId = call.requireRoomId()
                call.respond(identities.getOrCreate(userId, roomId))
            }

            // "Refresh my name & avatar in this community" — instant, user-controlled regeneration.
            post("/rooms/{roomId}/identity/refresh") {
                val userId = requireMember(call.caller())
                val roomId = call.requireRoomId()
                call.respond(identities.refresh(userId, roomId))
            }

            // Edit the caller's room-scoped profile (DESIGN §4, §5): a custom name plus per-room
            // overrides of interests/intents/languages/availability/providing. A Supporter perk
            // ("Tailor your profile per community"); Sharers stick to the shared profile + name refresh.
            put("/rooms/{roomId}/identity") {
                val caller = call.caller()
                val userId = requireMember(caller)
                require(caller.role == Role.SUPPORTER || caller.role.isAdmin) {
                    "Only Supporters and the Founder can tailor their profile per community"
                }
                val roomId = call.requireRoomId()
                val update = call.receive<RoomIdentityUpdate>()
                call.respond(identities.updateProfile(userId, roomId, update))
            }

            // Founder-only room creation/management (DESIGN §6).
            post("/rooms") {
                val caller = call.caller()
                // Supporters and the Founder may create rooms (DESIGN §6).
                require(caller.role.isAdmin || caller.role == Role.SUPPORTER) {
                    "Only Supporters and the Founder can create communities"
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

            // Join a community (issue: "Join / Leave a community"). Members stay listed in the
            // occupant list even while offline. Idempotent: joining twice is a no-op.
            post("/rooms/{roomId}/join") {
                val userId = requireMember(call.caller())
                val roomId = call.requireRoomId()
                repositories.rooms.get(roomId)
                    ?: throw IllegalArgumentException("That community does not exist.")
                val existing = repositories.memberships.get(userId, roomId)
                val membership = existing ?: repositories.memberships.put(
                    RoomMembership(
                        userId = userId,
                        roomId = roomId,
                        joinedAt = System.currentTimeMillis(),
                    ),
                )
                call.respond(MembershipResponse(joined = true, joinedAt = membership.joinedAt))
            }

            // Leave a community (issue: "Join / Leave a community"). Idempotent.
            post("/rooms/{roomId}/leave") {
                val userId = requireMember(call.caller())
                val roomId = call.requireRoomId()
                repositories.rooms.get(roomId)
                    ?: throw IllegalArgumentException("That community does not exist.")
                repositories.memberships.delete(userId, roomId)
                call.respond(MembershipResponse(joined = false, joinedAt = null))
            }

            // Whether the caller has joined this community (for the Join/Leave button state).
            get("/rooms/{roomId}/membership") {
                val userId = requireMember(call.caller())
                val roomId = call.requireRoomId()
                val membership = repositories.memberships.get(userId, roomId)
                call.respond(
                    MembershipResponse(
                        joined = membership != null,
                        joinedAt = membership?.joinedAt,
                    ),
                )
            }

            // Recall a public-chat message (DESIGN §6, §9): clear its text and flag it as recalled so
            // the UI shows a "message recalled" notice in place of the content. Only the message's
            // own author or the Founder may do this. Recalling never touches the message's thread —
            // existing replies stay intact and readable.
            post("/rooms/{roomId}/messages/{messageId}/recall") {
                val caller = call.caller()
                val userId = requireMember(caller)
                val roomId = call.requireRoomId()
                val messageId = call.parameters["messageId"].orEmpty()
                require(messageId.isNotBlank()) { "messageId required" }
                val message = repositories.messages.get(messageId)
                    ?: throw IllegalArgumentException("That message does not exist.")
                require(message.roomId == roomId) { "That message is not in this community." }
                if (message.authorId != userId && !caller.role.isAdmin) {
                    throw ForbiddenException("Only the author or the Founder can recall a message.")
                }
                val recalled = repositories.messages.update(message.copy(text = "", recalled = true))
                // Tell everyone in the room right away so the message flips to its "recalled" notice
                // live, the same way new replies propagate (DESIGN §6, §9).
                chatHub.broadcast(roomId, Outgoing.Recalled(roomId = roomId, messageId = recalled.id))
                call.respond(
                    FeedMessage(
                        id = recalled.id,
                        roomId = recalled.roomId,
                        authorId = recalled.authorId,
                        authorName = recalled.authorName.ifBlank {
                            authorName(recalled.authorId, roomId, repositories, identities)
                        },
                        authorPhotoUrl = roomPhotoUrl(recalled.authorId, roomId, repositories, identities),
                        text = recalled.text,
                        createdAt = recalled.createdAt,
                        replyCount = threadService.replyCount(recalled.id),
                        recalled = recalled.recalled,
                    ),
                )
            }

            // Recall a thread reply (DESIGN §6, §9): same as a message recall, but scoped to one
            // reply inside a thread. Clears the reply's text and flags it recalled so the UI shows
            // a "message recalled" notice in its place. Only the reply's own author or the Founder
            // may do this. The rest of the thread (and the parent message) are left untouched.
            post("/rooms/{roomId}/messages/{messageId}/replies/{replyId}/recall") {
                val caller = call.caller()
                val userId = requireMember(caller)
                val roomId = call.requireRoomId()
                val messageId = call.parameters["messageId"].orEmpty()
                val replyId = call.parameters["replyId"].orEmpty()
                require(replyId.isNotBlank()) { "replyId required" }
                val reply = repositories.threads.get(replyId)
                    ?: throw IllegalArgumentException("That reply does not exist.")
                require(reply.parentMessageId == messageId) { "That reply is not in this thread." }
                val parent = repositories.messages.get(messageId)
                    ?: throw IllegalArgumentException("That message does not exist.")
                require(parent.roomId == roomId) { "That reply is not in this community." }
                if (reply.authorId != userId && !caller.role.isAdmin) {
                    throw ForbiddenException("Only the author or the Founder can recall a reply.")
                }
                val recalled = repositories.threads.update(reply.copy(text = "", recalled = true))
                // Tell everyone in the room right away so the reply flips to its "recalled" notice
                // live, the same way new replies propagate (DESIGN §6, §9).
                chatHub.broadcast(
                    roomId,
                    Outgoing.Recalled(roomId = roomId, messageId = messageId, replyId = recalled.id),
                )
                call.respond(
                    ThreadReplyView(
                        id = recalled.id,
                        parentMessageId = recalled.parentMessageId,
                        replyToId = recalled.replyToId,
                        authorId = recalled.authorId,
                        authorName = recalled.authorName.ifBlank {
                            authorName(recalled.authorId, roomId, repositories, identities)
                        },
                        authorPhotoUrl = roomPhotoUrl(recalled.authorId, roomId, repositories, identities),
                        text = recalled.text,
                        createdAt = recalled.createdAt,
                        recalled = recalled.recalled,
                    ),
                )
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

            // Founder-only: recent service searches, newest first, so the founder can see what
            // services people are looking for (mirrors room searches). `limit` caps the result.
            get("/founder/service-searches") {
                requireFounder(call.caller())
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val searches = repositories.serviceSearches.recent(limit)
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

            // Founder-only: list the active (unredeemed) invite codes, newest first, so the UI can
            // offer to re-share or delete them. The filtering and ordering are done by the data
            // store itself (not in application code) so already-used codes are never loaded.
            get("/founder/invites") {
                requireFounder(call.caller())
                val invites = repositories.invites.active()
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

            // --- Services & Feedback (authenticated writes) ---

            // Create a service from a room. The provider is the caller and must be a Supporter
            // (server-side validation): only Supporters/Founder offer services. The new service id
            // is appended to the provider's `services` list.
            post("/rooms/{roomId}/services") {
                val userId = requireMember(call.caller())
                val roomId = call.requireRoomId()
                val room = repositories.rooms.get(roomId)
                    ?: throw IllegalArgumentException("That community does not exist.")
                val user = repositories.users.get(userId)
                    ?: throw IllegalArgumentException("Unknown user")
                require(user.role == Role.SUPPORTER || user.role == Role.FOUNDER) {
                    "Only Supporters can host adventures."
                }
                val req = call.receive<ServiceCreate>()
                require(req.emoji.isNotBlank()) { "An emoji is required." }
                require(req.name.isNotBlank()) { "An adventure name is required." }
                val service = repositories.services.create(
                    Service(
                        id = UUID.randomUUID().toString(),
                        providerId = userId,
                        roomId = room.id,
                        emoji = req.emoji.trim(),
                        name = req.name.trim(),
                        description = req.description.trim(),
                        price = req.price.coerceAtLeast(0),
                        included = req.included.trim(),
                        notIncluded = req.notIncluded.trim(),
                        toBring = req.toBring.trim(),
                        notToBring = req.notToBring.trim(),
                        destinations = sanitizeDestinations(req.destinations),
                        activities = sanitizeActivities(req.activities),
                        cancellationPolicy = req.cancellationPolicy.trim()
                            .ifBlank { Service.DEFAULT_CANCELLATION_POLICY },
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                repositories.users.update(user.copy(services = user.services + service.id))
                call.respond(service.toServiceView(repositories))
            }

            // The caller's own services across all rooms — including unlisted ones, so they can
            // manage (edit / relist / delete) them from their "Adventures hosted" screen.
            get("/services/mine") {
                val userId = requireMember(call.caller())
                val mine = repositories.services.byProvider(userId)
                    .sortedByDescending { it.createdAt }
                    .map { it.toServiceView(repositories) }
                call.respond(mine)
            }

            // Edit a service (emoji, name, description, price). Allowed for the provider or Founder.
            put("/services/{id}") {
                val caller = call.caller()
                val userId = requireMember(caller)
                val serviceId = call.parameters["id"].orEmpty()
                val service = repositories.services.get(serviceId)
                    ?: throw IllegalArgumentException("That adventure does not exist.")
                require(service.providerId == userId || caller.role.isAdmin) {
                    "You cannot edit this adventure."
                }
                val req = call.receive<ServiceUpdate>()
                require(req.emoji.isNotBlank()) { "An emoji is required." }
                require(req.name.isNotBlank()) { "An adventure name is required." }
                val updated = repositories.services.update(
                    service.copy(
                        emoji = req.emoji.trim(),
                        name = req.name.trim(),
                        description = req.description.trim(),
                        price = req.price.coerceAtLeast(0),
                        included = req.included.trim(),
                        notIncluded = req.notIncluded.trim(),
                        toBring = req.toBring.trim(),
                        notToBring = req.notToBring.trim(),
                        destinations = sanitizeDestinations(req.destinations),
                        activities = sanitizeActivities(req.activities),
                        cancellationPolicy = req.cancellationPolicy.trim()
                            .ifBlank { Service.DEFAULT_CANCELLATION_POLICY },
                    ),
                )
                call.respond(updated.toServiceView(repositories))
            }

            // Unlist a service (make it invisible in the public search) without deleting it.
            post("/services/{id}/unlist") {
                call.setServiceListed(repositories, listed = false)
            }

            // Relist a previously unlisted service.
            post("/services/{id}/relist") {
                call.setServiceListed(repositories, listed = true)
            }

            // Delete a service (hard delete). Allowed for the provider or Founder. The id is also
            // removed from the provider's `services` list.
            delete("/services/{id}") {
                val caller = call.caller()
                val userId = requireMember(caller)
                val serviceId = call.parameters["id"].orEmpty()
                val service = repositories.services.get(serviceId)
                    ?: throw IllegalArgumentException("That adventure does not exist.")
                require(service.providerId == userId || caller.role.isAdmin) {
                    "You cannot delete this adventure."
                }
                repositories.services.delete(serviceId)
                repositories.users.get(service.providerId)?.let { provider ->
                    repositories.users.update(
                        provider.copy(services = provider.services.filterNot { it == serviceId }),
                    )
                }
                call.respond(mapOf("deleted" to serviceId))
            }

            // Add a photo to a service (issue: "upload adventure photos"). Allowed for the provider
            // or a Founder. The new photo is prepended so the most recent one shows first.
            post("/services/{id}/photos") {
                val caller = call.caller()
                val userId = requireMember(caller)
                val serviceId = call.parameters["id"].orEmpty()
                val service = repositories.services.get(serviceId)
                    ?: throw IllegalArgumentException("That adventure does not exist.")
                require(service.providerId == userId || caller.role.isAdmin) {
                    "You cannot add photos to this adventure."
                }
                val req = call.receive<AddServicePhotoRequest>()
                val photoUrl = req.url.trim()
                require(photoUrl.isNotBlank()) { "A photo URL is required." }
                val photo = ProfilePhoto(
                    url = photoUrl,
                    message = req.message.trim(),
                    createdAt = System.currentTimeMillis(),
                )
                val updated = repositories.services.update(
                    service.copy(photos = listOf(photo) + service.photos),
                )
                call.respond(updated.toServiceView(repositories))
            }

            // Update one of a service's photos (replace image and/or caption). Allowed for the
            // provider or a Founder.
            put("/services/{id}/photos") {
                val caller = call.caller()
                val userId = requireMember(caller)
                val serviceId = call.parameters["id"].orEmpty()
                val service = repositories.services.get(serviceId)
                    ?: throw IllegalArgumentException("That adventure does not exist.")
                require(service.providerId == userId || caller.role.isAdmin) {
                    "You cannot edit photos on this adventure."
                }
                val req = call.receive<UpdatePhotoRequest>()
                val oldUrl = req.originalUrl.trim()
                val photoUrl = req.url.trim()
                require(oldUrl.isNotBlank()) { "The original photo URL is required." }
                require(photoUrl.isNotBlank()) { "A photo URL is required." }
                val index = service.photos.indexOfFirst { it.url == oldUrl }
                require(index >= 0) { "No such photo" }
                val existing = service.photos[index]
                val photos = service.photos.toMutableList()
                photos[index] = existing.copy(url = photoUrl, message = req.message.trim())
                val updated = repositories.services.update(service.copy(photos = photos))
                call.respond(updated.toServiceView(repositories))
            }

            // Delete one of a service's photos by its URL (`?url=`). Allowed for the provider or a
            // Founder.
            delete("/services/{id}/photos") {
                val caller = call.caller()
                val userId = requireMember(caller)
                val serviceId = call.parameters["id"].orEmpty()
                val service = repositories.services.get(serviceId)
                    ?: throw IllegalArgumentException("That adventure does not exist.")
                require(service.providerId == userId || caller.role.isAdmin) {
                    "You cannot delete photos from this adventure."
                }
                val url = call.request.queryParameters["url"].orEmpty()
                require(url.isNotBlank()) { "url required" }
                val remaining = service.photos.filterNot { it.url == url }
                require(remaining.size < service.photos.size) { "No such photo" }
                val updated = repositories.services.update(service.copy(photos = remaining))
                call.respond(updated.toServiceView(repositories))
            }

            // Add public feedback to a service. Any member may leave feedback.
            post("/services/{id}/feedback") {
                val userId = requireMember(call.caller())
                val serviceId = call.parameters["id"].orEmpty()
                repositories.services.get(serviceId)
                    ?: throw IllegalArgumentException("That adventure does not exist.")
                val req = call.receive<FeedbackCreate>()
                require(req.emoji.isNotBlank()) { "An emoji is required." }
                val feedback = repositories.feedback.create(
                    Feedback(
                        id = UUID.randomUUID().toString(),
                        serviceId = serviceId,
                        userId = userId,
                        emoji = req.emoji.trim(),
                        message = req.message.trim(),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                call.respond(feedback.toFeedbackView(repositories))
            }

            // Delete feedback (hard delete). Allowed for the service provider, the Founder, or the
            // author of the feedback.
            delete("/feedback/{id}") {
                val caller = call.caller()
                val userId = requireMember(caller)
                val feedbackId = call.parameters["id"].orEmpty()
                val feedback = repositories.feedback.get(feedbackId)
                    ?: throw IllegalArgumentException("That feedback does not exist.")
                val service = repositories.services.get(feedback.serviceId)
                val isProvider = service?.providerId == userId
                val isAuthor = feedback.userId == userId
                val isFounder = caller.role.isAdmin
                require(isProvider || isAuthor || isFounder) {
                    "You cannot delete this feedback."
                }
                repositories.feedback.delete(feedbackId)
                call.respond(mapOf("deleted" to feedbackId))
            }

            // Contact a service provider: open (or reuse) a direct chat scoped to the service's
            // room and post the caller's opening message, then return the chat coordinates so the
            // client can navigate straight to the conversation (DESIGN §7).
            post("/services/{id}/contact") {
                val userId = requireMember(call.caller())
                val serviceId = call.parameters["id"].orEmpty()
                val service = repositories.services.get(serviceId)
                    ?: throw IllegalArgumentException("That adventure does not exist.")
                val req = call.receive<ContactProviderRequest>()
                val message = req.message.trim()
                require(message.isNotBlank()) { "A message is required." }
                val chat = privateChatService.openOrStart(userId, service.providerId, service.roomId)
                // Append a hint so the provider knows which service prompted the message.
                privateChatService.post(chat.id, userId, "$message — Sent from ${service.name}")
                val providerName = authorName(service.providerId, service.roomId, repositories, identities)
                call.respond(
                    ContactProviderResponse(
                        roomId = service.roomId,
                        providerId = service.providerId,
                        providerName = providerName,
                    ),
                )
            }

            // --- Founder-only invite request management ---

            // Founder-only: pending invite requests (dismissed/resolved are never listed). The count
            // of this list backs the admin badge.
            get("/founder/invite-requests") {
                requireFounder(call.caller())
                val pending = repositories.inviteRequests.all()
                    .filter { it.status == InviteStatus.PENDING }
                    .sortedByDescending { it.createdAt }
                    .map { InviteRequestView(it.id, it.name, it.zaloNumber, it.description, it.status, it.createdAt) }
                call.respond(pending)
            }

            // Founder-only: mark an invite request resolved (the founder handled it manually).
            post("/founder/invite-requests/{id}/resolve") {
                requireFounder(call.caller())
                val id = call.parameters["id"].orEmpty()
                val request = repositories.inviteRequests.get(id)
                    ?: throw IllegalArgumentException("That invite request does not exist.")
                repositories.inviteRequests.update(request.copy(status = InviteStatus.RESOLVED))
                call.respond(mapOf("id" to id, "status" to InviteStatus.RESOLVED.name))
            }

            // Founder-only: dismiss an invite request.
            post("/founder/invite-requests/{id}/dismiss") {
                requireFounder(call.caller())
                val id = call.parameters["id"].orEmpty()
                val request = repositories.inviteRequests.get(id)
                    ?: throw IllegalArgumentException("That invite request does not exist.")
                repositories.inviteRequests.update(request.copy(status = InviteStatus.DISMISSED))
                call.respond(mapOf("id" to id, "status" to InviteStatus.DISMISSED.name))
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

            // Founder-only hard removal (MEMBER_MANAGEMENT.md): permanently delete a member's
            // account. Distinct from a demote-to-Seeker soft removal — the row is gone for good.
            // The Founder cannot remove their own account, so the platform always keeps an admin.
            delete("/founder/members/{id}") {
                val founderId = requireFounder(call.caller())
                val userId = call.parameters["id"].orEmpty()
                require(userId.isNotBlank()) { "id required" }
                require(userId != founderId) { "The Founder cannot remove their own account." }
                val user = repositories.users.get(userId)
                    ?: throw IllegalArgumentException("That member does not exist.")
                require(user.role != Role.FOUNDER) { "The Founder's account cannot be removed." }
                repositories.users.delete(userId)
                call.respond(mapOf("removed" to userId))
            }

            // Founder-only password reset (MEMBER_MANAGEMENT.md): set a member's sign-in password on
            // their behalf (e.g. when they are locked out), without knowing the current one. The
            // Founder's authority is the gate; the hash is never returned.
            post("/founder/members/{id}/password") {
                val founderId = requireFounder(call.caller())
                val userId = call.parameters["id"].orEmpty()
                require(userId.isNotBlank()) { "id required" }
                require(userId != founderId) {
                    "Use your own profile to change the Founder password."
                }
                val req = call.receive<ResetPasswordRequest>()
                val updated = passwordService.resetPassword(userId, req.newPassword)
                call.respond(updated.toMemberView(economicsService))
            }

            // Founder-only name change (MEMBER_MANAGEMENT.md): set a member's display name on their
            // behalf. The display name is how the Founder ties an account to the verified person
            // IRL, so only the Founder may change it. The Founder can also change their own name here.
            patch("/founder/members/{id}/name") {
                val founderId = requireFounder(call.caller())
                val userId = call.parameters["id"].orEmpty()
                require(userId.isNotBlank()) { "id required" }
                val req = call.receive<SetNameRequest>()
                val displayName = req.displayName.trim()
                require(displayName.isNotBlank()) { "A name is required." }
                val user = repositories.users.get(userId)
                    ?: throw IllegalArgumentException("That member does not exist.")
                val updated = repositories.users.update(user.copy(displayName = displayName))
                call.respond(updated.toMemberView(economicsService))
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
    /** Text of the room's most recent message, loaded with the list; null if the room has none. */
    val lastMessageText: String? = null,
    /** The most recent message's room-scoped sender name; null if the room has no messages. */
    val lastMessageSender: String? = null,
    /** Unix epoch millis of the most recent message; null if the room has no messages. */
    val lastMessageAt: Long? = null,
    /** Room photos (issue: "Community photos will show up visibly in the community lists..."). */
    val photos: List<ProfilePhoto> = emptyList(),
    /** Markdown-supported community description (issue: "Two room upgrades"). */
    val description: String = "",
)

/** A person currently in a room, with intents + languages shown next to them (DESIGN §6). */
@Serializable
data class OccupantView(
    /** The occupant's persistent user id, used to start a private chat (DESIGN §7). */
    val userId: String = "",
    val name: String,
    /** The member's persistent display name, shown on the detail sheet alongside their role. */
    val displayName: String = "",
    val avatarUrl: String,
    val role: Role,
    val intents: List<String> = emptyList(),
    val languages: List<String>,
    /** Free-text interest tags shown on the detail sheet (DESIGN §4). */
    val interests: List<String> = emptyList(),
    /** Plain-text introduction shown below interests on the detail sheet. */
    val introduction: String = "",
    /** Skills (emoji + name + level 0–5) shown under photos on the detail sheet. */
    val skills: List<Skill> = emptyList(),
    /** Time frame(s) the member is often available offline, shown on the detail sheet (DESIGN §4). */
    val offlineSchedule: List<String> = emptyList(),
    /** "Available for chat" indicator for active Supporters (DESIGN §7). */
    val available: Boolean = false,
    /** The member's main profile photo URL; empty when they have none. Drives the avatar. */
    val photoUrl: String = "",
    /** All profile photos (main first, then previous), for the room-profile sheet's photo cards. */
    val photos: List<ProfilePhoto> = emptyList(),
    /**
     * True when the person currently has a live WebSocket session in this room. Joined members who
     * are offline still appear in the list with [online] = false (issue: "Join / Leave a community").
     */
    val online: Boolean = true,
    /** True when the person has durable membership in this community. */
    val member: Boolean = false,
)

/** Whether the caller has joined a community (`GET/POST .../membership`, `.../join`, `.../leave`). */
@Serializable
data class MembershipResponse(
    val joined: Boolean,
    val joinedAt: Long? = null,
)

/** Public-facing view of a user's own persistent profile (DESIGN §4). */
@Serializable
data class ProfileResponse(
    val id: String,
    val role: Role,
    val displayName: String,
    val interests: List<String>,
    /** Plain-text introduction shown below interests. */
    val introduction: String = "",
    /** Skills (emoji + name + level 0–5) shown under photos. */
    val skills: List<Skill> = emptyList(),
    val intents: List<String> = emptyList(),
    val languages: List<String>,
    val offlineSchedule: List<String>,
    val supporterUntil: Long?,
    val capabilities: List<Capability>,
    /** The login-only handle used as the username for password sign-in (PASSWORD_AUTH.md §2C). */
    val handle: String? = null,
    /** True once the member has set a durable sign-in password (PASSWORD_AUTH.md §2B). */
    val passwordSet: Boolean = false,
    /** The member's current (main) profile photo; null until they upload one. */
    val photo: ProfilePhoto? = null,
    /** Previous profile photos, newest first (issue: "Profile and community profile photos"). */
    val previousPhotos: List<ProfilePhoto> = emptyList(),
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
    /** True once the author (or the Founder) recalled the message; its [text] is then blank. */
    val recalled: Boolean = false,
    /** The author's main profile photo URL (empty when they have none), for their avatar. */
    val authorPhotoUrl: String = "",
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
    /** True once the author (or the Founder) recalled the reply; its [text] is then blank. */
    val recalled: Boolean = false,
    /** The author's main profile photo URL (empty when they have none), for their avatar. */
    val authorPhotoUrl: String = "",
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
    val intents: List<String> = emptyList(),
    /** Language codes the member speaks. */
    val languages: List<String> = emptyList(),
    /** The member's free-text introduction (formerly "interests"). */
    val introduction: List<String> = emptyList(),
    /** The member's main profile photo URL; empty when they have none. Drives the avatar. */
    val photoUrl: String = "",
)

/** Body for a founder-only manual tier change (MEMBER_MANAGEMENT.md B). */
@Serializable
data class SetTierRequest(val role: Role)

/** Body for a founder-only password reset on a member's behalf (MEMBER_MANAGEMENT.md). */
@Serializable
data class ResetPasswordRequest(val newPassword: String)

/** Body for a founder-only display-name change on a member's behalf (MEMBER_MANAGEMENT.md). */
@Serializable
data class SetNameRequest(val displayName: String)

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
    photoUrl = photo?.url ?: "",
)

private fun User.toProfileResponse(): ProfileResponse = ProfileResponse(
    id = id,
    role = role,
    displayName = displayName,
    interests = interests,
    introduction = introduction,
    skills = skills,
    intents = intents,
    languages = languages,
    offlineSchedule = offlineSchedule,
    supporterUntil = supporterUntil,
    capabilities = CapabilityMatrix.capabilities(role).toList(),
    handle = handle,
    // Never expose the hash itself — only whether a password exists (PASSWORD_AUTH.md §2E).
    passwordSet = passwordHash != null,
    photo = photo,
    previousPhotos = previousPhotos,
)

/**
 * Flip a service's public visibility (unlist/relist). Allowed for the provider or Founder; responds
 * with the refreshed [ServiceView].
 */
private suspend fun ApplicationCall.setServiceListed(repositories: Repositories, listed: Boolean) {
    val caller = caller()
    val userId = requireMember(caller)
    val serviceId = parameters["id"].orEmpty()
    val service = repositories.services.get(serviceId)
        ?: throw IllegalArgumentException("That adventure does not exist.")
    require(service.providerId == userId || caller.role.isAdmin) {
        "You cannot change this adventure."
    }
    val updated = repositories.services.update(service.copy(listed = listed))
    respond(updated.toServiceView(repositories))
}

/** Enrich a [Service] with its room name and provider details for the client (search + detail). */
private fun Service.toServiceView(repositories: Repositories): ServiceView {
    val room = repositories.rooms.get(roomId)
    val provider = repositories.users.get(providerId)
    return ServiceView(
        id = id,
        emoji = emoji,
        name = name,
        description = description,
        roomId = roomId,
        roomName = room?.name ?: roomId,
        roomEmoji = room?.emoji.orEmpty(),
        providerId = providerId,
        providerName = provider?.displayName ?: providerId,
        providerRole = provider?.role ?: Role.SHARER,
        providerLanguages = provider?.languages ?: emptyList(),
        providerInterests = provider?.interests ?: emptyList(),
        price = price,
        included = included,
        notIncluded = notIncluded,
        toBring = toBring,
        notToBring = notToBring,
        destinations = destinations,
        activities = activities,
        cancellationPolicy = cancellationPolicy,
        listed = listed,
        photos = photos,
        createdAt = createdAt,
    )
}

/** Trim destination fields and drop fully-empty stops so the stored itinerary stays tidy. */
private fun sanitizeDestinations(items: List<ServiceDestination>): List<ServiceDestination> =
    items.map { dest ->
        ServiceDestination(
            name = dest.name.trim(),
            address = dest.address.trim(),
            url = dest.url.trim(),
            photos = dest.photos,
        )
    }.filter { it.name.isNotEmpty() || it.address.isNotEmpty() || it.url.isNotEmpty() || it.photos.isNotEmpty() }

/** Trim activity fields and drop fully-empty entries. */
private fun sanitizeActivities(items: List<ServiceActivity>): List<ServiceActivity> =
    items.map { activity ->
        ServiceActivity(
            name = activity.name.trim(),
            text = activity.text.trim(),
            photos = activity.photos,
        )
    }.filter { it.name.isNotEmpty() || it.text.isNotEmpty() || it.photos.isNotEmpty() }

/** Enrich a [Feedback] with the author's display name + role (for the name badge). */
private fun Feedback.toFeedbackView(repositories: Repositories): FeedbackView {
    val author = repositories.users.get(userId)
    return FeedbackView(
        id = id,
        serviceId = serviceId,
        userId = userId,
        authorName = author?.displayName ?: userId,
        authorRole = author?.role ?: Role.SHARER,
        emoji = emoji,
        message = message,
        createdAt = createdAt,
    )
}

/**
 * Build a room occupant view for [userId], combining the room identity with main-profile facets and
 * the caller's live/membership flags (issue: "Join / Leave a community").
 */
private suspend fun toOccupantView(
    userId: String,
    roomId: String,
    repositories: Repositories,
    identities: IdentityService,
    privateChatService: PrivateChatService,
    online: Boolean,
    member: Boolean,
): OccupantView? {
    val user = repositories.users.get(userId) ?: return null
    val identity = identities.getOrCreate(userId, roomId)
    return OccupantView(
        userId = userId,
        // An empty room identity falls back to the member's main profile name (DESIGN §4, §5).
        name = identity.name.ifBlank { user.displayName },
        displayName = user.displayName,
        avatarUrl = identity.avatarUrl,
        role = user.role,
        // Per-room overrides win over the main profile; a null override inherits (DESIGN §5).
        intents = identity.intents ?: user.intents,
        languages = identity.languages ?: user.languages,
        interests = identity.interests ?: user.interests,
        introduction = identity.introduction ?: user.introduction,
        skills = identity.skills ?: user.skills,
        offlineSchedule = identity.offlineSchedule ?: user.offlineSchedule,
        // "Available for chat" indicator for active Supporters (DESIGN §7).
        available = privateChatService.isAvailable(userId),
        // The main profile photo drives the avatar. The sheet's photo cards list this room's photos
        // first (issue: "Community profile photos"), then the full main-profile set (main + previous).
        photoUrl = roomPhotoUrl(userId, roomId, repositories, identities),
        photos = identity.photos + listOfNotNull(user.photo) + user.previousPhotos,
        online = online,
        member = member,
    )
}

/**
 * Resolve a message/reply author's room-scoped display name (DESIGN §5) so the public feed and
 * thread sheets never surface a raw user id. A room identity without a name of its own falls back
 * to the member's main profile name. Anonymous (non-member) authors keep their id.
 */
private suspend fun authorName(
    authorId: String,
    roomId: String,
    repositories: Repositories,
    identities: IdentityService,
): String =
    repositories.users.get(authorId)?.let { user ->
        identities.getOrCreate(authorId, roomId).name.ifBlank { user.displayName }
    } ?: authorId

/**
 * Resolve the avatar photo URL to show for a member in a room context (issue: "everything community
 * related ... should take the latest photo from the community profile if there is one, otherwise fall
 * back to the user profile"). Prefers the room identity's latest photo, then the member's main
 * profile photo; empty when neither exists (non-member / no photo).
 */
private suspend fun roomPhotoUrl(
    userId: String,
    roomId: String,
    repositories: Repositories,
    identities: IdentityService,
): String {
    val roomPhoto = identities.getOrCreate(userId, roomId).photos.firstOrNull()?.url
    return roomPhoto ?: repositories.users.get(userId)?.photo?.url ?: ""
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
            val authorName = session.userId?.let { authorName(it, frame.roomId, repositories, identities) }
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
                    // Room-first avatar photo (room profile latest, else main profile).
                    authorPhotoUrl = roomPhotoUrl(reply.authorId, frame.roomId, repositories, identities),
                ),
            )

            // Route thread reply notifications to the thread starter and all other participants
            val parent = repositories.messages.get(frame.parentMessageId)
            if (parent != null) {
                val previousReplies = repositories.threads.byParent(parent.id)
                val room = repositories.rooms.get(frame.roomId)
                if (room != null) {
                    val recipients = (listOf(parent.authorId) + previousReplies.map { it.authorId })
                        .distinct()
                        .filter { it != authorId && it.isNotBlank() } // Do not notify the replier themselves
                    
                    val parentAuthorName = parent.authorName.ifBlank {
                        authorName(parent.authorId, room.id, repositories, identities)
                    }

                    recipients.forEach { userId ->
                        chatHub.sendToUser(
                            userId,
                            Outgoing.MessageReplyReceived(
                                roomId = room.id,
                                roomName = room.name,
                                roomEmoji = room.emoji,
                                parentMessageId = parent.id,
                                parentMessageText = parent.text,
                                parentMessageAuthorName = parentAuthorName,
                                replyId = reply.id,
                                replyText = reply.text,
                                replyAuthorName = reply.authorName,
                                replyCreatedAt = reply.createdAt,
                                replyCount = threadService.replyCount(parent.id)
                            )
                        )
                    }
                }
            }
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
            val authorName = session.userId?.let { authorName(it, frame.roomId, repositories, identities) }
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
                Outgoing.Said(
                    message.roomId,
                    message.authorId,
                    message.text,
                    message.createdAt,
                    authorName,
                    message.id,
                    // Room-first avatar photo (room profile latest, else main profile).
                    authorPhotoUrl = roomPhotoUrl(message.authorId, frame.roomId, repositories, identities),
                ),
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
                session.send(Outgoing.Error(e.message ?: "Cannot start direct chat"))
                return
            }
            // Each party sees the other's room-scoped display name (DESIGN §5, §7), falling back
            // to the main profile name when the room identity has none.
            val initiatorName = authorName(initiatorId, frame.roomId, repositories, identities)
            val targetName = authorName(frame.targetUserId, frame.roomId, repositories, identities)
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
                session.send(Outgoing.Error(e.message ?: "Cannot end direct chat"))
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
