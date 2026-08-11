package social.saigon

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
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
    fun serviceSupportsPriceEditUnlistRelistDeleteAndSearchTracking() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "founder", role = Role.FOUNDER, displayName = "F"))
        repositories.users.create(User(id = "sup", role = Role.SUPPORTER, displayName = "Minh"))
        repositories.rooms.create(Room(id = "r1", name = "Room 1", emoji = "🏠", createdBy = "sup"))
        val authConfig = AuthConfig(secret = "test-secret")
        val ts = TokenService(authConfig)
        val supToken = ts.issueMemberToken("sup", Role.SUPPORTER)
        val founderToken = ts.issueMemberToken("founder", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Create with a price and included/not-included lists (semicolon-separated free text).
        val created = client.post("/rooms/r1/services") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"roomId":"r1","emoji":"🎸","name":"Guitar lessons","price":50000,""" +
                    """"included":"1-hour lesson; instrument provided","notIncluded":"sheet music; travel"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val body = created.bodyAsText()
        assertTrue(body.contains("\"price\":50000"))
        assertTrue(body.contains("\"included\":\"1-hour lesson; instrument provided\""))
        assertTrue(body.contains("\"notIncluded\":\"sheet music; travel\""))
        val serviceId = extractId(body)

        // Edit the price, name, and inclusion fields as the provider.
        val edited = client.put("/services/$serviceId") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"emoji":"🎸","name":"Guitar lessons","description":"","price":75000,""" +
                    """"included":"2-hour lesson; picks","notIncluded":"amp"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, edited.status)
        val editedBody = edited.bodyAsText()
        assertTrue(editedBody.contains("\"price\":75000"))
        assertTrue(editedBody.contains("\"included\":\"2-hour lesson; picks\""))
        assertTrue(editedBody.contains("\"notIncluded\":\"amp\""))

        // Unlist: hidden from the public search but still returned in the provider's own list.
        client.post("/services/$serviceId/unlist") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }
        assertFalse(client.get("/services?q=guitar").bodyAsText().contains("Guitar lessons"))
        val mine = client.get("/services/mine") { header(HttpHeaders.Authorization, "Bearer $supToken") }
        assertTrue(mine.bodyAsText().contains("Guitar lessons"))

        // Relist restores it to the public search.
        client.post("/services/$serviceId/relist") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }
        assertTrue(client.get("/services?q=guitar").bodyAsText().contains("Guitar lessons"))

        // The by-name search above is recorded for the founder.
        val searches = client.get("/founder/service-searches") {
            header(HttpHeaders.Authorization, "Bearer $founderToken")
        }
        assertEquals(HttpStatusCode.OK, searches.status)
        assertTrue(searches.bodyAsText().contains("guitar"))

        // Delete removes it and drops it from the provider's services list.
        client.delete("/services/$serviceId") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }
        assertFalse(repositories.users.get("sup")!!.services.contains(serviceId))
        assertEquals(HttpStatusCode.NotFound, client.get("/services/$serviceId").status)
    }

    @Test
    fun servicePersistsItineraryActivitiesAndCancellationPolicy() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "sup", role = Role.SUPPORTER, displayName = "Minh"))
        repositories.rooms.create(Room(id = "r1", name = "Room 1", emoji = "🏠", createdBy = "sup"))
        val authConfig = AuthConfig(secret = "test-secret")
        val supToken = TokenService(authConfig).issueMemberToken("sup", Role.SUPPORTER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Create with destinations, activities (Markdown body), and an explicit cancellation policy.
        val created = client.post("/rooms/r1/services") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "roomId":"r1",
                  "emoji":"🛵",
                  "name":"Food tour",
                  "destinations":[
                    {"name":"Ben Thanh","address":"Le Loi","url":"https://maps.example/1","photos":[{"url":"https://content.local/d1.jpg","message":""}]},
                    {"name":"Bui Vien","address":"District 1","url":"https://maps.example/2","photos":[]}
                  ],
                  "activities":[
                    {"name":"Street snacks","text":"**Try** the local *banh mi*","photos":[]}
                  ],
                  "cancellationPolicy":"Free cancel up to 12 hours before."
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val body = created.bodyAsText()
        assertTrue(body.contains("\"name\":\"Ben Thanh\""))
        assertTrue(body.contains("\"url\":\"https://maps.example/1\""))
        assertTrue(body.contains("\"name\":\"Street snacks\""))
        assertTrue(body.contains("**Try** the local *banh mi*"))
        assertTrue(body.contains("Free cancel up to 12 hours before."))
        val serviceId = extractId(body)

        // Blank cancellation policy on edit falls back to the default 24-hour promise.
        val edited = client.put("/services/$serviceId") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "emoji":"🛵",
                  "name":"Food tour",
                  "destinations":[{"name":"Only stop","address":"","url":"","photos":[]}],
                  "activities":[],
                  "cancellationPolicy":"   "
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, edited.status)
        // Blank input is stored as the default 24-hour promise (may be omitted from JSON when it
        // equals the kotlinx default, so assert against the persisted row).
        assertEquals(
            social.saigon.domain.Service.DEFAULT_CANCELLATION_POLICY,
            repositories.services.get(serviceId)!!.cancellationPolicy,
        )
        assertTrue(edited.bodyAsText().contains("\"name\":\"Only stop\""))
        // Empty activity list is preserved; the old activity is gone.
        assertFalse(edited.bodyAsText().contains("Street snacks"))
        assertEquals(listOf("Only stop"), repositories.services.get(serviceId)!!.destinations.map { it.name })
        assertTrue(repositories.services.get(serviceId)!!.activities.isEmpty())
    }

    @Test
    fun serviceOwnerCanAddAndDeletePhotosButOthersCannot() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "sup", role = Role.SUPPORTER, displayName = "Minh"))
        repositories.users.create(User(id = "other", role = Role.SUPPORTER, displayName = "Nam"))
        repositories.rooms.create(Room(id = "r1", name = "Room 1", emoji = "🏠", createdBy = "sup"))
        val authConfig = AuthConfig(secret = "test-secret")
        val ts = TokenService(authConfig)
        val supToken = ts.issueMemberToken("sup", Role.SUPPORTER)
        val otherToken = ts.issueMemberToken("other", Role.SUPPORTER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val serviceId = extractId(
            client.post("/rooms/r1/services") {
                header(HttpHeaders.Authorization, "Bearer $supToken")
                contentType(ContentType.Application.Json)
                setBody("""{"roomId":"r1","emoji":"🎸","name":"Guitar lessons"}""")
            }.bodyAsText(),
        )

        // The provider adds a photo; it comes back on the service (issue: "upload service photos").
        val added = client.post("/services/$serviceId/photos") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://content.local/content/s1.jpg","message":"My studio"}""")
        }
        assertEquals(HttpStatusCode.OK, added.status)
        assertTrue(added.bodyAsText().contains("\"url\":\"https://content.local/content/s1.jpg\""))
        assertTrue(added.bodyAsText().contains("\"message\":\"My studio\""))

        // It is served on the public service page too.
        assertTrue(client.get("/services/$serviceId").bodyAsText().contains("s1.jpg"))

        // A different member cannot add photos to someone else's service.
        val forbidden = client.post("/services/$serviceId/photos") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://content.local/content/nope.jpg"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, forbidden.status)

        // Anonymous callers cannot add photos.
        val anonymous = client.post("/services/$serviceId/photos") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://content.local/content/anon.jpg"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        // The provider can update caption/image in place.
        val updated = client.put("/services/$serviceId/photos") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "originalUrl":"https://content.local/content/s1.jpg",
                  "url":"https://content.local/content/s1b.jpg",
                  "message":"Updated studio"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val updatedBody = updated.bodyAsText()
        assertTrue(updatedBody.contains("\"url\":\"https://content.local/content/s1b.jpg\""))
        assertTrue(updatedBody.contains("\"message\":\"Updated studio\""))
        assertFalse(updatedBody.contains("s1.jpg\""))

        // A different member cannot edit photos on someone else's service.
        val forbiddenUpdate = client.put("/services/$serviceId/photos") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "originalUrl":"https://content.local/content/s1b.jpg",
                  "url":"https://content.local/content/nope.jpg",
                  "message":"nope"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, forbiddenUpdate.status)

        // The provider deletes the photo by URL.
        val deleted = client.delete("/services/$serviceId/photos") {
            header(HttpHeaders.Authorization, "Bearer $supToken")
            parameter("url", "https://content.local/content/s1b.jpg")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertFalse(client.get("/services/$serviceId").bodyAsText().contains("s1b.jpg"))
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
