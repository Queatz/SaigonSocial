package social.saigon

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
import social.saigon.domain.Room
import social.saigon.domain.User
import social.saigon.repository.Repositories

/**
 * Services, feedback, and "Request an Invite" flow (round-3 issue): a Supporter creates a service
 * from a room, everyone can search it (with the room name shown), feedback is public, and the
 * founder-only invite-request management is gated and drops resolved/dismissed requests.
 */
class ServicesTest {

    private fun extractId(json: String): String =
        Regex("\"id\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    @Test
    fun supporterCreatesAServiceFromARoomAndItIsSearchableWithTheRoomName() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "sup", role = Role.SUPPORTER, displayName = "Minh"))
        repositories.rooms.create(Room(id = "district1", name = "District 1", emoji = "🏙", createdBy = "sup"))
        val authConfig = AuthConfig(secret = "test-secret")
        val supToken = TokenService(authConfig).issueMemberToken("sup", Role.SUPPORTER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val create = client.post("/rooms/district1/services") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
            contentType(ContentType.Application.Json)
            setBody("""{"roomId":"district1","emoji":"🎸","name":"Guitar lessons","description":"Learn guitar"}""")
        }
        assertEquals(HttpStatusCode.OK, create.status)
        val body = create.bodyAsText()
        // The service always carries the room name (issue §1).
        assertTrue(body.contains("\"roomName\":\"District 1\""))
        val serviceId = extractId(body)

        // The provider id is recorded on the supporter's user record.
        assertTrue(repositories.users.get("sup")!!.services.contains(serviceId))

        // Case-insensitive partial search by name.
        val search = client.get("/services?q=guitar")
        assertEquals(HttpStatusCode.OK, search.status)
        assertTrue(search.bodyAsText().contains("Guitar lessons"))

        // A query that doesn't match the name returns nothing.
        val miss = client.get("/services?q=cooking")
        assertFalse(miss.bodyAsText().contains("Guitar lessons"))
    }

    @Test
    fun nonSupporterCannotCreateAService() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "sharer", role = Role.SHARER, displayName = "Sam"))
        repositories.rooms.create(Room(id = "r1", name = "Room 1", emoji = "🏠", createdBy = "sharer"))
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("sharer", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val create = client.post("/rooms/r1/services") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"roomId":"r1","emoji":"🎸","name":"Guitar lessons"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, create.status)
    }

    @Test
    fun feedbackIsPublicAndAggregatable() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "sup", role = Role.SUPPORTER, displayName = "Minh"))
        repositories.users.create(User(id = "member", role = Role.SHARER, displayName = "Sam"))
        repositories.rooms.create(Room(id = "r1", name = "Room 1", emoji = "🏠", createdBy = "sup"))
        val authConfig = AuthConfig(secret = "test-secret")
        val ts = TokenService(authConfig)
        val supToken = ts.issueMemberToken("sup", Role.SUPPORTER)
        val memberToken = ts.issueMemberToken("member", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val serviceId = extractId(
            client.post("/rooms/r1/services") {
                header(HttpHeaders.Authorization, "Bearer $supToken")
                contentType(ContentType.Application.Json)
                setBody("""{"roomId":"r1","emoji":"🎸","name":"Guitar lessons"}""")
            }.bodyAsText(),
        )

        client.post("/services/$serviceId/feedback") {
            header(HttpHeaders.Authorization, "Bearer $memberToken")
            contentType(ContentType.Application.Json)
            setBody("""{"emoji":"👍","message":"Great teacher"}""")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }

        // Feedback is public — no auth needed to read it.
        val feedback = client.get("/services/$serviceId/feedback")
        assertEquals(HttpStatusCode.OK, feedback.status)
        assertTrue(feedback.bodyAsText().contains("Great teacher"))
    }

    @Test
    fun inviteRequestFlowIsPublicToSubmitAndFounderGated() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "sharer", role = Role.SHARER, displayName = "Sam"))
        val authConfig = AuthConfig(secret = "test-secret")
        val ts = TokenService(authConfig)
        val founderToken = ts.issueMemberToken("founder", Role.FOUNDER)
        val sharerToken = ts.issueMemberToken("sharer", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Anyone (no auth) may submit a request.
        val submit = client.post("/invite-requests") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Lan","zaloNumber":"0900","description":"massage"}""")
        }
        assertEquals(HttpStatusCode.OK, submit.status)

        // A non-founder is rejected by the management endpoints.
        val forbidden = client.get("/founder/invite-requests") {
            header(HttpHeaders.Authorization, "Bearer $sharerToken")
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        // The founder sees the pending request.
        val pending = client.get("/founder/invite-requests") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertEquals(HttpStatusCode.OK, pending.status)
        assertTrue(pending.bodyAsText().contains("Lan"))
        val requestId = extractId(pending.bodyAsText())

        // Resolving it removes it from the pending list.
        client.post("/founder/invite-requests/$requestId/resolve") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }

        val after = client.get("/founder/invite-requests") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertFalse(after.bodyAsText().contains("Lan"))
    }
}
