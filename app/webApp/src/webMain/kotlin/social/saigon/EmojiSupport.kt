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
import social.saigon.ui.ThemedLoadingBackground
import social.saigon.ui.cjkFontBytes
import social.saigon.ui.emojiFontBytes

/**
 * Registers bundled emoji + CJK fonts as global fallbacks so emojis and Chinese/Japanese/Korean
 * text stop rendering as "tofu" boxes on the Compose Web/Skiko renderer (which ships neither an
 * emoji- nor a CJK-capable font).
 *
 * The fonts are preloaded into the active [FontFamily.Resolver]: Skia first looks up glyphs in the
 * fonts referenced by a `Text`/theme, then in the bundled default (Roboto), and finally in the
 * preloaded fallback collection — which is where the emoji and CJK glyphs now come from. [content]
 * is shown only once the fallback fonts are ready, so the first frame never renders boxes.
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
        runCatching {
            val cjkFamily = FontFamily(Font(identity = "NotoSansCJK", data = cjkFontBytes()))
            fontFamilyResolver.preload(cjkFamily)
        }
        fontsLoaded = true
    }

    if (fontsLoaded) {
        content()
    } else {
        // While the fallback fonts download, `content()` (and therefore the whole app UI) isn't
        // composed yet, so the Compose canvas would otherwise paint its default opaque white —
        // a visible white flash on top of the dark loader when the OS is in dark mode. Fill the
        // canvas with the theme background (which follows the OS light/dark scheme) so this gap
        // matches both the pre-app loader and the app itself.
        ThemedLoadingBackground()
    }
}
