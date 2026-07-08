package social.saigon.identity

import social.saigon.ai.IdentityGenerator
import social.saigon.domain.RoomIdentity
import social.saigon.domain.RoomIdentityUpdate
import social.saigon.repository.RoomIdentityRepository

/**
 * Room-scoped, user-controlled identity management (TASKS Phase 2, DESIGN §5).
 *
 * Each (user, room) pair has its own identity. A new room profile starts out *empty* — no
 * generated name or avatar — so it falls back to the user's main profile everywhere it is shown.
 * The [IdentityGenerator] (Qwen AI in production) is only invoked when the user explicitly asks
 * for a fresh anonymous name via [refresh]. The identity is stable and independent across rooms;
 * there are no time-based or forced resets — the user is always in control.
 */
class IdentityService(
    private val identities: RoomIdentityRepository,
    private val generator: IdentityGenerator,
) {

    /**
     * The caller's identity in [roomId], persisting an empty one on first join.
     *
     * A new room profile deliberately has no name or avatar of its own: every facet — including
     * the name — falls back to the user's main profile until they customize it or [refresh] a
     * fresh anonymous name. Idempotent: repeated calls return the same stored identity, so a user
     * keeps a stable identity within a room across reconnects (DESIGN §5, "No time-based reset").
     */
    suspend fun getOrCreate(userId: String, roomId: String): RoomIdentity =
        identities.get(userId, roomId)
            ?: identities.put(RoomIdentity(userId = userId, roomId = roomId, name = "", avatarUrl = ""))

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
                // Room photos are additive, not an inherit/override facet: a null list leaves the
                // stored photos as-is, a non-null list wholesale-replaces them (issue: "Room
                // profile photos").
                photos = update.photos ?: current.photos,
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
