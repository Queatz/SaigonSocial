package social.saigon.profile

import social.saigon.domain.Capability
import social.saigon.domain.EmojiOptions
import social.saigon.domain.Languages
import social.saigon.domain.ProfileUpdate
import social.saigon.domain.User
import social.saigon.domain.can
import social.saigon.repository.UserRepository

/**
 * Persistent-profile management (TASKS Phase 1, DESIGN §4).
 *
 * Owns validation of the editable profile fields, enforces the per-role capability matrix, and
 * aggregates the private emoji tally. Seekers have no persisted profile, so any attempt to read or
 * edit one is rejected.
 */
class ProfileService(private val users: UserRepository) {

    /** The user's own persistent profile. */
    fun get(userId: String): User =
        users.get(userId) ?: throw IllegalArgumentException("Unknown user")

    /**
     * Apply a validated [ProfileUpdate]. Null fields are left untouched; provided fields are
     * trimmed, de-duplicated, and validated before persisting.
     */
    fun update(userId: String, update: ProfileUpdate): User {
        val user = get(userId)
        require(user.role.can(Capability.EDIT_PROFILE)) { "Role ${user.role} cannot edit a profile" }

        val interests = update.interests?.let(::normalizeInterests) ?: user.interests
        val languages = update.languages?.let(::normalizeLanguages) ?: user.languages
        val schedule = update.offlineSchedule?.let(::normalizeSchedule) ?: user.offlineSchedule
        val intent = update.intent ?: user.intent

        return users.update(
            user.copy(
                interests = interests,
                languages = languages,
                offlineSchedule = schedule,
                intent = intent,
            ),
        )
    }

    /**
     * Add a received emoji to the user's private tally (DESIGN §8). Returns the updated user.
     */
    fun recordReceivedEmoji(userId: String, emoji: String): User {
        require(EmojiOptions.isValid(emoji)) { "Unknown emoji" }
        val user = get(userId)
        val updated = user.emojiStats.toMutableMap()
        updated[emoji] = (updated[emoji] ?: 0) + 1
        return users.update(user.copy(emojiStats = updated))
    }

    /** The private emoji tally, visible only to the user themself (DESIGN §4, §8). */
    fun emojiStats(userId: String): Map<String, Int> = get(userId).emojiStats

    /**
     * Free-text interest auto-suggest (DESIGN §4). Returns curated suggestions matching [query];
     * an empty/blank query returns the full curated set.
     */
    fun suggestInterests(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return CURATED_INTERESTS
        return CURATED_INTERESTS.filter { it.contains(q) }
    }

    private fun normalizeInterests(interests: List<String>): List<String> {
        val cleaned = interests.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(cleaned.size <= User.MAX_INTERESTS) {
            "At most ${User.MAX_INTERESTS} interests allowed"
        }
        return cleaned
    }

    private fun normalizeLanguages(languages: List<String>): List<String> {
        val cleaned = languages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        cleaned.forEach { code ->
            require(Languages.isSupported(code)) { "Unsupported language: $code" }
        }
        return cleaned
    }

    private fun normalizeSchedule(schedule: List<String>): List<String> =
        schedule.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    companion object {
        /** Auto-suggest seed list (DESIGN §4 examples + common Saigon interests). */
        val CURATED_INTERESTS: List<String> = listOf(
            "philosophy", "parenting", "startups", "guitar", "badminton",
            "coffee", "language exchange", "travel", "music", "books",
            "photography", "cooking", "fitness", "movies", "tech",
        )
    }
}
