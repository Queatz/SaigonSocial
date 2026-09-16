package social.saigon.ui

/**
 * No-op on Android: there is no browser tab title to drive. The web client is the primary
 * target for document-title sync (DESIGN §12).
 */
actual fun setDocumentTitle(title: String) {}
