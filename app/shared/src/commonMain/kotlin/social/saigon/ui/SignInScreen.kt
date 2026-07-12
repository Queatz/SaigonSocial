package social.saigon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The members-only entry screen. Saigon.Social is founder-managed: there is no open sign-up. A new
 * member creates their account by redeeming a short invite code the founder gave them (choosing a
 * username + password), and returning members sign in with that username + password.
 */
@Composable
internal fun SignInPanel(api: SaigonApi, onToken: (String?) -> Unit) {
    // The Founder seat can be claimed only until the first member exists (DESIGN §2). Probe the
    // server once so the claim affordance is shown only when it would actually succeed.
    var founderClaimable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        founderClaimable = api.bootstrapAvailable()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Members only", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Supporter managed, high trust, invite only.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CreateAccountSection(api = api, onToken = onToken)

        HorizontalDivider()

        SignInSection(api = api, onToken = onToken)

        HorizontalDivider()

        RequestInviteSection(api = api)

        if (founderClaimable) {
            HorizontalDivider()

            FounderClaimSection(api = api, onToken = onToken)
        }
    }
}

/**
 * "Request an Invite" at the bottom of the sign-in screen. Opens a dialog collecting the person's
 * full name, Zalo number, and a short description of the services they'd like to provide. On submit
 * it stores a pending invite request server-side (no account is created) and shows a success note —
 * the founder follows up manually (e.g. via Zalo).
 */
@Composable
private fun RequestInviteSection(api: SaigonApi) {
    var showDialog by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }

    Text("Not a member yet?", fontWeight = FontWeight.Bold)
    if (submitted) {
        Text(
            "Thanks! We've received your request and will reach out to you on Zalo.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = { showDialog = true },
    ) { Text("Request an Invite") }

    if (showDialog) {
        RequestInviteDialog(
            api = api,
            onDismiss = { showDialog = false },
            onSubmitted = { showDialog = false; submitted = true },
        )
    }
}

@Composable
private fun RequestInviteDialog(api: SaigonApi, onDismiss: () -> Unit, onSubmitted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var zalo by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Request an Invite") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Tell us a little about you. The founder will reach out on Zalo — no account is created automatically.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Full name") },
                    singleLine = true,
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = zalo,
                    onValueChange = { zalo = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Zalo number") },
                    singleLine = true,
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Services you'd like to provide") },
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && zalo.isNotBlank(),
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            api.requestInvite(name.trim(), zalo.trim(), description.trim())
                            onSubmitted()
                        } catch (e: Throwable) {
                            error = e.message ?: "Could not submit your request."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Submit") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun InvitePanel(api: SaigonApi, initialCode: String, onToken: (String?) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("You've been invited", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Welcome to Saigon.Social! Complete your account below.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CreateAccountSection(api = api, initialCode = initialCode, onToken = onToken)
    }
}

/** Redeem an invite code to create an account by choosing a username + password. */
@Composable
private fun CreateAccountSection(api: SaigonApi, initialCode: String = "", onToken: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(initialCode) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Create your account", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Invite code") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Username") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Password (min 8 characters)") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy && code.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
        onClick = {
            error = null
            busy = true
            scope.launch {
                try {
                    val res = api.redeemInvite(code.trim(), username.trim(), password)
                    onToken(res.token)
                } catch (e: Throwable) {
                    error = e.message ?: "Could not create your account."
                } finally {
                    busy = false
                }
            }
        },
    ) { Text("Create account") }
}

/**
 * Sign back in with the username + password set when the account was created (replaces the old
 * "Already set a password?" copy). Bad credentials show a generic inline error.
 */
@Composable
private fun SignInSection(api: SaigonApi, onToken: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Sign in", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = identifier,
        onValueChange = { identifier = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Username") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; error = null },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Password") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        onClick = {
            error = null
            busy = true
            scope.launch {
                try {
                    val res = api.login(identifier.trim(), password)
                    onToken(res.token)
                } catch (e: Throwable) {
                    error = e.message ?: "Invalid username and password."
                } finally {
                    busy = false
                }
            }
        },
    ) { Text("Sign in") }
}

/**
 * First-run only: claim the Founder account. Available until the first member exists (DESIGN §2),
 * after which the server closes it. Tucked away since it's a one-time bootstrap action.
 */
@Composable
private fun FounderClaimSection(api: SaigonApi, onToken: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(
        "First time here? Claim the Founder account — available only until the first member exists.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    TextButton(
        enabled = !busy,
        onClick = {
            error = null
            busy = true
            scope.launch {
                try {
                    onToken(api.bootstrapFounder().token)
                } catch (e: Throwable) {
                    error = e.message ?: "Could not start a founder account."
                } finally {
                    busy = false
                }
            }
        },
    ) { Text("Claim the Founder account") }
}
