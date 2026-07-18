package social.saigon.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
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
            label = { Text("Search services") },
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
                Text(
                    text = "Popular services",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
                results.forEach { service ->
                    ServiceCard(service = service, onClick = { onOpenService(service.id) })
                }
            }
        }
    }
}

/**
 * The caller's own "Services provided" list (reached from their profile). Lists every service the
 * signed-in user provides, across all rooms, with a polite empty state. Tapping a card opens the
 * service detail screen.
 */
@Composable
internal fun MyServicesScreen(
    api: SaigonApi,
    selfUserId: String?,
    onOpenService: (String) -> Unit,
) {
    val results = remember(selfUserId) { mutableStateListOf<ServiceView>() }
    var loaded by remember(selfUserId) { mutableStateOf(false) }
    var error by remember(selfUserId) { mutableStateOf<String?>(null) }

    LaunchedEffect(selfUserId) {
        if (selfUserId == null) { loaded = true; return@LaunchedEffect }
        error = null
        try {
            // The caller's own list includes unlisted services so they can relist/manage them.
            val list = api.myServices()
            results.clear()
            results.addAll(list)
        } catch (e: Throwable) {
            error = e.message ?: "Could not load your services"
        } finally {
            loaded = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        when {
            !loaded -> LoadingSpinner()
            results.isEmpty() -> Text(
                "You haven't offered any services yet. Open a room and offer one from your room profile.",
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

/**
 * The scrim laid over a service photo used as a card/row background (issue: "a gradient from 80%
 * alpha at the top, to 0% alpha at the bottom"), keeping the card text readable over the photo.
 */
internal fun servicePhotoScrim(color: Color): Brush =
    Brush.verticalGradient(listOf(color.copy(alpha = 0.4f), color.copy(alpha = 0.8f)))

/** A single result row in the service search list — with the service's photo as a faded background. */
@Composable
internal fun ServiceCard(service: ServiceView, onClick: () -> Unit) {
    val photoUrl = service.photos.firstOrNull()?.url
    val scrimColor = MaterialTheme.colorScheme.surface
    Card(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (photoUrl != null) {
                // Photo fills the row behind the content, with the top→bottom fade over it.
                UrlImage(url = photoUrl, modifier = Modifier.matchParentSize())
                Box(modifier = Modifier.matchParentSize().background(servicePhotoScrim(scrimColor)))
            }
        Row(modifier = Modifier.fillMaxWidth().innerGlow().padding(12.dp)) {
            Text(service.emoji.ifBlank { "🌟" }, fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(service.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(UiConstants.vnd(service.price), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                if (!service.listed) {
                    Text(
                        "Unlisted",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (service.description.isNotBlank()) {
                    Text(
                        "by ${nameWithBadge(service.providerName, service.providerRole)} • ${service.description}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "in ${service.roomEmoji.ifBlank { "🏠" }} ${service.roomName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }
}

/**
 * The service-photo area on the service detail screen (issue: "upload service photos ... display
 * the service photos on the service page"). Photos are shown to everyone as vertical cards in a
 * wrapping flex row; tapping a card opens a read-only display. When [canManage] is true (the
 * provider or a Founder) an "Add a photo" button opens [PhotoUploadDialog], and tapping a card asks
 * to delete it with a confirmation dialog. Mirrors `ProfilePhotoSection`.
 */
@Composable
private fun ServicePhotoSection(
    api: SaigonApi,
    service: ServiceView,
    canManage: Boolean,
    onUpdated: (ServiceView) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showUpload by remember(service.id) { mutableStateOf(false) }
    var confirmDelete by remember(service.id) { mutableStateOf<ProfilePhoto?>(null) }

    // Nothing to show for a visitor when there are no photos yet.
    if (service.photos.isEmpty() && !canManage) return

    Text("Photos", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    if (canManage) {
        Text(
            "Add photos of your service. Click a photo to delete it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (service.photos.isNotEmpty()) {
        PhotoCardsRow(
            title = service.name,
            photos = service.photos,
            // Managers delete on tap; everyone else just opens the read-only display.
            onPhotoClick = if (canManage) ({ confirmDelete = it }) else null,
        )
    } else {
        Text(
            "No photos yet.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (canManage) {
        OutlinedButton(onClick = { showUpload = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Add a photo")
        }
    }

    if (showUpload) {
        PhotoUploadDialog(
            api = api,
            title = "Add a service photo",
            // Always adding a fresh photo, so there is no current photo to pre-fill.
            currentPhoto = null,
            onDismiss = { showUpload = false },
            onSaved = { photo ->
                onUpdated(api.addServicePhoto(service.id, photo.url, photo.message))
                showUpload = false
            },
            confirmLabel = "Add photo",
        )
    }

    confirmDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this photo?") },
            text = { Text("This service photo will be removed for good.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { api.deleteServicePhoto(service.id, photo.url) }
                            .onSuccess { onUpdated(it) }
                        confirmDelete = null
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The service detail screen (issue §6). Shows the full service info (emoji, name, description,
 * provider with role badge, room), the aggregated feedback display, and a "Contact provider"
 * section whose "Send message" button opens (or reuses) a direct chat seeded with the typed
 * message and navigates the caller straight into that conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServiceDetailScreen(
    api: SaigonApi,
    serviceId: String,
    selfUserId: String?,
    isFounder: Boolean,
    canContact: Boolean,
    onOpenDirectChat: (ContactProviderResponse) -> Unit,
    onSignIn: () -> Unit,
    onDeleted: () -> Unit = {},
    onOpenService: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var service by remember(serviceId) { mutableStateOf<ServiceView?>(null) }
    val feedback = remember(serviceId) { mutableStateListOf<FeedbackView>() }
    var loaded by remember(serviceId) { mutableStateOf(false) }
    var error by remember(serviceId) { mutableStateOf<String?>(null) }
    var editing by remember(serviceId) { mutableStateOf(false) }
    var confirmingDelete by remember(serviceId) { mutableStateOf(false) }
    var managing by remember(serviceId) { mutableStateOf(false) }
    var selectedOccupant by remember(serviceId) { mutableStateOf<OccupantView?>(null) }

    suspend fun reloadFeedback() {
        runCatching { api.serviceFeedback(serviceId) }.onSuccess {
            feedback.clear()
            feedback.addAll(it)
        }
    }

    suspend fun reloadService() {
        runCatching { api.service(serviceId) }.onSuccess { service = it }
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(s.emoji.ifBlank { "🌟" }, fontSize = 36.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("${UiConstants.vnd(s.price)} • in ${s.roomEmoji.ifBlank { "🏠" }} ${s.roomName}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Copy-link affordance, mirroring rooms and threads (top-right).
                    ShareButton(isService = true)
                }
                if (!s.listed) {
                    Text(
                        "This service is unlisted — it won't show up in the public search.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (s.description.isNotBlank()) {
                    Text(s.description, fontSize = 15.sp)
                }

                Text(
                    text = "Provided by ${nameWithBadge(s.providerName, s.providerRole)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable {
                        scope.launch {
                            runCatching { api.occupant(s.roomId, s.providerId) }
                                .getOrNull()?.let { selectedOccupant = it }
                        }
                    }
                )
                // Disabled for now
                if (false && s.providerLanguages.isNotEmpty()) {
                    Text(
                        "Speaks ${s.providerLanguages.joinToString(" · ") { UiConstants.languageLabel(it) }}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Manage controls for the provider (or the Founder): edit, unlist/relist, delete.
                if (isFounder || selfUserId == s.providerId) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(enabled = !managing, onClick = { editing = true }) { Text("Edit") }
                        OutlinedButton(
                            enabled = !managing,
                            onClick = {
                                managing = true
                                scope.launch {
                                    runCatching {
                                        if (s.listed) api.unlistService(serviceId) else api.relistService(serviceId)
                                    }.onSuccess { service = it }
                                    managing = false
                                }
                            },
                        ) { Text(if (s.listed) "Unlist" else "Relist") }
                        TextButton(enabled = !managing, onClick = { confirmingDelete = true }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // Service photos (issue: "upload service photos ... display the service photos on
                // the service page"): shown to everyone; the provider/Founder can add or delete them.
                ServicePhotoSection(
                    api = api,
                    service = s,
                    canManage = isFounder || selfUserId == s.providerId,
                    onUpdated = { service = it },
                )

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

                if (selfUserId == null) {
                    // Signed-out visitors can't message yet — invite them to sign in first.
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSignIn,
                    ) { Text("Sign in to get in touch with ${s.providerName}") }
                } else if (canContact && selfUserId != s.providerId) {
                    ContactProviderSection(
                        api = api,
                        serviceId = serviceId,
                        providerName = s.providerName,
                        onOpened = onOpenDirectChat,
                    )
                }
            }

            if (editing) {
                EditServiceDialog(
                    api = api,
                    service = s,
                    onDismiss = { editing = false },
                    onSaved = { editing = false; scope.launch { reloadService() } },
                )
            }
            if (confirmingDelete) {
                AlertDialog(
                    onDismissRequest = { confirmingDelete = false },
                    title = { Text("Delete this service?") },
                    text = { Text("This can't be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmingDelete = false
                            scope.launch {
                                runCatching { api.deleteService(serviceId) }.onSuccess { onDeleted() }
                            }
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
                )
            }

            selectedOccupant?.let { person ->
                val isSelf = selfUserId != null && person.userId == selfUserId
                OccupantDetailSheet(
                    person = person,
                    isSelf = isSelf,
                    canStartPrivate = canContact,
                    api = api,
                    roomId = s.roomId,
                    onStartChat = {
                        selectedOccupant = null
                        onOpenDirectChat(
                            ContactProviderResponse(
                                roomId = s.roomId,
                                providerId = person.userId,
                                providerName = person.name,
                            )
                        )
                    },
                    onRefresh = {
                        scope.launch {
                            runCatching { api.refreshIdentity(s.roomId) }
                            runCatching { api.occupant(s.roomId, s.providerId) }
                                .getOrNull()?.let { selectedOccupant = it }
                        }
                    },
                    onEdited = {
                        scope.launch {
                            runCatching { api.occupant(s.roomId, s.providerId) }
                                .getOrNull()?.let { selectedOccupant = it }
                        }
                    },
                    onOpenService = onOpenService,
                    onClose = { selectedOccupant = null },
                )
            }
        }
    }
}

/** A dialog to edit a service's emoji, name, description, and price (provider or Founder). */
@Composable
private fun EditServiceDialog(
    api: SaigonApi,
    service: ServiceView,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var emoji by remember { mutableStateOf(service.emoji) }
    var name by remember { mutableStateOf(service.name) }
    var description by remember { mutableStateOf(service.description) }
    var price by remember { mutableStateOf(if (service.price > 0) service.price.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit service") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Emoji") },
                    singleLine = true,
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Service name") },
                    singleLine = true,
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description") },
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { new -> price = new.filter { it.isDigit() }; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Price (VND)") },
                    singleLine = true,
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && emoji.isNotBlank() && name.isNotBlank(),
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            api.updateService(
                                serviceId = service.id,
                                emoji = emoji.trim(),
                                name = name.trim(),
                                description = description.trim(),
                                price = price.toIntOrNull() ?: 0,
                            )
                            onSaved()
                        } catch (e: Throwable) {
                            error = e.message ?: "Could not save your changes."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
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
    // The feedback item awaiting a delete confirmation, if any.
    var pendingDelete by remember { mutableStateOf<FeedbackView?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$emoji feedback") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val withMessages = reviews.filter { it.message.isNotBlank() }
                if (withMessages.isEmpty()) {
                    Text(
                        "No written feedback for this emoji yet.",
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
                        Text(
                            timeAgo(review.createdAt),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (canDelete) {
                            TextButton(onClick = { pendingDelete = review }) {
                                Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    pendingDelete?.let { review ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this feedback?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        runCatching { api.deleteFeedback(review.id) }.onSuccess { onDeleted() }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
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

    Text("Get in touch with $providerName", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
