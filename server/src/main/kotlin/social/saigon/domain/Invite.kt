package social.saigon.domain

import kotlinx.serialization.Serializable

/**
 * A founder-issued invite code (replaces sharing a raw access token).
 *
 * The founder mints a short, human-friendly [code] for a given [role]; the recipient redeems it
 * once to create their account by choosing a username + password (no token is ever pasted). A code
 * is single-use: [redeemedBy] is set the moment it is consumed.
 */
@Serializable
data class InviteCode(
    val code: String,
    val role: Role,
    val createdBy: String,
    val createdAt: Long,
    val redeemedBy: String? = null,
    val redeemedAt: Long? = null,
) {
    val redeemed: Boolean get() = redeemedBy != null
}
