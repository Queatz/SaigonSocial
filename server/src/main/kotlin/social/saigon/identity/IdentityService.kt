package social.saigon.identity

import social.saigon.ai.IdentityGenerator
import social.saigon.domain.RoomIdentity
import social.saigon.domain.RoomIdentityUpdate
import social.saigon.repository.RoomIdentityRepository

/**
 * Room-scoped, user-controlled identity management (TASKS Phase 2, DESIGN §5).
 *
 * Each (user, room) pair has its own anonymous name + abstract avatar, generated on the user's
 * first join to that room via the [IdentityGenerator] (Qwen AI in production). The identity is
 * stable and independent across rooms; it changes only when the user explicitly refreshes it.
 * There are no time-based or forced resets — the user is always in control.
 */
class IdentityService(
    private val identities: RoomIdentityRepository,
    private val generator: IdentityGenerator,
) {

    /**
     * The caller's identity in [roomId], generating and persisting one on first join.
     *
     * Idempotent: repeated calls return the same stored identity until [refresh] is invoked, so a
     * user keeps a stable name/avatar within a room across reconnects (DESIGN §5, "No time-based
     * reset").
     */
    suspend fun getOrCreate(userId: String, roomId: String): RoomIdentity =
        identities.get(userId, roomId) ?: generateAndStore(userId, roomId)

    /**
     * Instantly regenerate the caller's name + avatar in [roomId] — the "Refresh my name & avatar
     * in this room" action (DESIGN §5). Always produces a fresh identity and replaces the stored
     * one. User-controlled only.
     */
    suspend fun refresh(userId: String, roomId: String): RoomIdentity =
        generateAndStore(userId, roomId)

    /**
     * Apply a caller's room-scoped profile edits (DESIGN §4, §5). A non-blank [RoomIdentityUpdate.name]
     * renames the room identity; the remaining fields wholesale-replace the per-room overrides, with
     * a null clearing the override so that facet inherits from the main profile again. Creates the
     * identity first if the caller has never joined the room.
     */
    suspend fun updateProfile(userId: String, roomId: String, update: RoomIdentityUpdate): RoomIdentity {
        val current = getOrCreate(userId, roomId)
        return identities.put(
            current.copy(
                name = update.name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
                interests = update.interests,
                intents = update.intents,
                languages = update.languages,
                offlineSchedule = update.offlineSchedule,
                providingServices = update.providingServices,
            ),
        )
    }

    private suspend fun generateAndStore(userId: String, roomId: String): RoomIdentity {
        val generated = generator.generate("$userId@$roomId")
        // Preserve any per-room profile overrides across a name/avatar refresh — only the anonymous
        // name + avatar regenerate; the user's room-scoped customizations stay put (DESIGN §5).
        val existing = identities.get(userId, roomId)
        return identities.put(
            (existing ?: RoomIdentity(userId = userId, roomId = roomId, name = "", avatarUrl = "")).copy(
                name = generated.name,
                avatarUrl = generated.avatarUrl,
            ),
        )
    }
}
