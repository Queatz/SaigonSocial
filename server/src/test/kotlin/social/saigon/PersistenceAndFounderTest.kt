package social.saigon

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import social.saigon.auth.PasswordHasher
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.repository.Repositories
import social.saigon.repository.seedInitialData

/**
 * Regression coverage for the "every account has a password, founder is `founder`/`founder`" rule
 * (issue 5). Durable persistence now lives exclusively in ArangoDB (DESIGN §12); restart-survival is
 * a property of the database rather than the in-memory test doubles, so it is not exercised here.
 */
class PersistenceAndFounderTest {
    @Test
    fun seedingCreatesAFounderWhosePasswordIsFounder() {
        val repositories = Repositories()
        seedInitialData(repositories)

        val founder = repositories.users.all().firstOrNull { it.role == Role.FOUNDER }
        assertNotNull(founder, "seedInitialData must create a founder account")
        val hash = founder.passwordHash
        assertNotNull(hash, "the founder must always have a password")
        assertTrue(PasswordHasher.verify("founder", hash), "the founder password must be 'founder'")
    }

    @Test
    fun seedingBackfillsPasswordsSoNoAccountIsEverWithoutOne() {
        val repositories = Repositories()
        // A legacy member created before passwords existed.
        repositories.users.create(
            User(id = "legacy", role = Role.SHARER, displayName = "Legacy", handle = "legacy"),
        )

        seedInitialData(repositories)

        repositories.users.all()
            .filter { it.role != Role.SEEKER }
            .forEach { assertNotNull(it.passwordHash, "account ${it.id} must have a password") }
    }

    @Test
    fun seedingDoesNotReseedOrOverwriteExistingFounder() {
        val repositories = Repositories()
        seedInitialData(repositories)

        val founder = repositories.users.all().firstOrNull { it.role == Role.FOUNDER }
        assertNotNull(founder, "founder should exist")

        // Modify founder's password and handle
        val updatedFounder = founder.copy(
            handle = "modified_founder",
            passwordHash = PasswordHasher.hash("new_password"),
            passwordSetAt = 12345L
        )
        repositories.users.update(updatedFounder)

        // Seed again
        seedInitialData(repositories)

        // Verify founder details are preserved and not overwritten
        val afterSeed = repositories.users.get(founder.id)
        assertNotNull(afterSeed)
        kotlin.test.assertEquals("modified_founder", afterSeed.handle, "handle must be preserved")
        assertTrue(PasswordHasher.verify("new_password", afterSeed.passwordHash!!), "password must be preserved")
        kotlin.test.assertEquals(12345L, afterSeed.passwordSetAt, "passwordSetAt must be preserved")
    }
}
