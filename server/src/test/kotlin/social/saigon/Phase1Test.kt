package social.saigon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import social.saigon.domain.Capability
import social.saigon.domain.CapabilityMatrix
import social.saigon.domain.Intent
import social.saigon.domain.ProfileUpdate
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.domain.can
import social.saigon.profile.ProfileService
import social.saigon.repository.InMemoryUserRepository

class Phase1Test {

    private fun service(): Pair<ProfileService, InMemoryUserRepository> {
        val repo = InMemoryUserRepository()
        return ProfileService(repo) to repo
    }

    private fun sharer(repo: InMemoryUserRepository, id: String = "u1"): User =
        repo.create(User(id = id, role = Role.SHARER, displayName = "Real Name"))

    @Test
    fun capabilityMatrixGatesRoles() {
        assertTrue(Role.SEEKER.can(Capability.VIEW_ROOMS))
        assertFalse(Role.SEEKER.can(Capability.CHAT_PUBLICLY))
        assertFalse(Role.SEEKER.can(Capability.EDIT_PROFILE))

        assertTrue(Role.SHARER.can(Capability.CHAT_PUBLICLY))
        assertTrue(Role.SHARER.can(Capability.EDIT_PROFILE))
        assertFalse(Role.SHARER.can(Capability.BE_PRIVATELY_MESSAGED))

        assertTrue(Role.SUPPORTER.can(Capability.BE_PRIVATELY_MESSAGED))
        assertFalse(Role.SUPPORTER.can(Capability.ADMINISTER))

        assertTrue(Role.FOUNDER.can(Capability.ADMINISTER))
        assertTrue(CapabilityMatrix.capabilities(Role.SEEKER).size < CapabilityMatrix.capabilities(Role.FOUNDER).size)
    }

    @Test
    fun updatesProfileFields() {
        val (svc, repo) = service()
        sharer(repo)
        val updated = svc.update(
            "u1",
            ProfileUpdate(
                interests = listOf("guitar", " coffee ", "guitar"),
                intent = Intent.DEEP_TALK,
                languages = listOf("vi", "en"),
                offlineSchedule = listOf("Mon-Fri 7-9 PM"),
            ),
        )
        // Trimmed + de-duplicated.
        assertEquals(listOf("guitar", "coffee"), updated.interests)
        assertEquals(Intent.DEEP_TALK, updated.intent)
        assertEquals(listOf("vi", "en"), updated.languages)
        assertEquals(listOf("Mon-Fri 7-9 PM"), updated.offlineSchedule)
    }

    @Test
    fun nullFieldsLeaveExistingValuesUntouched() {
        val (svc, repo) = service()
        sharer(repo)
        svc.update("u1", ProfileUpdate(interests = listOf("books"), intent = Intent.CASUAL))
        val updated = svc.update("u1", ProfileUpdate(languages = listOf("ko")))
        assertEquals(listOf("books"), updated.interests)
        assertEquals(Intent.CASUAL, updated.intent)
        assertEquals(listOf("ko"), updated.languages)
    }

    @Test
    fun rejectsTooManyInterests() {
        val (svc, repo) = service()
        sharer(repo)
        assertFailsWith<IllegalArgumentException> {
            svc.update("u1", ProfileUpdate(interests = listOf("a", "b", "c", "d", "e", "f")))
        }
    }

    @Test
    fun rejectsUnsupportedLanguage() {
        val (svc, repo) = service()
        sharer(repo)
        assertFailsWith<IllegalArgumentException> {
            svc.update("u1", ProfileUpdate(languages = listOf("xx")))
        }
    }

    @Test
    fun seekerLikeUserCannotEditProfile() {
        val (svc, repo) = service()
        repo.create(User(id = "s1", role = Role.SEEKER, displayName = ""))
        assertFailsWith<IllegalArgumentException> {
            svc.update("s1", ProfileUpdate(interests = listOf("x")))
        }
    }

    @Test
    fun emojiStatsAggregatePrivately() {
        val (svc, repo) = service()
        sharer(repo)
        svc.recordReceivedEmoji("u1", "🙏")
        svc.recordReceivedEmoji("u1", "🙏")
        svc.recordReceivedEmoji("u1", "😊")
        val stats = svc.emojiStats("u1")
        assertEquals(2, stats["🙏"])
        assertEquals(1, stats["😊"])
    }

    @Test
    fun rejectsUnknownEmoji() {
        val (svc, repo) = service()
        sharer(repo)
        assertFailsWith<IllegalArgumentException> {
            svc.recordReceivedEmoji("u1", "🍕")
        }
    }

    @Test
    fun interestSuggestionsFilterByQuery() {
        val (svc, _) = service()
        assertTrue(svc.suggestInterests("").isNotEmpty())
        assertEquals(listOf("guitar"), svc.suggestInterests("gui"))
        assertTrue(svc.suggestInterests("zzz").isEmpty())
    }
}
