package social.saigon.ui

import kotlinx.browser.window

actual fun copyToClipboard(text: String) {
    val nav = window.navigator.asDynamic()
    if (nav.clipboard != undefined) {
        nav.clipboard.writeText(text)
    }
}
