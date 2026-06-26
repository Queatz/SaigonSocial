package social.saigon.ui

private const val TOKEN_KEY = "saigon.social.accessToken"

/** Browser-backed token store using `window.localStorage`, so sign-in survives page reloads. */
actual object TokenStore {
    actual fun load(): String? = lsGet(TOKEN_KEY)

    actual fun save(token: String?) {
        if (token == null) lsRemove(TOKEN_KEY) else lsSet(TOKEN_KEY, token)
    }
}

private fun lsGet(key: String): String? = js("window.localStorage.getItem(key)") as? String

private fun lsSet(key: String, value: String) {
    js("window.localStorage.setItem(key, value)")
}

private fun lsRemove(key: String) {
    js("window.localStorage.removeItem(key)")
}
