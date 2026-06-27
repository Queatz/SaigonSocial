package social.saigon.repository

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ArangoDB is the mandatory datastore (DESIGN §12): the server must fail loudly when it is
 * unreachable rather than silently degrading to volatile storage. Because the database is usually
 * started moments before the server (local `docker compose up` / the deploy scripts), a fresh
 * container reports "Started" while it is still initialising, so [connectArango] retries for a
 * bounded window before giving up. These tests pin both behaviours without a running database.
 */
class ArangoConnectTest {
    // A host/port nothing listens on, so every connection attempt fails fast (connection refused).
    private val unreachable = ArangoConfig(
        host = "127.0.0.1",
        port = 65533,
        user = "root",
        password = "",
        database = "saigon_social",
    )

    @Test
    fun failsFastWithAHelpfulMessageWhenTheDatabaseIsUnreachable() {
        val error = assertFailsWith<IllegalStateException> {
            connectArango(unreachable, timeoutSeconds = 0, sleep = {})
        }
        val message = error.message ?: ""
        assertTrue(message.contains("Cannot reach ArangoDB"), "message should explain the failure: $message")
        assertTrue(message.contains("127.0.0.1:65533"), "message should name the host:port: $message")
        assertTrue(message.contains("ARANGO_"), "message should point at the ARANGO_* env vars: $message")
    }

    @Test
    fun retriesWhileTheDatabaseStartsUpBeforeGivingUp() {
        // A non-zero window must produce at least one retry (a wait between attempts) so a
        // still-booting container is tolerated. The injected sleep records calls and caps the real
        // wait, so a short window keeps the test fast while still exercising the retry path.
        var sleeps = 0
        assertFailsWith<IllegalStateException> {
            connectArango(unreachable, timeoutSeconds = 1, sleep = { requested ->
                sleeps++
                Thread.sleep(minOf(requested, 25))
            })
        }
        assertTrue(sleeps >= 1, "connectArango must wait/retry while ArangoDB is still starting")
    }
}
