package social.saigon.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// --- Private chat window (DESIGN §7) — ephemeral session, nothing persisted ---

/**
 * A live one-on-one private chat (DESIGN §7) over a real WebSocket, flowing straight into the
 * closing-emoji ritual (DESIGN §8) when the chat ends. Every message and emoji is real peer
 * transport — nothing is fabricated locally, and the closing emojis are revealed only once the
 * peer actually picks one too.
 */
@Composable
internal fun PrivateChatScreen(
    api: SaigonApi,
    roomId: String,
    targetUserId: String,
    peerName: String,
    selfUserId: String?,
    onClose: () -> Unit,
    onEnded: () -> Unit = {},
    onPeerName: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val messages = remember(targetUserId) { mutableStateListOf<PrivateMessage>() }
    var draft by remember(targetUserId) { mutableStateOf("") }
    var peer by remember(targetUserId) { mutableStateOf(peerName) }
    var channel by remember(targetUserId) { mutableStateOf<PrivateChatChannel?>(null) }
    var connected by remember(targetUserId) { mutableStateOf(false) }
    var error by remember(targetUserId) { mutableStateOf<String?>(null) }

    // The closing-emoji ritual (DESIGN §8). `ended` flips once the chat closes; `mine` holds our
    // locked-in pick (kept hidden), and `theirs` arrives only on the server's reveal.
    var ended by remember(targetUserId) { mutableStateOf(false) }
    var mine by remember(targetUserId) { mutableStateOf<String?>(null) }
    var theirs by remember(targetUserId) { mutableStateOf<String?>(null) }

    // Open the live channel, send StartPrivateChat, and stream peer events for this screen's life.
    DisposableEffect(targetUserId) {
        val job = scope.launch {
            runCatching {
                val ch = api.openPrivateChat(roomId, targetUserId)
                channel = ch
                ch.start(
                    PrivateChatChannel.Callbacks(
                        onStarted = { _, name ->
                            connected = true
                            name?.let { peer = it; onPeerName(it) }
                        },
                        onMessage = { msg ->
                            messages.add(
                                PrivateMessage(
                                    author = if (msg.authorId == selfUserId) "You" else peer,
                                    text = msg.text,
                                    mine = msg.authorId == selfUserId,
                                ),
                            )
                        },
                        // A private chat is ephemeral and gone once it ends (DESIGN §7): drop it
                        // from the "Direct chats" list so it no longer lingers on the home screen.
                        onEnded = { ended = true; onEnded() },
                        // The server opens the prompt for both parties; `ended` already drives the UI.
                        onFinalPrompt = { ended = true; onEnded() },
                        onRevealed = { youSent, theySent ->
                            mine = youSent
                            theirs = theySent
                        },
                        onError = { reason -> error = reason },
                    ),
                )
            }.onFailure { error = it.message ?: "Lost connection" }
        }
        onDispose {
            job.cancel()
            scope.launch { runCatching { channel?.close() } }
        }
    }

    if (ended) {
        FinalEmojiScreen(
            peer = peer,
            mine = mine,
            theirs = theirs,
            onPick = { emoji ->
                mine = emoji
                scope.launch { runCatching { channel?.selectFinalEmoji(emoji) } }
            },
            onClose = onClose,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        if (!connected) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingSpinner()
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting to $peer…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (messages.isEmpty()) {
            // No messages yet: offer a few random conversation starters, centered, to break the ice.
            val starters = remember(targetUserId) {
                UiConstants.chatStarters.shuffled().take(3)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    starters.forEach { starter ->
                        SuggestionBubble(
                            text = starter,
                            onClick = {
                                if (connected) {
                                    scope.launch { runCatching { channel?.say(starter) } }
                                }
                            },
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { m ->
                    ChatMessage(
                        authorName = m.author,
                        text = m.text,
                        reversed = m.mine,
                        showAuthor = false,
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
                if (text.isNotEmpty() && connected) {
                    scope.launch { runCatching { channel?.say(text) } }
                    draft = ""
                }
            },
            sendLabel = "Send",
            placeholder = "Message $peer",
            enabled = connected,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { scope.launch { runCatching { channel?.end() } } },
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("End chat") }
    }
}

// --- Final emoji selection + reveal (DESIGN §8) ---

/**
 * The closing-emoji ritual (DESIGN §8). [mine] is our locked-in pick (kept hidden until the
 * reveal); [theirs] is non-null only once the server reports the peer has also chosen, at which
 * point both are shown side-by-side. Nothing is revealed before the peer actually picks.
 */
@Composable
internal fun FinalEmojiScreen(
    peer: String,
    mine: String?,
    theirs: String?,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            mine == null -> {
                Text("One last emoji to sum it up?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(20.dp))
                EmojiGrid(onPick = onPick)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your pick stays hidden until $peer also chooses.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            theirs == null -> {
                // You've chosen; wait for the peer before revealing anything (no placeholder).
                Text("Waiting for $peer to choose…", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "Your pick is locked in. Both emojis are revealed only once $peer picks one too.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onClose) { Text("Leave") }
            }

            else -> {
                Text("How you summed each other up", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    RevealColumn("You sent", mine)
                    RevealColumn("$peer sent", theirs)
                }
                Spacer(Modifier.height(28.dp))
                Button(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SuggestionBubble(text: String, onClick: () -> Unit) {
    val bubbleShape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .clip(bubbleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, bubbleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
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
