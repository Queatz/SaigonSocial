package social.saigon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Wasm's JS interop can't hand raw ArrayBuffers to Kotlin the way the JS target can, so the glue
// functions below read the picked/dropped file in JS and pass its bytes back base64-encoded.

private fun openPhotoPickerJs(onPicked: (JsString, JsString) -> Unit): Unit = js(
    """
    {
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.onchange = function () {
            var file = input.files && input.files[0];
            if (!file) return;
            var reader = new FileReader();
            reader.onload = function () {
                var bytes = new Uint8Array(reader.result);
                var bin = '';
                for (var i = 0; i < bytes.length; i++) { bin += String.fromCharCode(bytes[i]); }
                onPicked(btoa(bin), file.type || 'image/jpeg');
            };
            reader.readAsArrayBuffer(file);
        };
        input.click();
    }
    """,
)

private fun registerPhotoDropJs(onDropped: (JsString, JsString) -> Unit): Unit = js(
    """
    {
        window.saigonPhotoDragOver = function (event) { event.preventDefault(); };
        window.saigonPhotoDrop = function (event) {
            event.preventDefault();
            var file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
            if (!file) return;
            var reader = new FileReader();
            reader.onload = function () {
                var bytes = new Uint8Array(reader.result);
                var bin = '';
                for (var i = 0; i < bytes.length; i++) { bin += String.fromCharCode(bytes[i]); }
                onDropped(btoa(bin), file.type || 'image/jpeg');
            };
            reader.readAsArrayBuffer(file);
        };
        document.addEventListener('dragover', window.saigonPhotoDragOver);
        document.addEventListener('drop', window.saigonPhotoDrop);
    }
    """,
)

private fun unregisterPhotoDropJs(): Unit = js(
    """
    {
        if (window.saigonPhotoDragOver) {
            document.removeEventListener('dragover', window.saigonPhotoDragOver);
            window.saigonPhotoDragOver = null;
        }
        if (window.saigonPhotoDrop) {
            document.removeEventListener('drop', window.saigonPhotoDrop);
            window.saigonPhotoDrop = null;
        }
    }
    """,
)

/** Decode the glue functions' base64 payload back into bytes and forward it. */
@OptIn(ExperimentalEncodingApi::class)
private fun deliverPhoto(base64: JsString, mimeType: JsString, onBytes: (ByteArray, String) -> Unit) {
    runCatching { Base64.decode(base64.toString()) }
        .getOrNull()
        ?.let { bytes -> onBytes(bytes, mimeType.toString().ifBlank { "image/jpeg" }) }
}

@Composable
internal actual fun rememberPhotoPicker(onPicked: (bytes: ByteArray, mimeType: String) -> Unit): () -> Unit {
    val current = rememberUpdatedState(onPicked)
    return remember {
        {
            openPhotoPickerJs { base64, mime ->
                deliverPhoto(base64, mime) { bytes, type -> current.value(bytes, type) }
            }
        }
    }
}

@Composable
internal actual fun PhotoDropEffect(enabled: Boolean, onDropped: (bytes: ByteArray, mimeType: String) -> Unit) {
    val current = rememberUpdatedState(onDropped)
    DisposableEffect(enabled) {
        if (enabled) {
            // Document-level handlers: Compose renders to a canvas, so per-element DOM drop
            // targets don't exist — while the upload dialog is open, any drop on the page counts.
            registerPhotoDropJs { base64, mime ->
                deliverPhoto(base64, mime) { bytes, type -> current.value(bytes, type) }
            }
        }
        onDispose { if (enabled) unregisterPhotoDropJs() }
    }
}
