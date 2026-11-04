package com.wasimaster.wmkeyboard.core.theme

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.theme.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlin.math.roundToInt

/**
 * Reading a Clink theme (`.clinktheme`).
 *
 * Clink is an iOS keyboard whose themes are plain JSON: what its theme editor
 * exports, and what its theme repositories (`anti-ltd/clink-themes` and the
 * forks of it) publish. Every colour is an object of four 0..1 components,
 * `{"r", "g", "b", "a"}`.
 *
 * | Clink | Here |
 * |---|---|
 * | `background`, `backgroundGradient` | the board, and its gradient |
 * | `keyFill`, `keyText`, `keyGradient` | the letter keys |
 * | `specialKeyFill`, `specialKeyText` | shift, delete, the mode keys, and enter unless a group paints it |
 * | `accent` | the accent |
 * | `suggestionText` | the words on the suggestion bar |
 * | `groupPaint.letters` / `.modifiers` / `.return` | the letter, modifier and enter colours |
 * | `groupPaint.space` / `.backspace` / `.shift` | that one key's own colours |
 * | `keyPaint` (`c:q`, `f:shift`) | one key's own colours |
 * | `keyOuterStroke`, else `keyInnerStroke` | the key border |
 * | `keyShadow` when on | the key's lift and shadow colour |
 * | `keyCapShape` | `capsule` is a pill, `rect` square corners |
 * | `keyFontWeight` | semibold and heavier draw bold labels |
 * | `keyFontDesign` | the nearest Google Font |
 * | `material: liquidGlass`, `liquidIce` | frosted glass |
 *
 * A theme with `pairedVariants` switches with the phone's light and dark mode
 * in Clink. That comes across as a family of two looks, dark first, the way the
 * other converted families here are laid out. The theme's own top-level fields
 * repeat one of the two, so they are only read when the pair is incomplete.
 *
 * Clink draws most of its themes with a material: brushed metal, stitched
 * cloth, ceramic glaze, a 3D key cap, holographic foil, a terminal's glitch.
 * Those are renderers, not colours, and this keyboard has none of them, so the
 * colours come across on plain keys and [ClinkUnsupported.MATERIAL] says so.
 * The animated backgrounds (`backgroundEffect`) and pictures go the same way.
 */
object ClinkTheme {

    const val FILE_EXTENSION = "clinktheme"

    /** What the import picker accepts: the file is JSON under its own extension. */
    val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * True when [text] looks like a Clink theme. Both key colours together, as
     * no other theme format here names either.
     */
    fun looksLikeClinkTheme(text: String): Boolean =
        text.trimStart().removePrefix(BOM).trimStart().startsWith("{") &&
            text.contains("\"keyFill\"") &&
            text.contains("\"specialKeyFill\"")

    /** Converts [text]. Never throws. */
    fun read(text: String): ClinkThemeResult {
        if (!looksLikeClinkTheme(text)) return ClinkThemeResult.NotATheme
        val root = runCatching { json.parseToJsonElement(text.trimStart().removePrefix(BOM)) }.getOrNull()
            as? JsonObject ?: return ClinkThemeResult.NotATheme
        if (colour(root["keyFill"]) == null || colour(root["background"]) == null) return ClinkThemeResult.NotATheme

        val dropped = linkedSetOf<ClinkUnsupported>()
        val name = root.string("name")?.trim()?.ifEmpty { null } ?: FALLBACK_NAME
        val paired = (root["pairedVariants"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { colour(it["keyFill"]) != null && colour(it["background"]) != null }
        val dark = paired.firstOrNull { it.isDark() }
        val light = paired.firstOrNull { !it.isDark() }

        val theme = if (dark != null && light != null) {
            look(dark, "$name dark", dropped).copy(
                familyName = name,
                variants = listOf(look(light, "$name light", dropped)),
            )
        } else {
            look(root, name, dropped)
        }
        return ClinkThemeResult.Converted(theme = theme, dropped = dropped.toList())
    }

    private fun look(obj: JsonObject, name: String, dropped: MutableSet<ClinkUnsupported>): ThemeSpec {
        val background = colour(obj["background"]) ?: DEFAULT_BOARD
        val keyFill = colour(obj["keyFill"]) ?: DEFAULT_KEY
        val keyText = colour(obj["keyText"]) ?: legibleOn(keyFill)
        val specialFill = colour(obj["specialKeyFill"]) ?: keyFill
        val specialText = colour(obj["specialKeyText"]) ?: keyText
        val accent = colour(obj["accent"]) ?: DEFAULT_ACCENT

        val groups = obj["groupPaint"] as? JsonObject
        val letters = groups?.get("letters") as? JsonObject
        val modifiers = groups?.get("modifiers") as? JsonObject
        val enter = groups?.get("return") as? JsonObject

        val overrides = LinkedHashMap<String, KeyOverride>()
        for ((group, key) in GROUP_KEYS) {
            paintOverride(groups?.get(group) as? JsonObject)?.let { overrides[key] = it }
        }
        (obj["keyPaint"] as? JsonObject)?.forEach { (id, paint) ->
            val key = keyPaintId(id) ?: return@forEach
            paintOverride(paint as? JsonObject)?.let { overrides[key] = it }
        }

        val (borderColour, borderWidth) = border(obj, dropped)
        val shadow = obj["keyShadow"] as? JsonObject
        val shadowOn = shadow?.bool("isOn") == true
        val elevation = if (shadowOn) {
            maxOf(shadow?.number("y") ?: 0f, (shadow?.number("radius") ?: 0f) / 2f).coerceIn(1f, MAX_ELEVATION_DP)
        } else {
            0f
        }

        val material = obj.string("material")
        val glass = material in GLASS_MATERIALS
        if ((material != null && material != MATERIAL_SOLID && !glass) || obj.containsKey("keyFinish") ||
            obj.containsKey("terminalGlitch")
        ) {
            dropped += ClinkUnsupported.MATERIAL
        }
        if (obj.containsKey("backgroundEffect")) dropped += ClinkUnsupported.BACKGROUND_EFFECT
        if (obj.containsKey("backgroundImageID") || obj.containsKey("keyImageID")) dropped += ClinkUnsupported.PICTURES

        val weight = obj.string("keyFontWeight")
        val design = obj.string("keyFontDesign")

        return ThemeSpec(
            id = "",
            name = name,
            dark = obj.bool("isDark") ?: (luminance(background) < HALF),
            boardBackground = background,
            boardGradient = gradient(obj["backgroundGradient"]),
            keyGradient = gradient(obj["keyGradient"]),
            keyBackground = colour(letters?.get("fill")) ?: keyFill,
            keyText = colour(letters?.get("text")) ?: keyText,
            modifierKeyBackground = colour(modifiers?.get("fill")) ?: specialFill,
            modifierKeyText = colour(modifiers?.get("text")) ?: specialText,
            enterKeyBackground = colour(enter?.get("fill")) ?: specialFill,
            enterKeyText = colour(enter?.get("text")) ?: specialText,
            pressedKeyBackground = colour(letters?.get("pressed")),
            keyBorderColor = borderColour,
            keyBorderWidthDp = borderWidth,
            keyElevationDp = elevation,
            keyShadowColor = if (shadowOn) colour(shadow?.get("color")) else null,
            keyShape = when (obj.string("keyCapShape")) {
                "capsule" -> KeyShapeKind.PILL
                "rect" -> KeyShapeKind.SHARP
                "circle" -> KeyShapeKind.CIRCLE
                else -> KeyShapeKind.ROUNDED
            },
            keyCornerRadiusDp = letters?.number("cornerRadius")?.roundToInt(),
            accent = accent,
            suggestionText = colour(obj["suggestionText"]),
            boldKeyLabels = weight?.let { it in BOLD_WEIGHTS },
            fontId = design?.let { FONT_FOR_DESIGN[it] },
            keyOverrides = overrides,
            frost = if (glass) FrostSpec() else null,
        )
    }

    /** A group's or one key's own fill, label and outline, or null when it sets none of them. */
    private fun paintOverride(paint: JsonObject?): KeyOverride? {
        paint ?: return null
        val override = KeyOverride(
            background = colour(paint["fill"]),
            text = colour(paint["text"]),
            border = colour(paint["outline"]),
        )
        return override.takeUnless { it.isEmpty }
    }

    /**
     * The key a `keyPaint` id names: `c:<letter>` is a character key, by its
     * lowercase label as [ThemeSpec.keyOverrides] wants it, and `f:<name>` a
     * function key. A function key with no counterpart here is skipped.
     */
    private fun keyPaintId(id: String): String? = when {
        id.startsWith("c:") -> id.removePrefix("c:").lowercase().ifEmpty { null }
        id.startsWith("f:") -> FUNCTION_KEYS[id.removePrefix("f:")]
        else -> null
    }

    /** The outer stroke when it is drawn, else the inner one; a dashed or dotted line is drawn solid. */
    private fun border(obj: JsonObject, dropped: MutableSet<ClinkUnsupported>): Pair<Long?, Float> {
        for (key in listOf("keyOuterStroke", "keyInnerStroke")) {
            val stroke = obj[key] as? JsonObject ?: continue
            val style = stroke.string("style") ?: STROKE_NONE
            if (style == STROKE_NONE) continue
            val colour = colour(stroke["color"]) ?: continue
            val width = (stroke.number("width") ?: 1f).coerceIn(MIN_BORDER_DP, MAX_BORDER_DP)
            if (style != STROKE_SOLID) dropped += ClinkUnsupported.STROKE_STYLE
            return colour to width
        }
        return null to 0f
    }

    /**
     * A Clink gradient: `type` (linear, radial, angular), `rotation` in degrees
     * and `stops` of a colour and a 0..1 position. The positions are dropped,
     * since a gradient here spaces its stops evenly; the colours keep their
     * order.
     */
    private fun gradient(element: JsonElement?): GradientSpec? {
        val obj = element as? JsonObject ?: return null
        val stops = (obj["stops"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .sortedBy { it.number("position") ?: 0f }
            .mapNotNull { colour(it["color"]) }
        if (stops.size < 2) return null
        return GradientSpec(
            colors = stops,
            type = when (obj.string("type")) {
                "radial" -> GradientType.RADIAL
                "angular" -> GradientType.SWEEP
                else -> GradientType.LINEAR
            },
            angleDeg = obj.number("rotation") ?: DEFAULT_ANGLE,
        )
    }

    /** `{"r", "g", "b", "a"}` with 0..1 components, as ARGB. Alpha defaults to opaque. */
    private fun colour(element: JsonElement?): Long? {
        val obj = element as? JsonObject ?: return null
        fun channel(name: String, default: Float? = null): Long? {
            val value = obj.number(name) ?: default ?: return null
            return (value.coerceIn(0f, 1f) * CHANNEL_MAX).roundToInt().toLong()
        }
        val r = channel("r") ?: return null
        val g = channel("g") ?: return null
        val b = channel("b") ?: return null
        val a = channel("a", 1f) ?: return null
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun luminance(argb: Long): Float {
        val r = ((argb shr 16) and 0xFF) / CHANNEL_MAX
        val g = ((argb shr 8) and 0xFF) / CHANNEL_MAX
        val b = (argb and 0xFF) / CHANNEL_MAX
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun legibleOn(fill: Long): Long = if (luminance(fill) < HALF) 0xFFFFFFFF else 0xFF000000

    private fun JsonObject.isDark(): Boolean =
        bool("isDark") ?: (luminance(colour(this["background"]) ?: DEFAULT_BOARD) < HALF)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Float? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    /** A `groupPaint` group that stands for one key, and that key's override id here. */
    private val GROUP_KEYS = listOf("space" to "SPACE", "backspace" to "DELETE", "shift" to "SHIFT")

    /** Clink's function-key ids, as this keyboard's action names. */
    private val FUNCTION_KEYS = mapOf(
        "shift" to "SHIFT",
        "space" to "SPACE",
        "return" to "ENTER",
        "backspace" to "DELETE",
        "delete" to "DELETE",
        "planeToggle" to "SYMBOLS",
    )

    private val BOLD_WEIGHTS = setOf("semibold", "bold", "heavy", "black")

    /**
     * Clink's font designs are the iOS system faces. Each is matched with the
     * Google Font nearest to it in look, which the keyboard fetches the first
     * time it draws; the plain system face stays the keyboard's own.
     */
    private val FONT_FOR_DESIGN = mapOf(
        "serif" to "google:Noto Serif",
        "georgia" to "google:Gelasio",
        "monospaced" to "google:Roboto Mono",
        "typewriter" to "google:Special Elite",
        "rounded" to "google:Nunito",
        "marker" to "google:Permanent Marker",
        "script" to "google:Dancing Script",
        "chalkboard" to "google:Gochi Hand",
        "avenir" to "google:Nunito Sans",
        "futura" to "google:Jost",
        "copperplate" to "google:Cinzel",
    )

    private const val MATERIAL_SOLID = "solid"
    /** The see-through materials: their boards are mostly transparent, and frosted glass is what keeps them legible here. */
    private val GLASS_MATERIALS = setOf("liquidGlass", "liquidIce")
    private const val STROKE_NONE = "none"
    private const val STROKE_SOLID = "solid"
    private const val FALLBACK_NAME = "Clink"
    private const val BOM = "﻿"

    private const val DEFAULT_BOARD = 0xFF17181CL
    private const val DEFAULT_KEY = 0xFF303338L
    private const val DEFAULT_ACCENT = 0xFF8AB4F8L
    private const val DEFAULT_ANGLE = 90f
    private const val CHANNEL_MAX = 255f
    private const val HALF = 0.5f
    private const val MIN_BORDER_DP = 0.5f
    private const val MAX_BORDER_DP = 4f
    private const val MAX_ELEVATION_DP = 8f
}

/** What reading a Clink theme produced. */
sealed interface ClinkThemeResult {

    /**
     * The theme, with its id still to be set. A theme that switched with the
     * phone's light and dark mode heads a family whose one variant is the
     * light half; store it with [withFreshIds] so the variant gets an id too.
     */
    data class Converted(
        val theme: ThemeSpec,
        /** What the theme asked for that did not come across whole. */
        val dropped: List<ClinkUnsupported> = emptyList(),
    ) : ClinkThemeResult

    /** Not a Clink theme. */
    data object NotATheme : ClinkThemeResult
}

/**
 * Something a Clink theme asked for that did not come across whole, with the
 * line that says so. The lines live here rather than in the app because the
 * repository installer reports them too.
 */
enum class ClinkUnsupported(@get:StringRes val messageRes: Int) {
    /** Metal, cloth, ceramic, 3D caps, foil and the rest: drawn here as flat colours. */
    MATERIAL(R.string.core_theme_clink_dropped_material),

    /** An animated background (bokeh, nebula, embers), which this keyboard does not draw. */
    BACKGROUND_EFFECT(R.string.core_theme_clink_dropped_effect),

    /** A background or key picture, which a Clink theme file never carries. */
    PICTURES(R.string.core_theme_clink_dropped_pictures),

    /** A dashed or dotted key outline, drawn here as a solid line. */
    STROKE_STYLE(R.string.core_theme_clink_dropped_stroke),
}
