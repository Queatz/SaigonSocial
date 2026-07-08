package social.saigon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get

@Composable
internal actual fun rememberPhotoPicker(onPicked: (bytes: ByteArray, mimeType: String) -> Unit): () -> Unit {
    val current = rememberUpdatedState(onPicked)
    return remember {
        {
            // A detached <input type="file"> — clicking it opens the browser's file chooser.
            val input = document.createElement("input") as HTMLInputElement
            input.type = "file"
            input.accept = "image/*"
            input.onchange = {
                input.files?.get(0)?.let { file ->
                    readImageFile(file) { bytes, type -> current.value(bytes, type) }
                }
            }
            input.click()
        }
    }
}

@Composable
internal actual fun PhotoDropEffect(enabled: Boolean, onDropped: (bytes: ByteArray, mimeType: String) -> Unit) {
    val current = rememberUpdatedState(onDropped)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        // Document-level handlers: Compose renders to a canvas, so per-element DOM drop targets
        // don't exist — while the upload dialog is open, any drop on the page counts.
        val onDragOver: (Event) -> Unit = { it.preventDefault() }
        val onDrop: (Event) -> Unit = { event ->
            event.preventDefault()
            (event as? DragEvent)?.dataTransfer?.files?.get(0)?.let { file ->
                readImageFile(file) { bytes, type -> current.value(bytes, type) }
            }
        }
        document.addEventListener("dragover", onDragOver)
        document.addEventListener("drop", onDrop)
        onDispose {
            document.removeEventListener("dragover", onDragOver)
            document.removeEventListener("drop", onDrop)
        }
    }
}

/** Read [file]'s bytes with a FileReader and hand them (plus the mime type) to [onLoaded]. */
private fun readImageFile(file: File, onLoaded: (ByteArray, String) -> Unit) {
    val reader = FileReader()
    reader.onload = {
        val view = Int8Array(reader.result as ArrayBuffer)
        val bytes = ByteArray(view.length) { i -> view[i] }
        onLoaded(bytes, file.type.ifBlank { "image/jpeg" })
    }
    reader.readAsArrayBuffer(file)
}
