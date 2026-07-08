package social.saigon.content

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Saigon.Social content server: a simple, authenticated put/get file host (issue: "Profile and
 * room profile photos").
 *
 * - `PUT`/`POST /content` uploads a file and answers with its public URL. The caller's bearer
 *   token is authenticated against the *main backend* (`GET {apiBaseUrl}/profile/me`) before the
 *   upload is accepted, so only signed-in members can store content.
 * - `GET /content/{name}` serves a previously uploaded file back on that URL — publicly, so photos
 *   render for everyone (the URL itself is an unguessable UUID).
 *
 * The server is environment-aware (see [ContentConfig.fromEnv]): in development it points at the
 * local backend and hands out `http://localhost:{port}` URLs; in production (`SAIGON_ENV=production`)
 * it authenticates against `https://api.saigon.social` and hands out
 * `https://content.saigon.social` URLs. Every default can be overridden by environment variables.
 */
data class ContentConfig(
    val host: String = "0.0.0.0",
    val port: Int = 9091,
    /** The public base URL baked into the upload responses, e.g. "https://content.saigon.social". */
    val publicBaseUrl: String = "http://localhost:9091",
    /** The main backend that authenticates upload tokens, e.g. "https://api.saigon.social". */
    val apiBaseUrl: String = "http://localhost:9090",
    /** Directory the uploaded files live in. Created on boot when missing. */
    val storageDir: Path = Path.of("content-files"),
    /** Uploads larger than this are rejected with 413 (photos should stay well under it). */
    val maxUploadBytes: Int = 10 * 1024 * 1024,
) {
    companion object {
        /** The production domains (DESIGN §12); development falls back to localhost. */
        const val PRODUCTION_PUBLIC_URL = "https://content.saigon.social"
        const val PRODUCTION_API_URL = "https://api.saigon.social"

        /**
         * Resolve the configuration from the environment. `SAIGON_ENV=production` flips both the
         * public URL and the main-backend URL to the production domains; `CONTENT_PUBLIC_URL`,
         * `API_BASE_URL`, `CONTENT_DIR`, `PORT`, and `HOST` each override their default explicitly.
         */
        fun fromEnv(env: (String) -> String? = System::getenv): ContentConfig {
            val production = env("SAIGON_ENV").equals("production", ignoreCase = true)
            val port = env("PORT")?.toIntOrNull() ?: 9091
            return ContentConfig(
                host = env("HOST") ?: "0.0.0.0",
                port = port,
                publicBaseUrl = env("CONTENT_PUBLIC_URL")
                    ?: if (production) PRODUCTION_PUBLIC_URL else "http://localhost:$port",
                apiBaseUrl = env("API_BASE_URL")
                    ?: if (production) PRODUCTION_API_URL else "http://localhost:9090",
                storageDir = Path.of(env("CONTENT_DIR") ?: "content-files"),
            )
        }
    }
}

/** The upload response: the public URL the stored file is served back on. */
@Serializable
data class UploadResponse(val url: String)

fun main() {
    val config = ContentConfig.fromEnv()
    embeddedServer(
        Netty,
        port = config.port,
        host = config.host,
        module = { contentModule(config) },
    ).start(wait = true)
}

/**
 * Authenticate an upload token against the main backend: any 2xx from `GET /profile/me` means the
 * bearer token belongs to a signed-in member (Seekers and anonymous callers are turned away by the
 * backend itself). Network failures fail closed.
 */
fun mainBackendTokenValidator(apiBaseUrl: String): suspend (String) -> Boolean {
    val client = HttpClient(CIO)
    return { token ->
        runCatching {
            client.get("$apiBaseUrl/profile/me") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.status.isSuccess()
        }.getOrDefault(false)
    }
}

/**
 * The content server's routing: authenticated `PUT`/`POST /content` uploads and public
 * `GET /content/{name}` downloads. [tokenValidator] is pluggable so tests can stub the main
 * backend; production validates against `GET {apiBaseUrl}/profile/me`.
 */
fun Application.contentModule(
    config: ContentConfig = ContentConfig.fromEnv(),
    tokenValidator: suspend (String) -> Boolean = mainBackendTokenValidator(config.apiBaseUrl),
) {
    Files.createDirectories(config.storageDir)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    // The web client is served from a different origin (saigon.social / localhost dev server), so
    // uploads need CORS just like the main API.
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respondText(cause.message ?: "Bad request", status = HttpStatusCode.BadRequest)
        }
    }

    suspend fun handleUpload(call: ApplicationCall) {
        // Authenticate with the main backend before accepting anything.
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer")?.trim().orEmpty()
        if (token.isEmpty() || !tokenValidator(token)) {
            call.respondText("A valid access token is required to upload", status = HttpStatusCode.Unauthorized)
            return
        }
        val extension = extensionFor(call.request.contentType())
        if (extension == null) {
            call.respondText("Only image uploads are supported", status = HttpStatusCode.UnsupportedMediaType)
            return
        }
        val bytes = call.receive<ByteArray>()
        if (bytes.isEmpty()) {
            call.respondText("Empty upload", status = HttpStatusCode.BadRequest)
            return
        }
        if (bytes.size > config.maxUploadBytes) {
            call.respondText("Upload too large", status = HttpStatusCode.PayloadTooLarge)
            return
        }
        // An unguessable, collision-free name; the extension keeps the served content type honest.
        val name = "${UUID.randomUUID()}.$extension"
        Files.write(config.storageDir.resolve(name), bytes)
        call.respond(UploadResponse(url = "${config.publicBaseUrl}/content/$name"))
    }

    routing {
        get("/") {
            call.respondText("Saigon's content server.")
        }

        put("/content") { handleUpload(call) }
        post("/content") { handleUpload(call) }

        get("/content/{name}") {
            val name = call.parameters["name"].orEmpty()
            // Only names we minted ourselves (uuid.ext) are servable — no path traversal.
            val file = if (SAFE_NAME.matches(name)) config.storageDir.resolve(name) else null
            if (file == null || !Files.isRegularFile(file)) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondBytes(Files.readAllBytes(file), contentTypeFor(name))
        }
    }
}

/** Names this server mints: a UUID plus a short alphanumeric extension. */
private val SAFE_NAME = Regex("[a-fA-F0-9-]{36}\\.[a-z0-9]{1,8}")

/** The stored file extension for an upload's content type; null for types we don't host. */
private fun extensionFor(contentType: ContentType): String? = when {
    contentType.match(ContentType.Image.JPEG) -> "jpg"
    contentType.match(ContentType.Image.PNG) -> "png"
    contentType.match(ContentType.Image.GIF) -> "gif"
    contentType.match(ContentType.Image.SVG) -> "svg"
    contentType.match(ContentType.parse("image/webp")) -> "webp"
    else -> null
}

/** The content type a stored file is served back with, derived from its extension. */
private fun contentTypeFor(name: String): ContentType = when (name.substringAfterLast('.')) {
    "jpg" -> ContentType.Image.JPEG
    "png" -> ContentType.Image.PNG
    "gif" -> ContentType.Image.GIF
    "svg" -> ContentType.Image.SVG
    "webp" -> ContentType.parse("image/webp")
    else -> ContentType.Application.OctetStream
}
