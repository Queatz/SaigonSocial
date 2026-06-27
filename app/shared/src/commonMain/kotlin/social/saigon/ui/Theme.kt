package social.saigon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Saigon.Social visual identity (DESIGN §1 — "A digital room for the city").
 *
 * Reimagined professional palette: deep forest green primary paired with earthy browns and muted
 * blues for a grounded, trustworthy feel. High contrast neutrals over warm off-white (light) and
 * deep charcoal (dark) ensure readability in professional settings. Accents provide subtle warmth
 * without distraction.
 */
private val SaigonLightColors = lightColorScheme(
    primary = Color(0xFF1a6749),            // richer, darker forest green for light mode
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF319f6f),
    onPrimaryContainer = Color(0xFF052014),

    secondary = Color(0xFF6B4E31),          // earthy brown
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4B89E),
    onSecondaryContainer = Color(0xFF2C2118),

    tertiary = Color(0xFF3D5A80),           // earthy blue
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9DB4D9),
    onTertiaryContainer = Color(0xFF1A2A40),

    background = Color(0xFFF8F6F0),         // warm off-white for professional contrast
    onBackground = Color(0xFF1C1B17),
    // Cards / surfaces use clean neutrals for separation and readability.
    surface = Color(0xFFFCFBF7),
    onSurface = Color(0xFF1C1B17),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF7F1),
    surfaceContainer = Color(0xFFF3F1EC),
    surfaceContainerHigh = Color(0xFFEDEBE6),
    surfaceContainerHighest = Color(0xFFE7E6E2),
    surfaceVariant = Color(0xFFE6E3D7),     // calm side-panel / bubble fill
    onSurfaceVariant = Color(0xFF4B4538),

    outline = Color(0xFF6A6354),            // brighter so input borders read clearly
    outlineVariant = Color(0xFFC6BDA9),     // soft hairline borders
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val SaigonDarkColors = darkColorScheme(
    primary = Color(0xFF1a6749),            // richer, lighter forest green for dark mode
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF0D2818),
    onPrimaryContainer = Color(0xFFA8E0C0),

    secondary = Color(0xFFD4B89E),          // earthy brown
    onSecondary = Color(0xFF2C2118),
    secondaryContainer = Color(0xFF6B4E31),
    onSecondaryContainer = Color(0xFFF3E6D9),

    tertiary = Color(0xFF9DB4D9),           // earthy blue
    onTertiary = Color(0xFF1A2A40),
    tertiaryContainer = Color(0xFF3D5A80),
    onTertiaryContainer = Color(0xFFC5D4EB),

    background = Color(0xFF1A1C1F),         // deep charcoal for professional contrast
    onBackground = Color(0xFFE8E6E1),
    onSurface = Color(0xFFE8E6E1),
    // Cards / containers use cooler neutrals to separate from background while maintaining harmony.
    surface = Color(0xFF25282C),
    surfaceContainerLowest = Color(0xFF141618),
    surfaceContainerLow = Color(0xFF1F2226),
    surfaceContainer = Color(0xFF25282C),
    surfaceContainerHigh = Color(0xFF2E3136),
    surfaceContainerHighest = Color(0xFF373A40),
    surfaceVariant = Color(0xFF2E3136),     // calm side-panel / bubble fill
    onSurfaceVariant = Color(0xFFCBC8C2),

    outline = Color(0xFF8A857A),            // brighter so input borders read clearly
    outlineVariant = Color(0xFF44434D),     // soft hairline borders
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val SaigonShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Shared corner radius for every text input across the app (DESIGN §1). */
val InputFieldShape = SaigonShapes.medium

/**
 * Warm earthy gold accent — complements the forest green primary in top-bar gradients. Provides
 * subtle professional warmth without high saturation.
 */
private val SaigonHoneyLight = Color(0xFFD4A574)
private val SaigonHoneyDark = Color(0xFFE8C89A)

/**
 * Muted forest accent — used for interactive text (room titles, reply counts, links). Keeps a
 * natural, professional tone consistent with the new palette.
 */
private val SaigonAccentLight = Color(0xFF2D5A3D)
private val SaigonAccentDark = Color(0xFF8FB89A)

/** The muted forest accent for the active light/dark scheme. */
val accentColor: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SaigonAccentDark else SaigonAccentLight

/**
 * Container colour for the right-sidebar member cards (DESIGN §6). Deliberately a step lighter than
 * the default post-card surface so the people panel separates from the feed instead of merging into
 * one block of identical cards (feedback §6).
 */
val sidebarCardColor: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) Color(0xFF3E3D49) else Color(0xFFFFFFFF)

@Composable
fun SaigonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SaigonDarkColors else SaigonLightColors,
        shapes = SaigonShapes,
        content = content,
    )
}

/**
 * A full-screen fill painted with the theme background colour, themed to follow the OS light/dark
 * scheme. Used as the placeholder for the brief window after the Compose canvas mounts but before
 * the real UI is composed (e.g. while web fallback fonts are still loading): without it the canvas
 * would paint its default opaque white and flash white over the dark loader in dark mode.
 */
@Composable
fun ThemedLoadingBackground() {
    SaigonTheme {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

/**
 * A subtle top-to-bottom canvas gradient (DESIGN §1 — "subtle gradients"). Eases the flat
 * background into the slightly cooler surface-variant tone so the app feels soft and layered rather
 * than a single block of colour.
 */
@Composable
@ReadOnlyComposable
fun appBackgroundBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ),
)

/**
 * A sunrise gradient for the top app bar (DESIGN §1): the deep river-blue primary easing through the
 * mauve-purple (lavender) tertiary into a warm honey/peach, giving the bar a memorable sunrise sheen
 * instead of one flat fill. Kept a touch more saturated so it stays vivid.
 */
@Composable
@ReadOnlyComposable
fun topBarBrush(): Brush {
    return Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

/**
 * A soft hairline border (DESIGN §1 — "soft borders"): a thin, low-contrast outline that gives
 * cards and panels a gentle edge without the hard line of a full-strength stroke.
 */
@Composable
fun Modifier.softBorder(shape: Shape = SaigonShapes.medium): Modifier =
    this.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)

/**
 * A very subtle, static film-grain overlay (DESIGN §1). Scatters a fixed set of tiny, faint
 * speckles across the surface so the flat background gradient gains a little texture and feels less
 * digitally smooth. The grain is deterministic (a fixed seed) so it stays steady across recompositions
 * rather than shimmering, and the dots are drawn at very low alpha so they only register as a soft tint.
 */
@Composable
fun Modifier.subtleGrain(
    grainColor: Color = MaterialTheme.colorScheme.onBackground,
    density: Int = 1200,
    alpha: Float = 0.04f,
): Modifier = this.drawWithCache {
    // Pin the speckle layout to the current size so the grain only recomputes on resize, not on
    // every redraw — keeping it static and cheap.
    val random = Random(42)
    val dotRadius = 1f + random.nextFloat() * 1.6f
    val points = List(density) {
        Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
    }
    onDrawWithContent {
        drawContent()
        points.forEach { point ->
            drawCircle(
                color = grainColor,
                radius = dotRadius,
                center = point,
                alpha = alpha,
            )
        }
    }
}
