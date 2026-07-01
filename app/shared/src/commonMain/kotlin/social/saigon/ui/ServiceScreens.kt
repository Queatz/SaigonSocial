package social.saigon.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The `/services` search screen (issue §5). A search bar filters services by name (case-insensitive
 * partial match, done server-side), and each result card shows the emoji, name, a truncated
 * description, the room the service lives in, and the provider (with their role badge + a couple of
 * readily-available profile facets). Tapping a card opens the service detail screen.
 */
@Composable
internal fun ServiceSearchScreen(api: SaigonApi, onOpenService: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<ServiceView>() }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Reload whenever the query changes (debounce-free; the list is small and the filter is cheap).
    LaunchedEffect(query) {
        error = null
        try {
            val list = api.services(query)
            results.clear()
            results.addAll(list)
        } catch (e: Throwable) {
            error = e.message ?: "Could not load services"
        } finally {
            loaded = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search services by name") },
            singleLine = true,
            shape = InputFieldShape,
        )

        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }

        when {
            !loaded -> LoadingSpinner()
            results.isEmpty() -> Text(
                if (query.isBlank()) "No services yet." else "No services match \"$query\".",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                results.forEach { service ->
                    ServiceCard(service = service, onClick = { onOpenService(service.id) })
                }
            }
        }
    }
}

/** A single result card in the service search list. */
@Composable
private fun ServiceCard(service: ServiceView, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(service.emoji.ifBlank { "🔧" }, fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(service.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (service.description.isNotBlank()) {
                    Text(
                        service.description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "🏠 ${service.roomName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "by ${nameWithBadge(service.providerName, service.providerRole)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val facets = buildList {
                    if (service.providerLanguages.isNotEmpty()) {
                        add(service.providerLanguages.joinToString(" · ") { UiConstants.languageLabel(it) })
                    }
                    if (service.providerInterests.isNotEmpty()) {
                        add(service.providerInterests.joinToString(" · "))
                    }
                }
                if (facets.isNotEmpty()) {
                    Text(
                        facets.joinToString("  •  "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The service detail screen (issue §6). Shows the full service info (emoji, name, description,
 * provider with role badge, room), the aggregated feedback display, and a "Contact provider"
 * section whose "Send message" button opens (or reuses) a direct chat seeded with the typed
 * message and navigates the caller straight into that conversation.
 */
@Composable
internal fun ServiceDetailScreen(
    api: SaigonApi,
    serviceId: String,
    selfUserId: String?,
    isFounder: Boolean,
    canContact: Boolean,
    onOpenDirectChat: (ContactProviderResponse) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var service by remember(serviceId) { mutableStateOf<ServiceView?>(null) }
    val feedback = remember(serviceId) { mutableStateListOf<FeedbackView>() }
    var loaded by remember(serviceId) { mutableStateOf(false) }
    var error by remember(serviceId) { mutableStateOf<String?>(null) }

    suspend fun reloadFeedback() {
        runCatching { api.serviceFeedback(serviceId) }.onSuccess {
            feedback.clear()
            feedback.addAll(it)
        }
    }

    LaunchedEffect(serviceId) {
        error = null
        try {
            service = api.service(serviceId)
            reloadFeedback()
        } catch (e: Throwable) {
            error = e.message ?: "Could not load this service"
        } finally {
            loaded = true
        }
    }

    val s = service
    when {
        !loaded -> Centered { LoadingSpinner() }
        s == null -> Centered {
            Text(error ?: "This service could not be found.", color = MaterialTheme.colorScheme.error)
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.emoji.ifBlank { "🔧" }, fontSize = 36.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("🏠 ${s.roomName}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (s.description.isNotBlank()) {
                    Text(s.description, fontSize = 15.sp)
                }

                Text(
                    "Provided by ${nameWithBadge(s.providerName, s.providerRole)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (s.providerLanguages.isNotEmpty()) {
                    Text(
                        "Speaks ${s.providerLanguages.joinToString(" · ") { UiConstants.languageLabel(it) }}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FeedbackSection(
                    api = api,
                    serviceId = serviceId,
                    feedback = feedback,
                    providerId = s.providerId,
                    selfUserId = selfUserId,
                    isFounder = isFounder,
                    canLeaveFeedback = selfUserId != null,
                    onChanged = { scope.launch { reloadFeedback() } },
                )

                if (canContact && selfUserId != s.providerId) {
                    ContactProviderSection(
                        api = api,
                        serviceId = serviceId,
                        providerName = s.providerName,
                        onOpened = onOpenDirectChat,
                    )
                }
            }
        }
    }
}

/**
 * The aggregated feedback display (issue §2). Feedback is grouped by emoji, counted, and sorted by
 * count descending, then shown as a horizontal list of "emoji count" chips. Tapping a chip opens a
 * dialog listing every textual review left with that emoji. A polite empty state shows when there
 * is no feedback yet. Members can also leave their own feedback.
 */
@Composable
private fun FeedbackSection(
    api: SaigonApi,
    serviceId: String,
    feedback: List<FeedbackView>,
    providerId: String,
    selfUserId: String?,
    isFounder: Boolean,
    canLeaveFeedback: Boolean,
    onChanged: () -> Unit,
) {
    var selectedEmoji by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }

    // Aggregate by emoji, count occurrences, sort by count descending.
    val aggregated = remember(feedback.toList()) {
        feedback.groupBy { it.emoji }
            .map { (emoji, items) -> emoji to items.size }
            .sortedByDescending { it.second }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Feedback", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (canLeaveFeedback) {
            TextButton(onClick = { leaving = true }) { Text("Leave feedback") }
        }
    }

    if (aggregated.isEmpty()) {
        Text(
            "No feedback yet — be the first to leave some kind words. 🌱",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            aggregated.forEach { (emoji, count) ->
                OutlinedButton(onClick = { selectedEmoji = emoji }) {
                    Text("$emoji $count", fontSize = 14.sp)
                }
            }
        }
    }

    selectedEmoji?.let { emoji ->
        val reviews = feedback.filter { it.emoji == emoji }
        FeedbackReviewsDialog(
            api = api,
            emoji = emoji,
            reviews = reviews,
            providerId = providerId,
            selfUserId = selfUserId,
            isFounder = isFounder,
            onDismiss = { selectedEmoji = null },
            onDeleted = { onChanged(); selectedEmoji = null },
        )
    }

    if (leaving) {
        LeaveFeedbackDialog(
            api = api,
            serviceId = serviceId,
            onDismiss = { leaving = false },
            onSubmitted = { leaving = false; onChanged() },
        )
    }
}

/** Lists every textual review left with [emoji], with a delete control where permitted. */
@Composable
private fun FeedbackReviewsDialog(
    api: SaigonApi,
    emoji: String,
    reviews: List<FeedbackView>,
    providerId: String,
    selfUserId: String?,
    isFounder: Boolean,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$emoji reviews") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val withMessages = reviews.filter { it.message.isNotBlank() }
                if (withMessages.isEmpty()) {
                    Text(
                        "No written reviews for this emoji yet.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                reviews.forEach { review ->
                    val canDelete = isFounder || selfUserId == review.userId || selfUserId == providerId
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(nameWithBadge(review.authorName, review.authorRole), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (review.message.isNotBlank()) {
                            Text(review.message, fontSize = 14.sp)
                        }
                        if (canDelete) {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { api.deleteFeedback(review.id) }.onSuccess { onDeleted() }
                                }
                            }) { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A dialog to leave feedback: pick a closing emoji + optional message. */
@Composable
private fun LeaveFeedbackDialog(
    api: SaigonApi,
    serviceId: String,
    onDismiss: () -> Unit,
    onSubmitted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var emoji by remember { mutableStateOf(UiConstants.emojiOptions.first()) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Leave feedback") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick an emoji", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    UiConstants.emojiOptions.forEach { option ->
                        val selected = option == emoji
                        OutlinedButton(onClick = { emoji = option }) {
                            Text((if (selected) "✅ " else "") + option, fontSize = 14.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your review (optional)") },
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            api.addFeedback(serviceId, emoji, message.trim())
                            onSubmitted()
                        } catch (e: Throwable) {
                            error = e.message ?: "Could not submit your feedback."
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

/** The "Contact provider" section: a message input + a "Send message" button (issue §6). */
@Composable
private fun ContactProviderSection(
    api: SaigonApi,
    serviceId: String,
    providerName: String,
    onOpened: (ContactProviderResponse) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Contact $providerName", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    OutlinedTextField(
        value = message,
        onValueChange = { message = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Your message") },
        shape = InputFieldShape,
        enabled = !busy,
    )
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy && message.isNotBlank(),
        onClick = {
            error = null
            busy = true
            scope.launch {
                try {
                    val res = api.contactProvider(serviceId, message.trim())
                    onOpened(res)
                } catch (e: Throwable) {
                    error = e.message ?: "Could not send your message."
                } finally {
                    busy = false
                }
            }
        },
    ) { Text("Send message") }
}
