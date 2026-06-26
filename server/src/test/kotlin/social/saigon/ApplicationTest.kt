package social.saigon

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "Saigon.Social — a digital room for the city. Real people, real conversations, zero pressure.",
            response.bodyAsText(),
        )
    }
}
