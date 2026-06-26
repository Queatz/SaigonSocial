package social.saigon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import social.saigon.ui.emojiFontBytes

/**
 * Registers a bundled emoji font as a global fallback so emojis stop rendering as "tofu" boxes on
 * the Compose Web/Skiko renderer (which ships no emoji-capable font).
 *
 * The font is preloaded into the active [FontFamily.Resolver]: Skia first looks up glyphs in the
 * fonts referenced by a `Text`/theme, then in the bundled default (Roboto), and finally in the
 * preloaded fallback collection — which is where the emoji glyphs now come from. [content] is shown
 * only once the fallback font is ready, so the first frame never renders boxes.
 */
@Composable
fun WithEmojiFallback(content: @Composable () -> Unit) {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    var fontsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            val emojiFamily = FontFamily(Font(identity = "NotoColorEmoji", data = emojiFontBytes()))
            fontFamilyResolver.preload(emojiFamily)
        }
        fontsLoaded = true
    }

    if (fontsLoaded) {
        content()
    }
}
