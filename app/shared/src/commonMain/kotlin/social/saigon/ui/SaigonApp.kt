package social.saigon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose Web app shell + navigation (DESIGN §12), wired to the live [SaigonApi].
 *
 * Every screen renders data the backend actually persists (ArangoDB) and reads/writes over HTTP +
 * WebSocket: room list → room/public chat (occupant list + per-room identity + thread sheet) →
 * private chat → final-emoji, plus the profile editor. There is no in-memory sample data.
 *
 * The individual screens live in sibling files (RoomScreens, EphemeralScreens, ProfileScreen,
 * FounderScreens, SignInScreen); this file keeps only the navigation shell and the small shared
 * building blocks every screen reuses.
 */
private sealed interface Screen {
    data object RoomList : Screen

    /** A room / public chat. [threadId], when set, opens that message's thread sheet (DESIGN §9). */
    data class Room(val room: RoomSummary, val threadId: String? = null) : Screen
    data class Private(val roomId: String, val targetUserId: String, val peerName: String) : Screen
    data object DirectChats : Screen
    data object Profile : Screen
}

/**
 * The URL path that mirrors [screen] in the browser's address bar (DESIGN §12), or null for screens
 * that aren't deep-linkable (the session-only direct-chats list and the profile/sign-in editor) —
 * those leave the current URL untouched.
 *
 * A private chat (DESIGN §7) is deep-linkable as "/chat/{roomId}/{userId}" so it survives a reload
 * and takes part in Back/Forward navigation. The room it was started from is kept in the path so the
 * peer's room-scoped identity name (DESIGN §5) resolves consistently when the chat is reopened.
 */
private fun screenToPath(screen: Screen): String? = when (screen) {
    Screen.RoomList -> "/"
    is Screen.Room ->
        if (screen.threadId != null) "/room/${screen.room.id}/thread/${screen.threadId}"
        else "/room/${screen.room.id}"

    is Screen.Private -> "/chat/${screen.roomId}/${screen.targetUserId}"
    Screen.DirectChats -> null
    Screen.Profile -> null
}

@Composable
fun SaigonApp() {
    SaigonTheme {
        val api = remember { SaigonApi() }
        // Restore a previously-saved access token so a page reload doesn't sign the user out
        // (DESIGN §2): the token is persisted via TokenStore (localStorage on web).
        var token by remember { mutableStateOf(TokenStore.load()) }
        LaunchedEffect(token) {
            api.token = token
            TokenStore.save(token)
        }

        var screen by remember { mutableStateOf<Screen>(Screen.RoomList) }

        // Mirror the current screen into the browser URL + history so links are shareable and the
        // Back/Forward buttons walk the in-app navigation (DESIGN §12). The supported routes are
        // "/", "/room/{id}", "/room/{id}/thread/{id}", and "/chat/{roomId}/{userId}" (see
        // [screenToPath]).
        val scope = rememberCoroutineScope()
        val router = remember { UrlRouter() }

        // Open one-on-one chats the user can return to (DESIGN §7). Private chats are ephemeral and
        // never persisted, so this list lives only for the current session — it powers the "Direct
        // chats" screen and the in-room "someone started a chat with you" notification. Declared here
        // (ahead of the router wiring) so a deep-linked "/chat/..." path can recover the peer's
        // display name from it when restoring a chat by URL.
        val directChats = remember { mutableStateListOf<DirectChat>() }

        // Resolve a URL path to a screen. Rooms are matched by id from the live room list, since the
        // room screen needs the full RoomSummary; an unknown path falls back to the home screen.
        suspend fun screenForPath(path: String): Screen {
            val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
            // A private chat (DESIGN §7) is "/chat/{roomId}/{userId}". It keeps the room it started
            // from so the peer's room-scoped identity name (DESIGN §5) resolves the same way after a
            // reload; the peer's display name isn't in the URL, so recover it from the open direct-
            // chats list when present and otherwise let the server reveal it once the socket opens.
            if (segments.firstOrNull() == "chat" && segments.size >= 3) {
                val roomId = segments[1]
                val targetUserId = segments[2]
                val peerName = directChats.firstOrNull {
                    it.roomId == roomId && it.targetUserId == targetUserId
                }?.peerName ?: "…"
                return Screen.Private(roomId, targetUserId, peerName)
            }
            if (segments.firstOrNull() != "room" || segments.size < 2) return Screen.RoomList
            val roomId = segments[1]
            val room = runCatching { api.rooms() }.getOrNull()?.firstOrNull { it.id == roomId }
                ?: return Screen.RoomList
            val threadId = if (segments.size >= 4 && segments[2] == "thread") segments[3] else null
            return Screen.Room(room, threadId)
        }

        // On first load, adopt whatever the address bar already points at (a deep link or refresh)
        // and normalise the URL to match. Guarded by `routed` so the screen→URL sync below doesn't
        // clobber the incoming path before it has been applied.
        var routed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            screen = screenForPath(router.current())
            screenToPath(screen)?.let { router.replace(it) }
            routed = true
        }

        // Screen → URL: push a new history entry whenever navigation moves to a different path.
        LaunchedEffect(screen, routed) {
            if (!routed) return@LaunchedEffect
            val path = screenToPath(screen) ?: return@LaunchedEffect
            if (path != router.current()) router.push(path)
        }

        // URL → screen: the Back/Forward buttons (popstate) restore the matching screen.
        DisposableEffect(Unit) {
            val dispose = router.onPopState { path -> scope.launch { screen = screenForPath(path) } }
            onDispose { dispose() }
        }

        // Record a direct chat (newest first, de-duplicated by peer) so it shows in the list.
        fun recordDirectChat(chat: DirectChat) {
            directChats.removeAll { it.targetUserId == chat.targetUserId && it.roomId == chat.roomId }
            directChats.add(0, chat)
        }

        // Record the chat and open it.
        fun openDirectChat(chat: DirectChat) {
            recordDirectChat(chat)
            screen = Screen.Private(chat.roomId, chat.targetUserId, chat.peerName)
        }

        // Drop a direct chat once it has ended — ended private chats are gone for good (DESIGN §7),
        // so they must not keep showing under "Direct chats" on the home screen.
        fun removeDirectChat(roomId: String, targetUserId: String) {
            directChats.removeAll { it.targetUserId == targetUserId && it.roomId == roomId }
        }

        // The most recent "someone started a chat with you" notice, surfaced as an app-level banner
        // so the recipient learns about it from any screen — not only while sitting inside a room.
        var chatNotice by remember { mutableStateOf<DirectChat?>(null) }

        // The signed-in user's profile, used for top-bar identity and room permissions.
        var profile by remember { mutableStateOf<ProfileResponse?>(null) }
        LaunchedEffect(token, screen) {
            if (token != null) {
                try {
                    profile = api.profile()
                } catch (e: SessionExpiredException) {
                    // /profile/me rejected the token (401/400): clear it so the user is signed out.
                    // Nulling `token` also persists TokenStore.save(null), wiping local storage.
                    token = null
                    profile = null
                } catch (e: Throwable) {
                    // A transient profile fetch failure (network blip) must NOT sign the user out:
                    // keep the token and just leave `profile` null until the next successful load.
                    profile = null
                }
            } else {
                profile = null
            }
        }
        val role = profile?.role
        val canCreateRooms = role == Role.SUPPORTER || role == Role.FOUNDER
        val canManageRooms = role == Role.FOUNDER
        // Only members (not read-only Seekers/anonymous viewers) may start a private chat (DESIGN §7).
        val canStartPrivate = role != null && role != Role.SEEKER

        // An always-on notifications socket so the user is told when someone opens a chat with them
        // regardless of the current screen (DESIGN §7). The room socket only delivers this while the
        // user is sitting inside a room, so on the room list / profile / direct-chats screens the
        // notice would otherwise be dropped and the chat would never appear for the recipient.
        val selfId = profile?.id
        LaunchedEffect(token, selfId) {
            if (token == null || selfId == null) return@LaunchedEffect
            val channel = runCatching { api.openNotifications() }.getOrNull() ?: return@LaunchedEffect
            try {
                channel.start { notice ->
                    val chat = DirectChat(notice.roomId.orEmpty(), notice.peerId, notice.peerName ?: "Someone")
                    // Always list the chat so it shows up under "Direct chats" from any screen.
                    recordDirectChat(chat)
                    // Surface a banner too, but don't nag the initiator (already in this chat) or
                    // duplicate the in-room banner that the room screen already shows.
                    val current = screen
                    val alreadyInThisChat = current is Screen.Private && current.targetUserId == notice.peerId
                    val inARoom = current is Screen.Room
                    if (!alreadyInThisChat && !inARoom) chatNotice = chat
                }
            } finally {
                runCatching { channel.close() }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Keep content clear of the status/navigation bars on edge-to-edge Android; a no-op
                // on web/desktop where these vertical insets are zero.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            color = MaterialTheme.colorScheme.background,
        ) {
            // Subtle canvas gradient behind every screen (DESIGN §1 — "subtle gradients"), topped
            // with a faint film-grain texture so the background reads as soft and tactile.
            Column(modifier = Modifier.fillMaxSize().background(appBackgroundBrush()).subtleGrain()) {
                TopBar(
                    title = when (val s = screen) {
                        Screen.RoomList -> "Saigon.Social"
                        is Screen.Room -> "${s.room.emoji} ${s.room.name}"
                        is Screen.Private -> "Chat with ${s.peerName}"
                        Screen.DirectChats -> "Direct chats"
                        Screen.Profile -> if (token != null) "Your profile" else "Sign in"
                    },
                    canGoBack = screen != Screen.RoomList,
                    signedIn = token != null,
                    // Don't show the "Sign in" button while the sign-in page is already open.
                    showSignIn = screen != Screen.Profile,
                    profileName = profile?.displayName,
                    onBack = { screen = Screen.RoomList },
                    onProfile = { screen = Screen.Profile },
                )

                // App-level "someone started a chat with you" notice, shown above any screen so the
                // recipient never misses it (DESIGN §7).
                chatNotice?.let { notice ->
                    MaxWidthBox(modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) {
                        IncomingChatBanner(
                            chat = notice,
                            onOpen = { chatNotice = null; openDirectChat(notice) },
                            onDismiss = { chatNotice = null },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val s = screen) {
                        Screen.RoomList -> MaxWidthContainer {
                            RoomListScreen(
                                api = api,
                                onOpen = { screen = Screen.Room(it) },
                                canCreateRooms = canCreateRooms,
                                canManageRooms = canManageRooms,
                                directChatCount = directChats.size,
                                onOpenDirectChats = { screen = Screen.DirectChats },
                            )
                        }

                        is Screen.Room -> RoomChatScreen(
                            api = api,
                            room = s.room,
                            hasToken = token != null,
                            canStartPrivate = canStartPrivate,
                            selfUserId = profile?.id,
                            onStartPrivate = { occupant ->
                                openDirectChat(DirectChat(s.room.id, occupant.userId, occupant.name))
                            },
                            onRecordDirectChat = { recordDirectChat(it) },
                            // Surface the shared app-level banner (DESIGN §7) when the room socket
                            // reports someone opened a chat with us, instead of a duplicate in-room
                            // banner — but not for a chat we ourselves just opened.
                            onIncomingChat = { chat ->
                                val current = screen
                                val alreadyInThisChat =
                                    current is Screen.Private && current.targetUserId == chat.targetUserId
                                if (!alreadyInThisChat) chatNotice = chat
                            },
                            onOpenDirectChat = { openDirectChat(it) },
                            onOpenRoom = { screen = Screen.Room(it) },
                            onSignIn = { screen = Screen.Profile },
                            initialThreadId = s.threadId,
                            onThreadChange = { threadId ->
                                val current = screen
                                if (current is Screen.Room) screen = current.copy(threadId = threadId)
                            },
                        )

                        is Screen.Private -> PrivateChatScreen(
                            api = api,
                            roomId = s.roomId,
                            targetUserId = s.targetUserId,
                            peerName = s.peerName,
                            selfUserId = profile?.id,
                            onClose = { screen = Screen.RoomList },
                            onEnded = { removeDirectChat(s.roomId, s.targetUserId) },
                            // When restored from a "/chat/..." deep link the peer's display name is
                            // not in the URL, so adopt the name the server reveals: it fixes the
                            // top-bar title and lists the chat under "Direct chats" after a reload.
                            onPeerName = { name ->
                                val current = screen
                                if (current is Screen.Private && current.peerName != name) {
                                    screen = current.copy(peerName = name)
                                    recordDirectChat(
                                        DirectChat(current.roomId, current.targetUserId, name),
                                    )
                                }
                            },
                        )

                        Screen.DirectChats -> MaxWidthContainer {
                            DirectChatsScreen(
                                api = api,
                                chats = directChats,
                                onOpen = { openDirectChat(it) },
                            )
                        }

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
    showSignIn: Boolean,
    profileName: String?,
    onBack: () -> Unit,
    onProfile: () -> Unit,
) {
    // A subtle gradient sheen across the bar (DESIGN §1) instead of one flat fill.
    Box(modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 16.dp)
        .shadow(16.dp, RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp))
        .clip(RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp))
        .background(topBarBrush())
        .innerShadow(                                              // 2. Overlay the inset shadow
            shape = RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp),
            shadow = androidx.compose.ui.graphics.shadow.Shadow(
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
                radius = 12.dp,
                offset = DpOffset(x = 0.dp, y = -2.dp),
                spread = 1.dp
            )
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                // A back-arrow icon only, no "Back" label. Drawn with Canvas (rather than a "\u2190"
                // glyph or a Material icon dependency) so it renders regardless of which fonts the
                // Skiko renderer has loaded.
                IconButton(onClick = onBack) {
                    val arrowColor = MaterialTheme.colorScheme.onPrimary
                    Canvas(modifier = Modifier.width(20.dp).height(20.dp)) {
                        val w = size.width
                        val h = size.height
                        val midY = h / 2f
                        val strokeWidth = h * 0.12f
                        // Horizontal shaft.
                        drawLine(
                            arrowColor,
                            Offset(w * 0.1f, midY),
                            Offset(w * 0.9f, midY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        // Upper and lower barbs of the arrowhead.
                        drawLine(
                            arrowColor,
                            Offset(w * 0.1f, midY),
                            Offset(w * 0.42f, midY - h * 0.32f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            arrowColor,
                            Offset(w * 0.1f, midY),
                            Offset(w * 0.42f, midY + h * 0.32f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            } else {
                // Home icon. Drawn with Canvas (rather than a glyph or a Material icon dependency)
                // so it renders regardless of which fonts the Skiko renderer has loaded. Wrapped in
                // a Box sized to match the back-arrow's IconButton (48.dp default touch target) so
                // the top bar keeps the same height whether or not the back arrow is shown.
                val homeColor = MaterialTheme.colorScheme.onPrimary
                Box(
                    modifier = Modifier.width(48.dp).height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.width(20.dp).height(20.dp)) {
                        val w = size.width
                        val h = size.height
                        val strokeWidth = h * 0.12f
                        // Roof: two lines from the apex down to each eave.
                        drawLine(
                            homeColor,
                            Offset(w * 0.5f, h * 0.1f),
                            Offset(w * 0.1f, h * 0.45f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            homeColor,
                            Offset(w * 0.5f, h * 0.1f),
                            Offset(w * 0.9f, h * 0.45f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        // Walls.
                        drawLine(
                            homeColor,
                            Offset(w * 0.2f, h * 0.4f),
                            Offset(w * 0.2f, h * 0.9f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            homeColor,
                            Offset(w * 0.8f, h * 0.4f),
                            Offset(w * 0.8f, h * 0.9f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        // Floor.
                        drawLine(
                            homeColor,
                            Offset(w * 0.2f, h * 0.9f),
                            Offset(w * 0.8f, h * 0.9f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            if (signedIn) {
                // The user's profile icon doubles as the entry to the profile screen. Clip to a
                // circle *before* the clickable so the ripple stays inside the icon's bounds.
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onProfile),
                ) {
                    ProfileIcon(name = profileName ?: "?", size = 36.dp)
                }
            } else if (showSignIn) {
                TextButton(onClick = onProfile) {
                    Text("Sign in", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
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

@Composable
private fun MaxWidthBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().then(modifier), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = 800.dp)) { content() }
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
internal fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    sendLabel: String,
    placeholder: String,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f)
                    .onPreviewKeyEvent { event ->
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
            IconButton(
                enabled = enabled,
                onClick = onSend,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("➡️") }
        }
        // When disabled (e.g. "Sign in to send"/"Sign in to reply"), the field and button can't be
        // tapped, so overlay a transparent clickable that takes the user to the sign-in screen.
        if (!enabled && onDisabledClick != null) {
            // Clip to the input field's corner radius *before* the clickable so the hover/ripple
            // highlight follows the rounded shape instead of spilling into ugly square corners.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(InputFieldShape)
                    .clickable(onClick = onDisabledClick),
            )
        }
    }
}

/**
 * A plain bottom-sheet drag handle (DESIGN §9): just the visual grab bar, with none of the
 * Material default's hover highlight or "expand/collapse" tooltip semantics.
 */
@Composable
internal fun PlainDragHandle() {
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
