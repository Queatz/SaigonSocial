package social.saigon

import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import social.saigon.auth.TokenService
import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.repository.Repositories

/**
 * Founder invite-code management (round-2 issue): the public bootstrap probe reports whether the
 * Founder seat is still open, and the founder can list every minted code and delete unused ones.
 */
class InviteCodeTest {

    private fun extractCode(json: String): String =
        Regex("\"code\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    @Test
    fun bootstrapProbeReportsWhetherTheFounderSeatIsOpen() = testApplication {
        val repositories = Repositories()
        application { module(repositories = repositories, authConfig = AuthConfig(secret = "test-secret")) }

        val open = client.get("/auth/bootstrap")
        assertEquals(HttpStatusCode.OK, open.status)
        assertTrue(open.bodyAsText().contains("\"available\":true"))

        // Once a member exists the seat is closed, so the probe flips to false.
        repositories.users.create(User(id = "alice", role = Role.SHARER, displayName = "Alice"))
        val closed = client.get("/auth/bootstrap")
        assertEquals(HttpStatusCode.OK, closed.status)
        assertTrue(closed.bodyAsText().contains("\"available\":false"))
    }

    @Test
    fun founderListsInviteCodesAndDeletesUnusedOnes() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        val authConfig = AuthConfig(secret = "test-secret")
        val founderToken = TokenService(authConfig).issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Mint a code, then confirm it shows up in the founder's list as unused.
        val mint = client.post("/founder/invites") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"SHARER"}""")
        }
        assertEquals(HttpStatusCode.OK, mint.status)
        val code = extractCode(mint.bodyAsText())

        val list = client.get("/founder/invites") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains(code))
        assertTrue(list.bodyAsText().contains("\"redeemed\":false"))

        // Delete the unused code; it disappears from the list.
        val deleted = client.delete("/founder/invites/$code") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)
        val after = client.get("/founder/invites") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertFalse(after.bodyAsText().contains(code))
    }

    @Test
    fun aRedeemedCodeCannotBeDeleted() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        val authConfig = AuthConfig(secret = "test-secret")
        val founderToken = TokenService(authConfig).issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val mint = client.post("/founder/invites") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"SHARER"}""")
        }
        val code = extractCode(mint.bodyAsText())

        // Redeem it to create an account.
        val redeem = client.post("/auth/redeem") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code","username":"newbie","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, redeem.status)

        // A used code is kept as an audit trail and may not be deleted.
        val deleted = client.delete("/founder/invites/$code") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertEquals(HttpStatusCode.BadRequest, deleted.status)
    }
}
