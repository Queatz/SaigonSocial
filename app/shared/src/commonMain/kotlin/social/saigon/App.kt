package social.saigon

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import social.saigon.ui.SaigonApp

/**
 * Compose Web app entry point (TASKS Phase 9, DESIGN §12).
 *
 * Hosts the Saigon.Social client shell: room list, room/public chat with occupant list, thread
 * bottom-sheet, private chat, final-emoji selection/reveal, and the profile editor.
 */
@Composable
@Preview
fun App() {
    SaigonApp()
}
