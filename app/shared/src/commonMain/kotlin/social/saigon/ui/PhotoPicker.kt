package social.saigon.ui

import androidx.compose.runtime.Composable

// --- Platform photo picking (issue: "Profile and room profile photos") ---

/**
 * A platform image picker for profile-photo uploads. Returns a launcher: invoking it opens the
 * platform's picker (an `<input type="file">` on web, the system content picker on Android) and,
 * once the user chooses an image, calls [onPicked] with the file's raw bytes and mime type.
 */
@Composable
internal expect fun rememberPhotoPicker(onPicked: (bytes: ByteArray, mimeType: String) -> Unit): () -> Unit

/**
 * Page-level drag-and-drop upload support for the photo dialog's "drop or click to upload" area.
 * While [enabled], dropping an image file anywhere on the page hands its bytes to [onDropped].
 * Only meaningful on web (DESIGN §12: web-first); a no-op on Android, where the system picker
 * opened by [rememberPhotoPicker] is the only way in.
 */
@Composable
internal expect fun PhotoDropEffect(enabled: Boolean, onDropped: (bytes: ByteArray, mimeType: String) -> Unit)
