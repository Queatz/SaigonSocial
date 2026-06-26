package social.saigon.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- Private chat window (DESIGN §7) — ephemeral session, nothing persisted ---

@Composable
internal fun PrivateChatScreen(peer: String, onEnd: () -> Unit) {
    val messages = remember { mutableStateListOf<PrivateMessage>() }
    var draft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
internal fun FinalEmojiScreen(peer: String, onClose: () -> Unit) {
    var mine by remember { mutableStateOf<String?>(null) }
    // The peer's pick arrives over the WebSocket once they choose; null until then. Nothing is
    // revealed before that — we wait for the other person to send one (DESIGN §8).
    var theirs by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            mine == null -> {
                Text("One last emoji to sum it up?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(20.dp))
                EmojiGrid(onPick = { mine = it })
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
                    RevealColumn("You sent", mine!!)
                    RevealColumn("$peer sent", theirs!!)
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
