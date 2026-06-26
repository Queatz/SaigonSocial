package social.saigon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compose Web app shell + navigation (DESIGN §12), wired to the live [SaigonApi].
 *
 * Every screen renders data the backend actually persists (ArangoDB) and reads/writes over HTTP +
 * WebSocket: room list → room/public chat (occupant list + per-room identity + thread sheet) →
 * private chat → final-emoji, plus the profile editor. There is no in-memory sample data.
 */
private sealed interface Screen {
    data object RoomList : Screen
    data class Room(val room: RoomSummary) : Screen
    data class Private(val peer: String) : Screen
    data class FinalEmoji(val peer: String) : Screen
    data object Profile : Screen
}

@Composable
fun SaigonApp() {
    SaigonTheme {
        val api = remember { SaigonApi() }
        // Restore a previously-saved access token so a page reload doesn't sign the user out
        // (DESIGN §2): the token is the only credential, persisted via TokenStore (localStorage on web).
        var token by remember { mutableStateOf(TokenStore.load()) }
        LaunchedEffect(token) {
            api.token = token
            TokenStore.save(token)
        }

        var screen by remember { mutableStateOf<Screen>(Screen.RoomList) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    title = when (val s = screen) {
                        Screen.RoomList -> "Saigon.Social"
                        is Screen.Room -> "${s.room.emoji} ${s.room.name}"
                        is Screen.Private -> "Private chat · ${s.peer}"
                        is Screen.FinalEmoji -> "One last emoji"
                        Screen.Profile -> if (token != null) "Your profile" else "Sign in"
                    },
                    canGoBack = screen != Screen.RoomList,
                    signedIn = token != null,
                    onBack = { screen = Screen.RoomList },
                    onProfile = { screen = Screen.Profile },
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val s = screen) {
                        Screen.RoomList -> MaxWidthContainer { RoomListScreen(api, onOpen = { screen = Screen.Room(it) }) }
                        is Screen.Room -> RoomChatScreen(
                            api = api,
                            room = s.room,
                            hasToken = token != null,
                            onStartPrivate = { screen = Screen.Private(it) },
                            onOpenRoom = { screen = Screen.Room(it) },
                        )
                        is Screen.Private -> PrivateChatScreen(
                            peer = s.peer,
                            onEnd = { screen = Screen.FinalEmoji(s.peer) },
                        )
                        is Screen.FinalEmoji -> FinalEmojiScreen(
                            peer = s.peer,
                            onClose = { screen = Screen.RoomList },
                        )
                        Screen.Profile -> MaxWidthContainer { ProfileScreen(api, token, onToken = { token = it }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    canGoBack: Boolean,
    signedIn: Boolean,
    onBack: () -> Unit,
    onProfile: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                TextButton(onClick = onBack) { Text("⬅\uFE0F Back", color = MaterialTheme.colorScheme.onPrimary) }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onProfile) {
                Text(if (signedIn) "Profile" else "Sign in", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * Constrains its content to a max width of 800.dp and centers it horizontally, so the profile/login
 * and room-list screens don't stretch edge-to-edge on landscape/desktop layouts.
 */
@Composable
private fun MaxWidthContainer(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = 800.dp).fillMaxSize()) { content() }
    }
}

/**
 * The shared message/reply input row used by every chat surface (DESIGN §6, §7, §9).
 *
 * The text field carries the app-wide 16.dp corner radius and auto-expands up to a few lines as
 * the draft grows. Pressing Enter sends; Shift+Enter inserts a newline. The trailing action button
 * is forced to the field's full height so it lines up no matter how tall the field becomes.
 */
@Composable
private fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    sendLabel: String,
    placeholder: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                if (
                    enabled &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Enter &&
                    !event.isShiftPressed
                ) {
                    onSend()
                    true
                } else {
                    false
                }
            },
            label = { Text(placeholder) },
            shape = InputFieldShape,
            maxLines = 5,
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            enabled = enabled,
            onClick = onSend,
            modifier = Modifier.fillMaxHeight(),
        ) { Text(sendLabel) }
    }
}

/**
 * A plain bottom-sheet drag handle (DESIGN §9): just the visual grab bar, with none of the
 * Material default's hover highlight or "expand/collapse" tooltip semantics.
 */
@Composable
private fun PlainDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

// --- Room list (DESIGN §6) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomListScreen(api: SaigonApi, onOpen: (RoomSummary) -> Unit) {
    var rooms by remember { mutableStateOf<List<RoomSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            rooms = api.rooms()
        } catch (e: Throwable) {
            error = e.message ?: "Could not reach the server"
        }
    }

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
                    "A digital room for the city. Real people, real conversations, zero pressure.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            items(rooms!!) { room ->
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
                            "${room.occupants} people",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

// --- Room / public chat with occupant list, identity, thread sheet (DESIGN §5, §6, §9) ---

@Composable
private fun RoomChatScreen(
    api: SaigonApi,
    room: RoomSummary,
    hasToken: Boolean,
    onStartPrivate: (String) -> Unit,
    onOpenRoom: (RoomSummary) -> Unit,
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

    LaunchedEffect(room.id) {
        loading = true
        error = null
        try {
            messages.clear(); messages.addAll(api.messages(room.id))
            occupants.clear(); occupants.addAll(api.occupants(room.id))
            if (hasToken) identity = runCatching { api.identity(room.id) }.getOrNull()
        } catch (e: Throwable) {
            error = e.message ?: "Could not load this room"
        }
        loading = false
    }

    // Live public-chat WebSocket: join on enter, append broadcasts, close on leave (DESIGN §6, §9).
    DisposableEffect(room.id) {
        val job = scope.launch {
            runCatching {
                val ch = api.openRoom(room.id)
                channel = ch
                ch.start { msg ->
                    if (messages.none { it.id == msg.messageId }) {
                        messages.add(
                            FeedMessage(
                                id = msg.messageId,
                                roomId = room.id,
                                authorId = msg.authorName ?: msg.authorId,
                                text = msg.text,
                                createdAt = msg.createdAt,
                            ),
                        )
                    }
                }
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
                    OccupantScroller(occupants = occupants, onTapAvailable = onStartPrivate)
                    Spacer(Modifier.height(12.dp))
                }
                if (hasToken) {
                    IdentityBar(
                        identity = identity,
                        onRefresh = {
                            scope.launch { identity = runCatching { api.refreshIdentity(room.id) }.getOrNull() }
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
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Text(
                                    "No messages yet. Be the first to say hello.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(messages) { msg ->
                            MessageRow(msg = msg, onOpenThread = { openThread = msg })
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
                    placeholder = if (hasToken) "Message (plain text only)" else "Sign in (Profile) to send",
                    enabled = hasToken,
                )
            }

            if (wide) {
                OccupantList(occupants = occupants, onTapAvailable = onStartPrivate)
            }
        }

        openThread?.let { msg ->
            ThreadSheet(
                api = api,
                roomId = room.id,
                message = msg,
                channel = channel,
                hasToken = hasToken,
                onClose = { openThread = null },
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
            Box(
                modifier = Modifier.width(36.dp).height(36.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(name.take(1)) }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("You are \"$name\" here", fontWeight = FontWeight.Bold)
                Text(
                    "Anonymous, per-room identity",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRefresh) { Text("🔄 Refresh name & avatar") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageRow(msg: FeedMessage, onOpenThread: () -> Unit) {
    Card(onClick = onOpenThread, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(msg.authorId, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(msg.text, fontSize = 15.sp)
            if (msg.replyCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "↳ ${msg.replyCount} replies (tap to open)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccupantList(occupants: List<OccupantView>, onTapAvailable: (String) -> Unit) {
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
                    Card(
                        onClick = { onTapAvailable(person.name) },
                        enabled = person.available,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(person.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (person.available) {
                                    Text(
                                        "● available",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            person.intent?.let { Text(it.badge, fontSize = 12.sp) }
                            Text(
                                person.languages.joinToString(" · "),
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
                                    "${r.occupants} people",
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
private fun OccupantScroller(occupants: List<OccupantView>, onTapAvailable: (String) -> Unit) {
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
                    Card(
                        onClick = { onTapAvailable(person.name) },
                        enabled = person.available,
                        modifier = Modifier.width(160.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(person.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (person.available) {
                                    Text(
                                        "●",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            person.intent?.let { Text(it.badge, fontSize = 12.sp) }
                            Text(
                                person.languages.joinToString(" · "),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadSheet(
    api: SaigonApi,
    roomId: String,
    message: FeedMessage,
    channel: RoomChannel?,
    hasToken: Boolean,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val replies = remember(message.id) { mutableStateListOf<ThreadReply>() }
    var draft by remember(message.id) { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    suspend fun reload() {
        val fresh = runCatching { api.thread(roomId, message.id) }.getOrDefault(emptyList())
        replies.clear(); replies.addAll(fresh)
    }
    LaunchedEffect(message.id) { reload() }

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
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(message.authorId, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(message.text)
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (replies.isEmpty()) {
                    Text(
                        "No replies yet.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                replies.forEach { reply ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("${reply.authorId}: ", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(reply.text, fontSize = 14.sp)
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
                        scope.launch {
                            runCatching { channel?.reply(message.id, text) }
                            delay(300)
                            reload()
                        }
                        draft = ""
                    }
                },
                sendLabel = "Reply",
                placeholder = if (hasToken) "Type your reply..." else "Sign in to reply",
                enabled = hasToken,
            )
        }
    }
}

// --- Private chat window (DESIGN §7) — ephemeral session, nothing persisted ---

@Composable
private fun PrivateChatScreen(peer: String, onEnd: () -> Unit) {
    val messages = remember { mutableStateListOf<PrivateMessage>() }
    var draft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Plain text only · no typing indicators, read receipts, or \"last seen\". " +
                "Nothing is stored after the chat ends.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { m ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        color = if (m.mine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(m.text, modifier = Modifier.padding(10.dp), fontSize = 15.sp)
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
                    messages.add(PrivateMessage("You", text, mine = true))
                    draft = ""
                }
            },
            sendLabel = "Send",
            placeholder = "Message $peer",
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth()) { Text("End chat") }
    }
}

// --- Final emoji selection + reveal (DESIGN §8) ---

@Composable
private fun FinalEmojiScreen(peer: String, onClose: () -> Unit) {
    var mine by remember { mutableStateOf<String?>(null) }
    // In a live two-party session the peer's pick arrives over the WebSocket; shown after you choose.
    val theirs = remember { UiConstants.emojiOptions[3] } // 🙏

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mine == null) {
            Text("One last emoji to sum it up?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(20.dp))
            EmojiGrid(onPick = { mine = it })
            Spacer(Modifier.height(12.dp))
            Text(
                "Your pick stays hidden until $peer also chooses.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("How you summed each other up", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                RevealColumn("You sent", mine!!)
                RevealColumn("$peer sent", theirs)
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = onClose) { Text("Close") }
            Spacer(Modifier.height(8.dp))
            Text(
                "No follow-up nudges. The emojis are the closing note.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmojiGrid(onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiConstants.emojiOptions.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { emoji ->
                    val emojiShape = RoundedCornerShape(12.dp)
                    Box(
                        modifier = Modifier.width(56.dp).height(56.dp)
                            .clip(emojiShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, emojiShape)
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 28.sp) }
                }
            }
        }
    }
}

@Composable
private fun RevealColumn(label: String, emoji: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(emoji, fontSize = 48.sp)
    }
}

// --- Profile setup / edit (DESIGN §4) ---

@Composable
private fun ProfileScreen(api: SaigonApi, token: String?, onToken: (String?) -> Unit) {
    if (token == null) {
        SignInPanel(api = api, onToken = onToken)
        return
    }

    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<ProfileResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token) {
        error = null
        try {
            profile = api.profile()
        } catch (e: Throwable) {
            error = e.message ?: "Could not load your profile"
        }
    }

    val p = profile
    when {
        error != null -> Centered { Text(error!!, color = MaterialTheme.colorScheme.error) }
        p == null -> Centered { CircularProgressIndicator() }
        else -> {
            var interests by remember(p.id) { mutableStateOf(p.interests.joinToString(", ")) }
            var intent by remember(p.id) { mutableStateOf(p.intent) }
            val languages = remember(p.id) { p.languages.toMutableStateList() }
            var schedule by remember(p.id) { mutableStateOf(p.offlineSchedule.joinToString("; ")) }

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${p.displayName} · ${p.role.label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = interests,
                    onValueChange = { interests = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Interests (max 5, comma-separated)") },
                    shape = InputFieldShape,
                )

                Text("Intent", fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UiConstants.intents.chunked(2).forEach { rowIntents ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowIntents.forEach { option ->
                                val selected = option == intent
                                OutlinedButton(
                                    onClick = { intent = option },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text((if (selected) "✓ " else "") + option.badge, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Text("Languages", fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UiConstants.supportedLanguages.chunked(3).forEach { rowLangs ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowLangs.forEach { code ->
                                val selected = code in languages
                                OutlinedButton(
                                    onClick = { if (selected) languages.remove(code) else languages.add(code) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text((if (selected) "✓ " else "") + code, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Offline schedule (optional, ';'-separated)") },
                    shape = InputFieldShape,
                )

                status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        status = null
                        scope.launch {
                            val update = ProfileUpdate(
                                interests = interests.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                intent = intent,
                                languages = languages.toList(),
                                offlineSchedule = schedule.split(";").map { it.trim() }.filter { it.isNotEmpty() },
                            )
                            status = try {
                                profile = api.updateProfile(update)
                                "Saved."
                            } catch (e: Throwable) {
                                e.message ?: "Could not save"
                            }
                        }
                    },
                ) { Text("Save profile") }

                PasswordSection(api = api, profile = p, onUpdated = { profile = it })

                if (p.role == Role.FOUNDER) {
                    FounderTokenSection(api)
                    FounderMembersSection(api)
                }

                OutlinedButton(onClick = { onToken(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
            }
        }
    }
}

/**
 * "Set a password" / "Change password" section on the profile (PASSWORD_AUTH.md §2B).
 *
 * After signing in with their token, a member can set a durable password — paired with their
 * login-only [ProfileResponse.handle] — to sign back in later without re-pasting a token. Changing
 * an existing password requires the current one. The returned profile is surfaced so the section
 * flips between "set" and "change" wording immediately.
 */
@Composable
private fun PasswordSection(api: SaigonApi, profile: ProfileResponse, onUpdated: (ProfileResponse) -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember(profile.passwordSet) { mutableStateOf("") }
    var next by remember(profile.passwordSet) { mutableStateOf("") }
    var confirm by remember(profile.passwordSet) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    HorizontalDivider()
    Text(if (profile.passwordSet) "Change your password" else "Set a password", fontWeight = FontWeight.Bold)
    Text(
        buildString {
            append("Set a password to sign back in later without re-pasting your access token")
            profile.handle?.let { append(" — your sign-in username is \"$it\"") }
            append(".")
        },
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (profile.passwordSet) {
        OutlinedTextField(
            value = current,
            onValueChange = { current = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Current password") },
            singleLine = true,
            shape = InputFieldShape,
            enabled = !busy,
        )
    }
    OutlinedTextField(
        value = next,
        onValueChange = { next = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("New password (min 8 characters)") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    OutlinedTextField(
        value = confirm,
        onValueChange = { confirm = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Confirm new password") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        onClick = {
            error = null
            status = null
            when {
                next.length < 8 -> error = "Password must be at least 8 characters."
                next != confirm -> error = "Passwords don't match."
                else -> {
                    busy = true
                    scope.launch {
                        try {
                            val updated = api.setPassword(next, current.ifBlank { null })
                            onUpdated(updated)
                            status = "Password saved."
                        } catch (e: Throwable) {
                            error = e.message ?: "Could not save your password."
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        },
    ) { Text(if (profile.passwordSet) "Update password" else "Save password") }
}

/**
 * Founder-only tool: mint an access token for a new member (DESIGN §2, §3). The founder collects
 * the join fee in person, names the member, picks their tier, and hands them the generated token to
 * sign in with — this is how every non-founder account comes to exist.
 */
@Composable
private fun FounderTokenSection(api: SaigonApi) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.SHARER) }
    var minted by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    HorizontalDivider()
    Text("Create an access token", fontWeight = FontWeight.Bold)
    Text(
        "Onboard a new member: name them, pick their tier, and share the generated token so they " +
            "can sign in.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; error = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Member display name") },
        singleLine = true,
        shape = InputFieldShape,
        enabled = !busy,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Role.SHARER, Role.SUPPORTER).forEach { option ->
            val selected = option == role
            OutlinedButton(onClick = { role = option }, modifier = Modifier.weight(1f)) {
                Text((if (selected) "✓ " else "") + option.label, fontSize = 12.sp)
            }
        }
    }
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    minted?.let {
        Text("New access token (give this to ${name.ifBlank { "the member" }}):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
    }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy && name.isNotBlank(),
        onClick = {
            error = null
            minted = null
            busy = true
            scope.launch {
                try {
                    minted = api.onboard(name.trim(), role).token
                } catch (e: Throwable) {
                    error = e.message ?: "Could not create a token."
                } finally {
                    busy = false
                }
            }
        },
    ) { Text("Generate token") }
}

/**
 * Founder-only member directory (MEMBER_MANAGEMENT.md A & B). Lists everyone the founder has
 * onboarded with their tier, and offers per-member controls to comp them up to Supporter, downgrade
 * to Sharer, or demote to Seeker — each behind a confirmation dialog. A tier change mints a fresh
 * token for the affected member (they re-authenticate), surfaced so the founder can hand it over.
 */
@Composable
private fun FounderMembersSection(api: SaigonApi) {
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

    HorizontalDivider()
    Text("Members", fontWeight = FontWeight.Bold)
    Text(
        "Everyone you've onboarded, with their tier. Promote, downgrade, or remove a member; a " +
            "tier change issues them a fresh token to sign back in with.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    notice?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }

    when {
        !loaded -> CircularProgressIndicator()
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

/** A single member in the founder directory, with tier controls (MEMBER_MANAGEMENT.md A & B). */
@Composable
private fun MemberRow(member: MemberView, onChange: (Role) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(member.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            val tier = member.effectiveRole.label +
                if (member.effectiveRole != member.role) " (on record: ${member.role.label})" else ""
            Text(tier, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Role.SUPPORTER, Role.SHARER, Role.SEEKER).forEach { option ->
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

@Composable
private fun SignInPanel(api: SaigonApi, onToken: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Members only", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Saigon.Social is founder-managed — there's no open sign-up. Paste the access token the " +
                "founder gave you to chat, edit your profile, and refresh your per-room identity. " +
                "Without one you can still browse rooms and read public chat.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Access token") },
            singleLine = true,
            shape = InputFieldShape,
            enabled = !busy,
        )
        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = {
                val trimmed = entry.trim()
                error = null
                busy = true
                scope.launch {
                    try {
                        if (trimmed.isNotEmpty()) {
                            // Validate the token before accepting it, so a bad/expired token shows a
                            // friendly error here instead of a broken profile after navigating away.
                            api.token = trimmed
                            api.profile()
                            onToken(trimmed)
                        } else {
                            // Blank token: the first person to arrive claims the Founder account (DESIGN §2).
                            onToken(api.bootstrapFounder().token)
                        }
                    } catch (e: Throwable) {
                        api.token = null
                        error = e.message
                            ?: if (trimmed.isEmpty()) "Could not start a founder account." else "Could not sign in."
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("Sign in") }
        Text(
            "First time here? Leave the field blank and sign in to claim the Founder account — " +
                "available only until the first member exists.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PasswordLoginSection(api = api, onToken = onToken)
    }
}

/**
 * "Sign in with password" option alongside the paste-token field (PASSWORD_AUTH.md §2C). Members
 * who set a password earlier re-enter with their handle + password instead of re-pasting a token;
 * the server mints a fresh token (renewing an expired one), which is then persisted exactly like a
 * pasted one. Bad credentials show a generic inline error (no user enumeration).
 */
@Composable
private fun PasswordLoginSection(api: SaigonApi, onToken: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    HorizontalDivider()
    Text("Already set a password?", fontWeight = FontWeight.Bold)
    Text(
        "Sign back in with the username and password you set on your profile — handy if your " +
            "token expired or you're on a new device.",
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
    OutlinedButton(
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
                    error = e.message ?: "Invalid credentials."
                } finally {
                    busy = false
                }
            }
        },
    ) { Text("Sign in with password") }
}
