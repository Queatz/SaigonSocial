package social.saigon

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import social.saigon.ui.SaigonApi
import social.saigon.ui.SaigonApp

/**
 * Compose Web app entry point (TASKS Phase 9, DESIGN §12).
 *
 * Hosts the Saigon.Social client shell: room list, room/public chat with occupant list, thread
 * bottom-sheet, private chat, final-emoji selection/reveal, and the profile editor.
 *
 * [baseUrl] is where the client talks to the API. Each platform supplies it: web bakes it via the
 * deploy script, Android passes its build-type `BuildConfig.API_BASE_URL` (debug → local dev server,
 * release → production). Defaults to [SaigonApi.DEFAULT_BASE_URL] for previews and callers that
 * don't override it.
 */
@Composable
@Preview
fun App(baseUrl: String = SaigonApi.DEFAULT_BASE_URL) {
    SaigonApp(baseUrl)
}
