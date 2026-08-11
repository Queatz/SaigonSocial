package social.saigon.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberPhotoPicker(onPicked: (bytes: ByteArray, mimeType: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val current = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val type = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) current.value(bytes, type)
        }
    }
    return { launcher.launch("image/*") }
}

@Composable
internal actual fun PhotoDropEffect(
    enabled: Boolean,
    onDropped: (bytes: ByteArray, mimeType: String) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
) {
    // Drag-and-drop is a web affordance (DESIGN §12: web-first); on Android the system picker
    // opened by [rememberPhotoPicker] is the only way in.
}
