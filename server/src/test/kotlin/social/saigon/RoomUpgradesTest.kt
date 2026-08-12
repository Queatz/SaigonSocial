package social.saigon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import social.saigon.domain.RoomCreate
import social.saigon.domain.RoomMembership
import social.saigon.domain.RoomUpdate
import social.saigon.domain.User
import social.saigon.repository.InMemoryRoomRepository
import social.saigon.repository.Repositories
import social.saigon.room.RoomService

/**
 * Room description + join/leave membership (issue: "Two room upgrades").
 */
class RoomUpgradesTest {

    @Test
    fun roomServicePersistsDescriptionOnCreateAndUpdate() {
        val svc = RoomService(InMemoryRoomRepository())
        val created = svc.create(
            "founder",
            RoomCreate(name = "Coffee", emoji = "☕", atmosphere = "cozy", description = " **Hello** community "),
        )
        assertEquals("**Hello** community", created.description)

        val updated = svc.update(created.id, RoomUpdate(description = "Updated **desc**"))
        assertEquals("Updated **desc**", updated.description)
        // Other fields stay put when description is the only edit.
        assertEquals("Coffee", updated.name)
        assertEquals("cozy", updated.atmosphere)
    }

    @Test
    fun descriptionRoundTripsOverHttpAndAppearsInRoomList() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "f1", role = Role.FOUNDER, displayName = "Founder"))
        val authConfig = AuthConfig(secret = "test-secret")
        val founder = TokenService(authConfig).issueMemberToken("f1", Role.FOUNDER)
        application { module(repositories = repositories, authConfig = authConfig) }

        val created = client.post("/rooms") {
            bearerAuth(founder)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Deep Talks","emoji":"🧠","atmosphere":"kind","description":"A **safe** space"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        assertTrue(created.bodyAsText().contains("A **safe** space"))

        val list = client.get("/rooms").bodyAsText()
        assertTrue(list.contains("A **safe** space"))
        assertTrue(list.contains("Deep Talks"))

        val roomId = repositories.rooms.all().first { it.name == "Deep Talks" }.id
        val edited = client.put("/rooms/$roomId") {
            bearerAuth(founder)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Deep Talks","emoji":"🧠","atmosphere":"kind","description":"Even **deeper**"}""")
        }
        assertEquals(HttpStatusCode.OK, edited.status)
        assertTrue(client.get("/rooms").bodyAsText().contains("Even **deeper**"))
    }

    @Test
    fun joinAndLeaveAreIdempotentAndTrackedServerSide() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "u1", role = Role.SHARER, displayName = "Sam"))
        repositories.rooms.create(Room(id = "coffee", name = "Coffee", emoji = "☕", createdBy = "f1"))
        val authConfig = AuthConfig(secret = "test-secret")
        val token = TokenService(authConfig).issueMemberToken("u1", Role.SHARER)
        application { module(repositories = repositories, authConfig = authConfig) }

        // Not joined yet.
        val before = client.get("/rooms/coffee/membership") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, before.status)
        assertTrue(before.bodyAsText().contains("\"joined\":false"))

        val join = client.post("/rooms/coffee/join") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, join.status)
        assertTrue(join.bodyAsText().contains("\"joined\":true"))
        assertTrue(repositories.memberships.get("u1", "coffee") != null)

        // Joining again is a no-op and keeps the original joinedAt.
        val firstJoinedAt = repositories.memberships.get("u1", "coffee")!!.joinedAt
        val joinAgain = client.post("/rooms/coffee/join") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, joinAgain.status)
        assertEquals(firstJoinedAt, repositories.memberships.get("u1", "coffee")!!.joinedAt)

        val status = client.get("/rooms/coffee/membership") { bearerAuth(token) }
        assertTrue(status.bodyAsText().contains("\"joined\":true"))

        val leave = client.post("/rooms/coffee/leave") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, leave.status)
        assertTrue(leave.bodyAsText().contains("\"joined\":false"))
        assertTrue(repositories.memberships.get("u1", "coffee") == null)

        // Leaving again is a no-op.
        val leaveAgain = client.post("/rooms/coffee/leave") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, leaveAgain.status)
        assertTrue(leaveAgain.bodyAsText().contains("\"joined\":false"))
    }

    @Test
    fun occupantsListIncludesOfflineJoinedMembers() = testApplication {
        val repositories = Repositories()
        repositories.users.create(User(id = "online", role = Role.SHARER, displayName = "Online"))
        repositories.users.create(User(id = "member", role = Role.SHARER, displayName = "Member"))
        repositories.rooms.create(Room(id = "r1", name = "Room", emoji = "🏠", createdBy = "f1"))
        // Durable membership without a live WebSocket session.
        repositories.memberships.put(
            RoomMembership(userId = "member", roomId = "r1", joinedAt = 1_000L),
        )
        val authConfig = AuthConfig(secret = "test-secret")
        application { module(repositories = repositories, authConfig = authConfig) }

        val body = client.get("/rooms/r1/occupants").bodyAsText()
        // Offline joined member is listed with member=true and online=false.
        assertTrue(body.contains("\"userId\":\"member\""))
        assertTrue(body.contains("\"member\":true"))
        assertTrue(body.contains("\"online\":false"))
        // Nobody is live, so the online-only user is absent.
        assertFalse(body.contains("\"userId\":\"online\""))
    }

    @Test
    fun anonymousCannotJoin() = testApplication {
        val repositories = Repositories()
        repositories.rooms.create(Room(id = "r1", name = "Room", emoji = "🏠", createdBy = "f1"))
        application { module(repositories = repositories, authConfig = AuthConfig(secret = "test-secret")) }

        val join = client.post("/rooms/r1/join")
        // Unauthenticated requests are rejected by the JWT auth gate.
        assertTrue(
            join.status == HttpStatusCode.Unauthorized || join.status == HttpStatusCode.Forbidden,
            "expected 401/403, got ${join.status}",
        )
    }
}
