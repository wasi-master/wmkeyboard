package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlin.math.roundToInt

/**
 * A theme guessed from JSON in no format this app knows: the files a chat
 * assistant writes when asked for "a keyboard theme", and the one-off schemas
 * of keyboards with no reader here.
 *
 * Such files have no schema, but they are written for people to read, so the
 * names carry the meaning: `background`, `keyText`, `keys.enter.background`,
 * `func`, `accent`. Every value that reads as a colour is filed under a *part*
 * of the keyboard (the board, the keys, the function keys, enter, space, the
 * popup, the chips, the toolbar) and a *slot* of that part (its fill, its text,
 * its border, its glow), from its own name and the names above it. A compound
 * name (`keyTextColor`, `specialKeyFill`) is split into the two.
 *
 * Where the same part and slot turn up more than once, the copy outside a
 * light/dark sub-object wins, then the deeper (more specific) one, then the
 * later one, which is the order such files are written in: general colours
 * first, a skin refining them after.
 *
 * Beside the colours it reads what this app has an answer for: gradients (an
 * object with a list of colours, or a list of colours alone), a pill or
 * rounded key, corner radii, a key opacity, a glass material, a jelly press,
 * placed colour pools (`orbs`, `blobs`), a glow on single keys, and a shadow.
 *
 * Only reached once every real format has turned the text down, and it still
 * wants a board colour *and* a key colour by name before it calls the text a
 * theme, so a JSON file of some other kind is not mistaken for one.
 */
object LooseTheme {

    private val json = Json { isLenient = true }

    /** [text] as a theme, or null when it does not name enough colours to be one. Never throws. */
    fun read(text: String, fallbackName: String = FALLBACK_NAME): ThemeSpec? {
        val root = runCatching {
            json.parseToJsonElement(text.trimStart().removePrefix(BOM).trimStart())
        }.getOrNull() as? JsonObject ?: return null
        val facts = Facts()
        walk(root, emptyList(), facts, 0)
        return facts.toTheme(root, fallbackName)
    }

    // ---- walking ----------------------------------------------------------

    private class Hit<T>(val value: T, val path: List<String>, val order: Int) {
        /** Main look over a light/dark copy, then the more specific, then the later. */
        fun beats(other: Hit<*>): Boolean {
            val mine = path.none { it in VARIANT_WORDS }
            val theirs = other.path.none { it in VARIANT_WORDS }
            if (mine != theirs) return mine
            if (path.size != other.path.size) return path.size > other.path.size
            return order > other.order
        }
    }

    private class Facts {
        var order = 0
        val colors = HashMap<Pair<Part, Slot>, Hit<Long>>()
        val gradients = HashMap<Part, Hit<GradientSpec>>()
        val shapes = HashMap<Part, Hit<KeyShapeKind>>()
        val radii = HashMap<Part, Hit<Int>>()
        var opacity: Hit<Float>? = null
        var shadow: Hit<Float>? = null
        var material: Hit<KeyMaterialKind>? = null
        var press: Hit<KeyPressMotion>? = null
        var orbs: Hit<List<Pair<Long, OrbSpot?>>>? = null
        var dark: Boolean? = null
        var glowDp: Hit<Float>? = null

        fun <T> put(map: MutableMap<Part, Hit<T>>, part: Part, hit: Hit<T>) {
            val old = map[part]
            if (old == null || hit.beats(old)) map[part] = hit
        }

        fun color(part: Part, slot: Slot, value: Long, path: List<String>) {
            val key = part to slot
            val hit = Hit(value, path, order++)
            val old = colors[key]
            if (old == null || hit.beats(old)) colors[key] = hit
        }
    }

    private fun walk(element: JsonElement, path: List<String>, facts: Facts, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                // A colour written as an object of components is a leaf.
                componentColor(element)?.let { leaf(path, it, facts); return }
                gradientOf(element)?.let { gradient ->
                    val placed = place(path)
                    if (placed != null && placed.second == Slot.FILL) {
                        facts.put(facts.gradients, placed.first, Hit(gradient, path, facts.order++))
                        return
                    }
                }
                for ((name, child) in element) walk(child, path + normalise(name), facts, depth + 1)
            }
            is JsonArray -> {
                val last = path.lastOrNull()
                if (last != null && last in ORB_WORDS) {
                    orbsOf(element)?.let { facts.orbs = pick(facts.orbs, Hit(it, path, facts.order++)) }
                    return
                }
                // A bare list of colours under a fill name is a gradient.
                val stops = element.mapNotNull { (it as? JsonPrimitive)?.let(::colorOf) }
                if (stops.size >= 2 && stops.size == element.size) {
                    val placed = place(path)
                    if (placed != null && placed.second == Slot.FILL) {
                        val gradient = GradientSpec(stops.take(MAX_STOPS), GradientType.LINEAR, 90f)
                        facts.put(facts.gradients, placed.first, Hit(gradient, path, facts.order++))
                    }
                    return
                }
                element.forEachIndexed { i, child -> walk(child, path + "#$i", facts, depth + 1) }
            }
            is JsonPrimitive -> primitive(element, path, facts)
        }
    }

    private fun <T> pick(old: Hit<T>?, new: Hit<T>): Hit<T> = if (old == null || new.beats(old)) new else old

    private fun primitive(value: JsonPrimitive, path: List<String>, facts: Facts) {
        val last = path.lastOrNull() ?: return
        if (value.isString) {
            colorOf(value)?.let { leaf(path, it, facts); return }
            val word = normalise(value.content)
            when {
                last in MODE_WORDS -> when (word) {
                    in DARK_WORDS -> facts.dark = facts.dark ?: true
                    in LIGHT_WORDS -> facts.dark = facts.dark ?: false
                }
                last in SHAPE_WORDS -> shapeOf(word)?.let { shape ->
                    containerPart(path.dropLast(1))?.let { facts.put(facts.shapes, it, Hit(shape, path, facts.order++)) }
                }
                last in MATERIAL_WORDS -> materialOf(word)?.let { facts.material = pick(facts.material, Hit(it, path, facts.order++)) }
                last in PRESS_WORDS -> pressOf(word)?.let { facts.press = pick(facts.press, Hit(it, path, facts.order++)) }
            }
            return
        }
        value.booleanOrNull?.let { on ->
            if (last == "dark" || last == "isdark" || last == "darkmode") facts.dark = facts.dark ?: on
            return
        }
        val number = value.floatOrNull?.takeIf { it.isFinite() } ?: return
        // A compound name ("keyCornerRadius", "popupRadius") names its part itself.
        val prefixed = PART_PREFIXES.firstOrNull { (prefix, _) ->
            last.startsWith(prefix) && last.length > prefix.length && last.removePrefix(prefix) in RADIUS_WORDS
        }
        if (prefixed != null) {
            facts.put(facts.radii, prefixed.second, Hit(number.roundToInt(), path, facts.order++))
            return
        }
        val part = containerPart(path.dropLast(1))
        when (last) {
            in RADIUS_WORDS -> if (part != null) facts.put(facts.radii, part, Hit(number.roundToInt(), path, facts.order++))
            "keyopacity", "keyalpha" -> facts.opacity = pick(facts.opacity, Hit(number, path, facts.order++))
            "opacity", "alpha" -> if (part == Part.KEY) facts.opacity = pick(facts.opacity, Hit(number, path, facts.order++))
            "elevation", "shadowy", "shadowoffset", "shadowoffsety" ->
                if (part == Part.KEY) facts.shadow = pick(facts.shadow, Hit(number, path, facts.order++))
            "shadow", "shadowradius", "shadowblur" ->
                if (part == Part.KEY && facts.shadow == null) facts.shadow = Hit(number / 3f, path, facts.order++)
            "glowradius", "glowsize", "glowdp" -> facts.glowDp = pick(facts.glowDp, Hit(number, path, facts.order++))
        }
    }

    private fun leaf(path: List<String>, color: Long, facts: Facts) {
        val (part, slot) = place(path) ?: return
        facts.color(part, slot, color, path)
    }

    // ---- naming ----------------------------------------------------------

    /**
     * Which part of the keyboard, and which slot of it, [path] names; null
     * when it names neither, or sits under a container this reader has no
     * part for (a `panel`, a `status` bar), whose colours would otherwise
     * land on the board.
     */
    private fun place(path: List<String>): Pair<Part, Slot>? {
        val last = path.lastOrNull() ?: return null
        // A whole-name role first: accent, trail, hint.
        STANDALONE[last]?.let { return it }
        // A compound name: "keytext", "specialkeyfill", "enterbackground".
        split(last)?.let { (part, slot) -> return part to slot }
        PARTS[last]?.let { return it to Slot.FILL }
        val slot = SLOTS[last] ?: return null
        return containerPart(path.dropLast(1))?.let { it to slot }
    }

    /**
     * The part the object at [ancestors] belongs to: the nearest named part
     * above, or the board when every name above is a plain wrapper (`colors`,
     * `theme`, `skin`, a light or dark copy). Null under anything else.
     */
    private fun containerPart(ancestors: List<String>): Part? {
        ancestors.asReversed().firstNotNullOfOrNull { segment -> PARTS[segment] ?: split(segment)?.first }
            ?.let { return it }
        val plain = ancestors.all { it in NEUTRAL_WORDS || it in VARIANT_WORDS || it.startsWith("#") }
        return if (plain) Part.BOARD else null
    }

    private fun split(name: String): Pair<Part, Slot>? {
        for ((prefix, part) in PART_PREFIXES) {
            if (!name.startsWith(prefix) || name.length == prefix.length) continue
            SLOTS[name.removePrefix(prefix)]?.let { return part to it }
        }
        return null
    }

    private fun normalise(name: String): String =
        if (name == "*") "*" else name.lowercase().filter { it.isLetterOrDigit() || it == '#' }

    // ---- values ----------------------------------------------------------

    private fun colorOf(value: JsonPrimitive): Long? {
        if (!value.isString) return null
        return parseColor(value.content.trim())
    }

    /** `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB`, `0x…`, `rgb()` and `rgba()`, as ARGB. */
    internal fun parseColor(text: String): Long? {
        val hex = when {
            text.startsWith("#") -> text.drop(1)
            text.startsWith("0x", ignoreCase = true) -> text.drop(2)
            else -> null
        }
        if (hex != null) {
            if (hex.any { Character.digit(it, 16) < 0 }) return null
            val full = when (hex.length) {
                3 -> "FF" + hex.map { "$it$it" }.joinToString("")
                4 -> hex.map { "$it$it" }.joinToString("")
                6 -> "FF$hex"
                8 -> hex
                else -> return null
            }
            return full.toLongOrNull(16)
        }
        val lower = text.lowercase()
        if (lower.startsWith("rgb")) {
            val inner = lower.substringAfter('(', "").substringBefore(')', "")
            val parts = inner.split(',', ' ', '/').filter { it.isNotBlank() }
            if (parts.size !in 3..4) return null
            fun channel(s: String): Int? =
                if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).roundToInt() } else s.toFloatOrNull()?.roundToInt()
            val (r, g, b) = parts.take(3).map { channel(it)?.coerceIn(0, 255) ?: return null }
            val a = parts.getOrNull(3)?.let { s ->
                val f = if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.div(100f) else s.toFloatOrNull()
                f?.let { if (it <= 1f) (it * 255).roundToInt() else it.roundToInt() }?.coerceIn(0, 255) ?: return null
            } ?: 255
            return (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
        }
        return null
    }

    /** `{"r","g","b","a"}` as 0..1 or 0..255 components. */
    private fun componentColor(obj: JsonObject): Long? {
        if (obj.size !in 3..4) return null
        fun c(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
        val r = c("r") ?: c("red") ?: return null
        val g = c("g") ?: c("green") ?: return null
        val b = c("b") ?: c("blue") ?: return null
        val a = c("a") ?: c("alpha") ?: 1f
        val unit = r <= 1f && g <= 1f && b <= 1f
        fun ch(v: Float) = (if (unit) v * 255f else v).roundToInt().coerceIn(0, 255).toLong()
        val alpha = (if (a <= 1f) a * 255f else a).roundToInt().coerceIn(0, 255).toLong()
        return (alpha shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private fun gradientOf(obj: JsonObject): GradientSpec? {
        val list = GRADIENT_LIST_WORDS.firstNotNullOfOrNull { word ->
            obj.entries.firstOrNull { normalise(it.key) == word }?.value as? JsonArray
        } ?: return null
        val stops = list.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> colorOf(item)
                is JsonObject -> componentColor(item)
                    ?: (item["color"] as? JsonPrimitive)?.let(::colorOf)
                    ?: (item["colour"] as? JsonPrimitive)?.let(::colorOf)
                else -> null
            }
        }
        if (stops.size < 2) return null
        val angle = obj.entries.firstOrNull { normalise(it.key) in ANGLE_WORDS }
            ?.let { (it.value as? JsonPrimitive)?.floatOrNull }
            ?.takeIf { it.isFinite() }
        val type = obj.entries.firstOrNull { normalise(it.key) == "type" }
            ?.let { (it.value as? JsonPrimitive)?.content?.lowercase() }
        return GradientSpec(
            colors = stops.take(MAX_STOPS),
            type = when {
                type == null -> GradientType.LINEAR
                "radial" in type -> GradientType.RADIAL
                "sweep" in type || "conic" in type -> GradientType.SWEEP
                else -> GradientType.LINEAR
            },
            angleDeg = angle ?: 90f,
        )
    }

    private fun orbsOf(array: JsonArray): List<Pair<Long, OrbSpot?>>? {
        val orbs = array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> colorOf(item)?.let { it to null }
                is JsonObject -> {
                    val color = item.entries.firstOrNull { normalise(it.key) in setOf("color", "colour", "fill") }
                        ?.let { (it.value as? JsonPrimitive)?.let(::colorOf) } ?: return@mapNotNull null
                    fun n(vararg names: String) = item.entries.firstOrNull { normalise(it.key) in names }
                        ?.let { (it.value as? JsonPrimitive)?.floatOrNull }?.takeIf { it.isFinite() }
                    val x = n("x", "cx", "left")
                    val y = n("y", "cy", "top")
                    val r = n("r", "radius", "size")
                    color to if (x != null && y != null) {
                        OrbSpot(
                            x = x.coerceIn(ORB_SPOT_POSITION_RANGE),
                            y = y.coerceIn(ORB_SPOT_POSITION_RANGE),
                            radius = (r ?: 0.3f).coerceIn(ORB_SPOT_RADIUS_RANGE),
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
        return orbs.takeIf { it.isNotEmpty() }?.take(MAX_BOARD_EFFECT_COLORS)
    }

    private fun shapeOf(word: String): KeyShapeKind? = when {
        "pill" in word || "capsule" in word || "stadium" in word -> KeyShapeKind.PILL
        "circle" in word || "round" == word -> KeyShapeKind.CIRCLE
        "squircle" in word -> KeyShapeKind.SQUIRCLE
        "sharp" in word || "square" in word || "rect" == word -> KeyShapeKind.SHARP
        "rounded" in word || "roundrect" in word -> KeyShapeKind.ROUNDED
        "hex" in word -> KeyShapeKind.HEXAGON
        "none" == word || "flat" == word -> null
        else -> KeyShapeKind.entries.firstOrNull { it.name.equals(word, ignoreCase = true) && it != KeyShapeKind.NONE }
    }

    private fun materialOf(word: String): KeyMaterialKind? = when {
        "glass" in word || "liquid" in word || "frost" in word || "acrylic" in word -> KeyMaterialKind.GLASS
        else -> KeyMaterialKind.entries.firstOrNull {
            it != KeyMaterialKind.CUSTOM && it.name.equals(word, ignoreCase = true)
        }
    }

    private fun pressOf(word: String): KeyPressMotion? = when {
        "jelly" in word || "squish" in word || "bounce" in word || "wobble" in word -> KeyPressMotion.JELLY
        else -> KeyPressMotion.entries.firstOrNull { it.name.equals(word, ignoreCase = true) }
    }

    // ---- building --------------------------------------------------------

    private fun Facts.toTheme(root: JsonObject, fallbackName: String): ThemeSpec? {
        fun c(part: Part, slot: Slot): Long? = colors[part to slot]?.value
        val boardGradient = gradients[Part.BOARD]?.value
        val board = c(Part.BOARD, Slot.FILL) ?: boardGradient?.colors?.first()
        val keyGradient = gradients[Part.KEY]?.value
        val key = c(Part.KEY, Slot.FILL) ?: keyGradient?.colors?.first()
        val keyText = c(Part.KEY, Slot.TEXT) ?: c(Part.BOARD, Slot.TEXT)
        // The evidence that this is a keyboard theme at all.
        if (board == null || (key == null && keyText == null)) return null

        val dark = dark ?: (luminance(board) < 0.5f)
        val accent = c(Part.ACCENT, Slot.FILL) ?: c(Part.ENTER, Slot.FILL) ?: gradients[Part.ENTER]?.value?.colors?.first()
            ?: if (dark) 0xFF8AB4F8 else 0xFF1A73E8
        val keyFill = (key ?: if (dark) 0xFF303338 else 0xFFFFFFFF).withOpacity(opacity?.value)
        val text = keyText ?: legibleOn(keyFill, board)
        val modifier = c(Part.FUNC, Slot.FILL) ?: gradients[Part.FUNC]?.value?.colors?.first()
        val enterGradient = gradients[Part.ENTER]?.value
        val enter = c(Part.ENTER, Slot.FILL) ?: enterGradient?.colors?.first() ?: accent
        val keyShape = shapes[Part.KEY]?.value ?: shapes[Part.BOARD]?.value

        val overrides = buildMap {
            for ((part, id) in OVERRIDE_PARTS) {
                val override = KeyOverride(
                    background = c(part, Slot.FILL)?.takeIf { part != Part.ENTER }?.withOpacity(opacity?.value),
                    text = c(part, Slot.TEXT)?.takeIf { part != Part.ENTER },
                    border = c(part, Slot.BORDER),
                    gradient = gradients[part]?.value,
                    glow = c(part, Slot.GLOW),
                    glowDp = c(part, Slot.GLOW)?.let { glowDp?.value?.coerceIn(KEY_GLOW_RANGE) },
                    shape = shapes[part]?.value?.takeIf { it != keyShape }?.name,
                    cornerRadiusDp = radii[part]?.value?.coerceIn(KEY_OVERRIDE_RADIUS_RANGE),
                )
                if (!override.isEmpty) put(id, override)
            }
        }
        val orbList = orbs?.value
        return ThemeSpec(
            id = "loose_${root.hashCode().toUInt()}",
            name = nameOf(root) ?: fallbackName,
            dark = dark,
            boardBackground = board,
            boardGradient = boardGradient,
            keyShape = keyShape ?: KeyShapeKind.ROUNDED,
            keyCornerRadiusDp = radii[Part.KEY]?.value?.coerceIn(KEY_OVERRIDE_RADIUS_RANGE),
            keyGradient = keyGradient,
            keyBackground = keyFill,
            keyText = text,
            modifierKeyBackground = (modifier ?: keyFill).withOpacity(opacity?.value),
            modifierKeyText = c(Part.FUNC, Slot.TEXT),
            hintText = c(Part.HINT, Slot.FILL),
            enterKeyBackground = enter,
            enterKeyText = c(Part.ENTER, Slot.TEXT) ?: c(Part.ACCENT, Slot.TEXT) ?: legibleOn(enter, enter),
            pressedKeyBackground = c(Part.KEY, Slot.PRESSED),
            keyBorderColor = c(Part.KEY, Slot.BORDER),
            keyBorderWidthDp = if (c(Part.KEY, Slot.BORDER) != null) 1f else 0f,
            keyElevationDp = shadow?.value?.coerceIn(0f, 8f) ?: 0f,
            keyShadowColor = c(Part.KEY, Slot.SHADOW),
            accent = accent,
            gestureTrailColor = c(Part.TRAIL, Slot.FILL),
            popupBackground = c(Part.POPUP, Slot.FILL)?.atLeastAlpha(0xCC),
            popupText = c(Part.POPUP, Slot.TEXT),
            popupBorderColor = c(Part.POPUP, Slot.BORDER),
            popupBorderWidthDp = if (c(Part.POPUP, Slot.BORDER) != null) 1f else 0f,
            popupSelectedBackground = c(Part.POPUP, Slot.PRESSED),
            popupCornerRadiusDp = radii[Part.POPUP]?.value?.coerceIn(KEY_OVERRIDE_RADIUS_RANGE),
            suggestionBarBackground = c(Part.SUGGESTION, Slot.FILL),
            suggestionText = c(Part.SUGGESTION, Slot.TEXT) ?: c(Part.CHIP, Slot.TEXT),
            toolbarIcon = c(Part.TOOL, Slot.TEXT),
            toolCircleBackground = c(Part.TOOL, Slot.FILL),
            toolCircleActiveBackground = c(Part.TOOL, Slot.PRESSED),
            toolBorderColor = c(Part.TOOL, Slot.BORDER),
            toolBorderWidthDp = if (c(Part.TOOL, Slot.BORDER) != null) 1f else 0f,
            chipBackground = c(Part.CHIP, Slot.FILL),
            chipText = c(Part.CHIP, Slot.TEXT),
            chipActiveBackground = c(Part.CHIP, Slot.PRESSED),
            chipBorderColor = c(Part.CHIP, Slot.BORDER),
            chipBorderWidthDp = if (c(Part.CHIP, Slot.BORDER) != null) 1f else 0f,
            keyOverrides = overrides,
            keyMaterial = material?.value?.let { KeyMaterialSpec(kind = it.name) },
            touchBloom = press?.value?.let { TouchBloomSpec(light = 0f, dip = 0.6f, wobble = 0.6f, motion = it.name) },
            boardEffect = orbList?.let { list ->
                BoardEffectSpec(
                    kind = BoardEffectKind.ORBS.name,
                    colors = list.map { it.first },
                    density = 0f,
                    speed = 0f,
                    intensity = 1f,
                    orbSpots = list.mapNotNull { it.second }.takeIf { it.size == list.size }.orEmpty(),
                )
            },
        )
    }

    /** The theme's own name: at the top, or one level down (`{"theme": {"name": …}}`). */
    private fun nameOf(root: JsonObject): String? {
        fun direct(obj: JsonObject): String? {
            for (word in NAME_WORDS) {
                val value = obj.entries.firstOrNull { normalise(it.key) == word }?.value as? JsonPrimitive
                value?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(MAX_NAME) }
            }
            return null
        }
        return direct(root) ?: root.values.firstNotNullOfOrNull { (it as? JsonObject)?.let(::direct) }
    }

    private fun Long.withOpacity(opacity: Float?): Long {
        if (opacity == null || !opacity.isFinite()) return this
        val fraction = (if (opacity > 1f) opacity / 100f else opacity).coerceIn(0f, 1f)
        val alpha = ((this ushr 24) and 0xFF) * fraction
        return (this and 0x00FFFFFF) or (alpha.roundToInt().toLong() shl 24)
    }

    /** A popup has to be read over the keys it covers, so it is never left mostly see-through. */
    private fun Long.atLeastAlpha(alpha: Int): Long =
        if (((this ushr 24) and 0xFF) >= alpha) this else (this and 0x00FFFFFF) or (alpha.toLong() shl 24)

    private fun luminance(argb: Long): Float {
        fun ch(shift: Int): Float {
            val v = ((argb ushr shift) and 0xFF) / 255f
            return if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * ch(16) + 0.7152f * ch(8) + 0.0722f * ch(0)
    }

    /** White or near-black, whichever reads on [fill] (over [under] where [fill] is see-through). */
    private fun legibleOn(fill: Long, under: Long): Long {
        val opaque = ((fill ushr 24) and 0xFF) > 0x80
        return if (luminance(if (opaque) fill else under) < 0.45f) 0xFFFFFFFF else 0xFF15171C
    }

    // ---- vocabulary ------------------------------------------------------

    private enum class Part { BOARD, KEY, FUNC, ENTER, SPACE, SHIFT, DELETE, POPUP, CHIP, TOOL, SUGGESTION, ACCENT, TRAIL, HINT }

    private enum class Slot { FILL, TEXT, BORDER, PRESSED, GLOW, SHADOW }

    private val PARTS: Map<String, Part> = buildMap {
        listOf("keyboard", "board", "window", "canvas").forEach { put(it, Part.BOARD) }
        listOf("key", "keys", "*", "letter", "letters", "alphakey", "alphakeys", "character", "characters", "regularkey")
            .forEach { put(it, Part.KEY) }
        listOf("func", "function", "functional", "modifier", "modifiers", "mod", "special", "specialkey", "specialkeys", "system", "control", "fn")
            .forEach { put(it, Part.FUNC) }
        listOf("enter", "return", "actionkey", "done", "send", "submit").forEach { put(it, Part.ENTER) }
        listOf("space", "spacebar", "spacekey").forEach { put(it, Part.SPACE) }
        listOf("shift", "shiftkey", "caps").forEach { put(it, Part.SHIFT) }
        listOf("backspace", "delete", "del", "deletekey", "backspacekey").forEach { put(it, Part.DELETE) }
        listOf("popup", "popups", "preview", "bubble", "callout", "longpress", "keypreview").forEach { put(it, Part.POPUP) }
        listOf("chip", "chips", "pill", "pills").forEach { put(it, Part.CHIP) }
        listOf("toolbar", "buttons", "button", "tool", "tools", "icons").forEach { put(it, Part.TOOL) }
        listOf("suggestion", "suggestions", "candidate", "candidates", "strip", "suggestionbar", "candidatebar")
            .forEach { put(it, Part.SUGGESTION) }
    }

    /** Prefixes a compound name can start with, longest first so `specialkey` wins over `special`. */
    private val PART_PREFIXES: List<Pair<String, Part>> = buildList {
        addAll(PARTS.entries.filter { it.key != "*" }.map { it.key to it.value })
        add("accent" to Part.ACCENT)
        add("primary" to Part.ACCENT)
        add("bg" to Part.BOARD)
    }.sortedByDescending { it.first.length }

    private val SLOTS: Map<String, Slot> = buildMap {
        listOf("background", "bg", "fill", "color", "colour", "backgroundcolor", "face", "gradient", "base")
            .forEach { put(it, Slot.FILL) }
        listOf("text", "textcolor", "label", "labelcolor", "foreground", "fg", "font", "fontcolor", "icon", "iconcolor", "content")
            .forEach { put(it, Slot.TEXT) }
        listOf("border", "bordercolor", "stroke", "strokecolor", "outline", "outlinecolor").forEach { put(it, Slot.BORDER) }
        listOf("pressed", "pressedbackground", "active", "activebackground", "selected", "selectedbackground", "highlight", "down")
            .forEach { put(it, Slot.PRESSED) }
        listOf("glow", "glowcolor", "halo").forEach { put(it, Slot.GLOW) }
        listOf("shadowcolor").forEach { put(it, Slot.SHADOW) }
    }

    private val STANDALONE: Map<String, Pair<Part, Slot>> = mapOf(
        "accent" to (Part.ACCENT to Slot.FILL),
        "accentcolor" to (Part.ACCENT to Slot.FILL),
        "primary" to (Part.ACCENT to Slot.FILL),
        "seed" to (Part.ACCENT to Slot.FILL),
        "accenttext" to (Part.ACCENT to Slot.TEXT),
        "onaccent" to (Part.ACCENT to Slot.TEXT),
        "onprimary" to (Part.ACCENT to Slot.TEXT),
        "trail" to (Part.TRAIL to Slot.FILL),
        "trailcolor" to (Part.TRAIL to Slot.FILL),
        "gesturetrail" to (Part.TRAIL to Slot.FILL),
        "swipetrail" to (Part.TRAIL to Slot.FILL),
        "glidetrail" to (Part.TRAIL to Slot.FILL),
        "hint" to (Part.HINT to Slot.FILL),
        "hintcolor" to (Part.HINT to Slot.FILL),
        "hinttext" to (Part.HINT to Slot.FILL),
    )

    /** The parts that are one key each, and the [ThemeSpec.keyOverrides] id each one styles. */
    private val OVERRIDE_PARTS = listOf(
        Part.ENTER to "ENTER",
        Part.SPACE to "SPACE",
        Part.SHIFT to "SHIFT",
        Part.DELETE to "DELETE",
    )

    private val VARIANT_WORDS = setOf("dark", "light", "night", "day", "darkmode", "lightmode", "variants", "variant")
    private val MODE_WORDS = setOf("mode", "theme", "appearance", "scheme", "colorscheme", "brightness", "variant")
    private val DARK_WORDS = setOf("dark", "night", "amoled", "black", "darkmode", "dim")
    private val LIGHT_WORDS = setOf("light", "day", "white", "lightmode", "bright")

    /** Wrappers whose colours are the board's when no part is named under them. */
    private val NEUTRAL_WORDS = setOf(
        "colors", "colours", "color", "colour", "palette", "theme", "skin", "style", "styles", "tokens",
        "variables", "vars", "scheme", "values", "appearance", "design", "look", "data", "config",
        "settings", "keyboardtheme", "wmtheme", "properties", "attributes",
    )
    private val SHAPE_WORDS = setOf("shape", "keyshape", "corners", "cornerstyle")
    private val MATERIAL_WORDS = setOf("material", "keymaterial", "finish", "texture")
    private val PRESS_WORDS = setOf("press", "pressanimation", "presseffect", "pressstyle", "animation", "tap", "tapanimation")
    private val RADIUS_WORDS = setOf("radius", "cornerradius", "corner", "borderradius", "rounding", "keyradius")
    private val NAME_WORDS = listOf("name", "themename", "title", "displayname", "label")
    private val ORB_WORDS = setOf("orbs", "blobs", "spots", "pools", "bubbles", "lights")
    private val GRADIENT_LIST_WORDS = listOf("colors", "colours", "stops", "gradient")
    private val ANGLE_WORDS = setOf("angle", "angledeg", "rotation", "direction", "deg")

    private const val BOM = "﻿"
    private const val MAX_DEPTH = 12
    private const val MAX_STOPS = 6
    private const val MAX_NAME = 60
    const val FALLBACK_NAME = "Imported theme"
}
