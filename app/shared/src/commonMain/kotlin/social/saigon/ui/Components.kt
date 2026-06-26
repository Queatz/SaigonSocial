package social.saigon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
