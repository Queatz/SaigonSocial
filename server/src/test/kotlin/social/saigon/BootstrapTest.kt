package social.saigon

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import social.saigon.auth.AuthConfig
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.repository.FOUNDER_ID
import social.saigon.repository.Repositories

/**
 * Founder bootstrap (DESIGN §2): the first blank sign-in claims the Founder account, and the door
 * closes once any member exists.
 */
class BootstrapTest {

    @Test
    fun blankSignInClaimsFounderWhenNoMembersExist() = testApplication {
        val repositories = Repositories()
        application { module(repositories = repositories, authConfig = AuthConfig(secret = "test-secret")) }

        val response = client.post("/auth/bootstrap")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(FOUNDER_ID))
        assertTrue(body.contains("\"token\""))

        // The founder account is now persisted.
        val founder = repositories.users.get(FOUNDER_ID)
        assertEquals(Role.FOUNDER, founder?.role)
    }

    @Test
    fun bootstrapIsForbiddenOnceAMemberExists() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "alice", role = Role.SHARER, displayName = "Alice"))
        application { module(repositories = repositories, authConfig = AuthConfig(secret = "test-secret")) }

        val response = client.post("/auth/bootstrap")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("already has a founder"))
    }
}
