package social.saigon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
            "Saigon.Social is founder-managed — there's no open sign-up. Got an invite code from the " +
                "founder? Use it to create your account below. Already have an account? Sign in with " +
                "your username and password. Without an account you can still browse rooms and read " +
                "public chat.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CreateAccountSection(api = api, onToken = onToken)

        HorizontalDivider()

        SignInSection(api = api, onToken = onToken)

        if (founderClaimable) {
            HorizontalDivider()

            FounderClaimSection(api = api, onToken = onToken)
        }
    }
}

/** Redeem an invite code to create an account by choosing a username + password. */
@Composable
private fun CreateAccountSection(api: SaigonApi, onToken: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Create your account", fontWeight = FontWeight.Bold)
    Text(
        "Enter the invite code the founder gave you, then choose a username and password.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    Text(
        "Sign in with the username and password for your account.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
