package social.saigon

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Mount Compose into a dedicated `#app` host element instead of `document.body`. The element's
    // height is bound to `window.visualViewport` (see index.html) so the Compose canvas shrinks to
    // the space above the soft keyboard rather than staying as tall as the layout viewport. Without
    // this the bottom message input is painted behind the keyboard on browsers (e.g. Firefox
    // Android) that overlay the keyboard instead of panning the focused field into view.
    val container = document.getElementById("app") as HTMLElement
    ComposeViewport(container) {
        WithEmojiFallback {
            App()
        }
    }
}
