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
 * A warm, calm, paper-and-clay palette: terracotta accents over a soft off-white canvas with a
 * river-teal secondary. The mood is unhurried and human — the opposite of a loud social network.
 *
 * The dark scheme keeps the same warm character — terracotta and river-teal accents glowing over a
 * deep, low-contrast clay canvas — so the app feels at home in either light or dark mode.
 */
private val SaigonLightColors = lightColorScheme(
    primary = Color(0xFFC2562E),            // warm terracotta
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF3A0E00),

    secondary = Color(0xFF2A9D8F),          // river teal
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFEDE5),
    onSecondaryContainer = Color(0xFF00201C),

    tertiary = Color(0xFFE0A030),           // lantern amber
    onTertiary = Color(0xFF3A2600),
    tertiaryContainer = Color(0xFFFFE0AE),
    onTertiaryContainer = Color(0xFF271900),

    background = Color(0xFFFDF7F2),         // soft paper
    onBackground = Color(0xFF231914),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF231914),
    surfaceVariant = Color(0xFFF4E5DC),     // calm side-panel / bubble fill
    onSurfaceVariant = Color(0xFF53443C),

    outline = Color(0xFF85746B),
    outlineVariant = Color(0xFFD8C7BD),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val SaigonDarkColors = darkColorScheme(
    primary = Color(0xFFFFB59A),            // warm terracotta, lifted for dark surfaces
    onPrimary = Color(0xFF551C06),
    primaryContainer = Color(0xFF763D1C),
    onPrimaryContainer = Color(0xFFFFDBCC),

    secondary = Color(0xFFA3D3CA),          // river teal
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFFBFEDE5),

    tertiary = Color(0xFFF3C06A),           // lantern amber
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFE0AE),

    background = Color(0xFF1A120D),         // deep clay canvas
    onBackground = Color(0xFFECDFD7),
    surface = Color(0xFF1A120D),
    onSurface = Color(0xFFECDFD7),
    surfaceVariant = Color(0xFF52443C),     // calm side-panel / bubble fill
    onSurfaceVariant = Color(0xFFD8C7BD),

    outline = Color(0xFFA08D83),
    outlineVariant = Color(0xFF52443C),
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
