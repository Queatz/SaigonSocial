package social.saigon.ui

/**
 * Cross-platform persistence for the signed-in member's access token (DESIGN §2).
 *
 * The access token is the member's only credential — there is no separate password — so it must
 * survive a page reload / app restart. Without this, signing in would be lost the instant the user
 * refreshed the browser, locking even the founder out of their own account. On the web this is
 * backed by `localStorage`; other targets fall back to an in-memory store.
 */
expect object TokenStore {
    /** The persisted token, or null if the user has never signed in (or signed out). */
    fun load(): String?

    /** Persist [token], or clear the stored token when [token] is null (sign-out). */
    fun save(token: String?)
}
