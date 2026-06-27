package social.saigon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A short, human-friendly "time ago" label for a message/reply timestamp ([epochMillis], DESIGN §9).
 * Shown next to messages and thread replies so people can tell how fresh a conversation is. Returns
 * an empty string for missing/zero timestamps so callers can simply omit the label.
 */
@OptIn(ExperimentalTime::class)
internal fun timeAgo(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val now = Clock.System.now().toEpochMilliseconds()
    val diffSeconds = (now - epochMillis) / 1000
    return when {
        diffSeconds < 5 -> "just now"
        diffSeconds < 60 -> "${diffSeconds}s ago"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h ago"
        diffSeconds < 604_800 -> "${diffSeconds / 86_400}d ago"
        else -> "${diffSeconds / 604_800}w ago"
    }
}

/**
 * A small circular profile icon derived from a display [name]'s first letter (DESIGN §5). Used by
 * the room feed, thread replies, the occupant list, the founder member directory, and the identity
 * bar so every person is shown with a consistent avatar rather than a raw id.
 */
@Composable
internal fun ProfileIcon(name: String, size: Dp = 32.dp) {
    Box(
        modifier = Modifier
            .width(size)
            .height(size)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifBlank { "?" },
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value / 2.2f).sp,
        )
    }
}

/**
 * The shared chat message component used across every conversation surface: the room feed, thread
 * replies, and direct chats (DESIGN §6, §7, §9).
 *
 * The author's [ProfileIcon] sits *outside* the message bubble, and the bubble only grows to fit its
 * content (capped well short of full width) rather than stretching edge-to-edge. Setting [reversed]
 * mirrors the whole row — avatar on the right, bubble tinted as "mine" and right-aligned — so the
 * same component renders both incoming and outgoing messages in a one-on-one chat.
 *
 * [authorName] drives both the avatar and the (optional) name label; [timestamp] adds a "time ago"
 * label when known; [onClick] makes the bubble tappable (e.g. to open a thread); and [footer] adds
 * extra content beneath the text inside the bubble (e.g. a "↳ N replies" indicator).
 */
@Composable
internal fun ChatMessage(
    authorName: String,
    text: String,
    modifier: Modifier = Modifier,
    timestamp: Long? = null,
    reversed: Boolean = false,
    showAuthor: Boolean = true,
    onClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (reversed) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!reversed) {
            ProfileIcon(name = authorName, size = 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        val bubbleShape = RoundedCornerShape(12.dp)
        val bubbleColor = if (reversed) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .then(if (onClick != null) Modifier.clip(bubbleShape).clickable(onClick = onClick) else Modifier),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                val ago = timestamp?.let { timeAgo(it) }.orEmpty()
                if (showAuthor || ago.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showAuthor) {
                            Text(authorName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        if (ago.isNotEmpty()) {
                            Text(ago, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(text, fontSize = 15.sp)
                footer?.invoke()
            }
        }
        if (reversed) {
            Spacer(Modifier.width(8.dp))
            ProfileIcon(name = authorName, size = 32.dp)
        }
    }
}

/**
 * A collapsible "accordion" section: a tappable header that shows/hides [content]. Used to tuck
 * away the less-frequent profile tools (changing a password, creating an invite code) so the
 * profile screen stays uncluttered.
 */
@Composable
internal fun Accordion(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // Expand/collapse chevron drawn with Canvas rather than a "▲"/"▼" glyph, which the
                // Skiko renderer has no font for and would otherwise show as a "tofu" box.
                val chevronColor = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(modifier = Modifier.width(12.dp).height(12.dp)) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        if (expanded) {
                            // Upward triangle.
                            moveTo(w * 0.5f, h * 0.25f)
                            lineTo(w * 0.85f, h * 0.7f)
                            lineTo(w * 0.15f, h * 0.7f)
                        } else {
                            // Downward triangle.
                            moveTo(w * 0.15f, h * 0.3f)
                            lineTo(w * 0.85f, h * 0.3f)
                            lineTo(w * 0.5f, h * 0.75f)
                        }
                        close()
                    }
                    drawPath(path, chevronColor)
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}
