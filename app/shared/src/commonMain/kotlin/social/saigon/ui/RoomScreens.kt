package social.saigon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
) {
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<RoomSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
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
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search rooms") },
            singleLine = true,
            shape = InputFieldShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
    when {
        error != null -> Centered {
            Text(
                "Couldn't reach Saigon.Social.\n$error",
                color = MaterialTheme.colorScheme.error,
            )
        }
        rooms == null -> Centered { CircularProgressIndicator() }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Welcome to Saigon's trusted chat room. We're all real people, and we're into real conversation! What do you want to talk about?",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
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
                                "Direct chats",
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
            if (canCreateRooms) {
                item {
                    Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("➕ New room")
                    }
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
                            }
                            Text(
                                peopleCount(room.occupants),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (canManageRooms) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = room }) { Text("✏️ Rename") }
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
            onDismiss = { showCreate = false },
            onConfirm = { name, emoji, atmosphere ->
                showCreate = false
                scope.launch {
                    runCatching { api.createRoom(name, emoji, atmosphere) }
                    reload()
                }
            },
        )
    }

    editing?.let { room ->
        RoomFormDialog(
            title = "Rename room",
            initial = room,
            onDismiss = { editing = null },
            onConfirm = { name, emoji, atmosphere ->
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
    onConfirm: (name: String, emoji: String, atmosphere: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "") }
    var atmosphere by remember { mutableStateOf(initial?.atmosphere ?: "") }
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && emoji.isNotBlank(),
                onClick = { onConfirm(name.trim(), emoji.trim(), atmosphere.trim()) },
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
    onOpenDirectChat: (DirectChat) -> Unit,
    onOpenRoom: (RoomSummary) -> Unit,
    onSignIn: () -> Unit,
    initialThreadId: String? = null,
    onThreadChange: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val rooms = remember { mutableStateListOf<RoomSummary>() }
    LaunchedEffect(Unit) {
        runCatching { api.rooms() }.getOrNull()?.let { rooms.clear(); rooms.addAll(it) }
    }
    val messages = remember(room.id) { mutableStateListOf<FeedMessage>() }
    val occupants = remember(room.id) { mutableStateListOf<OccupantView>() }
    var loading by remember(room.id) { mutableStateOf(true) }
    var error by remember(room.id) { mutableStateOf<String?>(null) }
    var channel by remember(room.id) { mutableStateOf<RoomChannel?>(null) }
    var identity by remember(room.id) { mutableStateOf<RoomIdentity?>(null) }
    var draft by remember(room.id) { mutableStateOf("") }
    var openThread by remember(room.id) { mutableStateOf<FeedMessage?>(null) }
    // Live thread replies broadcast to the room, so an open thread sheet can append them in realtime
    // for every client — not just the member who sent it. Accumulated (rather than a single latest
    // value) so two replies arriving back-to-back are never dropped before the sheet sees them (DESIGN §9).
    val liveReplies = remember(room.id) { mutableStateListOf<ReplyEvent>() }
    // The most recent chat another member opened with us while we're in this room (DESIGN §7), shown
    // as a dismissible banner so we notice a new direct chat without leaving the room.
    var incomingChat by remember(room.id) { mutableStateOf<DirectChat?>(null) }
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
            occupants.clear(); occupants.addAll(api.occupants(room.id))
            if (hasToken) identity = runCatching { api.identity(room.id) }.getOrNull()
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
                        incomingChat = chat
                    },
                    onPresenceChanged = {
                        // Someone joined or left (DESIGN §6): refresh the live "In the room" list so
                        // it reflects who is actually present right now, not just at room-open time.
                        scope.launch {
                            runCatching { api.occupants(room.id) }.getOrNull()?.let {
                                occupants.clear(); occupants.addAll(it)
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (wide) {
                RoomNavPanel(rooms = rooms, currentRoomId = room.id, onOpenRoom = onOpenRoom)
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                if (!wide) {
                    OccupantScroller(occupants = occupants, selfUserId = selfUserId, canStartPrivate = canStartPrivate, onTapAvailable = onStartPrivate)
                    Spacer(Modifier.height(12.dp))
                }
                incomingChat?.let { chat ->
                    IncomingChatBanner(
                        chat = chat,
                        onOpen = { incomingChat = null; onOpenDirectChat(chat) },
                        onDismiss = { incomingChat = null },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (hasToken) {
                    IdentityBar(
                        identity = identity,
                        onRefresh = {
                            scope.launch {
                                identity = runCatching { api.refreshIdentity(room.id) }.getOrNull()
                                // The occupant "In the room" list caches the old name, so reload it
                                // too — otherwise the user's own entry keeps showing the prior name.
                                runCatching { api.occupants(room.id) }.getOrNull()?.let {
                                    occupants.clear(); occupants.addAll(it)
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (loading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
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
                                    CircularProgressIndicator()
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
                            MessageRow(msg = msg, onOpenThread = { openThread = msg; onThreadChange(msg.id) })
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
                    placeholder = if (hasToken) "Message" else "Sign in to send",
                    enabled = hasToken,
                    onDisabledClick = onSignIn,
                )
            }

            if (wide) {
                OccupantList(occupants = occupants, selfUserId = selfUserId, canStartPrivate = canStartPrivate, onTapAvailable = onStartPrivate)
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
        }
    }
}

@Composable
private fun IdentityBar(identity: RoomIdentity?, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = identity?.name ?: "…"
            ProfileIcon(name = name, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "You are \"$name\" here",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onRefresh) { Text("🔄 Refresh") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageRow(msg: FeedMessage, onOpenThread: () -> Unit) {
    Card(onClick = onOpenThread, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            ProfileIcon(name = msg.displayAuthor, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Author and, when known, how long ago the message was sent (DESIGN §9).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(msg.displayAuthor, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val ago = timeAgo(msg.createdAt)
                    if (ago.isNotEmpty()) {
                        Text(ago, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(msg.text, fontSize = 15.sp)
                if (msg.replyCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    val replyWord = if (msg.replyCount == 1) "reply" else "replies"
                    Text(
                        "\uD83D\uDCAC ${msg.replyCount} $replyWord",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
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
private fun OccupantList(occupants: List<OccupantView>, selfUserId: String?, canStartPrivate: Boolean, onTapAvailable: (OccupantView) -> Unit) {
    Surface(
        modifier = Modifier.width(260.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text("In the room", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (occupants.isEmpty()) {
                Text(
                    "Nobody's here right now.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(occupants) { person ->
                    // Identify yourself by your persistent user id, not your room-scoped name
                    // (names can collide and change). Never dim the card — just don't let a tap on
                    // yourself (or an unavailable person) open a chat.
                    // Only members may open a chat, and never with yourself or an unavailable person.
                    val isSelf = selfUserId != null && person.userId == selfUserId
                    val tappable = canStartPrivate && person.available && !isSelf
                    Card(
                        onClick = { if (tappable) onTapAvailable(person) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            ProfileIcon(name = person.name, size = 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(person.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    if (person.available) {
                                        Text(
                                            "\uD83D\uDFE2 available",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                person.intents.forEach { Text(it.badge, fontSize = 12.sp) }
                                Text(
                                    person.languages.joinToString(" · ") { UiConstants.languageLabel(it) },
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

/** Desktop/landscape left panel: the rooms list, letting members hop between rooms (DESIGN §6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomNavPanel(
    rooms: List<RoomSummary>,
    currentRoomId: String,
    onOpenRoom: (RoomSummary) -> Unit,
) {
    Surface(
        modifier = Modifier.width(260.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text("Rooms", fontWeight = FontWeight.Bold)
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

/** Portrait/mobile: the room's people in a horizontal scroller pinned to the top (DESIGN §6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccupantScroller(occupants: List<OccupantView>, selfUserId: String?, canStartPrivate: Boolean, onTapAvailable: (OccupantView) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("In the room", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (occupants.isEmpty()) {
            Text(
                "Nobody's here right now.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(occupants) { person ->
                    // Only members may open a chat, and never with yourself or an unavailable person.
                    val isSelf = selfUserId != null && person.userId == selfUserId
                    val tappable = canStartPrivate && person.available && !isSelf
                    Card(
                        onClick = { if (tappable) onTapAvailable(person) },
                        modifier = Modifier.width(180.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProfileIcon(name = person.name, size = 28.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(person.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (person.available) {
                                    Text(
                                        "\uD83D\uDFE2",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                person.languages.joinToString(" · ") { UiConstants.languageLabel(it) },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            person.intents.forEach { Text(it.badge, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
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
            Text("Thread", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp)) {
                    ProfileIcon(name = message.displayAuthor, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(message.displayAuthor, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            val ago = timeAgo(message.createdAt)
                            if (ago.isNotEmpty()) {
                                Text(ago, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(message.text)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loadingReplies) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
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
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        ProfileIcon(name = reply.displayAuthor, size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(reply.displayAuthor, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                val ago = timeAgo(reply.createdAt)
                                if (ago.isNotEmpty()) {
                                    Text(ago, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(reply.text, fontSize = 14.sp)
                        }
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
internal fun DirectChatsScreen(chats: List<DirectChat>, onOpen: (DirectChat) -> Unit) {
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
                    Text(chat.peerName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
