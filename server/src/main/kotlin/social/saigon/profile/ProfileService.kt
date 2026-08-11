package social.saigon.profile

import social.saigon.domain.Capability
import social.saigon.domain.EmojiOptions
import social.saigon.domain.ProfilePhoto
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
        val intents = update.intents?.let(::normalizeIntents) ?: user.intents

        return users.update(
            user.copy(
                interests = interests,
                languages = languages,
                offlineSchedule = schedule,
                intents = intents,
            ),
        )
    }

    /**
     * Set the user's profile photo (issue: "Profile and room profile photos").
     *
     * When [url] differs from the current photo, the new photo becomes the main one and the
     * previous main photo (if any) moves to the head of [User.previousPhotos]. When [url] matches
     * the current photo, only its [message] (the user's caption) is updated — no rotation happens.
     */
    fun setPhoto(userId: String, url: String, message: String): User {
        val user = get(userId)
        require(user.role.can(Capability.EDIT_PROFILE)) { "Role ${user.role} cannot edit a profile" }
        val photoUrl = url.trim()
        require(photoUrl.isNotEmpty()) { "A photo URL is required" }
        val caption = message.trim()
        val current = user.photo
        val updated = if (current != null && current.url == photoUrl) {
            user.copy(photo = current.copy(message = caption))
        } else {
            user.copy(
                photo = ProfilePhoto(url = photoUrl, message = caption, createdAt = System.currentTimeMillis()),
                previousPhotos = listOfNotNull(current) + user.previousPhotos,
            )
        }
        return users.update(updated)
    }

    /**
     * Update one of the user's *previous* profile photos identified by [originalUrl]: replace the
     * image URL and/or caption in place. The current main photo is edited via [setPhoto] instead.
     */
    fun updatePreviousPhoto(userId: String, originalUrl: String, url: String, message: String): User {
        val user = get(userId)
        require(user.role.can(Capability.EDIT_PROFILE)) { "Role ${user.role} cannot edit a profile" }
        val oldUrl = originalUrl.trim()
        val photoUrl = url.trim()
        require(oldUrl.isNotEmpty()) { "The original photo URL is required" }
        require(photoUrl.isNotEmpty()) { "A photo URL is required" }
        val caption = message.trim()
        val index = user.previousPhotos.indexOfFirst { it.url == oldUrl }
        require(index >= 0) { "No such photo" }
        val existing = user.previousPhotos[index]
        val updatedPhotos = user.previousPhotos.toMutableList()
        updatedPhotos[index] = existing.copy(url = photoUrl, message = caption)
        return users.update(user.copy(previousPhotos = updatedPhotos))
    }

    /**
     * Delete one of the user's *previous* profile photos by its [url]. The current main photo
     * cannot be deleted — only replaced by [setPhoto].
     */
    fun deletePhoto(userId: String, url: String): User {
        val user = get(userId)
        require(user.role.can(Capability.EDIT_PROFILE)) { "Role ${user.role} cannot edit a profile" }
        val remaining = user.previousPhotos.filterNot { it.url == url }
        require(remaining.size < user.previousPhotos.size) { "No such photo" }
        return users.update(user.copy(previousPhotos = remaining))
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

    // Languages are free-text (a launch set plus any custom languages a Supporter/Founder adds),
    // so just trim, drop blanks, and de-duplicate rather than validating against a fixed set.
    private fun normalizeLanguages(languages: List<String>): List<String> =
        languages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun normalizeSchedule(schedule: List<String>): List<String> =
        schedule.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    // Intent badges are free-text (a launch set plus any custom badges a Supporter/Founder creates),
    // so just trim, drop blanks, and de-duplicate rather than validating against a fixed enum.
    private fun normalizeIntents(intents: List<String>): List<String> =
        intents.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    companion object {
        /** Auto-suggest seed list (DESIGN §4 examples + common Saigon interests). */
        val CURATED_INTERESTS: List<String> = listOf(
            "philosophy", "parenting", "startups", "guitar", "badminton",
            "coffee", "language exchange", "travel", "music", "books",
            "photography", "cooking", "fitness", "movies", "tech",
        )
    }
}
