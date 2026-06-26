package social.saigon.repository

import social.saigon.domain.Message
import social.saigon.domain.Room

/**
 * Idempotent startup seeding (DESIGN §2, §6).
 *
 * Saigon.Social is founder-managed with no open sign-up. We deliberately do NOT pre-create the
 * founder account: the very first person to arrive claims it by signing in with a blank access
 * token (see `POST /auth/bootstrap`). We only seed the city's opening rooms so there is something
 * to browse from day one. Seeding is keyed by stable ids and only writes when the corresponding
 * record is missing, so restarts never duplicate data.
 */
/** Stable id of the founder account, claimed on first blank sign-in (DESIGN §2). */
const val FOUNDER_ID = "founder"

private data class SeededRoom(
    val id: String,
    val name: String,
    val emoji: String,
    val atmosphere: String,
    val welcome: String,
)

private val seededRooms = listOf(
    SeededRoom("coffee", "Coffee Chat", "☕", "Easy mornings, slow talk.", "Morning, Saigon. What's in your cup today?"),
    SeededRoom("lang", "Language Exchange (EN/VI)", "🌱", "Practice together, no pressure.", "Chào! Drop a sentence in your second language."),
    SeededRoom("deep", "Deep Talks", "🧠", "Heavier topics, kind people.", "What's been on your mind lately?"),
    SeededRoom("startup", "Startup Talk", "🚀", "Builders comparing notes.", "What are you building this week?"),
)

/**
 * Ensure the opening rooms (each with a welcome message) exist. Safe to call on every startup
 * against either the in-memory or ArangoDB repositories. The founder account is not seeded here;
 * it is claimed on the first blank sign-in via `POST /auth/bootstrap`.
 */
fun seedInitialData(repositories: Repositories) {
    seededRooms.forEach { seed ->
        if (repositories.rooms.get(seed.id) == null) {
            repositories.rooms.create(
                Room(
                    id = seed.id,
                    name = seed.name,
                    emoji = seed.emoji,
                    atmosphere = seed.atmosphere,
                    createdBy = FOUNDER_ID,
                ),
            )
        }
        val welcomeId = "${seed.id}-welcome"
        if (repositories.messages.get(welcomeId) == null) {
            repositories.messages.add(
                Message(
                    id = welcomeId,
                    roomId = seed.id,
                    authorId = "Saigon.Social",
                    text = seed.welcome,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
