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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Saigon.Social visual identity (DESIGN §1 — "A digital room for the city").
 *
 * A warm, natural palette: a deep river-blue primary leans on earthy brown and soft mauve-purple
 * accents, lifted by a sunlit honey-yellow over a warm sandstone canvas. The mood stays unhurried
 * and human — the opposite of a loud social network — while feeling grounded and organic.
 *
 * The dark scheme keeps the same character — river blue, warm brown, mauve purple and honey yellow
 * glowing over a deep, low-contrast walnut-black canvas — so the app feels at home in either light
 * or dark mode.
 */
private val SaigonLightColors = lightColorScheme(
    primary = Color(0xFF1F5C82),            // deep river blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFAFD4ED),
    onPrimaryContainer = Color(0xFF03192A),

    secondary = Color(0xFF7A5638),          // earthy brown
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAD3BD),
    onSecondaryContainer = Color(0xFF2A1A0B),

    tertiary = Color(0xFF6E5295),           // soft mauve purple
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE3D4F2),
    onTertiaryContainer = Color(0xFF22103B),

    background = Color(0xFFF4EEE2),         // warm sandstone, a touch deeper for card contrast
    onBackground = Color(0xFF1C1810),
    // Cards / surfaces lean a hair cooler than the warm canvas so the eye can separate them.
    surface = Color(0xFFFCFBF7),
    onSurface = Color(0xFF1C1810),
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
    primary = Color(0xFF8DC4EA),            // river blue, lifted but not neon for dark surfaces
    onPrimary = Color(0xFF042E47),
    primaryContainer = Color(0xFF124264),
    onPrimaryContainer = Color(0xFFC3E1F6),

    secondary = Color(0xFFDBB893),          // warm brown
    onSecondary = Color(0xFF42290F),
    secondaryContainer = Color(0xFF5C3E22),
    onSecondaryContainer = Color(0xFFF3DEC6),

    tertiary = Color(0xFFC7B0E6),           // soft mauve purple
    onTertiary = Color(0xFF382150),
    tertiaryContainer = Color(0xFF503869),
    onTertiaryContainer = Color(0xFFE9DBF8),

    background = Color(0xFF12100A),         // deep, slightly darker walnut canvas for more contrast
    onBackground = Color(0xFFE9E1D2),
    onSurface = Color(0xFFE9E1D2),
    // Cards / containers step away from the warm brown into a cooler charcoal, both to lift them
    // off the olive canvas and to give the all-warm UI a calm cool note to rest against.
    surface = Color(0xFF1B1A20),
    surfaceContainerLowest = Color(0xFF131217),
    surfaceContainerLow = Color(0xFF1E1D24),
    surfaceContainer = Color(0xFF24232B),
    surfaceContainerHigh = Color(0xFF2C2B34),
    surfaceContainerHighest = Color(0xFF34333D),
    surfaceVariant = Color(0xFF2A2932),     // calm side-panel / bubble fill, cool charcoal
    onSurfaceVariant = Color(0xFFCBC8C2),

    outline = Color(0xFFA39A86),            // brighter so input borders read clearly
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
 * Sunlit honey-yellow accent (DESIGN §1) — the fourth note of the natural palette. Material's color
 * scheme has no dedicated slot for it, so it lives here and is woven into the top-bar gradient to
 * warm the blue-to-purple sweep. Tuned per light/dark so it glows without ever shouting.
 */
private val SaigonHoneyLight = Color(0xFFE89A3C)
private val SaigonHoneyDark = Color(0xFFEFB36A)

/**
 * Warm community accent (DESIGN §1) — a soft sage-teal used for interactive text (room titles, reply
 * counts, links). Standard Material blue would tip the warm palette back toward "software"; a muted
 * green-teal keeps the "neighbourhood, human conversation" feel while still reading as a link.
 */
private val SaigonAccentLight = Color(0xFF2E8A78)
private val SaigonAccentDark = Color(0xFF80C7B3)

/** The warm sage-teal community accent for the active light/dark scheme (DESIGN §1). */
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
    val honey = if (isSystemInDarkTheme()) SaigonHoneyDark else SaigonHoneyLight
    return Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            honey,
        ),
    )
}

/**
 * A soft hairline border (DESIGN §1 — "soft borders"): a thin, low-contrast outline that gives
 * cards and panels a gentle edge without the hard line of a full-strength stroke.
 */
@Composable
fun Modifier.softBorder(shape: Shape = SaigonShapes.medium): Modifier =
    this.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
