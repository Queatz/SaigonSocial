package social.saigon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
    onViewRoomSearches: () -> Unit = {},
    onViewServiceSearches: () -> Unit = {},
    onViewMyServices: () -> Unit = {},
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
            // Custom "Here for" badges the member has beyond the launch set (DESIGN §4): any intent
            // on their profile that isn't one of the fixed options, plus any created this session.
            val customIntents = remember(p.id) {
                (p.intents - UiConstants.intents.toSet()).toMutableStateList()
            }
            var addingIntent by remember(p.id) { mutableStateOf(false) }
            val languages = remember(p.id) { p.languages.toMutableStateList() }
            // Custom languages the member has beyond the launch set (DESIGN §4): any language on
            // their profile that isn't one of the fixed codes, plus any added this session.
            val customLanguages = remember(p.id) {
                (p.languages - UiConstants.supportedLanguages.toSet()).toMutableStateList()
            }
            var addingLanguage by remember(p.id) { mutableStateOf(false) }
            var availability by remember(p.id) { mutableStateOf(p.offlineSchedule.joinToString("; ")) }
            // Availability is a Supporter perk (DESIGN §3, §7); Founders share it too.
            val isSupporter = p.role == Role.SUPPORTER || p.role == Role.FOUNDER

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The member's profile photo (issue: "Profile and community profile photos"): a large,
                // centered photo button at the top; clicking it opens the upload dialog.
                ProfilePhotoSection(api = api, profile = p, onUpdated = { profile = it })

                if (p.role == Role.SHARER) {
                    BecomeSupporterBubble()
                }

                Text("${p.displayName} · ${p.role.label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = introduction,
                    onValueChange = { introduction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your interests (comma-separated)") },
                    shape = InputFieldShape,
                )

                Text("Here for", fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The launch set plus any custom badges the member has, so their own "Here for"
                    // badges stay selectable alongside the defaults (DESIGN §4).
                    (UiConstants.intents + customIntents).distinct().chunked(2).forEach { rowIntents ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowIntents.forEach { option ->
                                val selected = option in intents
                                DimmableToggleButton(
                                    selected = selected,
                                    onClick = { if (selected) intents.remove(option) else intents.add(option) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text((if (selected) "✅ " else "") + option, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    // Supporters/Founders can craft their own custom "Here for" badge (DESIGN §3, §4).
                    if (isSupporter) {
                        OutlinedButton(
                            onClick = { addingIntent = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("➕ Add your own", fontSize = 12.sp) }
                    }
                }
                if (addingIntent) {
                    AddIntentDialog(
                        existing = (UiConstants.intents + customIntents).distinct(),
                        onDismiss = { addingIntent = false },
                        onCreate = { badge ->
                            if (badge !in customIntents && badge !in UiConstants.intents) {
                                customIntents.add(badge)
                            }
                            // Select the new badge right away so a save persists it.
                            if (badge !in intents) intents.add(badge)
                            addingIntent = false
                        },
                    )
                }

                Text("Languages", fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The launch set plus any custom languages the member has, so their own
                    // languages stay selectable alongside the defaults (DESIGN §4).
                    (UiConstants.supportedLanguages + customLanguages).distinct().chunked(2).forEach { rowLangs ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowLangs.forEach { code ->
                                val selected = code in languages
                                DimmableToggleButton(
                                    selected = selected,
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
                    // Supporters/Founders can add their own language beyond the launch set (DESIGN §3, §4).
                    if (isSupporter) {
                        OutlinedButton(
                            onClick = { addingLanguage = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("➕ Add your own", fontSize = 12.sp) }
                    }
                }
                if (addingLanguage) {
                    AddLanguageDialog(
                        existing = (UiConstants.supportedLanguages + customLanguages).distinct()
                            .map { UiConstants.languageLabel(it) },
                        onDismiss = { addingLanguage = false },
                        onCreate = { language ->
                            if (language !in customLanguages && language !in UiConstants.supportedLanguages) {
                                customLanguages.add(language)
                            }
                            // Select the new language right away so a save persists it.
                            if (language !in languages) languages.add(language)
                            addingLanguage = false
                        },
                    )
                }

                if (isSupporter) {
                    Text("Supporter", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = availability,
                        onValueChange = { availability = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Times during the week when you're often available") },
                        shape = InputFieldShape,
                    )
                }

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
                                offlineSchedule = if (isSupporter) {
                                    availability.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                                } else {
                                    null
                                },
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

                if (isSupporter) {
                    OutlinedButton(onClick = onViewMyServices, modifier = Modifier.fillMaxWidth()) {
                        Text("Adventures hosted by you")
                    }
                }

                EmojiStatsSection(api = api, profileId = p.id)

                Accordion(title = "Your username") {
                    UsernameSection(api = api, profile = p, onUpdated = { profile = it })
                }

                Accordion(title = if (p.passwordSet) "Change your password" else "Set a password") {
                    PasswordSection(api = api, profile = p, onUpdated = { profile = it })
                }

                Accordion(title = "Your name") {
                    NameSection(api = api, profile = p, onUpdated = { profile = it })
                }

                if (p.role == Role.FOUNDER) {
                    Accordion(title = "Invite requests") {
                        FounderInviteRequestsSection(api)
                    }
                    Accordion(title = "Create an invite code") {
                        FounderInviteSection(api)
                    }
                    OutlinedButton(onClick = onViewMembers, modifier = Modifier.fillMaxWidth()) {
                        Text("View members")
                    }
                    OutlinedButton(onClick = onViewRoomSearches, modifier = Modifier.fillMaxWidth()) {
                        Text("Community searches")
                    }
                    OutlinedButton(onClick = onViewServiceSearches, modifier = Modifier.fillMaxWidth()) {
                        Text("Adventure searches")
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
 * The profile-photo area at the top of the profile screen (issue: "Profile and community profile
 * photos"): a large, centered profile button (the photo, or the letter avatar until one exists)
 * with its label underneath. Clicking it opens [PhotoUploadDialog]. Previous profile photos are
 * shown below as vertical cards in a wrapping flex row; clicking a card opens the "Edit this photo"
 * dialog to replace, recaption, or delete it.
 */
@Composable
private fun ProfilePhotoSection(
    api: SaigonApi,
    profile: ProfileResponse,
    onUpdated: (ProfileResponse) -> Unit,
) {
    var showUpload by remember(profile.id) { mutableStateOf(false) }
    var editingPhoto by remember(profile.id) { mutableStateOf<ProfilePhoto?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Clip to a circle *before* the clickable so the ripple stays inside the photo's bounds.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { showUpload = true },
        ) {
            ProfileIcon(name = profile.displayName, size = 96.dp, photoUrl = profile.photo?.url)
        }
        Text(
            if (profile.photo == null) "Add a profile photo" else "Change your profile photo",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        profile.photo?.message?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }

    // Previous profile photos: vertical cards in a wrapping flex row; click a card to edit it.
    if (profile.previousPhotos.isNotEmpty()) {
        Text("Previous profile photos", fontWeight = FontWeight.Bold)
        Text(
            "Click a photo to edit it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PhotoCardsRow(
            title = profile.displayName,
            photos = profile.previousPhotos,
            onPhotoClick = { editingPhoto = it },
        )
    }

    if (showUpload) {
        PhotoUploadDialog(
            api = api,
            title = if (profile.photo == null) "Add a profile photo" else "Update your profile photo",
            currentPhoto = profile.photo,
            onDismiss = { showUpload = false },
            // A new URL becomes the main photo (rotating the old one into "previous"); an unchanged
            // URL just re-captions it.
            onSaved = { photo ->
                onUpdated(api.setProfilePhoto(photo.url, photo.message))
                showUpload = false
            },
        )
    }

    editingPhoto?.let { photo ->
        PhotoUploadDialog(
            api = api,
            title = "Edit this photo",
            currentPhoto = photo,
            onDismiss = { editingPhoto = null },
            onSaved = { updated ->
                onUpdated(api.updateProfilePhoto(photo.url, updated.url, updated.message))
                editingPhoto = null
            },
            onDelete = {
                onUpdated(api.deleteProfilePhoto(photo.url))
                editingPhoto = null
            },
        )
    }
}

/**
 * A dialog for a Supporter/Founder to craft their own custom "Here for"/intent badge (DESIGN §3,
 * §4). Now that intents are free-text rather than a fixed enum, a member can type any short badge
 * (ideally an emoji + a couple of words, e.g. "🎨 Art Buddies"). The badge is trimmed, checked for
 * blanks and duplicates, then handed to [onCreate], which adds it to the selectable set.
 */
@Composable
private fun AddIntentDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    val duplicate = trimmed.isNotEmpty() && existing.any { it.equals(trimmed, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a \"Here for\" badge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Add your own badge — try leading with an emoji, e.g. \"🎨 Art Buddies\".",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Badge") },
                    singleLine = true,
                    shape = InputFieldShape,
                )
                if (duplicate) {
                    Text(
                        "You already have that badge.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !duplicate,
                onClick = { onCreate(trimmed) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A dialog for a Supporter/Founder to add their own language beyond the launch set (DESIGN §3, §4).
 * Now that languages are free-text rather than a fixed set, a member can type any language name
 * (ideally leading with a flag emoji, e.g. "🇵🇭 Tagalog"). The value is trimmed, checked for blanks
 * and duplicates (against existing labels), then handed to [onCreate], which adds it to the
 * selectable set.
 */
@Composable
private fun AddLanguageDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    val duplicate = trimmed.isNotEmpty() && existing.any { it.equals(trimmed, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a language") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Add your own language — try leading with a flag, e.g. \"🇵🇭 Tagalog\".",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Language") },
                    singleLine = true,
                    shape = InputFieldShape,
                )
                if (duplicate) {
                    Text(
                        "You already have that language.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !duplicate,
                onClick = { onCreate(trimmed) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * An accept-coloured info banner shown at the top of a Sharer's profile, nudging them toward the
 * Supporter tier. Supporters/Founders already have these capabilities, and Seekers can't reach this
 * screen, so it only renders for Sharers. The forest-green primary container reads as the platform's
 * "accept"/go colour.
 */
@Composable
private fun BecomeSupporterBubble() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Become a supporter!",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Talk with the founder team to become a supporter and start accepting direct chats from people!",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "🤝 Host your adventures on your profile",
                    "🎯 Tailor your profile in each community",
                    "🏠 Create your own communities",
                    "💬 Accept direct chats from people",
                ).forEach { line ->
                    Text(
                        "• $line",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
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
    Accordion(title = "Direct chat emoji stats") {
        val current = stats
        when {
            current == null -> LoadingSpinner()
            current.isEmpty() -> Text(
                "No emojis yet. They'll appear here after your direct chats wrap up.",
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
            visualTransformation = PasswordVisualTransformation(),
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
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    OutlinedTextField(
        value = confirm,
        onValueChange = { confirm = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Confirm new password") },
        visualTransformation = PasswordVisualTransformation(),
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

/**
 * "Your name" section on the profile.
 *
 * Explains to the user their display name. If they are a Founder, allows them to change it.
 */
@Composable
private fun NameSection(api: SaigonApi, profile: ProfileResponse, onUpdated: (ProfileResponse) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val isFounder = profile.role == Role.FOUNDER

    Text(
        if (isFounder) {
            "As a Founder, you can change your display name here."
        } else {
            "This is your display name. Only the founder can change your display name on your behalf to match your verified IRL identity."
        },
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = name,
        onValueChange = { name = it; error = null; status = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Display name") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = isFounder && !busy,
    )

    if (isFounder) {
        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && name.isNotBlank() && name.trim() != profile.displayName,
            onClick = {
                error = null
                status = null
                busy = true
                scope.launch {
                    try {
                        val updated = api.setMemberName(profile.id, name.trim())
                        onUpdated(profile.copy(displayName = updated.displayName))
                        status = "Name updated."
                    } catch (e: Throwable) {
                        error = e.message ?: "Could not change your name."
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("Change name") }
    }
}
