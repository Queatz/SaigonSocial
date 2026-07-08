package social.saigon.content

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentServerTest {

    /** A config rooted in a throwaway temp dir so tests never touch real storage. */
    private fun testConfig(): ContentConfig = ContentConfig(
        publicBaseUrl = "http://localhost:9091",
        storageDir = Files.createTempDirectory("content-test"),
    )

    @Test
    fun uploadRequiresAnAccessToken() = testApplication {
        application { contentModule(testConfig(), tokenValidator = { it == "member-token" }) }

        val response = client.put("/content") {
            header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun uploadRejectsATokenTheMainBackendDoesNotRecognise() = testApplication {
        application { contentModule(testConfig(), tokenValidator = { it == "member-token" }) }

        val response = client.put("/content") {
            bearerAuth("forged-token")
            header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun uploadThenGetRoundTripsTheBytesOnTheReturnedUrl() = testApplication {
        val config = testConfig()
        application { contentModule(config, tokenValidator = { it == "member-token" }) }

        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        val upload = client.put("/content") {
            bearerAuth("member-token")
            header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.OK, upload.status)
        val body = upload.bodyAsText()
        // The URL points at the configured public base and carries the stored file name.
        assertTrue(body.contains("\"url\":\"${config.publicBaseUrl}/content/"))
        val name = body.substringAfterLast("/content/").substringBefore("\"")
        assertTrue(name.endsWith(".png"))

        val served = client.get("/content/$name")
        assertEquals(HttpStatusCode.OK, served.status)
        assertEquals(ContentType.Image.PNG, served.contentTypeOrNull())
        assertContentEquals(bytes, served.bodyAsBytes())
    }

    @Test
    fun uploadRejectsNonImageContent() = testApplication {
        application { contentModule(testConfig(), tokenValidator = { it == "member-token" }) }

        val response = client.put("/content") {
            bearerAuth("member-token")
            header(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun uploadRejectsEmptyAndOversizedBodies() = testApplication {
        val config = testConfig().copy(maxUploadBytes = 4)
        application { contentModule(config, tokenValidator = { it == "member-token" }) }

        val empty = client.put("/content") {
            bearerAuth("member-token")
            header(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
            setBody(ByteArray(0))
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)

        val oversized = client.put("/content") {
            bearerAuth("member-token")
            header(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
            setBody(ByteArray(5))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    @Test
    fun getRefusesNamesItNeverMinted() = testApplication {
        application { contentModule(testConfig(), tokenValidator = { it == "member-token" }) }

        // Traversal-style and free-form names never hit the file system.
        assertEquals(HttpStatusCode.NotFound, client.get("/content/..%2F..%2Fetc%2Fpasswd").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/content/logback.xml").status)
        // A well-formed name that simply doesn't exist is also a 404.
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/content/00000000-0000-0000-0000-000000000000.png").status,
        )
    }

    @Test
    fun configIsEnvironmentAware() {
        // Development defaults: everything points at localhost.
        val dev = ContentConfig.fromEnv { null }
        assertEquals("http://localhost:9091", dev.publicBaseUrl)
        assertEquals("http://localhost:9090", dev.apiBaseUrl)

        // Production flips both domains (DESIGN §12).
        val prodEnv = mapOf("SAIGON_ENV" to "production")
        val prod = ContentConfig.fromEnv { prodEnv[it] }
        assertEquals("https://content.saigon.social", prod.publicBaseUrl)
        assertEquals("https://api.saigon.social", prod.apiBaseUrl)

        // Explicit overrides beat both defaults.
        val custom = mapOf(
            "SAIGON_ENV" to "production",
            "CONTENT_PUBLIC_URL" to "https://cdn.example.com",
            "API_BASE_URL" to "https://api.example.com",
            "PORT" to "8123",
        )
        val overridden = ContentConfig.fromEnv { custom[it] }
        assertEquals("https://cdn.example.com", overridden.publicBaseUrl)
        assertEquals("https://api.example.com", overridden.apiBaseUrl)
        assertEquals(8123, overridden.port)
    }
}

/** The response's content type without its charset parameter, for stable comparisons. */
private fun io.ktor.client.statement.HttpResponse.contentTypeOrNull(): ContentType? =
    headers[HttpHeaders.ContentType]?.let { ContentType.parse(it).withoutParameters() }
