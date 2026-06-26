package social.saigon.auth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Server-side password hashing (PASSWORD_AUTH.md §2A, §2E).
 *
 * Uses PBKDF2-HMAC-SHA256 with a per-user random salt — a vetted KDF available in the JDK, so no
 * extra dependency is needed. The encoded form is self-describing
 * (`pbkdf2-sha256$<iterations>$<saltB64>$<hashB64>`) so the iteration count can be raised later
 * without breaking existing hashes. Plaintext is never stored, logged, or returned.
 */
object PasswordHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private const val PREFIX = "pbkdf2-sha256"

    /** Minimum acceptable password length (PASSWORD_AUTH.md §2B strength check). */
    const val MIN_LENGTH = 8

    private val random = SecureRandom()

    /** Hash [password] with a fresh random salt, returning the self-describing encoded form. */
    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = pbkdf2(password.toCharArray(), salt, ITERATIONS)
        val encoder = Base64.getEncoder()
        return "$PREFIX\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    /**
     * Verify [password] against a previously [hash]ed value in constant time. Returns false for any
     * malformed or unrecognized encoding rather than throwing.
     */
    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split("$")
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        val actual = pbkdf2(password.toCharArray(), salt, iterations)
        return constantTimeEquals(expected, actual)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }
}
