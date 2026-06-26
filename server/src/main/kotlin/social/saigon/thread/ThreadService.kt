package social.saigon.thread

import java.util.UUID
import social.saigon.domain.PlainText
import social.saigon.domain.ThreadReply
import social.saigon.repository.MessageRepository
import social.saigon.repository.ThreadRepository

/**
 * Ephemeral message threads — the TikTok-style comment sheet (TASKS Phase 4, DESIGN §9).
 *
 * Every room message can carry its own thread of replies. Replies may be nested (a reply to a
 * reply) yet are surfaced linearly, ordered by time. Threads are **ephemeral**: they are dropped
 * when the room empties or after 24 hours of inactivity. Who may reply (Sharers/Supporters, not
 * read-only Seekers) is enforced at the transport layer; this service owns validation, ordering,
 * and the ephemeral lifecycle.
 *
 * A [clock] seam keeps the 24h inactivity sweep deterministic in tests.
 */
class ThreadService(
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Threads disappear after this much inactivity (DESIGN §9). */
        const val INACTIVITY_WINDOW_MS: Long = 24L * 60 * 60 * 1000
    }

    /**
     * Append a reply to the thread hanging off [parentMessageId].
     *
     * @param replyToId an existing reply in the same thread this one is nested under, or null for
     *   a top-level reply.
     * @throws IllegalArgumentException if the parent message is unknown, the text is not plain
     *   text, or [replyToId] does not belong to the same thread.
     */
    fun reply(
        parentMessageId: String,
        authorId: String,
        text: String,
        replyToId: String? = null,
    ): ThreadReply {
        val parent = messages.get(parentMessageId)
            ?: throw IllegalArgumentException("Unknown message")
        val body = text.trim()
        require(PlainText.isValid(body)) { "Plain text only" }
        if (replyToId != null) {
            require(threads.byParent(parentMessageId).any { it.id == replyToId }) {
                "Reply target not in this thread"
            }
        }
        return threads.add(
            ThreadReply(
                id = UUID.randomUUID().toString(),
                parentMessageId = parent.id,
                replyToId = replyToId,
                authorId = authorId,
                text = body,
                createdAt = clock(),
            ),
        )
    }

    /** The thread for [parentMessageId], displayed linearly in time order (DESIGN §9). */
    fun thread(parentMessageId: String): List<ThreadReply> =
        threads.byParent(parentMessageId).sortedBy { it.createdAt }

    /** Reply-count for the main-feed indicator, e.g. "↳ 4 replies" (DESIGN §9). */
    fun replyCount(parentMessageId: String): Int = threads.replyCount(parentMessageId)

    /** Drop every thread attached to a message in [roomId] — used when the room empties. */
    fun clearForRoom(roomId: String) {
        val ids = messages.byRoom(roomId).map { it.id }
        if (ids.isNotEmpty()) threads.clear(ids)
    }

    /**
     * Drop threads idle for longer than [INACTIVITY_WINDOW_MS]. A thread's activity is the
     * timestamp of its most recent reply. Returns the number of threads cleared.
     */
    fun sweepInactive(): Int {
        val now = clock()
        val stale = threads.parents().filter { parent ->
            val last = threads.byParent(parent).maxOfOrNull { it.createdAt } ?: return@filter false
            now - last > INACTIVITY_WINDOW_MS
        }
        if (stale.isNotEmpty()) threads.clear(stale)
        return stale.size
    }
}
