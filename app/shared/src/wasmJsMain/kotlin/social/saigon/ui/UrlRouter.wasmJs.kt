package social.saigon.ui

import kotlinx.browser.window
import org.w3c.dom.events.Event

/** Browser-backed router using `window.location` + `window.history` (DESIGN §12). */
actual class UrlRouter actual constructor() {
    actual fun current(): String = window.location.pathname

    actual fun currentUrl(): String = window.location.href

    actual fun push(path: String) {
        window.history.pushState(null, "", path)
    }

    actual fun replace(path: String) {
        window.history.replaceState(null, "", path)
    }

    actual fun onPopState(listener: (String) -> Unit): () -> Unit {
        val handler: (Event) -> Unit = { listener(window.location.pathname) }
        window.addEventListener("popstate", handler)
        return { window.removeEventListener("popstate", handler) }
    }
}
