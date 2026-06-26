package social.saigon.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
    primary = Color(0xFF2E6B3E),            // forest green
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7E4C0),
    onPrimaryContainer = Color(0xFF052110),

    secondary = Color(0xFF2D9CDB),          // sky blue
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7FB),
    onSecondaryContainer = Color(0xFF04263A),

    tertiary = Color(0xFF5BB6A6),           // soft teal accent
    onTertiary = Color(0xFF00322B),
    tertiaryContainer = Color(0xFFBFEDE5),
    onTertiaryContainer = Color(0xFF00201C),

    background = Color(0xFFF5FBF4),         // soft leafy white
    onBackground = Color(0xFF152017),
    surface = Color(0xFFFAFEF8),
    onSurface = Color(0xFF152017),
    surfaceVariant = Color(0xFFDEEBDE),     // calm side-panel / bubble fill
    onSurfaceVariant = Color(0xFF42493F),

    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2CDC0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val SaigonDarkColors = darkColorScheme(
    primary = Color(0xFF8FD89B),            // forest green, lifted for dark surfaces
    onPrimary = Color(0xFF06371A),
    primaryContainer = Color(0xFF1C5130),
    onPrimaryContainer = Color(0xFFB7E4C0),

    secondary = Color(0xFF9CD3F5),          // sky blue
    onSecondary = Color(0xFF04344F),
    secondaryContainer = Color(0xFF0B4E72),
    onSecondaryContainer = Color(0xFFCDE7FB),

    tertiary = Color(0xFF8CD3C6),           // soft teal accent
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005046),
    onTertiaryContainer = Color(0xFFBFEDE5),

    background = Color(0xFF101710),         // deep green-black canvas
    onBackground = Color(0xFFDFE8DC),
    surface = Color(0xFF101710),
    onSurface = Color(0xFFDFE8DC),
    surfaceVariant = Color(0xFF3F493F),     // calm side-panel / bubble fill
    onSurfaceVariant = Color(0xFFC2CDC0),

    outline = Color(0xFF8C958A),
    outlineVariant = Color(0xFF3F493F),
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
