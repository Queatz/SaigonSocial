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
 * A fresh, calm palette: forest-green accents over a soft, leafy off-white canvas with a sky-blue
 * secondary. The mood is unhurried and human — the opposite of a loud social network.
 *
 * The dark scheme keeps the same character — forest green and sky blue glowing over a deep,
 * low-contrast green-black canvas — so the app feels at home in either light or dark mode.
 */
private val SaigonLightColors = lightColorScheme(
    primary = Color(0xFF14502B),            // darker forest green
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FD3AE),
    onPrimaryContainer = Color(0xFF03190C),

    secondary = Color(0xFF1E7FB8),          // deeper sky blue
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFDFF4),
    onSecondaryContainer = Color(0xFF04263A),

    tertiary = Color(0xFF3F8E80),           // deeper teal accent
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB2E3DA),
    onTertiaryContainer = Color(0xFF00201C),

    background = Color(0xFFEEF6ED),         // soft leafy white
    onBackground = Color(0xFF101A12),
    surface = Color(0xFFF4FBF2),
    onSurface = Color(0xFF101A12),
    surfaceVariant = Color(0xFFD4E5D5),     // calm side-panel / bubble fill
    onSurfaceVariant = Color(0xFF3C443A),

    outline = Color(0xFF6B7268),
    outlineVariant = Color(0xFFBBC8BA),     // soft hairline borders
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val SaigonDarkColors = darkColorScheme(
    primary = Color(0xFF6FC585),            // forest green, lifted but not neon for dark surfaces
    onPrimary = Color(0xFF03260F),
    primaryContainer = Color(0xFF143F24),
    onPrimaryContainer = Color(0xFFA9DDB6),

    secondary = Color(0xFF8AC6EE),          // sky blue
    onSecondary = Color(0xFF04344F),
    secondaryContainer = Color(0xFF0A4566),
    onSecondaryContainer = Color(0xFFCDE7FB),

    tertiary = Color(0xFF7DCABD),           // soft teal accent
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF004A41),
    onTertiaryContainer = Color(0xFFBFEDE5),

    background = Color(0xFF0A100A),         // deeper green-black canvas
    onBackground = Color(0xFFDCE6D9),
    surface = Color(0xFF0D140D),
    onSurface = Color(0xFFDCE6D9),
    surfaceVariant = Color(0xFF36402F),     // calm side-panel / bubble fill
    onSurfaceVariant = Color(0xFFBEC9BB),

    outline = Color(0xFF828B7F),
    outlineVariant = Color(0xFF36402F),     // soft hairline borders
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
val InputFieldShape = RoundedCornerShape(16.dp)

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
 * A subtle gradient for the top app bar (DESIGN §1): the darker forest-green primary easing into the
 * teal-leaning tertiary, giving the bar a soft sheen instead of one flat fill.
 */
@Composable
@ReadOnlyComposable
fun topBarBrush(): Brush = Brush.horizontalGradient(
    colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
    ),
)

/**
 * A soft hairline border (DESIGN §1 — "soft borders"): a thin, low-contrast outline that gives
 * cards and panels a gentle edge without the hard line of a full-strength stroke.
 */
@Composable
fun Modifier.softBorder(shape: Shape = SaigonShapes.medium): Modifier =
    this.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
