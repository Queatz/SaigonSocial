package social.saigon.ui

/**
 * Cross-platform binding to the browser's address bar + history stack (DESIGN §12).
 *
 * The web client mirrors its current screen into the URL so links are shareable and the browser's
 * Back/Forward buttons walk the in-app navigation:
 *
 *  - `/`                        → the room list (home)
 *  - `/room/{id}`               → a room / public chat
 *  - `/room/{id}/thread/{id}`   → that room with the thread sheet open
 *  - `/chat/{roomId}/{userId}`  → a one-on-one private chat (DESIGN §7)
 *
 * On the web this is backed by `window.location` + `window.history`; other targets (Android) fall
 * back to a no-op router since there is no browser history to drive.
 */
expect class UrlRouter() {
    /** The current path, e.g. "/" or "/room/abc/thread/xyz". */
    fun current(): String

    /** Navigate forward, adding a new entry to the history back stack. */
    fun push(path: String)

    /** Replace the current history entry in place, without growing the back stack. */
    fun replace(path: String)

    /**
     * Observe Back/Forward navigations (the browser `popstate` event), delivering the new path.
     * Returns a disposer that unregisters the listener.
     */
    fun onPopState(listener: (String) -> Unit): () -> Unit
}
