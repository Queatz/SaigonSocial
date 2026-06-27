package social.saigon.ui

/**
 * No-op on Android: the shareable, URL-backed flow this powers is the web one. Copying to the
 * Android system clipboard would need an Activity/Context, which this shared composable does not hold.
 */
actual fun copyToClipboard(text: String) {}
