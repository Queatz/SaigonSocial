package social.saigon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// --- Profile setup / edit (DESIGN §4) ---

@Composable
internal fun ProfileScreen(
    api: SaigonApi,
    token: String?,
    onToken: (String?) -> Unit,
    onViewMembers: () -> Unit = {},
) {
    if (token == null) {
        SignInPanel(api = api, onToken = onToken)
        return
    }

    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<ProfileResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token) {
        error = null
        try {
            profile = api.profile()
        } catch (e: SessionExpiredException) {
            // The token was rejected by /profile/me (401/400): clear it and drop back to sign-in.
            onToken(null)
        } catch (e: Throwable) {
            error = e.message ?: "Could not load your profile"
        }
    }

    val p = profile
    when {
        error != null -> Centered { Text(error!!, color = MaterialTheme.colorScheme.error) }
        p == null -> Centered { LoadingSpinner() }
        else -> {
            var introduction by remember(p.id) { mutableStateOf(p.interests.joinToString(", ")) }
            val intents = remember(p.id) { p.intents.toMutableStateList() }
            val languages = remember(p.id) { p.languages.toMutableStateList() }
            var availability by remember(p.id) { mutableStateOf(p.offlineSchedule.joinToString("; ")) }

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${p.displayName} · ${p.role.label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = introduction,
                    onValueChange = { introduction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your interests (comma-separated)") },
                    shape = InputFieldShape,
                )

                Text("Intent", fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UiConstants.intents.chunked(2).forEach { rowIntents ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowIntents.forEach { option ->
                                val selected = option in intents
                                OutlinedButton(
                                    onClick = { if (selected) intents.remove(option) else intents.add(option) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text((if (selected) "✅ " else "") + option.badge, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Text("Languages", fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UiConstants.supportedLanguages.chunked(2).forEach { rowLangs ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowLangs.forEach { code ->
                                val selected = code in languages
                                OutlinedButton(
                                    onClick = { if (selected) languages.remove(code) else languages.add(code) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        (if (selected) "✅ " else "") + UiConstants.languageLabel(code),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = availability,
                    onValueChange = { availability = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Times during the week you're often available offline") },
                    shape = InputFieldShape,
                )

                status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        status = null
                        scope.launch {
                            val update = ProfileUpdate(
                                interests = introduction.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                intents = intents.toList(),
                                languages = languages.toList(),
                                offlineSchedule = availability.split(";").map { it.trim() }.filter { it.isNotEmpty() },
                            )
                            status = try {
                                profile = api.updateProfile(update)
                                "Saved."
                            } catch (e: Throwable) {
                                e.message ?: "Could not save"
                            }
                        }
                    },
                ) { Text("Save profile") }

                EmojiStatsSection(api = api, profileId = p.id)

                Accordion(title = "Your username") {
                    UsernameSection(api = api, profile = p, onUpdated = { profile = it })
                }

                Accordion(title = if (p.passwordSet) "Change your password" else "Set a password") {
                    PasswordSection(api = api, profile = p, onUpdated = { profile = it })
                }

                if (p.role == Role.FOUNDER) {
                    Accordion(title = "Create an invite code") {
                        FounderInviteSection(api)
                    }
                    OutlinedButton(onClick = onViewMembers, modifier = Modifier.fillMaxWidth()) {
                        Text("View members")
                    }
                }

                var confirmSignOut by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
                if (confirmSignOut) {
                    AlertDialog(
                        onDismissRequest = { confirmSignOut = false },
                        title = { Text("Sign out?") },
                        text = { Text("You'll need your username and password to sign back in.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmSignOut = false
                                onToken(null)
                            }) { Text("Sign out") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The caller's private emoji stats (DESIGN §4, §8): the tally of closing emojis they have received,
 * shown only here on their own profile and never publicly. Loaded lazily from `emojiStats()`.
 */
@Composable
private fun EmojiStatsSection(api: SaigonApi, profileId: String) {
    var stats by remember(profileId) { mutableStateOf<Map<String, Int>?>(null) }
    LaunchedEffect(profileId) {
        stats = runCatching { api.emojiStats() }.getOrDefault(emptyMap())
    }
    Accordion(title = "Your emoji stats") {
        val current = stats
        when {
            current == null -> LoadingSpinner()
            current.isEmpty() -> Text(
                "No emojis yet. They'll appear here after your private chats wrap up.",
                fontSize = 13.sp,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                current.entries.sortedByDescending { it.value }.forEach { (emoji, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(emoji, fontSize = 20.sp)
                        Text("× $count", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Change-your-username control: lets a member pick a new login username if it isn't taken. */
@Composable
private fun UsernameSection(api: SaigonApi, profile: ProfileResponse, onUpdated: (ProfileResponse) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember(profile.handle) { mutableStateOf(profile.handle.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(
        "This is the username you sign in with. You can change it to any name that isn't already taken.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it; error = null; status = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Username") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy && username.isNotBlank() && username.trim() != profile.handle,
        onClick = {
            error = null
            status = null
            busy = true
            scope.launch {
                try {
                    onUpdated(api.changeUsername(username.trim()))
                    status = "Username updated."
                } catch (e: Throwable) {
                    error = e.message ?: "Could not change your username."
                } finally {
                    busy = false
                }
            }
        },
    ) { Text("Change username") }
}

/**
 * "Set a password" / "Change password" section on the profile (PASSWORD_AUTH.md §2B).
 *
 * After signing in, a member can set a durable password — paired with their login-only
 * [ProfileResponse.handle] username — to sign back in later. Changing an existing password requires
 * the current one. The returned profile is surfaced so the wording flips immediately.
 */
@Composable
private fun PasswordSection(api: SaigonApi, profile: ProfileResponse, onUpdated: (ProfileResponse) -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember(profile.passwordSet) { mutableStateOf("") }
    var next by remember(profile.passwordSet) { mutableStateOf("") }
    var confirm by remember(profile.passwordSet) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(
        buildString {
            append("Set a password to sign back in later")
            profile.handle?.let { append(" — your sign-in username is \"$it\"") }
            append(".")
        },
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (profile.passwordSet) {
        OutlinedTextField(
            value = current,
            onValueChange = { current = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Current password") },
            singleLine = true,
            shape = InputFieldShape,
            enabled = !busy,
        )
    }
    OutlinedTextField(
        value = next,
        onValueChange = { next = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("New password (min 8 characters)") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    OutlinedTextField(
        value = confirm,
        onValueChange = { confirm = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Confirm new password") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        onClick = {
            error = null
            status = null
            when {
                next.length < 8 -> error = "Password must be at least 8 characters."
                next != confirm -> error = "Passwords don't match."
                else -> {
                    busy = true
                    scope.launch {
                        try {
                            val updated = api.setPassword(next, current.ifBlank { null })
                            onUpdated(updated)
                            status = "Password saved."
                        } catch (e: Throwable) {
                            error = e.message ?: "Could not save your password."
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        },
    ) { Text(if (profile.passwordSet) "Update password" else "Save password") }
}
