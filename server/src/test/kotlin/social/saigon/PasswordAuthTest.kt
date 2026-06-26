package social.saigon

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import social.saigon.auth.AuthConfig
import social.saigon.auth.PasswordHasher
import social.saigon.auth.TokenService
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.repository.Repositories

/**
 * Password sign-in (PASSWORD_AUTH.md): a member sets a durable password while signed in with their
 * token, then re-enters later with their handle + password, which mints a fresh token.
 */
class PasswordAuthTest {

    private fun authConfig() = AuthConfig(secret = "test-secret")

    @Test
    fun setPasswordThenLogInMintsAFreshToken() = testApplication {
        val repositories = Repositories()
        repositories.users.create(
            User(id = "linh", role = Role.SHARER, displayName = "Linh", handle = "linh"),
        )
        val authConfig = authConfig()
        val token = TokenService(authConfig).issueMemberToken("linh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Set a password while authenticated.
        val set = client.post("/profile/password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"hunter2024"}""")
        }
        assertEquals(HttpStatusCode.OK, set.status)
        val profile = set.bodyAsText()
        assertTrue(profile.contains("\"passwordSet\":true"))
        // The hash is never returned.
        assertFalse(profile.contains("passwordHash"))
        assertTrue(repositories.users.get("linh")!!.passwordHash != null)

        // Sign in with handle + password — no token needed, returns a fresh one.
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"linh","password":"hunter2024"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.bodyAsText().contains("\"token\""))
    }

    @Test
    fun wrongPasswordReturnsGenericUnauthorized() = testApplication {
        val repositories = Repositories()
        repositories.users.create(
            User(
                id = "linh",
                role = Role.SHARER,
                displayName = "Linh",
                handle = "linh",
                passwordHash = PasswordHasher.hash("hunter2024"),
            ),
        )
        val authConfig = authConfig()
        application { module(repositories = repositories, authConfig = authConfig) }

        val bad = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"linh","password":"nope"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        // Same generic message whether the user exists or not (no enumeration).
        val unknown = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"ghost","password":"whatever"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(bad.bodyAsText(), unknown.bodyAsText())
    }

    @Test
    fun changingPasswordRequiresTheCurrentOne() = testApplication {
        val repositories = Repositories()
        repositories.users.create(
            User(
                id = "linh",
                role = Role.SHARER,
                displayName = "Linh",
                handle = "linh",
                passwordHash = PasswordHasher.hash("hunter2024"),
                passwordSetAt = 1L,
            ),
        )
        val authConfig = authConfig()
        val token = TokenService(authConfig).issueMemberToken("linh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Missing/incorrect current password is rejected.
        val rejected = client.post("/profile/password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"newpassword1"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)

        // Correct current password lets the change through.
        val ok = client.post("/profile/password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"newpassword1","currentPassword":"hunter2024"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(PasswordHasher.verify("newpassword1", repositories.users.get("linh")!!.passwordHash!!))
    }

    @Test
    fun tooShortPasswordIsRejected() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "linh", role = Role.SHARER, displayName = "Linh", handle = "linh"))
        val authConfig = authConfig()
        val token = TokenService(authConfig).issueMemberToken("linh", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val res = client.post("/profile/password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"short"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun hasherVerifiesCorrectlyAndRejectsWrong() {
        val encoded = PasswordHasher.hash("correct horse battery staple")
        assertTrue(PasswordHasher.verify("correct horse battery staple", encoded))
        assertFalse(PasswordHasher.verify("wrong", encoded))
        assertFalse(PasswordHasher.verify("anything", "not-a-valid-encoding"))
    }
}
