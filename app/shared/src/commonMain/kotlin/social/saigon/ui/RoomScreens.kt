package social.saigon.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import saigonsocial.app.shared.generated.resources.Res
import saigonsocial.app.shared.generated.resources.icon

/** Human-friendly occupant count for room cards: "No people", "1 person", or "N people". */
internal fun peopleCount(count: Int): String = when (count) {
    0 -> "No people"
    1 -> "1 person"
    else -> "$count people"
}

// --- Room list (DESIGN §6) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomListScreen(
    api: SaigonApi,
    onOpen: (RoomSummary) -> Unit,
    canCreateRooms: Boolean = false,
    canManageRooms: Boolean = false,
    directChatCount: Int = 0,
    onOpenDirectChats: () -> Unit = {},
    signedIn: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<RoomSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    // Error shown inside the create dialog when the server rejects the room (e.g. the chosen URL is
    // already taken); the dialog stays open so the user can fix the URL and retry.
    var createError by remember { mutableStateOf<String?>(null) }
    var creatingRoom by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RoomSummary?>(null) }
    var deleting by remember { mutableStateOf<RoomSummary?>(null) }

    suspend fun reload() {
        runCatching { api.rooms(search.trim().ifBlank { null }) }.onSuccess { rooms = it }
    }

    // Search the rooms on the backend, debounced by 500ms so we don't fire a request per keystroke
    // (DESIGN §6). Re-runs whenever the query changes; changing it cancels the pending delay, which
    // is exactly the debounce we want. An empty query simply loads the full list.
    LaunchedEffect(search) {
        val query = search.trim()
        if (query.isNotEmpty()) delay(500)
        error = null
        try {
            rooms = api.rooms(query.ifBlank { null })
        } catch (e: Throwable) {
            error = e.message ?: "Could not reach the server"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null -> Centered {
                    Text(
                        "Couldn't reach Saigon.Social.\n$error",
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                rooms == null -> Centered { LoadingSpinner() }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Hero section (logo + welcome text) is for first-time/visitor context only;
                    // once the user is signed in we hide it so the "Search rooms" row sits right
                    // below the top bar.
                    if (!signedIn) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // App logo, centered above the search field and room list on the home screen.
                                Image(
                                    painter = painterResource(Res.drawable.icon),
                                    contentDescription = "Saigon.Social",
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(bottom = 24.dp)
                                        .size(128.dp),
                                )
                                Text(
                                    text = "Welcome to Saigon's trusted chat room. " +
                                            "We're real people here for real talk! " +
                                            "What's on your mind?",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    // Entry to the open direct chats (DESIGN §7). Shown once at least one chat exists so the
                    // user can see their open conversations and find chats others started with them.
                    if (directChatCount > 0) {
                        item {
                            Card(onClick = onOpenDirectChats, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("\uD83D\uDCAC", fontSize = 24.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Direct chats",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "$directChatCount open",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = search,
                                    onValueChange = { search = it },
                                    label = { Text("Search rooms") },
                                    singleLine = true,
                                    shape = InputFieldShape,
                                    modifier = Modifier.weight(1f),
                                )
                                if (canCreateRooms) {
                                    IconButton(
                                        onClick = { showCreate = true },
                                        modifier = Modifier.padding(top = 8.dp)
                                    ) {
                                        Text("➕")
                                    }
                                }
                            }
                        }
                    }
                    val searching = search.trim().isNotEmpty()
                    item {
                        Text(
                            text = if (searching) "Results" else "Popular",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                    if (rooms!!.isEmpty() && searching) {
                        item {
                            Text(
                                text = "No rooms found.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    items(rooms!!) { room ->
                        Column {
                            Card(onClick = { onOpen(room) }, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(room.emoji, fontSize = 28.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(room.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(
                                            room.atmosphere,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        // The latest message preview, "Sender: text", below the
                                        // atmosphere line (loaded with the room list). Shown only when
                                        // the room actually has a message.
                                        room.lastMessageText?.let { text ->
                                            val sender = room.lastMessageSender?.takeIf { it.isNotBlank() }
                                            Text(
                                                text = buildAnnotatedString {
                                                    if (sender != null) {
                                                        // Bold the sender's name to make it stand out
                                                        // from the message text on the preview line.
                                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                            append("$sender: ")
                                                        }
                                                    }
                                                    append(text)
                                                },
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        // For signed-out visitors, surface the primary call-to-action on every
                                        // room so it's obvious that opening a chat is the main thing to do.
                                        if (!signedIn) {
                                            Button(
                                                onClick = { onOpen(room) },
                                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                            ) {
                                                Text("Open chat")
                                            }
                                        }
                                    }
                                    // Time since the last message, or the people count when the room
                                    // has no message yet.
                                    Text(
                                        room.lastMessageAt?.let { timeAgo(it) } ?: peopleCount(room.occupants),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            if (canManageRooms) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { editing = room }) { Text("✏️ Edit") }
                                    TextButton(onClick = { deleting = room }) { Text("🗑 Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        RoomFormDialog(
            title = "New room",
            initial = null,
            error = createError,
            submitting = creatingRoom,
            onDismiss = {
                showCreate = false
                createError = null
            },
            onConfirm = { name, emoji, atmosphere, id ->
                createError = null
                creatingRoom = true
                scope.launch {
                    val result = runCatching { api.createRoom(name, emoji, atmosphere, id) }
                    creatingRoom = false
                    result.fold(
                        onSuccess = {
                            showCreate = false
                            createError = null
                            reload()
                        },
                        onFailure = {
                            createError = it.message ?: "Could not create the room. Please try again."
                        },
                    )
                }
            },
        )
    }

    editing?.let { room ->
        RoomFormDialog(
            title = "Rename room",
            initial = room,
            onDismiss = { editing = null },
            onConfirm = { name, emoji, atmosphere, _ ->
                editing = null
                scope.launch {
                    runCatching { api.updateRoom(room.id, name, emoji, atmosphere) }
                    reload()
                }
            },
        )
    }

    deleting?.let { room ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete room") },
            text = { Text("Delete \"${room.name}\"? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { api.deleteRoom(room.id) }
                        reload()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Dialog for creating or renaming a room: name, emoji, and atmosphere fields (DESIGN §6). */
@Composable
private fun RoomFormDialog(
    title: String,
    initial: RoomSummary?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, emoji: String, atmosphere: String, id: String) -> Unit,
    /** A server-side error (e.g. the chosen room URL is already taken) shown inside the dialog. */
    error: String? = null,
    /** True while a create request is in flight, so the Save button is disabled to avoid retries. */
    submitting: Boolean = false,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "") }
    var atmosphere by remember { mutableStateOf(initial?.atmosphere ?: "") }
    // The room URL (id) can only be chosen at creation time; existing rooms keep their id.
    var id by remember { mutableStateOf("") }
    val isCreate = initial == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = InputFieldShape,
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text("Emoji") },
                    singleLine = true,
                    shape = InputFieldShape,
                )
                OutlinedTextField(
                    value = atmosphere,
                    onValueChange = { atmosphere = it },
                    label = { Text("Atmosphere (optional)") },
                    shape = InputFieldShape,
                )
                if (isCreate) {
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        label = { Text("URL (optional)") },
                        singleLine = true,
                        shape = InputFieldShape,
                        isError = error != null,
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && emoji.isNotBlank() && !submitting,
                onClick = { onConfirm(name.trim(), emoji.trim(), atmosphere.trim(), id.trim()) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Room / public chat with occupant list, identity, thread sheet (DESIGN §5, §6, §9) ---

@Composable
internal fun RoomChatScreen(
    api: SaigonApi,
    room: RoomSummary,
    hasToken: Boolean,
    canStartPrivate: Boolean,
    selfUserId: String?,
    onStartPrivate: (OccupantView) -> Unit,
    onRecordDirectChat: (DirectChat) -> Unit,
    onIncomingChat: (DirectChat) -> Unit,
    onOpenDirectChat: (DirectChat) -> Unit,
    onOpenRoom: (RoomSummary) -> Unit,
    onSignIn: () -> Unit,
    initialThreadId: String? = null,
    onThreadChange: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val rooms = remember { mutableStateListOf<RoomSummary>() }
    // Search box for the desktop left-hand rooms panel (DESIGN §6). Filtered on the backend and
    // debounced by 500ms, mirroring the standalone room list so the founder gets the same recorded
    // searches no matter where a member looks for a room.
    var roomSearch by remember { mutableStateOf("") }
    LaunchedEffect(roomSearch) {
        val query = roomSearch.trim()
        if (query.isNotEmpty()) delay(500)
        runCatching { api.rooms(query.ifBlank { null }) }.getOrNull()?.let {
            rooms.clear(); rooms.addAll(it)
        }
    }
    val messages = remember(room.id) { mutableStateListOf<FeedMessage>() }
    val occupants = remember(room.id) { mutableStateListOf<OccupantView>() }
    var loading by remember(room.id) { mutableStateOf(true) }
    var error by remember(room.id) { mutableStateOf<String?>(null) }
    var channel by remember(room.id) { mutableStateOf<RoomChannel?>(null) }
    var draft by remember(room.id) { mutableStateOf("") }
    var openThread by remember(room.id) { mutableStateOf<FeedMessage?>(null) }
    // Tapping a message's profile icon opens that author's detail sheet, resolved from the live
    // occupant list by persistent user id (DESIGN §5). Null means no sheet is shown.
    var selectedOccupant by remember(room.id) { mutableStateOf<OccupantView?>(null) }
    // Live thread replies broadcast to the room, so an open thread sheet can append them in realtime
    // for every client — not just the member who sent it. Accumulated (rather than a single latest
    // value) so two replies arriving back-to-back are never dropped before the sheet sees them (DESIGN §9).
    val liveReplies = remember(room.id) { mutableStateListOf<ReplyEvent>() }
    // Infinite scroll: page upward by 20, stopping once a short page signals the start of history.
    var loadingMore by remember(room.id) { mutableStateOf(false) }
    var reachedStart by remember(room.id) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(room.id) {
        loading = true
        error = null
        try {
            val first = api.messages(room.id)
            messages.clear(); messages.addAll(first)
            reachedStart = first.size < 20
            // De-duplicate by persistent user id: a single member can hold several live
            // connections at once (the room socket plus the private-chat socket, or a reconnect
            // that raced the old socket's close), and we must never show one person twice (DESIGN §6).
            occupants.clear(); occupants.addAll(api.occupants(room.id).distinctBy { it.userId })
        } catch (e: Throwable) {
            error = e.message ?: "Could not load this room"
        }
        loading = false
    }

    suspend fun loadOlder() {
        if (loadingMore || reachedStart || messages.isEmpty()) return
        loadingMore = true
        val before = messages.first().createdAt
        val older = runCatching { api.messages(room.id, before = before) }.getOrDefault(emptyList())
        if (older.size < 20) reachedStart = true
        val fresh = older.filter { o -> messages.none { it.id == o.id } }
        if (fresh.isNotEmpty()) messages.addAll(0, fresh)
        loadingMore = false
    }

    // Keep the thread sheet in sync with the route (DESIGN §12): opening `/room/{id}/thread/{id}`
    // directly, or walking the history back stack, drives which message's thread is shown. Re-runs
    // as messages stream in so a deep-linked thread opens once its parent message has loaded.
    LaunchedEffect(initialThreadId, messages.size) {
        when {
            initialThreadId == null -> openThread = null
            openThread?.id != initialThreadId ->
                messages.firstOrNull { it.id == initialThreadId }?.let { openThread = it }
        }
    }

    // Trigger an older-page load only while the user is actively scrolling at the very top.
    LaunchedEffect(listState, room.id) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .collect { (idx, scrolling) -> if (idx == 0 && scrolling) loadOlder() }
    }

    // Scroll to the bottom so the latest message is in view: on initial load (once the list is
    // composed, i.e. loading has finished) and whenever a new message arrives (DESIGN §6).
    // Keyed on the newest message's id so paging in older history (prepended at the top) doesn't
    // yank the view down, and on `loading` so the very first page scrolls down once it's rendered.
    LaunchedEffect(messages.lastOrNull()?.id, loading, room.id) {
        if (!loading && messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount) }
        }
    }

    // Live public-chat WebSocket: join on enter, append broadcasts, close on leave (DESIGN §6, §9).
    DisposableEffect(room.id) {
        val job = scope.launch {
            runCatching {
                val ch = api.openRoom(room.id)
                channel = ch
                ch.start(
                    onMessage = { msg ->
                        if (messages.none { it.id == msg.messageId }) {
                            messages.add(
                                FeedMessage(
                                    id = msg.messageId,
                                    roomId = room.id,
                                    authorId = msg.authorId,
                                    authorName = msg.authorName.orEmpty(),
                                    text = msg.text,
                                    createdAt = msg.createdAt,
                                ),
                            )
                        }
                    },
                    onReply = { ev ->
                        // A thread reply landed: refresh the parent's "↳ N replies" count live so the
                        // messages list shows the message now has replies — updated in place so the
                        // message keeps its position and sent time (DESIGN §9).
                        val idx = messages.indexOfFirst { it.id == ev.parentMessageId }
                        if (idx >= 0) messages[idx] = messages[idx].copy(replyCount = ev.replyCount)
                        // Surface the full reply so an open thread sheet can append it in realtime for
                        // every client in the room, not just the member who sent it (DESIGN §9).
                        if (liveReplies.none { it.replyId == ev.replyId }) liveReplies.add(ev)
                    },
                    onPrivateChatStarted = { peerId, peerName, originRoomId ->
                        // Someone tapped us to open a private chat (DESIGN §7): notify and record it.
                        // Prefer the room the chat was started from; fall back to the current room.
                        val chat = DirectChat(originRoomId ?: room.id, peerId, peerName ?: "Someone")
                        onRecordDirectChat(chat)
                        // Surface the app-level banner (DESIGN §7) rather than a duplicate in-room
                        // one, so the notice is shown the same way it is from every other screen.
                        onIncomingChat(chat)
                    },
                    onPresenceChanged = {
                        // Someone joined or left (DESIGN §6): refresh the live "In the room" list so
                        // it reflects who is actually present right now, not just at room-open time.
                        scope.launch {
                            runCatching { api.occupants(room.id) }.getOrNull()?.let {
                                occupants.clear(); occupants.addAll(it.distinctBy { o -> o.userId })
                            }
                        }
                    },
                )
            }
        }
        onDispose {
            job.cancel()
            scope.launch { runCatching { channel?.close() } }
        }
    }

    if (error != null) {
        Centered { Text(error!!, color = MaterialTheme.colorScheme.error) }
        return
    }

    // Refresh the caller's room-scoped identity (a fresh anonymous name) and reload the occupant
    // list so the user's own entry in "In the room" picks up the new name (DESIGN §5). Surfaced from
    // the member-detail sheet on the user's own card, replacing the standalone identity bar.
    val onRefreshIdentity: () -> Unit = {
        scope.launch {
            runCatching { api.refreshIdentity(room.id) }
            runCatching { api.occupants(room.id) }.getOrNull()?.let {
                occupants.clear(); occupants.addAll(it.distinctBy { o -> o.userId })
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (wide) {
                    RoomNavPanel(
                        rooms = rooms,
                        currentRoomId = room.id,
                        search = roomSearch,
                        onSearchChange = { roomSearch = it },
                        onOpenRoom = onOpenRoom,
                    )
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().then(
                    if (wide) {
                        Modifier.padding(vertical = 16.dp)
                    } else {
                        Modifier.padding(horizontal = 16.dp)
                    }
                )) {
                    if (!wide) {
                        OccupantScroller(
                            occupants = occupants,
                            loading = loading,
                            selfUserId = selfUserId,
                            canStartPrivate = canStartPrivate,
                            onTapAvailable = onStartPrivate,
                            onRefreshSelf = onRefreshIdentity,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    if (loading) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            LoadingSpinner()
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (loadingMore) {
                                item {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        LoadingSpinner()
                                    }
                                }
                            }
                            if (messages.isEmpty()) {
                                item {
                                    Text(
                                        "No messages yet. Be the first to say hello.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // Messages always stay in chronological order (oldest→newest). Receiving a
                            // reply updates a message's "↳ N replies" indicator in place and must never
                            // change its position or sent time (DESIGN §9).
                            items(messages, key = { it.id }) { msg ->
                                MessageRow(
                                    msg = msg,
                                    onOpenThread = { openThread = msg; onThreadChange(msg.id) },
                                    onOpenProfile = {
                                        // Prefer the live occupant (it carries the freshest
                                        // "available" flag); otherwise fetch the author by id so the
                                        // sheet still opens for people who've since left (DESIGN §5, §6).
                                        val present = occupants.firstOrNull { it.userId == msg.authorId }
                                        if (present != null) {
                                            selectedOccupant = present
                                        } else {
                                            scope.launch {
                                                runCatching { api.occupant(room.id, msg.authorId) }
                                                    .getOrNull()?.let { selectedOccupant = it }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    MessageComposer(
                        value = draft,
                        onValueChange = { draft = it },
                        onSend = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                scope.launch { runCatching { channel?.say(text) } }
                                draft = ""
                            }
                        },
                        sendLabel = "Send",
                        placeholder = if (hasToken) "Message" else "Sign in to reply",
                        enabled = hasToken,
                        onDisabledClick = onSignIn,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                if (wide) {
                    OccupantList(
                        occupants = occupants,
                        selfUserId = selfUserId,
                        canStartPrivate = canStartPrivate,
                        onTapAvailable = onStartPrivate,
                        onRefreshSelf = onRefreshIdentity,
                    )
                }
            }

            openThread?.let { msg ->
                ThreadSheet(
                    api = api,
                    roomId = room.id,
                    message = msg,
                    channel = channel,
                    hasToken = hasToken,
                    liveReplies = liveReplies,
                    onSignIn = onSignIn,
                    onClose = { openThread = null; onThreadChange(null) },
                )
            }

            selectedOccupant?.let { selected ->
                // Resolve from the live occupant list so a name change from "Refresh" updates the
                // open sheet in place rather than dismissing it.
                val person = occupants.firstOrNull { it.userId == selected.userId } ?: selected
                val isSelf = selfUserId != null && person.userId == selfUserId
                OccupantDetailSheet(
                    person = person,
                    isSelf = isSelf,
                    canStartPrivate = canStartPrivate,
                    onStartChat = { selectedOccupant = null; onStartPrivate(person) },
                    onRefresh = onRefreshIdentity,
                    onClose = { selectedOccupant = null },
                )
            }
        }
    }
}

/** Order the people so the current user is always first, keeping everyone else in place (DESIGN §6). */
private fun List<OccupantView>.selfFirst(selfUserId: String?): List<OccupantView> =
    if (selfUserId == null) this else sortedByDescending { it.userId == selfUserId }

/**
 * A bottom sheet showing the full details of a single room member (DESIGN §5, §6, §7). Opened by
 * tapping anyone in the "In the room" list — including yourself. The primary action adapts to who
 * is shown: "🔄 Refresh" for your own identity (merging the former identity bar), "Start chat" for
 * Supporters/Founders you can reach, and "Close" for everyone else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccupantDetailSheet(
    person: OccupantView,
    isSelf: Boolean,
    canStartPrivate: Boolean,
    onStartChat: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    // Animate the sheet shut before clearing the selection, so tapping "Close" slides the sheet
    // down the same way swiping it away or tapping the scrim does — rather than vanishing instantly.
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
        PlainDragHandle()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ProfileIcon(name = person.name, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isSelf) "You are \"${person.name}\" in this room" else person.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        person.role.label,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (person.available) {
                    Text(
                        "\uD83D\uDFE2 available",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (person.interests.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Interests", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(person.interests.joinToString(" · "), fontSize = 14.sp)
            }
            if (person.intents.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Here for", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                person.intents.forEach { Text(it.badge, fontSize = 14.sp) }
            }
            if (person.languages.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Speaks", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    person.languages.joinToString(" · ") { UiConstants.languageLabel(it) },
                    fontSize = 14.sp,
                )
            }
            if (person.offlineSchedule.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Availability", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(person.offlineSchedule.joinToString(" · "), fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
            // CTA adapts to who is shown: refresh your own identity, start a chat with a reachable
            // Supporter/Founder, or simply close for everyone else.
            val canChat = canStartPrivate &&
                (person.role == Role.SUPPORTER || person.role == Role.FOUNDER)
            when {
                isSelf -> OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🔄 Refresh") }
                canChat -> Button(
                    onClick = onStartChat,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Chat with ${person.name}") }
                else -> OutlinedButton(
                    onClick = animateClose,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Close") }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: FeedMessage, onOpenThread: () -> Unit, onOpenProfile: () -> Unit) {
    ChatMessage(
        authorName = msg.displayAuthor,
        text = msg.text,
        timestamp = msg.createdAt,
        onClick = onOpenThread,
        onAvatarClick = onOpenProfile,
        footer = if (msg.replyCount > 0) {
            {
                Spacer(Modifier.height(4.dp))
                val replyWord = if (msg.replyCount == 1) "reply" else "replies"
                Text(
                    "\uD83D\uDCAC ${msg.replyCount} $replyWord",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else null,
    )
}

/**
 * A dismissible in-room notice that another member just opened a one-on-one chat with the user
 * (DESIGN §7), with a quick action to jump into it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IncomingChatBanner(chat: DirectChat, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("\uD83D\uDCAC", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                "${chat.peerName} started a chat with you",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccupantList(
    occupants: List<OccupantView>,
    selfUserId: String?,
    canStartPrivate: Boolean,
    onTapAvailable: (OccupantView) -> Unit,
    onRefreshSelf: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
    ) {
        // Final dedup at the render boundary: a LazyColumn key must be unique, so guarantee one
        // card per persistent user id here regardless of how the list was assembled — a member can
        // briefly hold several live connections during join/leave churn (DESIGN §6). The current
        // user is always pinned to the top of the list (DESIGN §6).
        val people = occupants.distinctBy { it.userId }.selfFirst(selfUserId)
        // Tapping anyone opens a detail sheet (DESIGN §5–§7); null means no sheet is shown.
        var selected by remember { mutableStateOf<OccupantView?>(null) }
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("In the room", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                ShareButton()
            }
            Spacer(Modifier.height(8.dp))
            if (people.isEmpty()) {
                Text(
                    text = "Nobody's here right now.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(people, key = { it.userId }) { person ->
                    // Identify yourself by your persistent user id, not your room-scoped name
                    // (names can collide and change). Any tap opens the detail sheet.
                    val isSelf = selfUserId != null && person.userId == selfUserId
                    Card(
                        onClick = { selected = person },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProfileIcon(name = person.name, size = 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (isSelf) "You are \"${person.name}\" in this room" else person.name,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (!isSelf && person.available) {
                                        Text(
                                            "\uD83D\uDFE2",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                OccupantEmojiLine(person)
                            }
                            if (isSelf) {
                                IconButton(onClick = onRefreshSelf, modifier = Modifier.size(28.dp)) {
                                    Text("\uD83D\uDD04", fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        selected?.let { sel ->
            // Resolve from the live occupant list so a name change from "Refresh" updates the open
            // sheet in place rather than dismissing it.
            val person = occupants.firstOrNull { it.userId == sel.userId } ?: sel
            val isSelf = selfUserId != null && person.userId == selfUserId
            OccupantDetailSheet(
                person = person,
                isSelf = isSelf,
                canStartPrivate = canStartPrivate,
                onStartChat = { selected = null; onTapAvailable(person) },
                onRefresh = onRefreshSelf,
                onClose = { selected = null },
            )
        }
    }
}

/**
 * Compact one-line summary of an occupant's languages and intents, shown as emoji only to save
 * space in the member-list cards (DESIGN §6). Shared by both the desktop [OccupantList] and the
 * mobile [OccupantScroller] so the two stay in sync.
 */
@Composable
private fun OccupantEmojiLine(person: OccupantView) {
    val emojis = person.languages.map { UiConstants.languageEmoji(it) } +
        person.intents.map { it.emoji }
    if (emojis.isNotEmpty()) {
        Text(emojis.joinToString(" "), fontSize = 12.sp)
    }
}

/**
 * A share affordance shown beside a section header: tapping it opens a dialog with the current,
 * shareable URL and a "Copy link" button so members can hand the room/thread link to someone else.
 */
@Composable
private fun ShareButton() {
    val scope = rememberCoroutineScope()
    val router = remember { UrlRouter() }
    var showDialog by remember { mutableStateOf(false) }
    // Briefly confirms the link landed on the clipboard after "Copy link" is tapped, then clears
    // itself so the dialog returns to its normal state.
    var copied by remember { mutableStateOf(false) }
    IconButton(onClick = { showDialog = true }, modifier = Modifier.size(28.dp)) {
        Text("\uD83D\uDD17", fontSize = 16.sp)
    }
    if (showDialog) {
        val url = remember { router.currentUrl() }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Share") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        shape = InputFieldShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (copied) {
                        Text(
                            "Link copied!",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(url)
                    // Show a brief "Link copied!" toast, then clear it after a moment.
                    copied = true
                    scope.launch {
                        delay(1500)
                        copied = false
                    }
                }) { Text("Copy link") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Close") }
            },
        )
    }
}

/** Desktop/landscape left panel: the rooms list, letting members hop between rooms (DESIGN §6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomNavPanel(
    rooms: List<RoomSummary>,
    currentRoomId: String,
    search: String,
    onSearchChange: (String) -> Unit,
    onOpenRoom: (RoomSummary) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Popular", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                label = { Text("Search rooms") },
                singleLine = true,
                shape = InputFieldShape,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rooms) { r ->
                    val current = r.id == currentRoomId
                    Card(
                        onClick = { if (!current) onOpenRoom(r) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.emoji, fontSize = 22.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    r.name,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = if (current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    peopleCount(r.occupants),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single-line summary for the collapsed mobile occupant accordion (DESIGN §6): "1 person in the
 * room", "2 people in the room", or a friendly note when the room is empty.
 */
internal fun occupantSummary(count: Int): String = when (count) {
    0 -> "Nobody's here right now"
    1 -> "1 person in the room"
    else -> "$count people in the room"
}

/**
 * The expand/collapse cue for the mobile occupant accordion. Drawn with [Canvas] rather than a font
 * glyph so it renders identically on every platform, and animates a 180° rotation between pointing
 * down (collapsed) and up (expanded).
 */
@Composable
private fun Chevron(expanded: Boolean) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(20.dp).rotate(rotation)) {
        // A downward "v": from the top-left, down to the bottom-centre, back up to the top-right.
        val left = size.width * 0.28f
        val right = size.width * 0.72f
        val top = size.height * 0.40f
        val bottom = size.height * 0.62f
        val mid = size.width / 2f
        val stroke = Stroke(width = size.width * 0.10f, cap = StrokeCap.Round)
        drawLine(color, Offset(left, top), Offset(mid, bottom), strokeWidth = stroke.width, cap = stroke.cap)
        drawLine(color, Offset(right, top), Offset(mid, bottom), strokeWidth = stroke.width, cap = stroke.cap)
    }
}

/** Portrait/mobile: the room's people in a horizontal scroller pinned to the top (DESIGN §6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccupantScroller(
    occupants: List<OccupantView>,
    loading: Boolean,
    selfUserId: String?,
    canStartPrivate: Boolean,
    onTapAvailable: (OccupantView) -> Unit,
    onRefreshSelf: () -> Unit,
) {
    // Final dedup at the render boundary: guarantee one card per persistent user id regardless of
    // how the list was assembled — a member can briefly hold several live connections during
    // join/leave churn (DESIGN §6). The current user is always pinned first (DESIGN §6).
    val people = occupants.distinctBy { it.userId }.selfFirst(selfUserId)
    // Tapping anyone opens a detail sheet (DESIGN §5–§7); null means no sheet is shown.
    var selected by remember { mutableStateOf<OccupantView?>(null) }
    // On mobile the people list is an accordion that starts collapsed so it takes up only a single
    // line ("N people in the room") until the user expands it; the Share action and the scroller of
    // member cards are only revealed once expanded (DESIGN §6).
    var expanded by remember { mutableStateOf(false) }
    // The whole accordion — its summary/header row and the expanded scroller of member cards — lives
    // inside a single rounded soft border so it reads as one consistent component (DESIGN §6).
    val shape = if (expanded) MaterialTheme.shapes.large else MaterialTheme.shapes.medium
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .softBorder(shape)
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp, horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            if (loading && people.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text(
                    text = if (expanded) "In the room" else occupantSummary(people.size),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            // A chevron drawn directly (rather than a font glyph) so it always renders, rotating
            // from pointing-down (collapsed) to pointing-up (expanded) — the usual accordion cue.
            Chevron(expanded = expanded)
            // Share is only revealed once the accordion is open.
            if (expanded) {
                Spacer(modifier = Modifier.width(16.dp))
                ShareButton()
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            if (!loading && people.isEmpty()) {
                Text(
                    text = "Nobody's here right now.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // A horizontally-scrolling Row (rather than a LazyRow) measured at IntrinsicSize.Max
                // so the row is exactly as tall as its tallest card's natural content. Each card then
                // fills that height (fillMaxHeight), making every card the same height without forcing
                // the row to grow beyond the normal content height (DESIGN §6).
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .height(IntrinsicSize.Max)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    people.forEach { person ->
                        // Identify yourself by your persistent user id; any tap opens the detail sheet.
                        val isSelf = selfUserId != null && person.userId == selfUserId
                        Card(
                            onClick = { selected = person },
                            modifier = Modifier.width(180.dp).fillMaxHeight(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProfileIcon(name = person.name, size = 28.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (isSelf) "You are \"${person.name}\" in this room" else person.name,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                        fontSize = 14.sp
                                    )
                                    if (isSelf) {
                                        IconButton(
                                            onClick = onRefreshSelf,
                                            modifier = Modifier.size(24.dp).align(Alignment.CenterVertically),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text("\uD83D\uDD04", fontSize = 14.sp)
                                            }
                                        }
                                    } else if (person.available) {
                                        Text(
                                            "\uD83D\uDFE2",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                OccupantEmojiLine(person)
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { sel ->
        // Resolve from the live occupant list so a name change from "Refresh" updates the open
        // sheet in place rather than dismissing it.
        val person = occupants.firstOrNull { it.userId == sel.userId } ?: sel
        val isSelf = selfUserId != null && person.userId == selfUserId
        OccupantDetailSheet(
            person = person,
            isSelf = isSelf,
            canStartPrivate = canStartPrivate,
            onStartChat = { selected = null; onTapAvailable(person) },
            onRefresh = onRefreshSelf,
            onClose = { selected = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadSheet(
    api: SaigonApi,
    roomId: String,
    message: FeedMessage,
    channel: RoomChannel?,
    hasToken: Boolean,
    liveReplies: List<ReplyEvent>,
    onSignIn: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val replies = remember(message.id) { mutableStateListOf<ThreadReply>() }
    var draft by remember(message.id) { mutableStateOf("") }
    // Track the first load so the sheet shows a spinner instead of flashing the empty state while
    // the replies for a message that clearly has a thread are still being fetched (DESIGN §9).
    var loadingReplies by remember(message.id) { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState()
    // Scroll state for the reply list so the thread can pin to the latest reply, mirroring how the
    // room's message list scrolls to the bottom on load and on new messages (DESIGN §6, §9).
    val repliesScroll = rememberScrollState()

    suspend fun reload() {
        val fresh = runCatching { api.thread(roomId, message.id) }.getOrDefault(emptyList())
        replies.clear(); replies.addAll(fresh)
    }
    LaunchedEffect(message.id) {
        loadingReplies = true
        reload()
        loadingReplies = false
    }

    // Append live replies broadcast to the room so the open thread updates in realtime for every
    // client — including replies sent by other members — keeping the list in time order (DESIGN §9).
    LaunchedEffect(liveReplies.size, message.id) {
        val newOnes = liveReplies.filter { ev ->
            ev.parentMessageId == message.id && replies.none { it.id == ev.replyId }
        }
        if (newOnes.isEmpty()) return@LaunchedEffect
        newOnes.forEach { ev ->
            replies.add(
                ThreadReply(
                    id = ev.replyId,
                    parentMessageId = ev.parentMessageId,
                    replyToId = ev.replyToId,
                    authorId = ev.authorId,
                    authorName = ev.authorName.orEmpty(),
                    text = ev.text,
                    createdAt = ev.createdAt,
                ),
            )
        }
        replies.sortBy { it.createdAt }
    }

    // Scroll to the bottom so the latest reply is in view: on initial load (once the replies have
    // finished loading) and whenever a new reply arrives, mirroring the room's message list (DESIGN §9).
    LaunchedEffect(replies.size, loadingReplies, message.id) {
        if (!loadingReplies && replies.isNotEmpty()) {
            runCatching { repliesScroll.animateScrollTo(repliesScroll.maxValue) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        // Everything below the sheet's drag handle; the reply list scrolls on its own. The handle is
        // rendered inline (not via the dragHandle slot) so it carries no "Drag handle" tooltip/semantics.
        PlainDragHandle()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Thread", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                ShareButton()
            }
            Spacer(Modifier.height(16.dp))
            ChatMessage(
                authorName = message.displayAuthor,
                text = message.text,
                timestamp = message.createdAt,
            )
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(repliesScroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loadingReplies) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingSpinner(modifier = Modifier.width(18.dp).height(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Loading replies\u2026",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (replies.isEmpty()) {
                    Text(
                        "No replies yet.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                replies.forEach { reply ->
                    ChatMessage(
                        authorName = reply.displayAuthor,
                        text = reply.text,
                        timestamp = reply.createdAt,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            MessageComposer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        // Just send: the server broadcasts the reply back to the whole room, and the
                        // live-reply effect above appends it in realtime — same path every other
                        // client sees, so the sender's own reply stays in sync too (DESIGN §9).
                        scope.launch { runCatching { channel?.reply(message.id, text) } }
                        draft = ""
                    }
                },
                sendLabel = "Reply",
                placeholder = if (hasToken) "Reply" else "Sign in to reply",
                enabled = hasToken,
                onDisabledClick = onSignIn,
            )
        }
    }
}

/**
 * The open direct chats screen (DESIGN §7): the one-on-one conversations the user has started or
 * that others have started with them this session. Tapping one re-opens it. Private chats are
 * ephemeral, so this list resets when the app restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectChatsScreen(api: SaigonApi, chats: List<DirectChat>, onOpen: (DirectChat) -> Unit) {
    if (chats.isEmpty()) {
        Centered {
            Text(
                "No direct chats yet. Tap an available person in a room to start one.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Resolve each chat's origin room (DESIGN §7): the chat preserves the room it was created from
    // via its `roomId`, so look up that room's name + emoji to show where the conversation began.
    var roomsById by remember { mutableStateOf<Map<String, RoomSummary>>(emptyMap()) }
    LaunchedEffect(Unit) {
        runCatching { api.rooms() }.getOrNull()?.let { list ->
            roomsById = list.associateBy { it.id }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(chats, key = { it.targetUserId + it.roomId }) { chat ->
            Card(onClick = { onOpen(chat) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileIcon(name = chat.peerName, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(chat.peerName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        roomsById[chat.roomId]?.let { room ->
                            Text(
                                "${room.emoji} ${room.name}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
