package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The wallpaper's colours, as the two shapes a theme can follow them in.
 *
 * [roles] is the Material You scheme by role name, which is what a FlorisBoard
 * stylesheet names (`dynamic-dark-color(primary)`). [tones] is the five tonal
 * palettes the scheme itself is cut from, which is what a theme that names no
 * role at all is mapped onto.
 *
 * Read off the device by `deviceWallpaperPalette`; handed in rather than read
 * here so that the mapping stays plain arithmetic a unit test can run.
 */
data class WallpaperPalette(
    val roles: SnyggPalette,
    val tones: WallpaperTones,
)

/**
 * Android's five system tonal palettes (`system_accent1..3`, `system_neutral1..2`),
 * each [TONES].size colours long, in [TONES] order: white first, black last.
 */
data class WallpaperTones(
    val primary: List<Long>,
    val secondary: List<Long>,
    val tertiary: List<Long>,
    val neutral: List<Long>,
    val neutralVariant: List<Long>,
) {
    companion object {
        /**
         * The tone of each shade Android publishes, matching the `_0`, `_10`,
         * `_50`, `_100` … `_1000` suffixes of the system colour resources.
         */
        val TONES = listOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0)

        /**
         * A shade of one of the palettes at any [tone] from 0 to 100. Android
         * publishes thirteen, so a tone between two is mixed from them.
         */
        fun shade(palette: List<Long>, tone: Int): Long {
            val wanted = tone.coerceIn(0, 100)
            val upper = TONES.indexOfLast { it >= wanted }
            val lower = (upper + 1).coerceAtMost(TONES.lastIndex)
            val span = TONES[upper] - TONES[lower]
            val share = if (span == 0) 0f else (TONES[upper] - wanted).toFloat() / span
            fun channel(shift: Int): Long {
                val from = (palette[upper] shr shift) and 0xFF
                val to = (palette[lower] shr shift) and 0xFF
                return (from + (to - from) * share).roundToInt().toLong() shl shift
            }
            return 0xFF000000L or channel(16) or channel(8) or channel(0)
        }
    }

    /** The palettes in the order a picker offers them. */
    val palettes: List<List<Long>> get() = listOf(primary, secondary, tertiary, neutral, neutralVariant)

    /** The palette, by its place in [palettes], and the tone closest to [argb]. */
    fun nearestShade(argb: Long): Pair<Int, Int> {
        var best = 0 to 0
        var bestDistance = Long.MAX_VALUE
        palettes.forEachIndexed { index, palette ->
            for (tone in 0..100) {
                val shade = shade(palette, tone)
                var distance = 0L
                for (shift in intArrayOf(16, 8, 0)) {
                    val gap = ((shade shr shift) and 0xFF) - ((argb shr shift) and 0xFF)
                    distance += gap * gap
                }
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index to tone
                }
            }
        }
        return best
    }
}

/**
 * This theme with its colours moved onto the wallpaper's, when it asks for that
 * ([ThemeSpec.followWallpaper]) and the device has a wallpaper palette.
 *
 * Two ways, depending on what the theme knows about itself:
 *
 * - **It names roles** ([ThemeSpec.wallpaperRoles], from a FlorisBoard import).
 *   Every colour still equal to the value a role had at import takes that
 *   role's value now. A colour the user has since changed in the editor no
 *   longer matches and stays where they put it, and a colour the stylesheet
 *   gave as a fixed value was never a role and stays too. That is the theme
 *   FlorisBoard would draw.
 * - **It names none** (every other theme: built-in copies, custom, Gboard,
 *   HeliBoard). Each colour keeps its own lightness and takes the wallpaper
 *   palette that fits how colourful it is: greys go to the neutral palette,
 *   tinted greys to the neutral variant, colours to the primary one (or the
 *   tertiary one, when they sit on the far side of the colour wheel from the
 *   theme's accent). Lightness is what contrast is made of, so a theme that
 *   was legible stays legible, and black and white stay black and white.
 *
 * Alpha is always kept: a see-through key stays exactly as see-through.
 *
 * Returns this same instance when there is nothing to do, so a caller that
 * remembers on the result recomposes nothing extra for themes that do not
 * follow.
 */
fun ThemeSpec.followingWallpaper(palette: WallpaperPalette?): ThemeSpec {
    if (!followWallpaper || palette == null) return this
    val moved = recoloredBy(palette)
    // A glass theme may keep its board out of the accent: the board keeps its
    // own lightness and takes only the wallpaper's hue, as every theme without
    // roles does, while the keys still follow their roles. That is the quieter
    // board Frost had before it named roles, which some prefer to FrostKeys'
    // saturated one.
    if (frost?.accentBoard == false && wallpaperRoles.isNotEmpty()) {
        val toned = copy(wallpaperRoles = emptyMap()).recoloredBy(palette)
        return moved.copy(boardBackground = toned.boardBackground, boardGradient = toned.boardGradient)
    }
    return moved
}

/**
 * This theme moved onto [palette] whether or not it follows the wallpaper: the
 * same two mappings [followingWallpaper] uses, for a palette that came from
 * somewhere else. The app being typed into is the other source (see
 * [appPalette]); its colours replace the wallpaper's for as long as the
 * keyboard is in that app.
 */
fun ThemeSpec.recoloredBy(palette: WallpaperPalette): ThemeSpec {
    val remap: (Long) -> Long = if (wallpaperRoles.isNotEmpty()) {
        roleRemap(wallpaperRoles, palette.roles)
    } else {
        toneRemap(palette.tones, anchorHue())
    }
    return mapColors(remap)
}

/**
 * The key [ThemeSpec.wallpaperRoles] uses for one role in one scheme:
 * `dark:surfacecontainerlow`. The role is normalized the way [SnyggPalette]
 * normalizes it, so the key survives any spelling a stylesheet used.
 */
fun wallpaperRoleKey(dark: Boolean, role: String): String =
    (if (dark) DARK_PREFIX else LIGHT_PREFIX) + SnyggPalette.normalizeRole(role)

private const val DARK_PREFIX = "dark:"
private const val LIGHT_PREFIX = "light:"

/**
 * Old colour to new, one entry per distinct import-time value.
 *
 * Two roles can share a value at import (`surfaceTint` is `primary`, and in the
 * dark scheme `surfaceDim` is `surface`). The first role recorded wins, which
 * is the one the stylesheet named first; roles that were equal once almost
 * always still are.
 */
private fun roleRemap(recorded: Map<String, Long>, live: SnyggPalette): (Long) -> Long {
    val moves = HashMap<Long, Long>()
    for ((key, old) in recorded) {
        val dark = key.startsWith(DARK_PREFIX)
        val role = key.substringAfter(':')
        val now = live.resolve(dark, role) ?: continue
        moves.putIfAbsent(old and RGB, now and RGB)
    }
    return { color -> moves[color and RGB]?.let { (color and ALPHA) or it } ?: color }
}

/** The hue the theme is built around, or null for a theme with no colour in it. */
private fun ThemeSpec.anchorHue(): Float? =
    listOf(accent, enterKeyBackground)
        .map { Lab.of(it) }
        .firstOrNull { it.chroma >= CHROMATIC }
        ?.hue

private fun toneRemap(tones: WallpaperTones, anchorHue: Float?): (Long) -> Long {
    val shades = paletteShades(tones)
    return remap@{ color ->
        // Fully transparent draws nothing, and the black it usually carries is
        // not a colour anybody chose.
        if (color and ALPHA == 0L) return@remap color
        // Already one of the wallpaper's own shades: picked off the colour
        // picker's Palette tab, which shows exactly these. Moving it again by
        // chroma and lightness put a different palette or tone on the keys
        // from the swatch the user chose.
        if ((color and RGB) in shades) return@remap color
        toneMoved(tones, anchorHue, color)
    }
}

/**
 * Every shade the colour picker's Palette tab can offer from [tones]: each of
 * the five palettes at each whole tone, as [WallpaperTones.shade] mixes them.
 */
private fun paletteShades(tones: WallpaperTones): Set<Long> {
    val shades = HashSet<Long>()
    for (palette in tones.palettes) {
        if (palette.size != WallpaperTones.TONES.size) continue
        for (tone in 0..100) shades += WallpaperTones.shade(palette, tone) and RGB
    }
    return shades
}

private fun toneMoved(tones: WallpaperTones, anchorHue: Float?, color: Long): Long {
    val lab = Lab.of(color)
    val palette = when {
        lab.chroma < NEUTRAL -> tones.neutral
        lab.chroma < NEUTRAL_VARIANT -> tones.neutralVariant
        lab.chroma < CHROMATIC -> tones.secondary
        anchorHue != null && hueDistance(lab.hue, anchorHue) > FAR_HUE -> tones.tertiary
        else -> tones.primary
    }
    return (color and ALPHA) or (atTone(palette, lab.l) and RGB)
}

/** The palette's colour at [tone], between the two published shades around it. */
private fun atTone(palette: List<Long>, tone: Float): Long {
    val tones = WallpaperTones.TONES
    if (palette.size != tones.size) return palette.firstOrNull() ?: 0L
    val t = tone.coerceIn(0f, 100f)
    for (i in 0 until tones.lastIndex) {
        val upper = tones[i].toFloat()
        val lower = tones[i + 1].toFloat()
        if (t <= upper && t >= lower) {
            val fraction = if (upper == lower) 0f else (upper - t) / (upper - lower)
            return lerpRgb(palette[i], palette[i + 1], fraction)
        }
    }
    return palette.last()
}

private fun lerpRgb(a: Long, b: Long, t: Float): Long {
    fun channel(shift: Int): Long {
        val from = (a ushr shift) and 0xFF
        val to = (b ushr shift) and 0xFF
        return (from + (to - from) * t).roundToInt().toLong().coerceIn(0L, 255L)
    }
    return ALPHA or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** CIELAB, D65. `l` is Material's "tone", which is why it is the one kept. */
internal data class Lab(val l: Float, val a: Float, val b: Float) {
    val chroma: Float get() = sqrt(a * a + b * b)
    val hue: Float
        get() = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat().let { if (it < 0f) it + 360f else it }

    companion object {
        fun of(argb: Long): Lab {
            fun linear(shift: Int): Double {
                val c = ((argb ushr shift) and 0xFF) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            val r = linear(16)
            val g = linear(8)
            val bl = linear(0)
            val x = (0.4124 * r + 0.3576 * g + 0.1805 * bl) / 0.95047
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * bl
            val z = (0.0193 * r + 0.1192 * g + 0.9505 * bl) / 1.08883
            fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
            val fx = f(x)
            val fy = f(y)
            val fz = f(z)
            return Lab(
                l = (116.0 * fy - 16.0).toFloat(),
                a = (500.0 * (fx - fy)).toFloat(),
                b = (200.0 * (fy - fz)).toFloat(),
            )
        }
    }
}

/** Below this chroma a colour reads as grey. */
private const val NEUTRAL = 6f

/** Below this, as a grey with a tint in it. */
private const val NEUTRAL_VARIANT = 14f

/** At or above this, as a colour in its own right; between, as a muted one. */
private const val CHROMATIC = 28f

/** How far round the wheel from the accent a colour has to be to count as a contrast colour. */
private const val FAR_HUE = 90f

private const val ALPHA = 0xFF000000L
private const val RGB = 0x00FFFFFFL

private val walkJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Every colour in the theme through [transform], found by type rather than by
 * a list of field names.
 *
 * All of [ThemeSpec]'s colours are ARGB longs, and nothing else in it is a
 * long, so the serial descriptor already knows where every colour is: the
 * board, the keys, each gradient stop, every per-key override. A hand-written
 * list would have to be kept in step with a class that gains a colour field
 * every few weeks, and a field it missed would sit still on a theme that
 * otherwise followed the wallpaper, with nothing to say why.
 *
 * [ThemeSpec.wallpaperRoles] is skipped because it is the record of the old
 * colours, not a colour on screen, and [ThemeSpec.variants] because each look
 * is resolved on its own when it is the one shown.
 */
private fun ThemeSpec.mapColors(transform: (Long) -> Long): ThemeSpec {
    val serializer = ThemeSpec.serializer()
    val tree = walkJson.encodeToJsonElement(serializer, this)
    val mapped = walk(tree, serializer.descriptor, transform, top = true)
    return runCatching { walkJson.decodeFromJsonElement(serializer, mapped) }.getOrDefault(this)
}

@OptIn(ExperimentalSerializationApi::class)
private fun walk(
    element: JsonElement,
    descriptor: SerialDescriptor,
    transform: (Long) -> Long,
    top: Boolean = false,
): JsonElement = when (descriptor.kind) {
    PrimitiveKind.LONG -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        ?.let { JsonPrimitive(transform(it)) }
        ?: element

    StructureKind.CLASS -> {
        val obj = element as? JsonObject
        if (obj == null) {
            element
        } else {
            JsonObject(
                obj.mapValues { (name, value) ->
                    val index = descriptor.getElementIndex(name)
                    when {
                        index < 0 -> value
                        top && name in SKIPPED -> value
                        else -> walk(value, descriptor.getElementDescriptor(index), transform)
                    }
                },
            )
        }
    }

    StructureKind.LIST -> (element as? JsonArray)
        ?.let { array -> JsonArray(array.map { walk(it, descriptor.getElementDescriptor(0), transform) }) }
        ?: element

    StructureKind.MAP -> (element as? JsonObject)
        ?.let { obj -> JsonObject(obj.mapValues { walk(it.value, descriptor.getElementDescriptor(1), transform) }) }
        ?: element

    else -> element
}

private val SKIPPED = setOf("wallpaperRoles", "variants")
