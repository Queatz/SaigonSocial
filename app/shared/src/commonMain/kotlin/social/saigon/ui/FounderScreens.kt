package social.saigon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Founder-only tool: mint a single-use invite code for a new member (replaces sharing a raw access
 * token). The founder picks the new member's tier and shares the short code; the recipient redeems
 * it to create their own account with a username + password. After minting, the member directory
 * is reloaded so any account created from the code shows up.
 */
@Composable
internal fun FounderInviteSection(api: SaigonApi) {
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(Role.SHARER) }
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val invites = remember { mutableStateListOf<InviteCodeView>() }
    var pendingDelete by remember { mutableStateOf<InviteCodeView?>(null) }

    suspend fun reloadInvites() {
        runCatching { api.invites() }.onSuccess {
            invites.clear()
            invites.addAll(it)
        }
    }

    LaunchedEffect(Unit) { reloadInvites() }

    Text(
        "Mint a short invite code, pick the new member's tier, and share the code. They use it to " +
            "create their account with a username and password.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Role.SHARER, Role.SUPPORTER).forEach { option ->
            val selected = option == role
            OutlinedButton(onClick = { role = option }, modifier = Modifier.weight(1f)) {
                Text((if (selected) "✅ " else "") + option.label, fontSize = 12.sp)
            }
        }
    }
    // Spell out what each tier can do so the founder picks the right one for the new member.
    Text(
        when (role) {
            Role.SHARER -> "Sharer: Can chat in the rooms and initiate direct chats with supporters."
            Role.SUPPORTER -> "Supporter: Can create rooms and receive direct chat requests from sharers."
            else -> ""
        },
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    code?.let {
        Text("Invite code (share this with the new member):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(it, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        }
    }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        onClick = {
            error = null
            code = null
            busy = true
            scope.launch {
                try {
                    code = api.createInvite(role).code
                    reloadInvites()
                    // Reload the directory so any account created from a code is reflected.
                    MembersReloadBus.requestReload()
                } catch (e: Throwable) {
                    error = e.message ?: "Could not create an invite code."
                } finally {
                    busy = false
                }
            }
        },
    ) { Text("Generate invite code") }

    // Only active (unused) invite codes are listed so the founder can re-share or delete them;
    // the server returns just the unredeemed codes, so used codes are never loaded here.
    if (invites.isNotEmpty()) {
        Text("Active invite codes", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        invites.forEach { invite ->
            InviteRow(
                invite = invite,
                onDelete = { pendingDelete = invite },
            )
        }
    }

    pendingDelete?.let { invite ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete invite code") },
            text = {
                Text(
                    buildAnnotatedString {
                        append("Delete the invite code ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(invite.code) }
                        append("? It can no longer be redeemed and this can't be undone.")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    error = null
                    scope.launch {
                        runCatching { api.deleteInvite(invite.code) }
                            .onSuccess { reloadInvites() }
                            .onFailure { error = it.message ?: "Could not delete that invite code." }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/** A single founder-visible invite code, with copy and delete controls for codes that haven't been used. */
@Composable
private fun InviteRow(invite: InviteCodeView, onDelete: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Briefly confirms the code landed on the clipboard after "Copy" is tapped, then clears itself.
    var copied by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer { Text(invite.code, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Text(
                    invite.role.label + if (invite.redeemed) " · used" else " · unused",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!invite.redeemed) {
                TextButton(onClick = {
                    copyToClipboard("https://saigon.social/invite/${invite.code}")
                    copied = true
                    scope.launch {
                        delay(1500)
                        copied = false
                    }
                }) { Text(if (copied) "Copied!" else "Copy") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/**
 * Stand-alone page hosting the founder-only member directory ([FounderMembersSection]), reached via
 * the "View members" button on the profile screen. Scrolls independently with the same page padding
 * the profile editor uses so the directory reads as its own screen.
 */
@Composable
internal fun FounderMembersScreen(api: SaigonApi) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FounderMembersSection(api)
    }
}

/**
 * Stand-alone page hosting the founder-only list of recent room searches ([FounderRoomSearchesSection]),
 * reached via the "Room searches" button on the profile screen. Scrolls independently with the same
 * page padding the profile editor uses so it reads as its own screen.
 */
@Composable
internal fun FounderRoomSearchesScreen(api: SaigonApi) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FounderRoomSearchesSection(api)
    }
}

/**
 * Founder-only list of the latest room searches (DESIGN §6). Every time a member or visitor filters
 * the room list, the query is recorded server-side; surfacing the most recent searches here lets the
 * founder understand what kinds of rooms people are looking for.
 */
@Composable
internal fun FounderRoomSearchesSection(api: SaigonApi) {
    val searches = remember { mutableStateListOf<RoomSearchView>() }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        error = null
        try {
            val list = api.roomSearches()
            searches.clear()
            searches.addAll(list)
        } catch (e: Throwable) {
            error = e.message ?: "Could not load room searches"
        } finally {
            loaded = true
        }
    }

    Text("Room searches", fontWeight = FontWeight.Bold)
    Text(
        "The latest searches people ran against the room list, newest first. Use them to understand " +
            "what kinds of rooms people are looking for.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }

    when {
        !loaded -> LoadingSpinner()
        searches.isEmpty() -> Text(
            "No room searches yet.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> searches.forEach { search -> RoomSearchRow(search) }
    }
}

/** A single recorded room search: the query the user typed and how long ago it was run. */
@Composable
private fun RoomSearchRow(search: RoomSearchView) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(search.query, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            val ago = timeAgo(search.createdAt)
            if (ago.isNotEmpty()) {
                Text(ago, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Founder-only member directory (MEMBER_MANAGEMENT.md A & B). Lists everyone the founder has
 * onboarded with their tier, intent, languages, and interests, plus per-member controls to comp
 * them up to Supporter or downgrade to Sharer (each behind a confirmation dialog). The Founder's
 * own row is read-only. A tier change mints a fresh token surfaced so the founder can hand it over.
 */
@Composable
internal fun FounderMembersSection(api: SaigonApi) {
    val scope = rememberCoroutineScope()
    val members = remember { mutableStateListOf<MemberView>() }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<MemberView, Role>?>(null) }
    var pendingRemove by remember { mutableStateOf<MemberView?>(null) }
    var pendingReset by remember { mutableStateOf<MemberView?>(null) }
    var pendingRename by remember { mutableStateOf<MemberView?>(null) }

    suspend fun reload() {
        error = null
        try {
            val list = api.members()
            members.clear()
            members.addAll(list)
        } catch (e: Throwable) {
            error = e.message ?: "Could not load members"
        } finally {
            loaded = true
        }
    }

    LaunchedEffect(Unit) { reload() }
    // Reload whenever an invite code is generated (a redeemed code creates a new member).
    LaunchedEffect(MembersReloadBus.reloadSignal) {
        if (MembersReloadBus.reloadSignal > 0) reload()
    }

    Text("Members", fontWeight = FontWeight.Bold)
    Text(
        text = "Everyone you've onboarded.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    notice?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }

    when {
        !loaded -> LoadingSpinner()
        members.isEmpty() -> Text(
            "No members yet.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> members.forEach { member ->
            MemberRow(
                member = member,
                onChange = { role -> pending = member to role },
                onRemove = { pendingRemove = member },
                onResetPassword = { pendingReset = member },
                onChangeName = { pendingRename = member },
            )
        }
    }

    pending?.let { (member, role) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Change tier") },
            text = {
                Text(
                    "Set ${member.displayName} to ${role.label}? They'll need to sign back in with " +
                        "a new token.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    notice = null
                    scope.launch {
                        notice = try {
                            val res = api.setMemberRole(member.id, role)
                            reload()
                            val handover = res.token?.let { " New token: $it" } ?: ""
                            "Updated ${res.member.displayName} to ${res.member.role.label}.$handover"
                        } catch (e: Throwable) {
                            e.message ?: "Could not change tier"
                        }
                    }
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }

    pendingRemove?.let { member ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove member") },
            text = {
                Text(
                    "Permanently remove ${member.displayName}? Their account and sign-in are deleted " +
                        "and they'll lose access immediately. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    notice = null
                    scope.launch {
                        notice = try {
                            api.removeMember(member.id)
                            reload()
                            "Removed ${member.displayName}."
                        } catch (e: Throwable) {
                            e.message ?: "Could not remove that member."
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }

    pendingRename?.let { member ->
        var newName by remember(member.id) { mutableStateOf(member.displayName) }
        var dialogError by remember(member.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Change name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Set the display name for ${member.displayName}. This is how you tie the " +
                            "account to the verified person, so keep it accurate.",
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        shape = InputFieldShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    dialogError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isBlank()) {
                        dialogError = "A name is required."
                        return@TextButton
                    }
                    notice = null
                    scope.launch {
                        try {
                            val updated = api.setMemberName(member.id, name)
                            pendingRename = null
                            reload()
                            notice = "Renamed to ${updated.displayName}."
                        } catch (e: Throwable) {
                            dialogError = e.message ?: "Could not change that name."
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("Cancel") }
            },
        )
    }

    pendingReset?.let { member ->
        var newPassword by remember(member.id) { mutableStateOf("") }
        var dialogError by remember(member.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text("Reset password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Set a new sign-in password for ${member.displayName}. Share it with them " +
                            "securely; they can change it later from their profile.",
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("New password") },
                        singleLine = true,
                        shape = InputFieldShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    dialogError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val password = newPassword
                    notice = null
                    scope.launch {
                        try {
                            val updated = api.resetMemberPassword(member.id, password)
                            pendingReset = null
                            notice = "Reset the password for ${updated.displayName}."
                        } catch (e: Throwable) {
                            dialogError = e.message ?: "Could not reset that password."
                        }
                    }
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * A single member in the founder directory, with their profile details and tier controls
 * (MEMBER_MANAGEMENT.md A & B), plus founder-only account controls to reset the member's password
 * or permanently remove them. The Founder's own row exposes none of these controls (their role is
 * fixed and their account can't be removed); there is no "Seeker" tier option — members are only
 * ever comped to Supporter or Sharer.
 */
@Composable
private fun MemberRow(
    member: MemberView,
    onChange: (Role) -> Unit,
    onRemove: () -> Unit,
    onResetPassword: () -> Unit,
    onChangeName: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileIcon(name = member.displayName, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(member.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val tier = member.effectiveRole.label +
                        if (member.effectiveRole != member.role) " (on record: ${member.role.label})" else ""
                    Text(tier, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    member.handle?.let {
                        Text("@$it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            member.intents.forEach { Text(it.badge, fontSize = 12.sp) }
            if (member.languages.isNotEmpty()) {
                Text(
                    "Languages: " + member.languages.joinToString(" · ") { UiConstants.languageLabel(it) },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (member.interests.isNotEmpty()) {
                Text(
                    "Interests: " + member.interests.joinToString(", "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (member.role == Role.FOUNDER) {
                Text(
                    "The Founder's role can't be changed.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Role.SUPPORTER, Role.SHARER).forEach { option ->
                        val current = option == member.effectiveRole
                        OutlinedButton(
                            onClick = { onChange(option) },
                            enabled = !current,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                option.label,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // Founder-only account controls: reset the member's sign-in password on their
                // behalf, or permanently remove the account (MEMBER_MANAGEMENT.md).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onChangeName,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Change name",
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = onResetPassword,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Reset password",
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Remove",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
