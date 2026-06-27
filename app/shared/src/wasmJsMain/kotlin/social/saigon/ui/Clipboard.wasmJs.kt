package social.saigon.ui

private fun writeClipboard(text: String): Unit =
    js("{ if (navigator.clipboard) { navigator.clipboard.writeText(text); } }")

actual fun copyToClipboard(text: String) {
    writeClipboard(text)
}
