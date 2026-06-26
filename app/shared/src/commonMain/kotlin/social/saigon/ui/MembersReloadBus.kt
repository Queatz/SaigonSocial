package social.saigon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A tiny, process-wide signal that lets the founder's invite section ask the (sibling) member
 * directory to reload itself — e.g. after a new invite code is generated, so an account created
 * from that code shows up. The directory observes [reloadSignal] in a `LaunchedEffect`.
 */
internal object MembersReloadBus {
    /** Bumped each time a reload is requested; observed by the members directory. */
    var reloadSignal by mutableStateOf(0)
        private set

    fun requestReload() {
        reloadSignal += 1
    }
}
