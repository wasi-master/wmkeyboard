package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.wasimaster.wmkeyboard.core.fonts.FontVariations
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.axesFor
import com.wasimaster.wmkeyboard.core.theme.FontRole
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec

/**
 * The font the board's labels draw in, as it is needed past the typography:
 * the faces the corner hints, the suggestion strip and the key bubbles draw in
 * when their axes were tuned apart from the keys' ([hint], [strip], [popup],
 * each null while it follows the keys and so takes the typography's face), and
 * a way to cut the same font at other axis values ([withAxes]) for a key's own
 * style and for the theme's font motion.
 *
 * [variations] are the axes of the font the labels draw in, null for a static
 * face, which is what tells a key or a motion there is nothing to move.
 * [keyAxes] is where the labels rest: the values the typography's face was cut
 * at, already held to the font.
 */
@Stable
internal class BoardFonts(
    val hint: FontFamily?,
    val strip: FontFamily?,
    val popup: FontFamily?,
    val variations: FontVariations?,
    val keyAxes: Map<String, Float>,
    private val build: ((Map<String, Float>) -> FontFamily?)?,
) {
    /**
     * The labels' font at [axes] (whole values, not changes from [keyAxes]),
     * or null where it cannot be cut: a static face, or a chained face whose
     * Google half is still on its way. Cached by [KeyboardFonts], so asking
     * twice for one setting builds it once.
     */
    fun withAxes(axes: Map<String, Float>): FontFamily? {
        val v = variations ?: return null
        val build = build ?: return null
        // A moving label asks for the same few snapped settings frame after
        // frame, from its draw pass; answered here, they skip the clamp, the
        // cache key strings and the locks behind [KeyboardFonts] each time.
        synchronized(memo) { memo[axes]?.let { return it.family } }
        val family = build(v.clamp(axes))
        synchronized(memo) {
            if (memo.size >= MEMO_SIZE) memo.remove(memo.keys.first())
            memo[axes] = Cut(family)
        }
        return family
    }

    /** One answer of [withAxes], a null one (a face that cannot be cut) included. */
    private class Cut(val family: FontFamily?)

    /**
     * The platform typeface for [withAxes] at [weight], loaded: what a moving
     * label draws with straight onto the canvas, with no text layout behind
     * it. Kept per setting and weight, a null answer included, so a frame of
     * motion is a map lookup per label. Safe off the main thread, which is
     * where [FontMotionLayer] fills it ahead of the first press.
     */
    fun typefaceAt(axes: Map<String, Float>, weight: FontWeight, resolver: FontFamily.Resolver): android.graphics.Typeface? {
        val key = TypefaceKey(axes, weight.weight)
        synchronized(typefaces) { typefaces[key]?.let { return it.typeface } }
        val family = withAxes(axes)
        val typeface = family?.let {
            runCatching { resolver.resolve(it, weight).value as? android.graphics.Typeface }.getOrNull()
        }
        synchronized(typefaces) {
            if (typefaces.size >= MEMO_SIZE * 2) typefaces.remove(typefaces.keys.first())
            typefaces[key] = Loaded(typeface)
        }
        return typeface
    }

    private data class TypefaceKey(val axes: Map<String, Float>, val weight: Int)

    private class Loaded(val typeface: android.graphics.Typeface?)

    private val typefaces = LinkedHashMap<TypefaceKey, Loaded>(16, 0.75f, true)

    private val memo = LinkedHashMap<Map<String, Float>, Cut>(16, 0.75f, true)

    companion object {
        val None = BoardFonts(null, null, null, null, emptyMap(), null)
        private const val MEMO_SIZE = 384
    }
}

internal val LocalBoardFonts = staticCompositionLocalOf { BoardFonts.None }

/** [this] in [family] when a role has a face of its own, else unchanged. */
internal fun TextStyle.inRoleFont(family: FontFamily?): TextStyle =
    if (family == null) this else copy(fontFamily = family)

/**
 * The font the board's labels draw in: [id], and whether it is the Latin side
 * of the order (the user's English font, the layout's, the theme's, or the
 * system's) rather than a script's own face. The two take their axes from
 * different places in a theme; see [boardFontAxes].
 */
internal data class BoardFontPick(val id: String, val latinSide: Boolean)

/**
 * The same order [KeyboardThemeProvider] has always resolved the board's
 * face by, spelled out as an id so the axes can be looked up for it: the
 * script's face where the board has one, else the Latin side, falling to the
 * system default when nothing resolves.
 */
internal fun boardFontPick(
    context: Context,
    settings: KeyboardSettings,
    scriptId: ScriptId,
    layoutFontId: String?,
    themeFontId: String?,
    themeScriptFontId: String?,
    unicodeMyanmar: Boolean,
): BoardFontPick {
    KeyboardFonts.scriptFontId(
        context,
        scriptId,
        settings.scriptFontIds[scriptId.name] ?: KeyboardFonts.DEFAULT_ID,
        layoutFontId,
        themeScriptFontId,
        unicodeMyanmar,
    )?.let { return BoardFontPick(it, latinSide = false) }
    val latin = settings.keyFontId.takeIf { it != KeyboardFonts.DEFAULT_ID && KeyboardFonts.family(context, it) != null }
        ?: layoutFontId?.takeIf { KeyboardFonts.family(context, it) != null }
        ?: themeFontId?.takeIf { KeyboardFonts.family(context, it) != null }
        ?: KeyboardFonts.DEFAULT_ID
    return BoardFontPick(latin, latinSide = true)
}

/**
 * The axis values [id] draws at in [role].
 *
 * The user's own tuning of that font comes first, whatever supplied the font:
 * a font tuned on the Fonts screen keeps those numbers under every theme. Then
 * the theme's, but only for a font the theme asked for: its [ThemeSpec.fontId]
 * (or, for a theme naming no font, the system default it then draws in) on the
 * Latin side, its per-script face on a script's. A theme's axes on some other
 * font would be numbers written for a different design.
 */
internal fun boardFontAxes(
    settings: KeyboardSettings,
    theme: ThemeSpec?,
    script: ScriptId,
    id: String,
    latinSide: Boolean,
    role: FontRole,
): Map<String, Float> {
    settings.axesFor(id)?.let { return it.role(role) }
    if (theme == null) return emptyMap()
    if (!latinSide) {
        return if (theme.scriptFontIds[script.name] == id) {
            theme.scriptFontAxes[script.name]?.role(role).orEmpty()
        } else {
            emptyMap()
        }
    }
    val asked = theme.fontId == id || (theme.fontId == null && id == KeyboardFonts.DEFAULT_ID)
    return if (asked) theme.fontAxes?.role(role).orEmpty() else emptyMap()
}

/**
 * The face a key with its own font axes ([KeyVisual.fontAxes]) draws its label
 * in: the board's font at the board's values with the key's laid over them, so
 * a key that only widens keeps the theme's weight. Null, for every key without
 * axes of its own and on a static face, which leaves the typography's.
 */
@androidx.compose.runtime.Composable
internal fun keyOwnFontFamily(axes: Map<String, Float>?): FontFamily? {
    val fonts = LocalBoardFonts.current
    return androidx.compose.runtime.remember(axes, fonts) {
        axes?.let { fonts.withAxes(fonts.keyAxes + it) }
    }
}
