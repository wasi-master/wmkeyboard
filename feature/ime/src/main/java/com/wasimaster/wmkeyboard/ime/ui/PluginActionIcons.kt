package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Abc
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixHigh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Calculate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatQuote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatSize
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Numbers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SortByAlpha
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tag
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFormat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Title
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate

/**
 * The glyph for a plugin's selection action, from the name its manifest gave.
 *
 * Two vocabularies meet here. A Lua plugin names an icon the way a layout key
 * does, so [KeyIcons.byName] answers first and the documented key icon names
 * work unchanged. A Clink action names an SF Symbol (`textformat.size.larger`,
 * `arrow.left.arrow.right`), which has no Android counterpart, so the common
 * ones are matched by prefix onto the nearest Material Symbol. Anything else,
 * blank included, gets the plugin glyph: a chip with a picture is easier to
 * find again than one without, and a guessed picture could mislead.
 */
fun pluginActionIcon(name: String): ImageVector {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return Icons.Outlined.Extension
    KeyIcons.byName(trimmed)?.let { return it }
    val lower = trimmed.lowercase()
    return SfSymbolPrefixes.firstOrNull { (prefix, _) -> lower.startsWith(prefix) }?.second?.invoke()
        ?: Icons.Outlined.Extension
}

/** SF Symbol name prefixes, most specific first, onto the nearest glyph here. */
private val SfSymbolPrefixes: List<Pair<String, () -> ImageVector>> = listOf(
    "textformat.size" to { Icons.Outlined.FormatSize },
    "textformat.abc" to { Icons.Outlined.Abc },
    "textformat.characters" to { Icons.Outlined.Abc },
    "textformat" to { Icons.Outlined.TextFormat },
    "text.badge.checkmark" to { Icons.Outlined.Spellcheck },
    "checkmark" to { Icons.Outlined.Spellcheck },
    "text.quote" to { Icons.Outlined.FormatQuote },
    "quote" to { Icons.Outlined.FormatQuote },
    "text" to { Icons.Outlined.TextFields },
    "character.bubble" to { Icons.Outlined.Translate },
    "character" to { Icons.Outlined.Abc },
    "a.circle" to { Icons.Outlined.Abc },
    "arrow.left.arrow.right" to { Icons.Outlined.SwapHoriz },
    "arrow.left.and.right" to { Icons.Outlined.SwapHoriz },
    "arrow.triangle.swap" to { Icons.Outlined.SwapHoriz },
    "arrow.up.arrow.down" to { Icons.Outlined.SortByAlpha },
    "number" to { Icons.Outlined.Numbers },
    "plusminus" to { Icons.Outlined.Calculate },
    "function" to { Icons.Outlined.Calculate },
    "x.squareroot" to { Icons.Outlined.Calculate },
    "lasso" to { Icons.Outlined.Gesture },
    "scribble" to { Icons.Outlined.Gesture },
    "face." to { Icons.Outlined.EmojiEmotions },
    "smiley" to { Icons.Outlined.EmojiEmotions },
    "wand.and" to { Icons.Outlined.AutoFixHigh },
    "sparkles" to { Icons.Outlined.AutoFixHigh },
    "chevron.left.forwardslash.chevron.right" to { Icons.Outlined.Code },
    "curlybraces" to { Icons.Outlined.Code },
    "globe" to { Icons.Outlined.Translate },
    "link" to { Icons.Outlined.Link },
    "lock" to { Icons.Outlined.Lock },
    "tag" to { Icons.Outlined.Tag },
    "bold" to { Icons.Outlined.Title },
)
