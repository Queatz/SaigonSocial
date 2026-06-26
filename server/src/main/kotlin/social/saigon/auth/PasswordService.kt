package social.saigon.auth

import social.saigon.domain.Role
import social.saigon.domain.User
import social.saigon.repository.UserRepository

/**
 * Password set-up, change, and credential verification (PASSWORD_AUTH.md §2B, §2C).
 *
 * The access token stays the bootstrap/onboarding credential; a password is the durable re-entry
 * credential a member sets once signed in, then uses to sign back in later. All hashing is
 * server-side via [PasswordHasher]; plaintext is never stored or returned.
 */
class PasswordService(private val users: UserRepository) {

    /** A generic, non-enumerating failure for any bad login (PASSWORD_AUTH.md §2E). */
    class InvalidCredentials : Exception("Invalid credentials")

    /**
     * Set or change the caller's password. [currentPassword] is required only when one is already
     * set (change vs. first set). Validates strength, hashes, and records [User.passwordSetAt].
     */
    fun setPassword(userId: String, newPassword: String, currentPassword: String?): User {
        val user = users.get(userId) ?: throw IllegalArgumentException("Unknown user")
        require(user.role != Role.SEEKER) { "Seekers have no account to set a password on" }
        require(newPassword.length >= PasswordHasher.MIN_LENGTH) {
            "Password must be at least ${PasswordHasher.MIN_LENGTH} characters"
        }
        val existing = user.passwordHash
        if (existing != null) {
            require(!currentPassword.isNullOrEmpty()) { "Current password is required to change it" }
            require(PasswordHasher.verify(currentPassword, existing)) { "Current password is incorrect" }
        }
        return users.update(
            user.copy(
                passwordHash = PasswordHasher.hash(newPassword),
                passwordSetAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Verify a password sign-in. [identifier] matches the login-only handle (or, as an interim
     * fallback, the user id). On success returns the user so the caller can mint a fresh token;
     * any failure throws [InvalidCredentials] with a generic message (no user enumeration).
     */
    fun authenticate(identifier: String, password: String): User {
        val candidate = identifier.trim()
        if (candidate.isEmpty() || password.isEmpty()) throw InvalidCredentials()
        val user = users.findByHandle(candidate) ?: users.get(candidate) ?: throw InvalidCredentials()
        val hash = user.passwordHash ?: throw InvalidCredentials()
        if (user.role == Role.SEEKER) throw InvalidCredentials()
        if (!PasswordHasher.verify(password, hash)) throw InvalidCredentials()
        return user
    }
}
