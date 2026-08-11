package social.saigon.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

/**
 * The `/adventures` search screen (issue §5). A search bar filters services by name (case-insensitive
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
            error = e.message ?: "Could not load adventures"
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
            label = { Text("Search adventures") },
            singleLine = true,
            shape = InputFieldShape,
        )

        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }

        when {
            !loaded -> LoadingSpinner()
            results.isEmpty() -> Text(
                if (query.isBlank()) "No adventures yet." else "No adventures match \"$query\".",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Popular adventures",
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
 * The caller's own "Adventures hosted" list (reached from their profile). Lists every service the
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
            error = e.message ?: "Could not load your adventures"
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
                "You haven't offered any adventures yet. Open a community and offer one from your community profile.",
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
    // Photo-backed cards always use dark-mode colors so text stays light over the image in either theme.
    PhotoBackdropTheme(enabled = photoUrl != null) {
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
}

/**
 * The service-photo area on the service detail screen (issue: "upload adventure photos ... display
 * the adventure photos on the adventure page"). Photos are shown to everyone as vertical cards in a
 * wrapping flex row; tapping a card opens a read-only display. When [canManage] is true (the
 * provider or a Founder) an "Add a photo" button opens [PhotoUploadDialog], and tapping a card opens
 * the "Edit this photo" dialog to replace, recaption, or delete it. Mirrors `ProfilePhotoSection`.
 */
@Composable
private fun ServicePhotoSection(
    api: SaigonApi,
    service: ServiceView,
    canManage: Boolean,
    onUpdated: (ServiceView) -> Unit,
) {
    var showUpload by remember(service.id) { mutableStateOf(false) }
    var editingPhoto by remember(service.id) { mutableStateOf<ProfilePhoto?>(null) }

    // Nothing to show for a visitor when there are no photos yet.
    if (service.photos.isEmpty() && !canManage) return

    Text("Photos", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    if (canManage) {
        Text(
            "Add photos of your adventure. Click a photo to edit it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (service.photos.isNotEmpty()) {
        PhotoCardsRow(
            title = service.name,
            photos = service.photos,
            // Managers edit on tap; everyone else just opens the read-only display.
            onPhotoClick = if (canManage) ({ editingPhoto = it }) else null,
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
            title = "Add an adventure photo",
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

    editingPhoto?.let { photo ->
        PhotoUploadDialog(
            api = api,
            title = "Edit this photo",
            currentPhoto = photo,
            onDismiss = { editingPhoto = null },
            onSaved = { updated ->
                onUpdated(api.updateServicePhoto(service.id, photo.url, updated.url, updated.message))
                editingPhoto = null
            },
            onDelete = {
                onUpdated(api.deleteServicePhoto(service.id, photo.url))
                editingPhoto = null
            },
        )
    }
}

/**
 * The service detail screen (issue §6). Shows the full service info (emoji, name, description,
 * provider with role badge, room), the aggregated feedback display, and a "Contact host"
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
    onSignIn: () -> Unit,
    onDeleted: () -> Unit = {},
    onOpenService: (String) -> Unit = {},
    onOccupantSelected: (OccupantView) -> Unit = {},
    /** Called whenever the loaded service's display title (name + emoji) is known or changes. */
    onServiceTitle: (name: String, emoji: String) -> Unit = { _, _ -> },
    // Default fills the dedicated route page; the sheet passes a width-only modifier so short
    // content can hug height (same idea as ThreadSheet) instead of leaving empty bottom space.
    modifier: Modifier = Modifier.fillMaxSize(),
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

    LaunchedEffect(selectedOccupant) {
        if (selectedOccupant != null) {
            onOccupantSelected(selectedOccupant!!)
        }
    }

    // Keep the app top bar in sync with the loaded/edited service title.
    LaunchedEffect(service?.name, service?.emoji) {
        val loadedService = service ?: return@LaunchedEffect
        onServiceTitle(loadedService.name, loadedService.emoji)
    }

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
            error = e.message ?: "Could not load this adventure"
        } finally {
            loaded = true
        }
    }

    val s = service
    when {
        !loaded -> Box(modifier = modifier, contentAlignment = Alignment.Center) { LoadingSpinner(
            modifier = Modifier.padding(24.dp),
            useDelay = false // useful to give the sheet some initial height
        ) }
        s == null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(error ?: "This adventure could not be found.", color = MaterialTheme.colorScheme.error)
        }
        else -> {
            Column(
                modifier = modifier.verticalScroll(rememberScrollState()),
            ) {
                // Large 16:9 landscape photo scroller across the top (swipe + arrows + dots).
                if (s.photos.isNotEmpty()) {
                    LandscapePhotoScroller(
                        photos = s.photos,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp),
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(s.emoji.ifBlank { "🌟" }, fontSize = 36.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(
                                "${UiConstants.vnd(s.price)} • in ${s.roomEmoji.ifBlank { "🏠" }} ${s.roomName}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Copy-link affordance, mirroring rooms and threads (top-right).
                        ShareButton(isService = true, path = "/adventures/${s.id}")
                    }
                    if (!s.listed) {
                        Text(
                            "This adventure is unlisted — it won't show up in the public search.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (s.description.isNotBlank()) {
                        Text(s.description, fontSize = 15.sp)
                    }

                    Text(
                        text = "Hosted by ${nameWithBadge(s.providerName, s.providerRole)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable {
                            scope.launch {
                                runCatching { api.occupant(s.roomId, s.providerId) }
                                    .getOrNull()?.let { selectedOccupant = it }
                            }
                        },
                    )
                    // Disabled for now
                    if (s.providerLanguages.isNotEmpty()) {
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

                    ServiceInclusionsSection(
                        included = s.included,
                        notIncluded = s.notIncluded,
                    )

                    WhereYoullGoSection(destinations = s.destinations)

                    WhatYoullDoSection(activities = s.activities)

                    CancellationPolicySection(policy = s.cancellationPolicy)

                    // Service photos (issue: "upload adventure photos ... display the adventure photos on
                    // the adventure page"): shown to everyone; the provider/Founder can add or delete them.
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

                    YouMightAlsoLikeSection(
                        api = api,
                        currentServiceId = serviceId,
                        onOpenService = onOpenService,
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
                    title = { Text("Delete this adventure?") },
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


/**
 * Bottom sheet hosting the full [ServiceDetailScreen] UI so a service can be inspected without
 * leaving the surrounding screen (e.g. room chat). Mirrors [ThreadSheet] / [RoomServicesSheet]:
 * fully expanded, animated close, and the same content as the dedicated service route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServiceDetailSheet(
    api: SaigonApi,
    serviceId: String,
    selfUserId: String?,
    isFounder: Boolean,
    canContact: Boolean,
    onOpenDirectChat: (ContactProviderResponse) -> Unit,
    onSignIn: () -> Unit,
    onDeleted: () -> Unit = {},
    onOpenService: (String) -> Unit = {},
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Fully expand so the detail body (photos, feedback, contact) has room, matching ThreadSheet.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val animateClose: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onClose()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        // Same shell as ThreadSheet: cap at the allotted height, but let short content size
        // naturally (weight fill=false) so the sheet doesn't grow empty bottom space.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                PlainDragHandle()
                ServiceDetailScreen(
                    api = api,
                    serviceId = serviceId,
                    selfUserId = selfUserId,
                    isFounder = isFounder,
                    canContact = canContact,
                    onOpenDirectChat = onOpenDirectChat,
                    onSignIn = onSignIn,
                    onDeleted = {
                        onDeleted()
                        animateClose()
                    },
                    onOpenService = onOpenService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * "What's included" / "What's not included" on the service detail screen. Included items are
 * semicolon-split into a bullet list; not-included items sit in a small box as comma-separated text.
 */
@Composable
private fun ServiceInclusionsSection(included: String, notIncluded: String) {
    val includedItems = remember(included) { splitSemicolonItems(included) }
    val notIncludedItems = remember(notIncluded) { splitSemicolonItems(notIncluded) }
    if (includedItems.isEmpty() && notIncludedItems.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (includedItems.isNotEmpty()) {
            Text("What's included", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            includedItems.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("✅", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                    Text(item, fontSize = 14.sp)
                }
            }
        }
        if (notIncludedItems.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "What's not included",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        notIncludedItems.joinToString(", "),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Numbered itinerary ("Where you'll go"). Sequence is visit order; each stop can carry photos,
 * a name, an address, and a directions URL opened in a new tab. Numbered badges are linked by a
 * vertical timeline line so the visit order reads as a continuous path.
 */
@Composable
private fun WhereYoullGoSection(destinations: List<ServiceDestination>) {
    if (destinations.isEmpty()) return
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Where you'll go", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        destinations.forEachIndexed { index, dest ->
            val isLast = index == destinations.lastIndex
            // Intrinsic min height lets the rail Column stretch to the stop content so the
            // connector line runs from this badge down to the next.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    SequenceBadge(number = index + 1)
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .weight(1f)
                                .background(lineColor),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = if (isLast) 0.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (dest.name.isNotBlank()) {
                        Text(dest.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    if (dest.address.isNotBlank()) {
                        Text(
                            dest.address,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (dest.photos.isNotEmpty()) {
                        LandscapePhotoScroller(
                            photos = dest.photos,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    if (dest.url.isNotBlank()) {
                        OutlinedButton(onClick = { openUrl(dest.url) }) {
                            Text("Get directions")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Activity list ("What you'll do"). Each activity can carry photos, a name, and a Markdown body.
 */
@Composable
private fun WhatYoullDoSection(activities: List<ServiceActivity>) {
    if (activities.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("What you'll do", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        activities.forEachIndexed { index, activity ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SequenceBadge(number = index + 1)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        activity.name.ifBlank { "Activity ${index + 1}" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
                if (activity.photos.isNotEmpty()) {
                    LandscapePhotoScroller(
                        photos = activity.photos,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                    )
                }
                if (activity.text.isNotBlank()) {
                    Markdown(
                        content = activity.text,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Plain-text cancellation policy block on the adventure detail screen. */
@Composable
private fun CancellationPolicySection(policy: String) {
    if (policy.isBlank()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Cancellation policy", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(policy, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Up to six other publicly listed adventures, chosen at random and excluding the current one.
 * Rendered at the bottom of the detail screen with the same cards as the search list.
 */
@Composable
private fun YouMightAlsoLikeSection(
    api: SaigonApi,
    currentServiceId: String,
    onOpenService: (String) -> Unit,
) {
    val suggestions = remember(currentServiceId) { mutableStateListOf<ServiceView>() }
    var loaded by remember(currentServiceId) { mutableStateOf(false) }

    LaunchedEffect(currentServiceId) {
        loaded = false
        runCatching { api.services() }
            .onSuccess { all ->
                suggestions.clear()
                suggestions.addAll(
                    all.filter { it.id != currentServiceId }.shuffled().take(6),
                )
            }
        loaded = true
    }

    if (!loaded || suggestions.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("You might also like", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        suggestions.forEach { service ->
            ServiceCard(service = service, onClick = { onOpenService(service.id) })
        }
    }
}

/** Circular 1, 2, 3… badge used by the itinerary and activity lists. */
@Composable
private fun SequenceBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

/** Split a semicolon-separated free-text field into trimmed non-empty items. */
private fun splitSemicolonItems(raw: String): List<String> =
    raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Shared create/edit editors for the ordered "Where you'll go" list. Each stop is a compact card
 * with name/address/URL fields, inline photo chips, and remove/add controls — keeps the dialog tidy
 * without nested dialogs for every field.
 */
@Composable
internal fun ServiceDestinationsEditor(
    api: SaigonApi,
    destinations: SnapshotStateList<ServiceDestination>,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Where you'll go", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "Add stops in the order guests will visit them.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        destinations.forEachIndexed { index, dest ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SequenceBadge(number = index + 1)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = enabled,
                            onClick = { destinations.removeAt(index) },
                        ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                    OutlinedTextField(
                        value = dest.name,
                        onValueChange = { destinations[index] = dest.copy(name = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                        shape = InputFieldShape,
                        enabled = enabled,
                    )
                    OutlinedTextField(
                        value = dest.address,
                        onValueChange = { destinations[index] = dest.copy(address = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Address") },
                        singleLine = true,
                        shape = InputFieldShape,
                        enabled = enabled,
                    )
                    OutlinedTextField(
                        value = dest.url,
                        onValueChange = { destinations[index] = dest.copy(url = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Directions URL") },
                        placeholder = { Text("https://maps.google.com/…") },
                        singleLine = true,
                        shape = InputFieldShape,
                        enabled = enabled,
                    )
                    ItemPhotosEditor(
                        api = api,
                        photos = dest.photos,
                        enabled = enabled,
                        onChange = { destinations[index] = dest.copy(photos = it) },
                    )
                }
            }
        }
        OutlinedButton(
            enabled = enabled,
            onClick = { destinations.add(ServiceDestination()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add a stop") }
    }
}

/**
 * Shared create/edit editors for the "What you'll do" activity list. Mirrors
 * [ServiceDestinationsEditor]: numbered cards with name, Markdown body, and photos.
 */
@Composable
internal fun ServiceActivitiesEditor(
    api: SaigonApi,
    activities: SnapshotStateList<ServiceActivity>,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("What you'll do", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "Describe each activity. Markdown is supported in the text field.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        activities.forEachIndexed { index, activity ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SequenceBadge(number = index + 1)
                        Spacer(Modifier.width(8.dp))
                        Text("Activity ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = enabled,
                            onClick = { activities.removeAt(index) },
                        ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                    OutlinedTextField(
                        value = activity.name,
                        onValueChange = { activities[index] = activity.copy(name = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                        shape = InputFieldShape,
                        enabled = enabled,
                    )
                    OutlinedTextField(
                        value = activity.text,
                        onValueChange = { activities[index] = activity.copy(text = it) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        label = { Text("Details (Markdown)") },
                        placeholder = { Text("**Highlights**, lists, links…") },
                        shape = InputFieldShape,
                        enabled = enabled,
                    )
                    ItemPhotosEditor(
                        api = api,
                        photos = activity.photos,
                        enabled = enabled,
                        onChange = { activities[index] = activity.copy(photos = it) },
                    )
                }
            }
        }
        OutlinedButton(
            enabled = enabled,
            onClick = { activities.add(ServiceActivity()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add an activity") }
    }
}

/**
 * Compact multi-photo manager for a destination/activity draft: thumbnail row + add/remove.
 * Uploads go straight to the content server so create/edit can embed the resulting URLs.
 */
@Composable
private fun ItemPhotosEditor(
    api: SaigonApi,
    photos: List<ProfilePhoto>,
    enabled: Boolean,
    onChange: (List<ProfilePhoto>) -> Unit,
) {
    var showUpload by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Photos", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (photos.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                photos.forEach { photo ->
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = enabled) {
                                onChange(photos.filterNot { it.url == photo.url })
                            },
                    ) {
                        UrlImage(
                            url = photo.url,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("×", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
            Text(
                "Tap a photo to remove it.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            enabled = enabled,
            onClick = { showUpload = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add photo") }
    }

    if (showUpload) {
        PhotoUploadDialog(
            api = api,
            title = "Add a photo",
            currentPhoto = null,
            onDismiss = { showUpload = false },
            onSaved = { photo ->
                onChange(photos + photo)
                showUpload = false
            },
            confirmLabel = "Add photo",
        )
    }
}

/** A dialog to edit a service's fields, itinerary, activities, and cancellation policy. */
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
    var included by remember { mutableStateOf(service.included) }
    var notIncluded by remember { mutableStateOf(service.notIncluded) }
    val destinations = remember {
        mutableStateListOf<ServiceDestination>().also { it.addAll(service.destinations) }
    }
    val activities = remember {
        mutableStateListOf<ServiceActivity>().also { it.addAll(service.activities) }
    }
    var cancellationPolicy by remember {
        mutableStateOf(service.cancellationPolicy.ifBlank { DEFAULT_CANCELLATION_POLICY })
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit adventure") },
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
                    label = { Text("Adventure name") },
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
                OutlinedTextField(
                    value = included,
                    onValueChange = { included = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What's included") },
                    placeholder = { Text("item one; item two; item three") },
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = notIncluded,
                    onValueChange = { notIncluded = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What's not included") },
                    placeholder = { Text("item one; item two") },
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                ServiceDestinationsEditor(
                    api = api,
                    destinations = destinations,
                    enabled = !busy,
                )
                ServiceActivitiesEditor(
                    api = api,
                    activities = activities,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = cancellationPolicy,
                    onValueChange = { cancellationPolicy = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cancellation policy") },
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
                                included = included.trim(),
                                notIncluded = notIncluded.trim(),
                                destinations = destinations.toList(),
                                activities = activities.toList(),
                                cancellationPolicy = cancellationPolicy.trim()
                                    .ifBlank { DEFAULT_CANCELLATION_POLICY },
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

/** The "Contact host" section: a message input + a "Send message" button (issue §6). */
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

    Text("Book this adventure with $providerName", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
