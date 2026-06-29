package social.saigon.room

import java.util.UUID
import social.saigon.domain.Room
import social.saigon.domain.RoomCreate
import social.saigon.domain.RoomUpdate
import social.saigon.repository.RoomRepository

/**
 * Room creation and management (TASKS Phase 3, DESIGN §6).
 *
 * Rooms are the primary social unit and are **founder-created** — the role check lives at the
 * route layer; this service owns field validation and persistence. A room name is a single string
 * with no leading '#', and every room carries a primary emoji plus a free-text atmosphere.
 */
class RoomService(private val rooms: RoomRepository) {

    /**
     * Persist a new founder-created room after validating its fields.
     *
     * @throws IllegalArgumentException if the name is blank, starts with '#', or the emoji is blank.
     */
    fun create(founderId: String, request: RoomCreate): Room {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "Room name required" }
        require(!name.startsWith("#")) { "Room name must not start with '#'" }
        val emoji = request.emoji.trim()
        require(emoji.isNotEmpty()) { "Room emoji required" }
        val id = resolveId(request.id)
        return rooms.create(
            Room(
                id = id,
                name = name,
                emoji = emoji,
                atmosphere = request.atmosphere.trim(),
                createdBy = founderId,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Resolve the id for a new room: when the caller supplies one (the room's URL), validate it is
     * URL-safe and not already taken; otherwise assign a random id.
     *
     * @throws IllegalArgumentException if the requested id is malformed or already in use.
     */
    private fun resolveId(requested: String?): String {
        val candidate = requested?.trim()
        if (candidate.isNullOrEmpty()) return UUID.randomUUID().toString()
        require(candidate.matches(ID_PATTERN)) {
            "Room URL may only contain lowercase letters, digits and hyphens"
        }
        require(rooms.get(candidate) == null) { "That room URL is already taken" }
        return candidate
    }

    /**
     * Apply a founder edit (rename/emoji/atmosphere) to an existing room after validating fields.
     *
     * @throws IllegalArgumentException if the room is unknown or a provided field is invalid.
     */
    fun update(roomId: String, request: RoomUpdate): Room {
        val room = rooms.get(roomId) ?: throw IllegalArgumentException("Unknown room")
        val name = request.name?.trim()?.also {
            require(it.isNotEmpty()) { "Room name required" }
            require(!it.startsWith("#")) { "Room name must not start with '#'" }
        } ?: room.name
        val emoji = request.emoji?.trim()?.also {
            require(it.isNotEmpty()) { "Room emoji required" }
        } ?: room.emoji
        val atmosphere = request.atmosphere?.trim() ?: room.atmosphere
        return rooms.create(room.copy(name = name, emoji = emoji, atmosphere = atmosphere))
    }

    /** Delete a room. Idempotent: deleting an unknown room is a no-op. */
    fun delete(roomId: String) = rooms.delete(roomId)

    /** Resolve a room by id, or null when it does not exist. */
    fun get(roomId: String): Room? = rooms.get(roomId)

    /** All rooms, for the room-list view. */
    fun list(): List<Room> = rooms.all()

    private companion object {
        /** URL-safe room id: lowercase letters, digits and hyphens. */
        val ID_PATTERN = Regex("[a-z0-9-]+")
    }
}
