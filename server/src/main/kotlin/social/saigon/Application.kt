package social.saigon

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import social.saigon.ai.IdentityGenerator
import social.saigon.ai.QwenIdentityGenerator
import social.saigon.auth.AuthConfig
import social.saigon.auth.PasswordService
import social.saigon.auth.TokenService
import social.saigon.auth.configureAuth
import social.saigon.domain.ForbiddenException
import social.saigon.realtime.ChatHub
import social.saigon.repository.ArangoConfig
import social.saigon.repository.Repositories
import social.saigon.repository.arangoRepositories
import social.saigon.repository.connectArango
import social.saigon.repository.seedInitialData
import social.saigon.routes.registerRoutes

fun main() {
    // ArangoDB is the single source of truth for all durable state (DESIGN §12). It is mandatory:
    // connectArango fails loudly — both locally and in deployment — if the database is unreachable,
    // rather than silently degrading to file/in-memory storage that wiped accounts on restart.
    // Connection settings come from the ARANGO_* environment variables (sensible local defaults).
    val repositories = arangoRepositories(connectArango(ArangoConfig.fromEnv()))
    seedInitialData(repositories)

    // Founder-managed access with no open sign-up (DESIGN §2). The founder is not pre-created: the
    // first person to arrive claims the Founder account by signing in with a blank access token
    // (POST /auth/bootstrap), and everyone else is onboarded by the founder thereafter.
    val authConfig = AuthConfig(secret = System.getenv("JWT_SECRET") ?: "dev-secret-change-me")

    embeddedServer(
        Netty,
        port = (System.getenv("PORT")?.toIntOrNull() ?: 9090),
        host = System.getenv("HOST") ?: "0.0.0.0",
        module = { module(repositories = repositories, authConfig = authConfig) },
    ).start(wait = true)
}

/**
 * Phase 0 wiring: content negotiation (JSON), WebSockets (heartbeat), founder-managed JWT auth,
 * status pages, and the platform dependency graph (repositories, chat hub, identity generator).
 *
 * Dependencies can be overridden in tests; production values are read from the environment.
 */
fun Application.module(
    repositories: Repositories = Repositories(),
    chatHub: ChatHub = ChatHub(),
    identityGenerator: IdentityGenerator = QwenIdentityGenerator(apiKey = System.getenv("QWEN_API_KEY")),
    authConfig: AuthConfig = AuthConfig(
        secret = System.getenv("JWT_SECRET") ?: "dev-secret-change-me",
    ),
) {
    val tokenService = TokenService(authConfig)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    // Allow the Compose Web client (served from a different origin in dev) to call the API
    // (DESIGN §12: web-first). Tighten allowed hosts behind a real domain in production.
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
    }
    install(StatusPages) {
        exception<ForbiddenException> { call, cause ->
            call.respondText(cause.message ?: "Forbidden", status = HttpStatusCode.Forbidden)
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respondText(cause.message ?: "Bad request", status = HttpStatusCode.BadRequest)
        }
        // A bad password sign-in is always a generic 401 — never reveal whether the user/handle
        // exists (PASSWORD_AUTH.md §2E, no user enumeration).
        exception<PasswordService.InvalidCredentials> { call, _ ->
            call.respondText("Invalid credentials", status = HttpStatusCode.Unauthorized)
        }
    }

    configureAuth(authConfig, tokenService)

    routing {
        get("/") {
            call.respondText(
                "Saigon's trusted chat room.",
            )
        }
    }

    registerRoutes(repositories, chatHub, tokenService, identityGenerator)
}
