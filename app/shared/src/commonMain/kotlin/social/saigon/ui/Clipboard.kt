package social.saigon.ui

/**
 * Copy [text] to the system clipboard. On the web this is backed by the async Clipboard API
 * (`navigator.clipboard`); targets without a clipboard (Android, here) fall back to a no-op.
 */
expect fun copyToClipboard(text: String)
