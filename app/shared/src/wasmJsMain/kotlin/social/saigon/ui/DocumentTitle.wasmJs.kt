package social.saigon.ui

import kotlinx.browser.document

/** Sets the browser tab title via `document.title`. */
actual fun setDocumentTitle(title: String) {
    document.title = title
}
