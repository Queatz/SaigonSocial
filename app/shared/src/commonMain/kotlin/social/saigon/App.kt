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
 * [baseUrl] is where the client talks to the API; [contentUrl] is where it uploads/loads profile
 * photos (the `:content` server). Each platform supplies them: web bakes both via the deploy
 * script, Android passes its build-type `BuildConfig.API_BASE_URL` / `BuildConfig.CONTENT_BASE_URL`
 * (debug → local dev servers, release → production). Both default to the [SaigonApi] dev constants
 * for previews and callers that don't override them.
 */
@Composable
@Preview
fun App(
    baseUrl: String = SaigonApi.DEFAULT_BASE_URL,
    contentUrl: String = SaigonApi.DEFAULT_CONTENT_URL,
) {
    SaigonApp(baseUrl, contentUrl)
}
