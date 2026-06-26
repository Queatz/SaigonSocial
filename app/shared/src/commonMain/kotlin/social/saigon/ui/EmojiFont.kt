package social.saigon.ui

import saigonsocial.app.shared.generated.resources.Res

/**
 * Raw bytes of the bundled emoji font (Noto color emoji, COLRv1).
 *
 * The Compose Web/Skiko renderer only draws glyphs from fonts it has explicitly loaded and ships no
 * emoji-capable font, so emojis render as "tofu" boxes unless an emoji font is registered as a
 * fallback. The web entry point reads these bytes and preloads them into the font resolver
 * (see `WithEmojiFallback`).
 */
suspend fun emojiFontBytes(): ByteArray = Res.readBytes("font/noto_color_emoji.ttf")
