package social.saigon.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import java.util.Date
import social.saigon.domain.Role

/**
 * Auth configuration (DESIGN §12, TASKS Phase 0).
 *
 * Founder-managed: there is no open sign-up and no phone OTP. The founder onboards each member,
 * and the backend issues a signed JWT for the resulting account. Seekers get a lightweight token
 * bound to a device id with no server-side persistence.
 */
data class AuthConfig(
    val secret: String,
    val issuer: String = "saigon.social",
    val audience: String = "saigon.social",
    val realm: String = "Saigon.Social",
    /** Sharer/Supporter token lifetime in millis (default 30 days). */
    val memberTtlMillis: Long = 30L * 24 * 60 * 60 * 1000,
    /** Seeker token lifetime in millis (default 1 day). */
    val seekerTtlMillis: Long = 24L * 60 * 60 * 1000,
)

object Claims {
    const val USER_ID = "uid"
    const val ROLE = "role"
    const val DEVICE_ID = "device"
}

class TokenService(private val config: AuthConfig) {
    private val algorithm = Algorithm.HMAC256(config.secret)

    val verifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .build()

    /** Token for an onboarded member (Sharer/Supporter/Founder). */
    fun issueMemberToken(userId: String, role: Role): String {
        require(role != Role.SEEKER) { "Use issueSeekerToken for Seekers" }
        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withClaim(Claims.USER_ID, userId)
            .withClaim(Claims.ROLE, role.name)
            .withExpiresAt(Date(System.currentTimeMillis() + config.memberTtlMillis))
            .sign(algorithm)
    }

    /** Lightweight, non-persistent token for a Seeker, bound to a device id. */
    fun issueSeekerToken(deviceId: String): String =
        JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withClaim(Claims.ROLE, Role.SEEKER.name)
            .withClaim(Claims.DEVICE_ID, deviceId)
            .withExpiresAt(Date(System.currentTimeMillis() + config.seekerTtlMillis))
            .sign(algorithm)

    /**
     * Verify a raw token and resolve the caller, or null if it is missing/invalid/expired.
     *
     * Used outside the JWT auth plugin (e.g. on the WebSocket transport, which authenticates via a
     * query-param token) so the server can identify a member and gate who may send.
     */
    fun resolve(token: String): CallerPrincipal? = runCatching {
        val decoded = verifier.verify(token)
        val role = decoded.getClaim(Claims.ROLE).asString()?.let { Role.valueOf(it) } ?: Role.SEEKER
        CallerPrincipal(
            userId = decoded.getClaim(Claims.USER_ID).asString(),
            role = role,
            deviceId = decoded.getClaim(Claims.DEVICE_ID).asString(),
        )
    }.getOrNull()
}

/** Authenticated caller derived from a verified JWT. */
data class CallerPrincipal(
    val userId: String?,
    val role: Role,
    val deviceId: String?,
)

fun JWTPrincipal.toCaller(): CallerPrincipal {
    val role = payload.getClaim(Claims.ROLE).asString()?.let { Role.valueOf(it) } ?: Role.SEEKER
    return CallerPrincipal(
        userId = payload.getClaim(Claims.USER_ID).asString(),
        role = role,
        deviceId = payload.getClaim(Claims.DEVICE_ID).asString(),
    )
}

const val AUTH_JWT = "auth-jwt"

fun Application.configureAuth(config: AuthConfig, tokenService: TokenService) {
    authentication {
        jwt(AUTH_JWT) {
            realm = config.realm
            verifier(tokenService.verifier)
            validate { credential ->
                if (credential.payload.getClaim(Claims.ROLE).asString() != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }
}
