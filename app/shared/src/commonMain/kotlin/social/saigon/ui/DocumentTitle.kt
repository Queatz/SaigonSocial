package social.saigon.ui

/**
 * Set the browser tab / webpage title so it stays in sync with the in-app top bar (room and
 * service detail screens especially). On the web this writes `document.title`; other targets
 * (Android) are a no-op.
 */
expect fun setDocumentTitle(title: String)

/** Brand suffix appended to non-home browser tab titles. */
private const val DOCUMENT_TITLE_BRAND = "Saigon.Social"

/**
 * Browser tab title from the in-app top-bar string. Keeps the home title as-is; every other
 * screen gets " • Saigon.Social" so the tab stays branded.
 */
internal fun documentTitle(topBarTitle: String): String =
    if (topBarTitle == DOCUMENT_TITLE_BRAND) topBarTitle else "$topBarTitle • $DOCUMENT_TITLE_BRAND"

/**
 * Top-bar title for a room / public chat: emoji + name, matching the room list and top bar.
 */
internal fun roomTopBarTitle(emoji: String, name: String): String = "$emoji $name".trim()

/**
 * Top-bar title for a service detail screen. Prefers the loaded name (with emoji when present);
 * falls back to the generic adventures list title until the detail fetch completes.
 */
internal fun serviceTopBarTitle(serviceName: String?, serviceEmoji: String = ""): String =
    serviceName?.let { name ->
        val emoji = serviceEmoji.trim()
        if (emoji.isNotEmpty()) "$emoji $name" else name
    } ?: "Meets"
