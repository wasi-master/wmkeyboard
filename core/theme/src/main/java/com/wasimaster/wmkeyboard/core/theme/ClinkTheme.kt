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
 * | (no popup fields) | the preview bubble in the letter keys' colours and outline, rising from the key as Clink's callout does |
 *
 * A theme with `pairedVariants` switches with the phone's light and dark mode
 * in Clink. That comes across as a family of two looks, dark first, the way the
 * other converted families here are laid out. The theme's own top-level fields
 * repeat one of the two, so they are only read when the pair is incomplete.
 *
 * | `material` (metal, stitched, 3d, wood, neon…) | the key material of the same name |
 * | `backgroundEffect` | the board effect of the same name |
 *
 * Clink draws most of its themes with a material: brushed metal, stitched
 * cloth, ceramic glaze, a 3D key cap, holographic foil. Each one lands on the
 * [KeyMaterialKind] preset that draws it, with Clink's variant where it has
 * one, and the moving backgrounds on the [BoardEffectKind] of the same name.
 * A material or an effect a later Clink adds, and pictures, are reported in
 * [ClinkUnsupported] instead.
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

    /**
     * True when [text] is the wrapper Clink's own Export writes since its
     * portable packages: `{"clinkPackage": 1, "kind": …, "payload": …}`, the
     * theme inside encrypted. Clink's code calls that "casual-copy protection",
     * so this app does not open it. It is recognised only to say so, rather
     * than calling the file unreadable. The plain `.clinktheme` files Clink's
     * GitHub repositories publish are still read.
     */
    fun looksLikeProtectedPackage(text: String): Boolean {
        val head = text.trimStart().removePrefix(BOM).trimStart()
        return head.startsWith("{") && head.contains("\"clinkPackage\"") && head.contains("\"payload\"")
    }

    /** Converts [text]. Never throws. */
    fun read(text: String): ClinkThemeResult {
        if (looksLikeProtectedPackage(text)) return ClinkThemeResult.ProtectedPackage
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
        val keyMaterial = material(obj, material, glass)
        if (material != null && material != MATERIAL_SOLID && !glass && keyMaterial == null) {
            dropped += ClinkUnsupported.MATERIAL
        }
        // Rain on a terminal or a monospaced theme is the digital kind.
        val codeLike = material == MATERIAL_TERMINAL || obj.string("keyFontDesign") == "monospaced"
        val effect = (obj["backgroundEffect"] as? JsonObject)?.let { boardEffect(it, accent, keyFill, codeLike) }
        if (obj.containsKey("backgroundEffect") && effect == null) dropped += ClinkUnsupported.BACKGROUND_EFFECT
        if (obj.containsKey("backgroundImageID") || obj.containsKey("keyImageID")) dropped += ClinkUnsupported.PICTURES

        val keyShape = when (obj.string("keyCapShape")) {
            "capsule" -> KeyShapeKind.PILL
            "rect" -> KeyShapeKind.SHARP
            "circle" -> KeyShapeKind.CIRCLE
            else -> KeyShapeKind.ROUNDED
        }
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
            keyShape = keyShape,
            keyCornerRadiusDp = letters?.number("cornerRadius")?.roundToInt(),
            accent = accent,
            suggestionText = colour(obj["suggestionText"]),
            boldKeyLabels = weight?.let { it in BOLD_WEIGHTS },
            fontId = design?.let { FONT_FOR_DESIGN[it] },
            keyOverrides = overrides,
            frost = if (glass) FrostSpec() else null,
            // Clink's callout is the key itself grown upwards: the letter key's
            // colours and outline, rising out of the key rather than floating.
            popupBackground = colour(letters?.get("fill")) ?: keyFill,
            popupText = colour(letters?.get("text")) ?: keyText,
            popupPlacement = "key",
            popupShape = keyShape.name.takeIf { keyShape != KeyShapeKind.ROUNDED },
            popupBorderColor = borderColour,
            popupBorderWidthDp = borderWidth,
            keyMaterial = keyMaterial,
            boardEffect = effect,
        )
    }

    /**
     * Clink's material as a key material here, with its variant where Clink
     * has one; null for a plain key, and for a material this build has no
     * preset for. A plain key whose inner shadow is on still gets that shadow,
     * as a pressed-in top edge.
     */
    private fun material(obj: JsonObject, material: String?, glass: Boolean): KeyMaterialSpec? {
        val innerShadow = (obj["keyInnerShadow"] as? JsonObject)?.bool("isOn") == true
        if (glass) return null
        if (material == null || material == MATERIAL_SOLID) {
            return if (innerShadow) KeyMaterialSpec(kind = KeyMaterialKind.CUSTOM.name, edge = MaterialEdge.INSET.name) else null
        }
        val kind = MATERIAL_KINDS[material] ?: return null
        val spec = KeyMaterialSpec(kind = kind.name)
        return when (material) {
            MATERIAL_3D -> when (obj.string("threeDVariant")) {
                "mechanical" -> spec.copy(bevelDp = 3.5f, topInsetDp = 2.5f)
                "modern" -> spec.copy(bevelDp = 2f, topInsetDp = 1f, gloss = 0.12f)
                "brick" -> spec.copy(texture = MaterialTexture.STONE.name, textureOpacity = 0.35f)
                "berry" -> KeyMaterialSpec(kind = KeyMaterialKind.CANDY.name)
                // "retro", Clink's default 3D cap: deep and chunky.
                else -> spec.copy(bevelDp = obj.number("retroDepth")?.coerceIn(2f, 8f) ?: 4f, topInsetDp = 2f)
            }
            MATERIAL_CARTOON -> if (obj.string("cartoonVariant") == "flat") spec.copy(gloss = 0f) else spec
            else -> spec
        }
    }

    /**
     * Clink's moving background as a board effect. Its colours name the
     * accent, the key fill, or a colour of their own, and the second one is
     * optional; its reaction strength runs past one, which here is the most
     * typing can do.
     */
    private fun boardEffect(effect: JsonObject, accent: Long, keyFill: Long, codeLike: Boolean): BoardEffectSpec? {
        val style = effect.string("style") ?: return null
        val kind = when (style) {
            // The digital rain belongs to the terminal themes; elsewhere rain is weather.
            "rain" -> if (codeLike) BoardEffectKind.CODE_RAIN else BoardEffectKind.RAIN
            else -> EFFECT_KINDS[style]
        } ?: return null
        fun mode(name: String?, custom: JsonElement?): Long? = when (name) {
            "accent" -> accent
            "keyFill" -> keyFill
            "custom" -> colour(custom)
            else -> null
        }
        val colors = listOfNotNull(
            mode(effect.string("colorMode"), effect["customColor"]) ?: accent,
            mode(effect.string("secondaryColorMode"), effect["customColor2"]),
        ).distinct()
        val reactive = if (effect.bool("reactive") == true) (effect.number("reactionStrength") ?: 1f) else 0f
        return BoardEffectSpec(
            kind = kind.name,
            colors = colors,
            density = (effect.number("density") ?: 0.5f).coerceIn(BOARD_EFFECT_DENSITY_RANGE),
            speed = (effect.number("speed") ?: 1f).coerceIn(BOARD_EFFECT_SPEED_RANGE),
            intensity = (effect.number("intensity") ?: 0.6f).coerceIn(BOARD_EFFECT_INTENSITY_RANGE),
            scale = (effect.number("scale") ?: 1f).coerceIn(BOARD_EFFECT_SCALE_RANGE),
            reactive = reactive.coerceIn(BOARD_EFFECT_REACTIVE_RANGE),
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
    private const val MATERIAL_3D = "3d"
    private const val MATERIAL_CARTOON = "cartoon"
    private const val MATERIAL_TERMINAL = "terminal"

    /** Clink's materials, by the preset here that draws each. */
    private val MATERIAL_KINDS = mapOf(
        "metal" to KeyMaterialKind.METAL,
        "stitched" to KeyMaterialKind.STITCHED,
        "instrument" to KeyMaterialKind.INSTRUMENT,
        "holo" to KeyMaterialKind.HOLO,
        MATERIAL_TERMINAL to KeyMaterialKind.TERMINAL,
        "ceramic" to KeyMaterialKind.CERAMIC,
        "stone" to KeyMaterialKind.STONE,
        MATERIAL_3D to KeyMaterialKind.CAP,
        "classic" to KeyMaterialKind.CAP,
        MATERIAL_CARTOON to KeyMaterialKind.CARTOON,
        "resin" to KeyMaterialKind.RESIN,
        "letterpress" to KeyMaterialKind.LETTERPRESS,
        "artGlass" to KeyMaterialKind.GLASS,
        "candy" to KeyMaterialKind.CANDY,
        "paperCraft" to KeyMaterialKind.PAPER,
        "typewriter" to KeyMaterialKind.TYPEWRITER,
        "wood" to KeyMaterialKind.WOOD,
        "arcade" to KeyMaterialKind.ARCADE,
        "gamepad" to KeyMaterialKind.ARCADE,
        "neon" to KeyMaterialKind.NEON,
        "molten" to KeyMaterialKind.MOLTEN,
    )

    /** Clink's background effects by name; `rain` is decided by the material. */
    private val EFFECT_KINDS = mapOf(
        "bokeh" to BoardEffectKind.BOKEH,
        "nebula" to BoardEffectKind.NEBULA,
        "embers" to BoardEffectKind.EMBERS,
        "lava" to BoardEffectKind.LAVA,
        "confetti" to BoardEffectKind.CONFETTI,
        "starfield" to BoardEffectKind.STARFIELD,
        "fireworks" to BoardEffectKind.FIREWORKS,
        "snow" to BoardEffectKind.SNOW,
        "bubbles" to BoardEffectKind.BUBBLES,
        "warpSpeed" to BoardEffectKind.WARP,
        "aurora" to BoardEffectKind.AURORA,
        "fireflies" to BoardEffectKind.FIREFLIES,
        "waves" to BoardEffectKind.WAVES,
    )
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

    /** One of Clink's encrypted export packages; see [ClinkTheme.looksLikeProtectedPackage]. */
    data object ProtectedPackage : ClinkThemeResult
}

/**
 * Something a Clink theme asked for that did not come across whole, with the
 * line that says so. The lines live here rather than in the app because the
 * repository installer reports them too.
 */
enum class ClinkUnsupported(@get:StringRes val messageRes: Int) {
    /** A material newer than this build's presets: drawn here as flat colours. */
    MATERIAL(R.string.core_theme_clink_dropped_material),

    /** An animated background newer than this build's board effects: drawn still. */
    BACKGROUND_EFFECT(R.string.core_theme_clink_dropped_effect),

    /** A background or key picture, which a Clink theme file never carries. */
    PICTURES(R.string.core_theme_clink_dropped_pictures),

    /** A dashed or dotted key outline, drawn here as a solid line. */
    STROKE_STYLE(R.string.core_theme_clink_dropped_stroke),
}
