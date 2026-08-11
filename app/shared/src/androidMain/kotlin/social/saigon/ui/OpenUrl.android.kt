package social.saigon.ui

/**
 * Android has no Activity/Context here (same constraint as [copyToClipboard]); web is the primary
 * target (DESIGN §12), so opening external URLs is a no-op on this stub target.
 */
actual fun openUrl(url: String) {}
