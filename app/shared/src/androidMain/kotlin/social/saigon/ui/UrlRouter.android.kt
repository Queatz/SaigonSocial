package social.saigon.ui

/**
 * No-op router for the Android target: there is no browser address bar or history stack to drive,
 * so the app stays on its in-memory navigation. The durable, history-backed routing the issue is
 * about is the web one (`window.history`).
 */
actual class UrlRouter actual constructor() {
    actual fun current(): String = "/"

    actual fun currentUrl(): String = "/"

    actual fun push(path: String) {}

    actual fun replace(path: String) {}

    actual fun onPopState(listener: (String) -> Unit): () -> Unit = {}
}
