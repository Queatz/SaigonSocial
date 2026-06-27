package social.saigon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Active (unused) invite codes are listed so the founder can re-share or delete them; used
    // codes are kept as an audit trail and shown as already redeemed.
    if (invites.isNotEmpty()) {
        Text("Active invite codes", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        invites.forEach { invite ->
            InviteRow(
                invite = invite,
                onDelete = {
                    scope.launch {
                        runCatching { api.deleteInvite(invite.code) }
                            .onSuccess { reloadInvites() }
                            .onFailure { error = it.message ?: "Could not delete that invite code." }
                    }
                },
            )
        }
    }
}

/** A single founder-visible invite code, with a delete control for codes that haven't been used. */
@Composable
private fun InviteRow(invite: InviteCodeView, onDelete: () -> Unit) {
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
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/**
 * Founder-only member directory (MEMBER_MANAGEMENT.md A & B). Lists everyone the founder has
 * onboarded with their tier, intent, languages, and introduction, plus per-member controls to comp
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

    HorizontalDivider()
    Text("Members", fontWeight = FontWeight.Bold)
    Text(
        "Everyone you've onboarded, with their tier and profile details. Promote or downgrade a " +
            "member; a tier change issues them a fresh token to sign back in with.",
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
            MemberRow(member = member, onChange = { role -> pending = member to role })
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
}

/**
 * A single member in the founder directory, with their profile details and tier controls
 * (MEMBER_MANAGEMENT.md A & B). The Founder's own row exposes no tier controls (their role is
 * fixed), and there is no "Seeker" option — members are only ever comped to Supporter or Sharer.
 */
@Composable
private fun MemberRow(member: MemberView, onChange: (Role) -> Unit) {
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
            if (member.introduction.isNotEmpty()) {
                Text(
                    "Introduction: " + member.introduction.joinToString(", "),
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
                            Text(option.label, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
