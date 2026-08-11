package social.saigon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap

// --- Profile photo display (issue: "Profile and community profile photos") ---

/**
 * Fetches and decodes profile photos from the content server, with a session-wide in-memory cache
 * so the same photo (avatars repeat a lot in a chat feed) is downloaded and decoded only once.
 */
internal object PhotoLoader {
    private val client = HttpClient()
    private val cache = mutableMapOf<String, ImageBitmap>()

    /** The already-decoded bitmap for [url], if it has been loaded before. */
    fun cached(url: String): ImageBitmap? = cache[url]

    /** Load and decode [url], or null when the fetch/decode fails (the caller keeps its fallback). */
    suspend fun load(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        return runCatching {
            val bytes: ByteArray = client.get(url).body()
            bytes.decodeToImageBitmap()
        }.getOrNull()?.also { cache[url] = it }
    }

    /** Decode raw picked-file [bytes] for a local preview, or null when they aren't a valid image. */
    fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { bytes.decodeToImageBitmap() }.getOrNull()
}

/**
 * An image loaded from [url] (via [PhotoLoader]), cropped to fill [modifier]'s bounds. While the
 * photo is loading — or if it fails — [fallback] renders in its place, so callers can keep e.g.
 * their letter avatar until the photo arrives.
 */
@Composable
internal fun UrlImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: (@Composable () -> Unit)? = null,
) {
    var bitmap by remember(url) { mutableStateOf(PhotoLoader.cached(url)) }
    LaunchedEffect(url) { if (bitmap == null) bitmap = PhotoLoader.load(url) }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { fallback?.invoke() }
    }
}

/**
 * A large 16:9 landscape photo scroller: swipe/drag between [photos], with left/right arrow
 * controls and a centered bottom dot line that reflects the current index and total count.
 * Hidden when there are no photos. Used at the top of the service detail screen.
 */
@Composable
internal fun LandscapePhotoScroller(
    photos: List<ProfilePhoto>,
    modifier: Modifier = Modifier,
) {
    if (photos.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { photos.size })
    val scope = rememberCoroutineScope()
    val canGoBack = pagerState.currentPage > 0
    val canGoForward = pagerState.currentPage < photos.lastIndex

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            UrlImage(
                url = photos[page].url,
                modifier = Modifier.fillMaxSize(),
                fallback = { LoadingSpinner() },
            )
        }

        // Soft bottom scrim so the dots stay readable over busy photos.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                    ),
                ),
        )

        if (photos.size > 1) {
            PhotoScrollerArrow(
                facingLeft = true,
                enabled = canGoBack,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
            )
            PhotoScrollerArrow(
                facingLeft = false,
                enabled = canGoForward,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
            )
        }

        // Dot indicator line across the center bottom (current + total via filled/empty dots).
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            photos.forEachIndexed { index, _ ->
                val selected = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) Color.White
                            else Color.White.copy(alpha = 0.45f),
                        ),
                )
            }
        }
    }
}

/** Circular left/right chevron control for [LandscapePhotoScroller], drawn with Canvas (no glyph fonts). */
@Composable
private fun PhotoScrollerArrow(
    facingLeft: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (enabled) 0.35f else 0.15f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val chevronAlpha = if (enabled) 1f else 0.4f
        Canvas(modifier = Modifier.size(14.dp)) {
            val stroke = Stroke(width = size.minDimension * 0.18f)
            val insetX = size.width * 0.28f
            val midY = size.height / 2f
            val tipX = if (facingLeft) insetX else size.width - insetX
            val baseX = if (facingLeft) size.width - insetX else insetX
            val path = Path().apply {
                moveTo(baseX, size.height * 0.18f)
                lineTo(tipX, midY)
                lineTo(baseX, size.height * 0.82f)
            }
            drawPath(path, color = Color.White.copy(alpha = chevronAlpha), style = stroke)
        }
    }
}

/**
 * A profile-photo card: a vertical 9:16 photo on top with the user's caption underneath. Used for
 * the "previous profile photos" on the profile screen (clickable to delete) and the photo cards on
 * the room-profile sheet.
 */
@Composable
internal fun PhotoCard(
    photo: ProfilePhoto,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .width(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box {
            Column {
                UrlImage(
                    url = photo.url,
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f),
                    fallback = { LoadingSpinner() },
                )
                if (photo.message.isNotBlank()) {
                    Text(
                        photo.message,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            Box(Modifier.matchParentSize().innerGlow(RoundedCornerShape(12.dp)))
        }
    }
}

/**
 * A wrapping flex row of [PhotoCard]s, one per photo (issue: "vertical cards in a wrapping flex row").
 * When [onPhotoClick] is given (e.g. the profile/room edit forms that delete on tap) it drives the
 * click; otherwise tapping a card opens a read-only [PhotoDisplayDialog] showing the full photo and
 * description, so any user — including the current one — can view any photo card (issue: "open a
 * display-only dialog ... just showing the photo and full description").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoCardsRow(
    title: String = "Photo",
    photos: List<ProfilePhoto>,
    modifier: Modifier = Modifier,
    onPhotoClick: ((ProfilePhoto) -> Unit)? = null,
) {
    var viewing by remember { mutableStateOf<ProfilePhoto?>(null) }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        photos.forEach { photo ->
            PhotoCard(
                photo = photo,
                // With no caller-supplied handler, tapping a card just displays it.
                onClick = {
                    val handler = onPhotoClick
                    if (handler != null) handler(photo) else viewing = photo
                },
            )
        }
    }
    viewing?.let { photo ->
        PhotoDisplayDialog(title = title, photo = photo, onDismiss = { viewing = null })
    }
}

/**
 * A read-only counterpart to [PhotoUploadDialog] (issue: "a display-only dialog similar in nature to
 * the upload photo dialog but just showing the photo and full description"): a rounded-rectangle
 * vertical 9:16 area with the photo, its full (un-truncated) caption below, and a single Close button.
 */
@Composable
internal fun PhotoDisplayDialog(
    title: String = "Photo",
    photo: ProfilePhoto,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val areaShape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .align(Alignment.CenterHorizontally)
                        .aspectRatio(9f / 16f)
                        .clip(areaShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    UrlImage(
                        url = photo.url,
                        modifier = Modifier.fillMaxSize(),
                        fallback = { LoadingSpinner() },
                    )
                    Box(Modifier.matchParentSize().innerGlow(RoundedCornerShape(12.dp)))
                }
                if (photo.message.isNotBlank()) {
                    Text(photo.message, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** An image the user picked for upload: its raw bytes, mime type, and decoded local preview. */
internal class PickedPhoto(val bytes: ByteArray, val mimeType: String, val preview: ImageBitmap)

/**
 * A reusable photo-upload dialog (issue: "Profile and community profile photos"): a rounded-rectangle
 * area to drop or click to choose a photo — previewed once picked but still clickable to swap —
 * plus a caption box. On confirm it uploads any newly-picked bytes to the content server and hands
 * the resulting [ProfilePhoto] (URL + caption) to [onSaved]; with no new photo picked it reuses
 * [currentPhoto]'s URL so a caption-only edit still round-trips. [onSaved] is where the caller
 * persists the result (e.g. set the main profile photo, or add it to a room's photo list), and any
 * error it throws is shown inline. Used by both the main profile screen and the room profile form.
 */
@Composable
internal fun PhotoUploadDialog(
    api: SaigonApi,
    title: String,
    currentPhoto: ProfilePhoto?,
    onDismiss: () -> Unit,
    onSaved: suspend (ProfilePhoto) -> Unit,
    confirmLabel: String = "Save photo",
) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<PickedPhoto?>(null) }
    var message by remember { mutableStateOf(currentPhoto?.message.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dragActive by remember { mutableStateOf(false) }

    val onPicked: (ByteArray, String) -> Unit = { bytes, mimeType ->
        val preview = PhotoLoader.decode(bytes)
        if (preview != null) {
            picked = PickedPhoto(bytes, mimeType, preview)
            error = null
        } else {
            error = "That file doesn't look like an image."
        }
    }
    val launchPicker = rememberPhotoPicker(onPicked = onPicked)
    // On web, dropping an image anywhere on the page while this dialog is open counts as a pick.
    PhotoDropEffect(
        enabled = !busy,
        onDropped = onPicked,
        onDragActiveChange = { dragActive = it },
    )

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The rounded-rectangle drop/click upload area. It shows the picked photo (or the
                // current one) but can still be clicked to choose a different photo. While a file
                // is dragged over the page the border/background highlight to show it's a target.
                val areaShape = RoundedCornerShape(16.dp)
                val areaBorder = if (dragActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                val areaBackground = if (dragActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(areaShape)
                        .background(areaBackground)
                        .border(if (dragActive) 3.dp else 2.dp, areaBorder, areaShape)
                        .clickable(enabled = !busy) { launchPicker() },
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = picked?.preview
                    when {
                        dragActive -> Text(
                            "Drop photo to upload",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                        preview != null -> Image(
                            bitmap = preview,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        currentPhoto != null -> UrlImage(
                            url = currentPhoto.url,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Text(
                            "Drop a photo here, or click to choose one",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Say something about this photo") },
                    shape = InputFieldShape,
                    enabled = !busy,
                )
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                // Enabled once there's something to save: a new photo, or a caption change to the
                // current one.
                enabled = !busy && (picked != null || (currentPhoto != null && message.trim() != currentPhoto.message)),
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            val newPhoto = picked
                            val url = if (newPhoto != null) {
                                api.uploadContent(newPhoto.bytes, newPhoto.mimeType)
                            } else {
                                currentPhoto!!.url
                            }
                            onSaved(ProfilePhoto(url = url, message = message.trim()))
                        } catch (e: Throwable) {
                            error = e.message ?: "Could not upload your photo."
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Uploading…" else confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}
