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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    data class Room(val room: RoomSummary) : Screen
    data class Private(val roomId: String, val targetUserId: String, val peerName: String) : Screen
    data object DirectChats : Screen
    data object Profile : Screen
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

        // Open one-on-one chats the user can return to (DESIGN §7). Private chats are ephemeral and
        // never persisted, so this list lives only for the current session — it powers the "Direct
        // chats" screen and the in-room "someone started a chat with you" notification.
        val directChats = remember { mutableStateListOf<DirectChat>() }

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
            // Subtle canvas gradient behind every screen (DESIGN §1 — "subtle gradients").
            Column(modifier = Modifier.fillMaxSize().background(appBackgroundBrush())) {
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
                    MaxWidthContainer {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            IncomingChatBanner(
                                chat = notice,
                                onOpen = { chatNotice = null; openDirectChat(notice) },
                                onDismiss = { chatNotice = null },
                            )
                        }
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
                            onOpenDirectChat = { openDirectChat(it) },
                            onOpenRoom = { screen = Screen.Room(it) },
                        )
                        is Screen.Private -> PrivateChatScreen(
                            api = api,
                            roomId = s.roomId,
                            targetUserId = s.targetUserId,
                            peerName = s.peerName,
                            selfUserId = profile?.id,
                            onClose = { screen = Screen.RoomList },
                            onEnded = { removeDirectChat(s.roomId, s.targetUserId) },
                        )
                        Screen.DirectChats -> MaxWidthContainer {
                            DirectChatsScreen(
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
    Box(modifier = Modifier.fillMaxWidth().background(topBarBrush())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                        drawLine(arrowColor, Offset(w * 0.1f, midY), Offset(w * 0.9f, midY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                        // Upper and lower barbs of the arrowhead.
                        drawLine(arrowColor, Offset(w * 0.1f, midY), Offset(w * 0.42f, midY - h * 0.32f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                        drawLine(arrowColor, Offset(w * 0.1f, midY), Offset(w * 0.42f, midY + h * 0.32f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
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
                        drawLine(homeColor, Offset(w * 0.5f, h * 0.1f), Offset(w * 0.1f, h * 0.45f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                        drawLine(homeColor, Offset(w * 0.5f, h * 0.1f), Offset(w * 0.9f, h * 0.45f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                        // Walls.
                        drawLine(homeColor, Offset(w * 0.2f, h * 0.4f), Offset(w * 0.2f, h * 0.9f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                        drawLine(homeColor, Offset(w * 0.8f, h * 0.4f), Offset(w * 0.8f, h * 0.9f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                        // Floor.
                        drawLine(homeColor, Offset(w * 0.2f, h * 0.9f), Offset(w * 0.8f, h * 0.9f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
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
        // Match the input field's height exactly so the action button never towers over it.
        Button(
            enabled = enabled,
            onClick = onSend,
            modifier = Modifier.height(56.dp),
        ) { Text(sendLabel) }
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
