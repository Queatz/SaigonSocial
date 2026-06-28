package social.saigon.ui

/**
 * Android router. There is no browser address bar or history stack to drive, so navigation lives in
 * memory: the app pushes/replaces paths as the user moves between screens and we keep the latest one
 * so the share dialog can show a full, shareable link.
 *
 * The current path is held in shared (process-global) state rather than per-instance, because the
 * code that drives navigation and the code that reads the path for sharing each construct their own
 * `UrlRouter`. On the web every instance reads the one global `window.location`; mirroring that here
 * keeps a `currentUrl()` lookup consistent no matter which instance asks.
 *
 * The shareable link points at the public web client (`https://saigon.social`) since that is where a
 * recipient can open it — the path mirrors the same routes the web client uses (see [screenToPath]).
 */
actual class UrlRouter actual constructor() {
    actual fun current(): String = path

    actual fun currentUrl(): String = WEB_HOST + path

    actual fun push(path: String) {
        Companion.path = path
    }

    actual fun replace(path: String) {
        Companion.path = path
    }

    actual fun onPopState(listener: (String) -> Unit): () -> Unit = {}

    private companion object {
        /** Canonical public web host the in-app paths resolve against for shareable links. */
        const val WEB_HOST = "https://saigon.social"

        /** The latest in-app path, shared across all instances (mirrors the web's single location). */
        var path: String = "/"
    }
}
