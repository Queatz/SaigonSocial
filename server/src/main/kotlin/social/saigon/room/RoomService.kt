package social.saigon.room

import java.util.UUID
import social.saigon.domain.Room
import social.saigon.domain.RoomCreate
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
        return rooms.create(
            Room(
                id = UUID.randomUUID().toString(),
                name = name,
                emoji = emoji,
                atmosphere = request.atmosphere.trim(),
                createdBy = founderId,
            ),
        )
    }

    /** Resolve a room by id, or null when it does not exist. */
    fun get(roomId: String): Room? = rooms.get(roomId)

    /** All rooms, for the room-list view. */
    fun list(): List<Room> = rooms.all()
}
