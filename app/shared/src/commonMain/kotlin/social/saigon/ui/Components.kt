package social.saigon.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * A small circular profile icon (DESIGN §5). Used by the room feed, thread replies, the occupant
 * list, the founder member directory, and the identity bar so every person is shown with a
 * consistent avatar rather than a raw id.
 *
 * When the member has a profile photo ([photoUrl], issue: "Profile and community profile photos") it is
 * shown cropped to the circle; the [name]'s first letter renders underneath as the fallback while
 * the photo loads (or when there is none).
 */
@Composable
internal fun ProfileIcon(name: String, size: Dp = 32.dp, photoUrl: String? = null) {
    Box(
        modifier = Modifier
            .width(size)
            .height(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifBlank { "?" },
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value / 2.2f).sp,
        )
        if (!photoUrl.isNullOrBlank()) {
            UrlImage(url = photoUrl, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.matchParentSize().innerGlow(CircleShape))
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
 * [authorName] drives both the avatar and the (optional) name label; [authorPhotoUrl] shows the
 * author's profile photo on the avatar when they have one; [timestamp] adds a "time ago" label
 * when known; [onClick] makes the bubble tappable (e.g. to open a thread); [onAvatarClick] makes
 * the author's [ProfileIcon] tappable (e.g. to open their detail sheet); and [footer] adds extra
 * content beneath the text inside the bubble (e.g. a "↳ N replies" indicator).
 */
@Composable
internal fun ChatMessage(
    authorName: String,
    text: String,
    modifier: Modifier = Modifier,
    authorPhotoUrl: String? = null,
    timestamp: Long? = null,
    reversed: Boolean = false,
    showAuthor: Boolean = true,
    /**
     * When true the message has been recalled (DESIGN §6, §9): its content is hidden and a
     * "message recalled" notice with an icon is shown in its place instead of [text].
     */
    recalled: Boolean = false,
    onClick: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
    /** When set, shown in the header as "in <community name>" (home-screen latest threads). */
    inCommunity: String? = null,
    fill: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (reversed) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!reversed) {
            Box(
                modifier = if (onAvatarClick != null) {
                    Modifier.clip(CircleShape).clickable(onClick = onAvatarClick)
                } else Modifier,
            ) { ProfileIcon(name = authorName, size = 32.dp, photoUrl = authorPhotoUrl) }
            Spacer(Modifier.width(8.dp))
        }
        val bubbleShape = RoundedCornerShape(12.dp)
        val bubbleColor = if (reversed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            modifier = Modifier
                // Take only the width left over after the fixed-size avatar + spacer, so a long
                // message shrinks its own bubble instead of squishing the profile icon beside it.
                .weight(1f, fill = fill)
                .widthIn(max = 480.dp)
                .softShadow(bubbleShape)
                .then(
                    if (onClick != null) Modifier.clip(bubbleShape).clickable(onClick = onClick) else Modifier
                ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                val ago = timestamp?.let { timeAgo(it) }.orEmpty()
                val community = inCommunity?.trim().orEmpty()
                if (showAuthor || ago.isNotEmpty() || community.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (showAuthor) {
                            Text(authorName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        if (ago.isNotEmpty()) {
                            Text(ago, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (community.isNotEmpty()) {
                            Text(
                                "in $community",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (recalled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RecalledIcon()
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "This message was recalled",
                            fontSize = 14.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(text, fontSize = 15.sp)
                }
                footer?.invoke()
            }
        }
        if (reversed) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = if (onAvatarClick != null) {
                    Modifier.clip(CircleShape).clickable(onClick = onAvatarClick)
                } else Modifier,
            ) { ProfileIcon(name = authorName, size = 32.dp, photoUrl = authorPhotoUrl) }
        }
    }
}

/**
 * A small "recalled" badge — a circle with a diagonal slash (the universal "prohibited"/withdrawn
 * mark) — shown next to the "This message was recalled" notice (DESIGN §6, §9). Drawn with Canvas
 * rather than a glyph so the Skiko renderer never falls back to a "tofu" box, matching how the rest
 * of the app draws its small icons.
 */
@Composable
internal fun RecalledIcon(size: Dp = 14.dp) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.width(size).height(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.12f)
        val inset = stroke.width
        drawCircle(
            color = color,
            radius = this.size.minDimension / 2f - inset,
            style = stroke,
        )
        // The slash, drawn from the lower-left to the upper-right inside the circle.
        val r = this.size.minDimension / 2f - inset
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val d = r * 0.70710677f // r / sqrt(2): the chord that fits within the circle.
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(cx - d, cy + d),
            end = androidx.compose.ui.geometry.Offset(cx + d, cy - d),
            strokeWidth = stroke.width,
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

/**
 * An [OutlinedButton] that dims and shrinks slightly when [selected] is false, animating back to
 * full size/opacity once selected (DESIGN §4, §5). This is the shared toggle look for the
 * multi-select "Here for" and "Languages" buttons — used on the main profile screen and reused
 * as-is for the same buttons in the per-room profile editor, so both stay visually in sync.
 */
@Composable
internal fun DimmableToggleButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 1f else 0.95f)
    val alpha by animateFloatAsState(if (selected) 1f else 0.6f)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .alpha(alpha),
        content = content,
    )
}

/**
 * A landscape "large chip" card for a single [Skill]: emoji, name, optional description, and a
 * 0–5 level meter. Used on the occupant detail sheet (read-only) and the profile / room-profile
 * editors (tappable).
 */
@Composable
internal fun SkillCard(
    skill: Skill,
    modifier: Modifier = Modifier,
    full: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(skill.emoji.ifBlank { "✨" }, fontSize = 22.sp)
            Column(modifier = Modifier.widthIn(max = 160.dp)) {
                Text(
                    skill.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (skill.description.isNotBlank() && full) {
                    Text(
                        skill.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SkillLevelDots(level = skill.level)
                    if (full) {
                        Text(
                            Skill.levelName(skill.level),
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
}

/** Five dots showing a skill's self-rated level (filled up to [level], empty after). */
@Composable
internal fun SkillLevelDots(level: Int, modifier: Modifier = Modifier) {
    val clamped = level.coerceIn(Skill.MIN_LEVEL, Skill.MAX_LEVEL)
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(Skill.MAX_LEVEL) { index ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (index < clamped) filled else empty),
            )
        }
    }
}

/**
 * A wrapping row of [SkillCard]s. When [onSkillClick] is set, tapping a card invokes the handler
 * (edit surfaces open the editor; the occupant sheet opens a read-only detail dialog).
 */
@Composable
internal fun SkillsRow(
    skills: List<Skill>,
    modifier: Modifier = Modifier,
    full: Boolean = true,
    onSkillClick: ((Int) -> Unit)? = null,
) {
    if (skills.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        skills.forEachIndexed { index, skill ->
            SkillCard(
                skill = skill,
                full = full,
                onClick = onSkillClick?.let { handler -> { handler(index) } },
            )
        }
    }
}

/**
 * Read-only dialog showing the full details of a single [Skill]: emoji, name, description, and
 * named level with dots. Opened from [SkillsRow] on the occupant detail sheet.
 */
@Composable
internal fun SkillDetailDialog(
    skill: Skill,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(skill.emoji.ifBlank { "✨" }, fontSize = 28.sp)
                Text(skill.name.ifBlank { "Skill" }, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (skill.description.isNotBlank()) {
                    Text(skill.description, fontSize = 14.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Level", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SkillLevelDots(level = skill.level)
                        Text(
                            "${skill.level} · ${Skill.levelName(skill.level)}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * Add / edit a skill dialog. When [initial] is non-null the form is pre-filled for editing and a
 * Delete button is offered via [onDelete]. Level is chosen with named [DimmableToggleButton]s.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillEditorDialog(
    initial: Skill? = null,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var emoji by remember(initial) { mutableStateOf(initial?.emoji.orEmpty()) }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var level by remember(initial) { mutableStateOf(initial?.level ?: 3) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val trimmedName = name.trim()
    val trimmedEmoji = emoji.trim()
    val trimmedDescription = description.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add a skill" else "Edit skill") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Pick an emoji, a short name, an optional description, and your level.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Emoji") },
                    placeholder = { Text("🎸") },
                    singleLine = true,
                    shape = InputFieldShape,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Skill name") },
                    placeholder = { Text("Guitar") },
                    singleLine = true,
                    shape = InputFieldShape,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(Skill.MAX_DESCRIPTION_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description") },
                    placeholder = { Text("Open mics & jazz standards") },
                    singleLine = true,
                    shape = InputFieldShape,
                )
                Text("Level", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Skill.LEVEL_NAMES.forEachIndexed { option, label ->
                        val selected = option == level
                        DimmableToggleButton(
                            selected = selected,
                            onClick = { level = option },
                        ) {
                            Text(
                                "$option · $label",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkillLevelDots(level = level)
                    Text(
                        Skill.levelName(level),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmedName.isNotEmpty(),
                onClick = {
                    onSave(
                        Skill(
                            emoji = trimmedEmoji.ifBlank { "✨" },
                            name = trimmedName,
                            description = trimmedDescription,
                            level = level.coerceIn(Skill.MIN_LEVEL, Skill.MAX_LEVEL),
                        ),
                    )
                },
            ) { Text(if (initial == null) "Add" else "Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (confirmingDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this skill?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}
