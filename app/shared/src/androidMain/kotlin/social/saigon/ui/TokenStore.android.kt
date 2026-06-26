package social.saigon.ui

/**
 * In-memory token store for the Android target. Android persistence would require an Activity/
 * application [android.content.Context] that this shared module does not hold; the durable,
 * reload-surviving store that the issue is about is the web one (`localStorage`).
 */
actual object TokenStore {
    @Volatile
    private var cached: String? = null

    actual fun load(): String? = cached

    actual fun save(token: String?) {
        cached = token
    }
}
