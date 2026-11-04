package com.wasimaster.wmkeyboard.core.theme

import kotlin.math.min

/**
 * Reading a Kinetica theme (Kinetica 2.2 and later).
 *
 * Kinetica has no theme file and no list of named themes. Its look is a few
 * settings, and they travel in the one file it writes, its backup
 * (`kinetica_backup_<date>.txt`, from *Backup* in its settings): a header line
 * `kinetica-backup<TAB>1`, then one record a line, fields split by tabs. A
 * setting is `pref<TAB><type><TAB><key><TAB><value>`. The theme is these:
 *
 * | Key | Values |
 * |---|---|
 * | `pref_theme_mode` | `default` (its bundled colours), `dynamic` (Material You), `custom` (one typed colour) |
 * | `pref_theme_brightness` | `dark`, `light`, `system` |
 * | `pref_theme_color_hex` | `#RRGGBB`, the colour `custom` spreads over every surface |
 * | `pref_key_borders`, `pref_key_border_width` | a line round each key, 1 to 4 dp |
 * | `pref_trail_color`, `pref_trail_color_hex` | the swipe trail: `rainbow`, `theme`, five presets, or `custom` |
 *
 * Older builds wrote the custom colour as `pref_theme_hue` (a slider) or
 * `pref_theme_color` (a list); Kinetica turns those into a colour on start,
 * and they win over a stored hex there, so they win here too.
 *
 * Kinetica stores a source and a brightness, not colours, and works the
 * colours out when it draws. So does this, by the same rules: the bundled
 * palette is its `colors.xml`, and a typed colour is spread over the surfaces
 * by fixed HSV tones, re-derived from its `KeyboardTheme.fromPrimary` (2.2.1).
 * Material You keeps the bundled palette as its stored colours, which is what
 * Kinetica draws below Android 12 too, and follows the wallpaper from there.
 *
 * Brightness `system` switches with the phone; that comes across as a family
 * of two looks, dark first, so either one can be picked or paired up in the
 * automatic light and dark themes.
 *
 * The background picture never comes across: Kinetica keeps the picture on
 * the phone and leaves it out of its backup on purpose.
 */
object KineticaTheme {

    /** What the import picker accepts: a backup is a `.txt` file. */
    val IMPORT_MIME_TYPES = arrayOf("text/plain", "application/octet-stream", "*/*")

    /** The first field of a backup's first line. */
    const val BACKUP_FORMAT = "kinetica-backup"

    /**
     * True when [text] opens with a Kinetica backup's header line. Cheap: it
     * reads only up to the first line that is not blank.
     */
    fun looksLikeBackup(text: String): Boolean =
        text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.removePrefix(BOM)
            ?.startsWith("$BACKUP_FORMAT\t") == true

    /**
     * Converts [text], a whole backup or the `pref` lines of one. Never
     * throws. A backup that states no theme setting at all is still a theme,
     * Kinetica's default dark one, since that is what its user saw; text that
     * is neither a backup nor holds a theme setting is
     * [KineticaThemeResult.NotATheme].
     */
    fun read(text: String): KineticaThemeResult {
        val prefs = prefs(text)
        if (!looksLikeBackup(text) && THEME_KEYS.none { it in prefs }) return KineticaThemeResult.NotATheme

        val mode = prefs[MODE_KEY]?.takeIf { it in MODES } ?: MODE_DEFAULT
        val brightness = prefs[BRIGHTNESS_KEY]?.takeIf { it in BRIGHTNESSES } ?: BRIGHTNESS_DARK
        val primary = themeColour(prefs)
        val borderDp = if (prefs[BORDERS_KEY]?.toBooleanStrictOrNull() == true) {
            (prefs[BORDER_WIDTH_KEY]?.toIntOrNull() ?: 1).coerceIn(MIN_BORDER_DP, MAX_BORDER_DP)
        } else {
            0
        }
        val trailMode = prefs[TRAIL_KEY] ?: TRAIL_RAINBOW
        val trailColour = trailColour(prefs, trailMode)

        val dropped = linkedSetOf<KineticaUnsupported>()
        if (trailMode == TRAIL_RAINBOW) dropped += KineticaUnsupported.RAINBOW_TRAIL
        if (prefs[SWIPE_LOOK_KEY] == SWIPE_LOOK_KEYS || prefs[SWIPE_LOOK_KEY] == SWIPE_LOOK_BOTH) {
            dropped += KineticaUnsupported.LIT_KEYS
        }

        val base = when (mode) {
            MODE_DYNAMIC -> "Kinetica Material You"
            MODE_CUSTOM -> "Kinetica ${hex(primary)}"
            else -> "Kinetica"
        }
        fun look(light: Boolean, name: String): ThemeSpec {
            val palette = if (mode == MODE_CUSTOM) fromPrimary(primary, light) else bundled(light)
            return spec(palette, light, name, borderDp, trailColour, followWallpaper = mode == MODE_DYNAMIC)
        }
        val theme = when (brightness) {
            BRIGHTNESS_LIGHT -> look(light = true, name = "$base light")
            BRIGHTNESS_SYSTEM -> look(light = false, name = "$base dark").copy(
                familyName = base,
                variants = listOf(look(light = true, name = "$base light")),
            )
            else -> look(light = false, name = base)
        }
        return KineticaThemeResult.Converted(theme = theme, dropped = dropped.toList())
    }

    /**
     * Every `pref` record of [text], key to value. The value is the rest of
     * the line, tabs and all, the way Kinetica joins it back; a line of any
     * other kind is skipped.
     */
    private fun prefs(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (!line.startsWith("pref\t")) continue
            val fields = line.split('\t')
            if (fields.size < MIN_PREF_FIELDS || fields[2].isEmpty()) continue
            out[fields[2]] = fields.drop(3).joinToString("\t")
        }
        return out
    }

    private fun themeColour(prefs: Map<String, String>): Int {
        val oldHue = prefs[OLD_HUE_KEY]?.toIntOrNull()?.takeIf { it in 0..FULL_TURN }
        if (oldHue != null) return hsv(oldHue.toFloat(), OLD_THEME_SAT, OLD_THEME_VAL)
        prefs[OLD_COLOUR_KEY]?.let { old ->
            val hue = hueOf(parseHex(old) ?: parseHex(OLD_DEFAULT_COLOUR)!!)
            return hsv(hue, OLD_THEME_SAT, OLD_THEME_VAL)
        }
        return prefs[COLOUR_KEY]?.let(::parseHex) ?: parseHex(DEFAULT_COLOUR)!!
    }

    /** The trail's colour, or null to follow the accent, as `theme` and `rainbow` both end up here. */
    private fun trailColour(prefs: Map<String, String>, mode: String): Long? {
        val hue = when (mode) {
            "red" -> 0f
            "orange" -> 30f
            "green" -> 120f
            "cyan" -> 180f
            "violet" -> 275f
            TRAIL_CUSTOM -> {
                val oldHue = prefs[OLD_TRAIL_HUE_KEY]?.toIntOrNull()
                val colour = if (oldHue != null) {
                    hsv(oldHue.coerceIn(0, FULL_TURN).toFloat(), TRAIL_SAT, TRAIL_VAL)
                } else {
                    prefs[TRAIL_HEX_KEY]?.let(::parseHex) ?: parseHex(DEFAULT_TRAIL_COLOUR)!!
                }
                return argb(colour)
            }
            else -> return null
        }
        return argb(hsv(hue, TRAIL_SAT, TRAIL_VAL))
    }

    /** Kinetica's twelve colour roles. */
    private class Palette(
        val background: Int,
        val key: Int,
        val keySpecial: Int,
        val keyPressed: Int,
        val keyText: Int,
        val keyHint: Int,
        val suggestionBg: Int,
        val suggestionText: Int,
        val suggestionPrimary: Int,
        val chip: Int,
        val popupBg: Int,
        val accent: Int,
    )

    private fun spec(
        p: Palette,
        light: Boolean,
        name: String,
        borderDp: Int,
        trail: Long?,
        followWallpaper: Boolean,
    ) = ThemeSpec(
        id = "",
        name = name,
        dark = !light,
        boardBackground = argb(p.background),
        keyBackground = argb(p.key),
        keyText = argb(p.keyText),
        // Kinetica paints every key that is not a letter in one colour,
        // enter among them, and writes all labels in the key text colour.
        modifierKeyBackground = argb(p.keySpecial),
        enterKeyBackground = argb(p.keySpecial),
        enterKeyText = argb(p.keyText),
        pressedKeyBackground = argb(p.keyPressed),
        // The corner hint is drawn at 40% of the hint colour.
        hintText = (argb(p.keyHint) and RGB_MASK) or HINT_ALPHA,
        // The line round a key is the hint colour at full strength.
        keyBorderColor = if (borderDp > 0) argb(p.keyHint) else null,
        keyBorderWidthDp = borderDp.toFloat(),
        keyCornerRadiusDp = KEY_CORNER_DP,
        accent = argb(p.accent),
        gestureTrailColor = trail,
        popupBackground = argb(p.popupBg),
        popupText = argb(p.keyText),
        popupSelectedBackground = argb(p.accent),
        suggestionBarBackground = argb(p.suggestionBg),
        suggestionText = argb(p.suggestionPrimary),
        secondarySuggestionText = argb(p.suggestionText),
        chipBackground = argb(p.chip),
        followWallpaper = followWallpaper,
    )

    /** Kinetica's `colors.xml`. */
    private fun bundled(light: Boolean): Palette = if (light) {
        Palette(
            background = 0xFFE6E6EA.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keySpecial = 0xFFD5D5DC.toInt(),
            keyPressed = 0xFFBFBFCB.toInt(),
            keyText = 0xFF1B1B1F.toInt(),
            keyHint = 0xFF6C6C77.toInt(),
            suggestionBg = 0xFFF1F1F5.toInt(),
            suggestionText = 0xFF3A3A42.toInt(),
            suggestionPrimary = 0xFF101014.toInt(),
            chip = 0xFFC9CEF5.toInt(),
            popupBg = 0xFFFFFFFF.toInt(),
            accent = BUNDLED_ACCENT,
        )
    } else {
        Palette(
            background = 0xFF1B1B1F.toInt(),
            key = 0xFF303034.toInt(),
            keySpecial = 0xFF25252A.toInt(),
            keyPressed = 0xFF4A4A52.toInt(),
            keyText = 0xFFEDEDF2.toInt(),
            keyHint = 0xFF9A9AA5.toInt(),
            suggestionBg = 0xFF232327.toInt(),
            suggestionText = 0xFFD8D8E0.toInt(),
            suggestionPrimary = 0xFFFFFFFF.toInt(),
            chip = 0xFF3A3A55.toInt(),
            popupBg = 0xFF3F3F46.toInt(),
            accent = BUNDLED_ACCENT,
        )
    }

    /**
     * One typed colour over every role: each role pins a value (brightness)
     * and damps the saturation on large surfaces; a dark typed colour dims
     * the surfaces within a bounded reach.
     */
    private fun fromPrimary(primary: Int, light: Boolean): Palette {
        val h = hueOf(primary)
        val s = saturationOf(primary)
        val v = valueOf(primary)
        fun tone(sat: Float, value: Float) = hsv(h, sat, value)
        val surfaceSat = min(s, 0.55f)
        val dim = 1f - (if (light) LIGHT_VALUE_REACH else DARK_VALUE_REACH) * (1f - v)
        return if (light) {
            Palette(
                background = tone(surfaceSat * 0.22f, 0.90f * dim),
                key = tone(surfaceSat * 0.10f, 1.00f * dim),
                keySpecial = tone(surfaceSat * 0.20f, 0.94f * dim),
                keyPressed = tone(min(s, 0.40f), 0.82f * dim),
                keyText = tone(min(s, 0.30f), 0.16f),
                keyHint = tone(min(s, 0.35f), 0.45f),
                suggestionBg = tone(surfaceSat * 0.18f, 0.96f * dim),
                suggestionText = tone(min(s, 0.30f), 0.26f),
                suggestionPrimary = tone(min(s, 0.40f), 0.12f),
                chip = tone(min(s, 0.45f), 0.86f * dim),
                popupBg = tone(surfaceSat * 0.12f, 1.00f * dim),
                accent = tone(min(s, 0.85f), 0.70f),
            )
        } else {
            Palette(
                background = tone(surfaceSat * 0.55f, 0.10f * dim),
                key = tone(surfaceSat * 0.50f, 0.21f * dim),
                keySpecial = tone(surfaceSat * 0.50f, 0.16f * dim),
                keyPressed = tone(min(s, 0.65f), 0.35f * dim),
                keyText = 0xFFEDEDF2.toInt(),
                keyHint = tone(0.15f, 0.66f),
                suggestionBg = tone(surfaceSat * 0.55f, 0.13f * dim),
                suggestionText = 0xFFD8D8E0.toInt(),
                suggestionPrimary = 0xFFFFFFFF.toInt(),
                chip = tone(min(s, 0.60f), 0.30f * dim),
                popupBg = tone(surfaceSat * 0.50f, 0.27f * dim),
                accent = if (v >= ACCENT_MIN_VALUE) primary else tone(s, ACCENT_MIN_VALUE),
            )
        }
    }

    /**
     * Opaque ARGB for [hue] degrees, [sat] and [value] in 0..1, by
     * `android.graphics.Color.HSVToColor`'s rules: a hue outside 0 until 360
     * counts as 0, and each channel rounds half up.
     */
    private fun hsv(hue: Float, sat: Float, value: Float): Int {
        val s = sat.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val sector = if (!hue.isFinite() || hue < 0f || hue >= FULL_TURN) 0f else hue / SECTOR_DEGREES
        val index = sector.toInt()
        val f = sector - index
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (index) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        fun byte(c: Float) = (c * 255f + 0.5f).toInt().coerceIn(0, 255)
        return OPAQUE or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    private fun channels(colour: Int) = Triple(
        ((colour shr 16) and 0xFF) / 255f,
        ((colour shr 8) and 0xFF) / 255f,
        (colour and 0xFF) / 255f,
    )

    private fun hueOf(colour: Int): Float {
        val (r, g, b) = channels(colour)
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        if (d <= 0f) return 0f
        val h = when (max) {
            r -> SECTOR_DEGREES * (((g - b) / d) % 6f)
            g -> SECTOR_DEGREES * (((b - r) / d) + 2f)
            else -> SECTOR_DEGREES * (((r - g) / d) + 4f)
        }
        return if (h < 0f) h + FULL_TURN else h
    }

    private fun saturationOf(colour: Int): Float {
        val (r, g, b) = channels(colour)
        val max = maxOf(r, g, b)
        return if (max <= 0f) 0f else (max - minOf(r, g, b)) / max
    }

    private fun valueOf(colour: Int): Float {
        val (r, g, b) = channels(colour)
        return maxOf(r, g, b)
    }

    /** `#RRGGBB` the way Kinetica reads it: spaces dropped, `#` optional, six digits or nothing. */
    private fun parseHex(text: String): Int? {
        val digits = text.filterNot { it.isWhitespace() }.removePrefix("#")
        if (digits.length != HEX_DIGITS || digits.any { Character.digit(it, HEX) < 0 }) return null
        return digits.toInt(HEX) or OPAQUE
    }

    private fun hex(colour: Int) = "#%06X".format(colour and 0xFFFFFF)

    private fun argb(colour: Int): Long = colour.toLong() and 0xFFFFFFFFL

    private const val BOM = "﻿"
    private const val MIN_PREF_FIELDS = 3

    private const val MODE_KEY = "pref_theme_mode"
    private const val BRIGHTNESS_KEY = "pref_theme_brightness"
    private const val COLOUR_KEY = "pref_theme_color_hex"
    private const val OLD_HUE_KEY = "pref_theme_hue"
    private const val OLD_COLOUR_KEY = "pref_theme_color"
    private const val BORDERS_KEY = "pref_key_borders"
    private const val BORDER_WIDTH_KEY = "pref_key_border_width"
    private const val TRAIL_KEY = "pref_trail_color"
    private const val TRAIL_HEX_KEY = "pref_trail_color_hex"
    private const val OLD_TRAIL_HUE_KEY = "pref_trail_color_custom_hue"
    private const val SWIPE_LOOK_KEY = "pref_swipe_look"

    /** Settings whose presence in pasted text says it is a Kinetica theme. */
    private val THEME_KEYS = listOf(MODE_KEY, BRIGHTNESS_KEY, COLOUR_KEY, OLD_HUE_KEY, OLD_COLOUR_KEY)

    private const val MODE_DEFAULT = "default"
    private const val MODE_DYNAMIC = "dynamic"
    private const val MODE_CUSTOM = "custom"
    private val MODES = setOf(MODE_DEFAULT, MODE_DYNAMIC, MODE_CUSTOM)

    private const val BRIGHTNESS_DARK = "dark"
    private const val BRIGHTNESS_LIGHT = "light"
    private const val BRIGHTNESS_SYSTEM = "system"
    private val BRIGHTNESSES = setOf(BRIGHTNESS_DARK, BRIGHTNESS_LIGHT, BRIGHTNESS_SYSTEM)

    private const val TRAIL_RAINBOW = "rainbow"
    private const val TRAIL_CUSTOM = "custom"
    private const val SWIPE_LOOK_KEYS = "keys"
    private const val SWIPE_LOOK_BOTH = "both"

    private const val DEFAULT_COLOUR = "#475DFF"
    private const val OLD_DEFAULT_COLOUR = "#5468FF"
    private const val DEFAULT_TRAIL_COLOUR = "#26B7FF"
    private const val BUNDLED_ACCENT = 0xFF5468FF.toInt()

    /** The retired hue slider drew the theme colour at this saturation and value. */
    private const val OLD_THEME_SAT = 0.72f
    private const val OLD_THEME_VAL = 1f

    /** Every trail preset, and the retired trail slider, is drawn at this saturation and value. */
    private const val TRAIL_SAT = 0.85f
    private const val TRAIL_VAL = 1f

    private const val DARK_VALUE_REACH = 0.4f
    private const val LIGHT_VALUE_REACH = 0.2f
    private const val ACCENT_MIN_VALUE = 0.6f

    private const val MIN_BORDER_DP = 1
    private const val MAX_BORDER_DP = 4
    private const val KEY_CORNER_DP = 7
    private const val HINT_ALPHA = 0x66000000L
    private const val RGB_MASK = 0x00FFFFFFL

    private const val OPAQUE = -0x1000000
    private const val FULL_TURN = 360
    private const val SECTOR_DEGREES = 60f
    private const val HEX = 16
    private const val HEX_DIGITS = 6
}

/** What reading a Kinetica theme produced. */
sealed interface KineticaThemeResult {

    /**
     * The theme, with its id still to be set. For brightness `system` it heads
     * a family whose one variant is the light half; store it with
     * [withFreshIds] so the variant gets an id of its own.
     */
    data class Converted(
        val theme: ThemeSpec,
        /** What the theme asked for that did not come across whole. */
        val dropped: List<KineticaUnsupported> = emptyList(),
    ) : KineticaThemeResult

    /** Neither a Kinetica backup nor text with a theme setting in it. */
    data object NotATheme : KineticaThemeResult
}

/** Something a Kinetica theme asked for that did not come across whole. */
enum class KineticaUnsupported {
    /** A trail that changes colour key by key; here it takes the theme's accent. */
    RAINBOW_TRAIL,

    /** Keys that light up as a swipe crosses them, which this keyboard does not draw. */
    LIT_KEYS,
}
