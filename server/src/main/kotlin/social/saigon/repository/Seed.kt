package social.saigon.repository

import social.saigon.auth.PasswordHasher
import social.saigon.domain.Message
import social.saigon.domain.Role
import social.saigon.domain.Room
import social.saigon.domain.User

/**
 * Idempotent startup seeding (DESIGN §2, §6).
 *
 * Saigon.Social is founder-managed with no open sign-up. We deliberately do NOT pre-create the
 * founder account: the very first person to arrive claims it by signing in with a blank access
 * token (see `POST /auth/bootstrap`). We only seed the city's opening rooms so there is something
 * to browse from day one. Seeding is keyed by stable ids and only writes when the corresponding
 * record is missing, so restarts never duplicate data.
 */
/** Stable id of the founder account (DESIGN §2). */
const val FOUNDER_ID = "founder"

/** The founder's login handle and default password (issue: every account must have a password). */
const val FOUNDER_HANDLE = "founder"
const val FOUNDER_PASSWORD = "founder"

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
    seedFounder(repositories)
    ensureEveryAccountHasPassword(repositories)
    seededRooms.forEachIndexed { index, seed ->
        if (repositories.rooms.get(seed.id) == null) {
            repositories.rooms.create(
                Room(
                    id = seed.id,
                    name = seed.name,
                    emoji = seed.emoji,
                    atmosphere = seed.atmosphere,
                    createdBy = FOUNDER_ID,
                    // Small, descending timestamps keep the opening rooms in their listed order
                    // while any newly created room (stamped with the real epoch millis) sorts above
                    // them at the top of the list.
                    createdAt = (seededRooms.size - index).toLong(),
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

/**
 * Ensure a Founder account exists with the known credentials `founder` / `founder` so the platform
 * always has an admin who can sign in — even on a fresh data store or after a restart. The password
 * is set directly (bypassing the interactive strength check) because these are platform-managed
 * credentials, and is (re)applied on every startup so it stays the documented `founder` value.
 */
private fun seedFounder(repositories: Repositories) {
    val founderHash = PasswordHasher.hash(FOUNDER_PASSWORD)
    val existing = repositories.users.all().firstOrNull { it.role == Role.FOUNDER }
    if (existing == null) {
        repositories.users.create(
            User(
                id = FOUNDER_ID,
                role = Role.FOUNDER,
                displayName = "Founder",
                handle = FOUNDER_HANDLE,
                passwordHash = founderHash,
                passwordSetAt = System.currentTimeMillis(),
            ),
        )
    } else {
        repositories.users.update(
            existing.copy(
                handle = existing.handle ?: FOUNDER_HANDLE,
                passwordHash = founderHash,
                passwordSetAt = System.currentTimeMillis(),
            ),
        )
    }
}

/**
 * Backfill a password for any member account that somehow has none, so no account is ever left
 * without one. A passwordless account is given a default password equal to its login handle (or id
 * as a last resort); the member can change it later from their profile.
 */
private fun ensureEveryAccountHasPassword(repositories: Repositories) {
    repositories.users.all()
        .filter { it.role != Role.SEEKER && it.passwordHash == null }
        .forEach { user ->
            val default = user.handle ?: user.id
            repositories.users.update(
                user.copy(
                    passwordHash = PasswordHasher.hash(default),
                    passwordSetAt = System.currentTimeMillis(),
                ),
            )
        }
}
