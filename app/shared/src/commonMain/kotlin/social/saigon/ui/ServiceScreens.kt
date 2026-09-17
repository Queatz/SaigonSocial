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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The `/adventures` search screen (issue §5). A search bar filters services by name (case-insensitive
 * partial match, done server-side), and each result card shows the emoji, name, a truncated
 * description, the room the service lives in, and the provider (with their role badge + a couple of
 * readily-available profile facets). Tapping a card opens the service detail screen.
 *
 * When [canCreateServices] is true (Supporter/Founder), a ➕ beside the search bar lets the caller
 * pick a community and host a new adventure there via [CreateServiceDialog].
 */
@Composable
internal fun ServiceSearchScreen(
    api: SaigonApi,
    onOpenService: (String) -> Unit,
    canCreateServices: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<ServiceView>() }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped after a successful create so the search list refreshes even if the query is unchanged.
    var reloadToken by remember { mutableStateOf(0) }
    var selectingCommunity by remember { mutableStateOf(false) }
    var createInRoom by remember { mutableStateOf<RoomSummary?>(null) }

    // Reload whenever the query changes (debounce-free; the list is small and the filter is cheap).
    LaunchedEffect(query, reloadToken) {
        error = null
        try {
            val list = api.services(query)
            results.clear()
            results.addAll(list)
        } catch (e: Throwable) {
            error = e.message ?: "Could not load meets"
        } finally {
            loaded = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Search row mirrors the community list: text field + optional ➕ to create.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("Search meets") },
                singleLine = true,
                shape = InputFieldShape,
            )
            if (canCreateServices) {
                IconButton(
                    onClick = { selectingCommunity = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("➕")
                }
            }
        }

        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }

        when {
            !loaded -> LoadingSpinner()
            results.isEmpty() -> Text(
                if (query.isBlank()) "No meets yet." else "No meets match \"$query\".",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Popular meets",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
                results.forEach { service ->
                    ServiceCard(service = service, onClick = { onOpenService(service.routeId) })
                }
            }
        }
    }

    if (selectingCommunity) {
        SelectCommunityDialog(
            api = api,
            onDismiss = { selectingCommunity = false },
            onSelected = { room ->
                selectingCommunity = false
                createInRoom = room
            },
        )
    }

    createInRoom?.let { room ->
        CreateServiceDialog(
            api = api,
            roomId = room.id,
            roomName = room.name,
            onDismiss = { createInRoom = null },
            onCreated = {
                createInRoom = null
                reloadToken++
            },
        )
    }
}

/**
 * Picker shown before hosting an adventure from the search screen: loads communities and lets the
 * caller choose which one the new adventure belongs to. Includes a search field (debounced like the
 * home community list) so the list stays usable as communities grow.
 */
@Composable
private fun SelectCommunityDialog(
    api: SaigonApi,
    onDismiss: () -> Unit,
    onSelected: (RoomSummary) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var rooms by remember { mutableStateOf<List<RoomSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Same debounce as the home "Search communities" field so we don't hit the API per keystroke.
    LaunchedEffect(search) {
        val query = search.trim()
        if (query.isNotEmpty()) delay(500)
        error = null
        try {
            rooms = api.rooms(query.ifBlank { null })
        } catch (e: Throwable) {
            error = e.message ?: "Could not load communities"
        }
    }

    val searching = search.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a community") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Which community should this meet belong to?",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search communities") },
                    singleLine = true,
                    shape = InputFieldShape,
                )
                error?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                when {
                    rooms == null && error == null -> LoadingSpinner()
                    rooms.isNullOrEmpty() -> Text(
                        text = if (searching) {
                            "No communities found."
                        } else {
                            "No communities yet. Create one from the home screen first."
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> rooms!!.forEach { room ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { onSelected(room) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(room.emoji.ifBlank { "🏠" }, fontSize = 22.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(room.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    if (room.atmosphere.isNotBlank()) {
                                        Text(
                                            room.atmosphere,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
            error = e.message ?: "Could not load your meets"
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
                "You haven't offered any meets yet. Open a community and offer one from your community profile.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                results.forEach { service ->
                    ServiceCard(service = service, onClick = { onOpenService(service.routeId) })
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
                                "by ${nameWithBadge(service.providerName, service.providerRole)} • ${service.description.stripMarkdown()}",
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

/** Diff of service photos between the persisted list and the dialog's local draft. */
internal data class ServicePhotoChanges(
    val toAdd: List<ProfilePhoto>,
    val toDeleteUrls: List<String>,
    val toUpdate: List<ProfilePhoto>,
)

/** Compute add/delete/caption-update operations to turn [original] into [current]. */
internal fun servicePhotoChanges(
    original: List<ProfilePhoto>,
    current: List<ProfilePhoto>,
): ServicePhotoChanges {
    val originalByUrl = original.associateBy { it.url }
    val currentUrls = current.map { it.url }.toSet()
    return ServicePhotoChanges(
        toAdd = current.filter { it.url !in originalByUrl },
        toDeleteUrls = original.map { it.url }.filter { it !in currentUrls },
        toUpdate = current.filter { photo ->
            originalByUrl[photo.url]?.let { it.message != photo.message } == true
        },
    )
}

/** Persist [servicePhotoChanges] against an existing service. */
internal suspend fun applyServicePhotoChanges(
    api: SaigonApi,
    serviceId: String,
    original: List<ProfilePhoto>,
    current: List<ProfilePhoto>,
) {
    val changes = servicePhotoChanges(original, current)
    changes.toDeleteUrls.forEach { api.deleteServicePhoto(serviceId, it) }
    changes.toAdd.forEach { api.addServicePhoto(serviceId, it.url, it.message) }
    changes.toUpdate.forEach { api.updateServicePhoto(serviceId, it.url, it.url, it.message) }
}

/** Hide cancellation copy on free meets and when the provider left the policy blank. */
internal fun shouldShowCancellationPolicy(price: Int, policy: String): Boolean =
    price > 0 && policy.isNotBlank()

/**
 * Compact photo manager for the create/edit service dialogs. Uploads go to the content server
 * immediately; attaching them to the service happens when the dialog saves.
 */
@Composable
internal fun ServicePhotosEditor(
    api: SaigonApi,
    photos: SnapshotStateList<ProfilePhoto>,
    enabled: Boolean,
) {
    var showUpload by remember { mutableStateOf(false) }
    var editingPhoto by remember { mutableStateOf<ProfilePhoto?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Photos", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "Add photos of your meet. Click a photo to edit it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                            .clickable(enabled = enabled) { editingPhoto = photo },
                    ) {
                        UrlImage(
                            url = photo.url,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Text(
                "Tap a photo to edit it.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "No photos yet.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            enabled = enabled,
            onClick = { showUpload = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add a photo") }
    }

    if (showUpload) {
        PhotoUploadDialog(
            api = api,
            title = "Add a meet photo",
            currentPhoto = null,
            onDismiss = { showUpload = false },
            onSaved = { photo ->
                photos.add(photo)
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
                val index = photos.indexOfFirst { it.url == photo.url }
                if (index >= 0) photos[index] = updated else photos.add(updated)
                editingPhoto = null
            },
            onDelete = {
                photos.removeAll { it.url == photo.url }
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
    /** Opens the community this adventure belongs to ([Screen.Room] via [RoomSummary]). */
    onOpenRoom: (RoomSummary) -> Unit = {},
    onOccupantSelected: (OccupantView) -> Unit = {},
    /**
     * Called whenever the loaded service's display title (name + emoji) and preferred route id
     * (pretty [ServiceView.urlId] when set, else durable id) are known or change.
     */
    onServiceTitle: (name: String, emoji: String, routeId: String) -> Unit = { _, _, _ -> },
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
    // Host details for the "About your host" section (photo, intro, etc.). Same payload as the
    // occupant detail sheet so "Learn more" can open it without a second fetch when possible.
    var host by remember(serviceId) { mutableStateOf<OccupantView?>(null) }

    LaunchedEffect(selectedOccupant) {
        if (selectedOccupant != null) {
            onOccupantSelected(selectedOccupant!!)
        }
    }

    // Keep the app top bar (and browser path) in sync with the loaded/edited service title +
    // pretty route id. Prefer urlId when present so `/adventures/{uuid}` upgrades to the slug.
    LaunchedEffect(service?.name, service?.emoji, service?.urlId, service?.id) {
        val loadedService = service ?: return@LaunchedEffect
        onServiceTitle(loadedService.name, loadedService.emoji, loadedService.routeId)
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

    suspend fun reloadHost(roomId: String, providerId: String) {
        host = runCatching { api.occupant(roomId, providerId) }.getOrNull()
    }

    LaunchedEffect(serviceId) {
        error = null
        host = null
        try {
            service = api.service(serviceId)
            reloadFeedback()
            service?.let { reloadHost(it.roomId, it.providerId) }
        } catch (e: Throwable) {
            error = e.message ?: "Could not load this meet"
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
            Text(error ?: "This meet could not be found.", color = MaterialTheme.colorScheme.error)
        }
        else -> {
            // Sticky "Book this adventure" bar stays pinned while the detail body scrolls
            // (same pattern as ProfileScreen's sticky Save bar).
            val showContactBar = selfUserId != null && canContact && selfUserId != s.providerId
            val showSignInBar = selfUserId == null
            val enableBookBar = showContactBar || showSignInBar
            // Reserve scroll room from the sticky bar's measured height so content isn't hidden
            // under contact vs sign-in layouts of different sizes.
            val density = LocalDensity.current
            var bookBarBottomPadding by remember { mutableStateOf(0.dp) }

            Box(modifier = modifier) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bookBarBottomPadding)
                        .verticalScroll(rememberScrollState()),
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
                                // Price stays plain text; the community name is a link to Screen.Room.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${UiConstants.vnd(s.price)} • in ",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "${s.roomEmoji.ifBlank { "🏠" }} ${s.roomName}",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(MaterialTheme.shapes.extraSmall)
                                            .clickable {
                                                onOpenRoom(
                                                    RoomSummary(
                                                        id = s.roomId,
                                                        name = s.roomName,
                                                        emoji = s.roomEmoji,
                                                        atmosphere = "",
                                                        occupants = 0,
                                                    ),
                                                )
                                            },
                                    )
                                }
                            }
                            // Copy-link affordance, mirroring rooms and threads (top-right).
                            // Prefer the pretty urlId so shared links stay human-readable.
                            ShareButton(isService = true, path = "/adventures/${s.routeId}")
                        }
                        if (!s.listed) {
                            Text(
                                "This meet is unlisted — it won't show up in the public search.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (s.description.isNotBlank()) {
                            Markdown(s.description)
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

                        ServiceBringSection(
                            toBring = s.toBring,
                            notToBring = s.notToBring,
                        )

                        WhereYoullGoSection(destinations = s.destinations)

                        WhatYoullDoSection(activities = s.activities)

                        CancellationPolicySection(policy = s.cancellationPolicy, price = s.price)

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

                        YouMightAlsoLikeSection(
                            api = api,
                            // Durable id so a pretty urlId path segment still excludes this adventure.
                            currentServiceId = s.id,
                            onOpenService = onOpenService,
                        )

                        AboutYourHostSection(
                            host = host,
                            fallbackName = s.providerName,
                            fallbackRole = s.providerRole,
                            fallbackLanguages = s.providerLanguages,
                            fallbackInterests = s.providerInterests,
                            onLearnMore = {
                                val known = host
                                if (known != null) {
                                    selectedOccupant = known
                                } else {
                                    scope.launch {
                                        runCatching { api.occupant(s.roomId, s.providerId) }
                                            .getOrNull()?.let {
                                                host = it
                                                selectedOccupant = it
                                            }
                                    }
                                }
                            },
                        )
                    }
                }

                // Locked to the bottom of the adventure detail screen so booking stays reachable
                // while scrolling long itineraries, photos, and host info.
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .softShadow()
                        .onSizeChanged { size ->
                            bookBarBottomPadding = with(density) { size.height.toDp() }
                        },
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showSignInBar) {
                            // Signed-out visitors can't message yet — invite them to sign in first.
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onSignIn,
                                enabled = enableBookBar
                            ) { Text("Sign in to chat with ${s.providerName}") }
                        } else {
                            ContactProviderSection(
                                api = api,
                                serviceId = serviceId,
                                providerName = s.providerName,
                                price = s.price,
                                onOpened = onOpenDirectChat,
                                enabled = enableBookBar
                            )
                        }
                    }
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
                    title = { Text("Delete this meet?") },
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
                    roomName = s.roomName,
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
                                .getOrNull()?.let {
                                    selectedOccupant = it
                                    if (it.userId == s.providerId) host = it
                                }
                        }
                    },
                    onEdited = {
                        scope.launch {
                            runCatching { api.occupant(s.roomId, s.providerId) }
                                .getOrNull()?.let {
                                    selectedOccupant = it
                                    if (it.userId == s.providerId) host = it
                                }
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
    onOpenRoom: (RoomSummary) -> Unit = {},
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
                    onOpenRoom = { room ->
                        animateClose()
                        onOpenRoom(room)
                    },
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
 * "What to bring" / "What not to bring" on the service detail screen. Same layout as inclusions:
 * semicolon-split bring items as a bullet list; not-to-bring items in a small comma-separated box.
 */
@Composable
private fun ServiceBringSection(toBring: String, notToBring: String) {
    val toBringItems = remember(toBring) { splitSemicolonItems(toBring) }
    val notToBringItems = remember(notToBring) { splitSemicolonItems(notToBring) }
    if (toBringItems.isEmpty() && notToBringItems.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (toBringItems.isNotEmpty()) {
            Text("What to bring", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            toBringItems.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("🎒", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                    Text(item, fontSize = 14.sp)
                }
            }
        }
        if (notToBringItems.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "What not to bring",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        notToBringItems.joinToString(", "),
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
        Text("Where you'll meet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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

/** Plain-text cancellation policy block on the meet detail screen. Hidden for free meets. */
@Composable
private fun CancellationPolicySection(policy: String, price: Int) {
    if (!shouldShowCancellationPolicy(price, policy)) return
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
            ServiceCard(service = service, onClick = { onOpenService(service.routeId) })
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

/** Identity of a stop for de-duplicating recent locations (photos do not count). */
internal data class LocationKey(val name: String, val address: String, val url: String)

internal fun ServiceDestination.locationKey(): LocationKey =
    LocationKey(name.trim(), address.trim(), url.trim())

internal fun ServiceDestination.isBlankLocation(): Boolean =
    name.isBlank() && address.isBlank() && url.isBlank() && photos.isEmpty()

internal fun ServiceDestination.hasLocationInfo(): Boolean =
    name.isNotBlank() || address.isNotBlank() || url.isNotBlank()

/**
 * Unique recent itinerary stops from [services], newest meets first. [excludeServiceId] skips the
 * meet currently being edited so we only suggest locations from *other* meets.
 */
internal fun recentLocationsFromServices(
    services: List<ServiceView>,
    excludeServiceId: String? = null,
    limit: Int = 12,
): List<ServiceDestination> {
    val seen = linkedSetOf<LocationKey>()
    val result = mutableListOf<ServiceDestination>()
    services
        .sortedByDescending { it.createdAt }
        .asSequence()
        .filter { it.id != excludeServiceId }
        .flatMap { it.destinations.asSequence() }
        .filter { it.hasLocationInfo() }
        .forEach { dest ->
            if (seen.add(dest.locationKey())) {
                result += dest
            }
        }
    return result.take(limit)
}

/** Fill the first empty stop with [location], or append a new stop when every slot is in use. */
internal fun applyRecentLocation(
    destinations: MutableList<ServiceDestination>,
    location: ServiceDestination,
) {
    val emptyIndex = destinations.indexOfFirst { it.isBlankLocation() }
    val copy = location.copy(photos = location.photos.toList())
    if (emptyIndex >= 0) {
        destinations[emptyIndex] = copy
    } else {
        destinations.add(copy)
    }
}

/**
 * Shared create/edit editors for the ordered "Where you'll go" list. Each stop is a compact card
 * with name/address/URL fields, inline photo chips, and remove/add controls — keeps the dialog tidy
 * without nested dialogs for every field.
 *
 * Recent locations from other listed meets sit above the stop cards so a host can 1-click fill
 * name/address/directions/photos instead of retyping them.
 */
@Composable
internal fun ServiceDestinationsEditor(
    api: SaigonApi,
    destinations: SnapshotStateList<ServiceDestination>,
    enabled: Boolean,
    excludeServiceId: String? = null,
) {
    var recent by remember { mutableStateOf<List<ServiceDestination>>(emptyList()) }
    LaunchedEffect(excludeServiceId) {
        runCatching { api.services() }
            .onSuccess { recent = recentLocationsFromServices(it, excludeServiceId) }
    }
    val usedKeys = destinations.map { it.locationKey() }.toSet()
    val suggestions = recent.filter { it.locationKey() !in usedKeys }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Where you'll go", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "Add stops in the order guests will visit them.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (suggestions.isNotEmpty()) {
            RecentLocationsRow(
                locations = suggestions,
                enabled = enabled,
                onSelect = { applyRecentLocation(destinations, it) },
            )
        }
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

/** Horizontal chips of unique locations from other meets; tapping one fills a stop. */
@Composable
private fun RecentLocationsRow(
    locations: List<ServiceDestination>,
    enabled: Boolean,
    onSelect: (ServiceDestination) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Recent locations", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            "From other meets — tap one to fill in a stop.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            locations.forEach { location ->
                RecentLocationChip(
                    location = location,
                    enabled = enabled,
                    onClick = { onSelect(location) },
                )
            }
        }
    }
}

@Composable
private fun RecentLocationChip(
    location: ServiceDestination,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val title = location.name.ifBlank { location.address.ifBlank { "Saved location" } }
    val subtitle = when {
        location.name.isNotBlank() && location.address.isNotBlank() -> location.address
        location.url.isNotBlank() -> location.url
        else -> ""
    }
    Surface(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            location.photos.firstOrNull()?.let { photo ->
                UrlImage(
                    url = photo.url,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(modifier = Modifier.widthIn(max = 160.dp)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
    var toBring by remember { mutableStateOf(service.toBring) }
    var notToBring by remember { mutableStateOf(service.notToBring) }
    val destinations = remember {
        mutableStateListOf<ServiceDestination>().also { it.addAll(service.destinations) }
    }
    val activities = remember {
        mutableStateListOf<ServiceActivity>().also { it.addAll(service.activities) }
    }
    val photos = remember {
        mutableStateListOf<ProfilePhoto>().also { it.addAll(service.photos) }
    }
    var cancellationPolicy by remember {
        mutableStateOf(service.cancellationPolicy.ifBlank { DEFAULT_CANCELLATION_POLICY })
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit meet") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServicePhotosEditor(
                    api = api,
                    photos = photos,
                    enabled = !busy,
                )
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
                    label = { Text("Meet name") },
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
                OutlinedTextField(
                    value = toBring,
                    onValueChange = { toBring = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What to bring") },
                    placeholder = { Text("item one; item two; item three") },
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = notToBring,
                    onValueChange = { notToBring = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What not to bring") },
                    placeholder = { Text("item one; item two") },
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                ServiceDestinationsEditor(
                    api = api,
                    destinations = destinations,
                    enabled = !busy,
                    excludeServiceId = service.id,
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
                                toBring = toBring.trim(),
                                notToBring = notToBring.trim(),
                                destinations = destinations.toList(),
                                activities = activities.toList(),
                                cancellationPolicy = cancellationPolicy.trim()
                                    .ifBlank { DEFAULT_CANCELLATION_POLICY },
                            )
                            applyServicePhotoChanges(
                                api = api,
                                serviceId = service.id,
                                original = service.photos,
                                current = photos.toList(),
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

/**
 * Resolved fields for the adventure detail "About your host" section. Prefers the room-scoped
 * [host] occupant payload and falls back to the service's embedded provider facets.
 */
internal data class AboutHostDisplay(
    val name: String,
    val role: Role,
    val photoUrl: String,
    val interests: List<String>,
    val languages: List<String>,
    val introduction: String,
)

/** Merge host occupant details with service-level provider fallbacks for the about-host section. */
internal fun aboutHostDisplay(
    host: OccupantView?,
    fallbackName: String,
    fallbackRole: Role,
    fallbackLanguages: List<String>,
    fallbackInterests: List<String>,
): AboutHostDisplay = AboutHostDisplay(
    name = host?.name?.takeIf { it.isNotBlank() } ?: fallbackName,
    role = host?.role ?: fallbackRole,
    photoUrl = host?.photoUrl.orEmpty(),
    interests = host?.interests ?: fallbackInterests,
    languages = host?.languages ?: fallbackLanguages,
    introduction = host?.introduction.orEmpty(),
)

/**
 * Closing "About your host" block on the adventure detail screen: profile photo, name, interests,
 * spoken languages, introduction, and a button that opens the host's [OccupantDetailSheet].
 * Prefers the full room-scoped [host] payload; falls back to the service's embedded provider
 * facets when the occupant fetch has not completed (or failed).
 */
@Composable
private fun AboutYourHostSection(
    host: OccupantView?,
    fallbackName: String,
    fallbackRole: Role,
    fallbackLanguages: List<String>,
    fallbackInterests: List<String>,
    onLearnMore: () -> Unit,
) {
    val display = aboutHostDisplay(
        host = host,
        fallbackName = fallbackName,
        fallbackRole = fallbackRole,
        fallbackLanguages = fallbackLanguages,
        fallbackInterests = fallbackInterests,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("About your host", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ProfileIcon(
                name = display.name,
                size = 64.dp,
                photoUrl = display.photoUrl.ifBlank { null },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                nameWithBadge(display.name, display.role),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
        if (display.interests.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Interests", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(display.interests.joinToString(" · "), fontSize = 14.sp)
            }
        }
        if (display.languages.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Speaks", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    display.languages.joinToString(" · ") { UiConstants.languageLabel(it) },
                    fontSize = 14.sp,
                )
            }
        }
        if (display.introduction.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Introduction", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(display.introduction, fontSize = 14.sp)
            }
        }
        OutlinedButton(
            onClick = onLearnMore,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Learn more about ${display.name}")
        }
    }
}

/** The "Contact host" section: a message input + a "Send message" button (issue §6). */
@Composable
private fun ContactProviderSection(
    api: SaigonApi,
    serviceId: String,
    providerName: String,
    price: Int,
    onOpened: (ContactProviderResponse) -> Unit,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var isBooking by remember { mutableStateOf(false) }

    if (!isBooking) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                isBooking = true
            }
        ) {
            Text("Book this meet with $providerName • ${UiConstants.vnd(price)}", textAlign = TextAlign.Center)
        }
    } else {
        val focuser = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            focuser.requestFocus()
        }

        OutlinedTextField(
            value = message,
            onValueChange = { message = it; error = null },
            modifier = Modifier.fillMaxWidth().focusRequester(focuser),
            label = { Text("Your message") },
            shape = InputFieldShape,
            enabled = !busy && enabled,
        )
        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && message.isNotBlank() && enabled,
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
        ) { Text("Send booking request") }
    }
}
