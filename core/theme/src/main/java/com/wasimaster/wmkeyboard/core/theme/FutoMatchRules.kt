package com.wasimaster.wmkeyboard.core.theme

/**
 * FUTO's per-key pictures, carried onto this app's key textures, single-key
 * styles and icon slots.
 *
 * An advanced FUTO theme lists two kinds of rule. A `[[matchrules.border]]`
 * names a picture to draw as a key's face, a `[[matchrules.icon]]` a picture
 * to draw in place of its glyph; each has a `selector`, and the first rule
 * whose selector fits a key is the one drawn for it. Each picture then has an
 * `[[asset.border]]` or `[[asset.icon]]` entry saying how to draw it: where to
 * cut it nine-slice (`slicing`), how dense it was drawn (`target_density`) and
 * the colour of the label on top of it (`foreground_tint`).
 *
 * A selector is a list of words, every one of which has to hold:
 *
 * - a class: `normal` (letters), `functional` (shift, delete, the symbols key,
 *   the comma and full stop beside the space bar), `action` (enter),
 *   `spacebar`, `nobackground` (the number row), `popup` (the key preview),
 *   `morekey` and `morekeysbox` (the long-press popup);
 * - a state: `pressed`, or `stickyon` for a shift key that is on;
 * - `label x` or `icon name`, picking keys by what they show;
 * - `row n`, `col n`, `rowmod`, `colmod` and `layer`, picking keys by where
 *   they sit.
 *
 * The rule order matters, so the rules are not mapped one at a time. Instead a
 * fixed set of [Probe] keys stands for the board: every letter, the digits,
 * the comma and full stop, and each special key. Each probe is asked which
 * picture FUTO would draw on it at rest and held down, and the answers are
 * then laid out the way this app stores them: one picture per key class, and
 * a single-key style only where a key differs from its class.
 *
 * What cannot come across is collected in [dropped]: rules by position (a
 * layout here is not FUTO's grid, so `row 2 col 8` names a different key or
 * none), pictures for states this app has no picture for (a shift key that is
 * on, a held glyph, the long-press popup), and pictures the ZIP does not hold.
 *
 * Written from the theme files themselves (the 14 themes then in FUTO's
 * gallery were read for their selectors), not from FUTO's code.
 */
internal class FutoMatchRules(
    toml: Map<String, Any?>,
    private val files: Map<String, ByteArray>,
    private val colour: (String?) -> Long?,
) {

    private val borderRules = rules(toml, "border")
    private val iconRules = rules(toml, "icon")
    private val borderAssets = assets(toml, "border")
    private val iconAssets = assets(toml, "icon")

    val dropped = linkedSetOf<FutoThemeUnsupported>()

    val isEmpty: Boolean get() = borderRules.isEmpty() && iconRules.isEmpty()

    /** What the rules come to, ready to lay onto a [ThemeSpec]. */
    fun resolve(): FutoKeyPictures {
        noteLosses()
        val rest = PROBES.associateWith { border(it, emptySet()) }
        val held = PROBES.associateWith { border(it, setOf(PRESSED)) }

        fun mode(probes: List<Probe>, pick: (Probe) -> String?): String? =
            probes.map(pick).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        val letters = PROBES.filter { it.kind == ProbeKind.LETTER && it.classes == setOf(NORMAL) }
        val modifiers = PROBES.filter { it.kind == ProbeKind.MODIFIER }
        val normal = mode(letters) { rest[it] }
        val modifier = mode(modifiers) { rest[it] }
        val enterProbe = PROBES.first { it.id == ENTER_ID }
        val spaceProbe = PROBES.first { it.id == SPACE_ID }
        val enter = rest[enterProbe]
        val space = rest[spaceProbe]

        // What this app draws on a key from the class pictures alone, with the
        // fallbacks `KeyTextures.forKey` uses.
        fun classPicture(probe: Probe): String? = when (probe.kind) {
            ProbeKind.LETTER -> normal
            ProbeKind.SPACE -> space ?: normal
            ProbeKind.ENTER -> enter ?: modifier ?: normal
            ProbeKind.MODIFIER -> modifier ?: normal
        }

        // A picture held down only counts when it is not the resting one: FUTO
        // falls through to the resting rule when a theme has no pressed one.
        fun pressedOf(probe: Probe): String? = held[probe]?.takeIf { it != rest[probe] }
        val boardPressed = mode(letters, ::pressedOf)

        val out = FutoKeyPictures()
        out.classSlots[ASSET_KEY_TEXTURE] = normal
        out.classSlots[ASSET_KEY_TEXTURE_MODIFIER] = modifier?.takeIf { it != normal }
        out.classSlots[ASSET_KEY_TEXTURE_ENTER] = enter?.takeIf { it != (modifier ?: normal) }
        out.classSlots[ASSET_KEY_TEXTURE_SPACE] = space?.takeIf { it != normal }
        out.classSlots[ASSET_KEY_TEXTURE_PRESSED] = boardPressed
        out.classSlots[ASSET_POPUP_TEXTURE] = borderRules.firstOrNull { rule ->
            POPUP in rule.selector.classes && rule.selector.fits(POPUP_PROBE, emptySet())
        }?.asset
        out.classSlots.values.removeAll { it == null }

        for (probe in PROBES) {
            val own = rest[probe]
            val texture = own?.takeIf { it != classPicture(probe) }
            val wanted = pressedOf(probe)
            val pressed = when {
                wanted == boardPressed -> null
                wanted != null -> wanted
                // The board's held picture would land on a key FUTO draws the
                // same held as at rest; its own picture is the closer match.
                boardPressed != null && own != null -> own
                else -> null
            }
            if (texture != null) out.keyTextures[probe.id] = texture
            if (pressed != null) out.keyPressed[probe.id] = pressed
            if (own != null) out.drawn[probe.id] = own
        }

        out.foreground.putAll(borderAssets.mapNotNull { (name, asset) -> asset.foreground?.let { name to it } })
        icons(out)
        return out
    }

    /** The bytes of a border picture, and how to cut it; null when the ZIP does not hold it. */
    fun border(name: String): FutoPicture? {
        val bytes = files[name.trimStart('/')] ?: return null.also { dropped += FutoThemeUnsupported.KEY_PICTURES }
        val asset = borderAssets[name]
        return FutoPicture(bytes, asset?.slice(bytes))
    }

    /** The bytes of an icon picture, under the icon pipeline's size cap. */
    fun icon(name: String): ByteArray? {
        val bytes = files[name.trimStart('/')]
        if (bytes == null || bytes.size > GboardIcons.MAX_ICON_BYTES) {
            dropped += FutoThemeUnsupported.KEY_PICTURES
            return null
        }
        return bytes
    }

    private fun border(probe: Probe, states: Set<String>): String? =
        borderRules.firstOrNull { rule ->
            // A preview bubble is only ever named as one.
            POPUP !in rule.selector.classes && rule.selector.fits(probe, states)
        }?.asset

    private fun icons(out: FutoKeyPictures) {
        for ((slots, probe, states) in ICON_TARGETS) {
            val asset = iconRules.firstOrNull { it.selector.fits(probe, states) }?.asset ?: continue
            for (slot in slots) out.icons[slot] = asset
        }
        // An icon picked by its label would replace a letter, which has no
        // icon slot here.
        if (iconRules.any { it.selector.label != null }) dropped += FutoThemeUnsupported.KEY_PICTURES
    }

    private fun noteLosses() {
        val all = borderRules + iconRules
        if (all.any { it.selector.positional }) dropped += FutoThemeUnsupported.KEY_POSITIONS
        val stateLoss = borderRules.any { STICKY in it.selector.states } ||
            iconRules.any { PRESSED in it.selector.states } ||
            all.any { it.selector.classes.any { c -> c == MORE_KEY || c == MORE_KEYS_BOX } }
        if (stateLoss) dropped += FutoThemeUnsupported.KEY_STATES
        if (all.any { it.selector.unknown }) dropped += FutoThemeUnsupported.KEY_PICTURES
    }

    // ---- reading the tables ----

    private class Rule(val selector: FutoSelector, val asset: String)

    private class Asset(
        val slicing: List<Float>?,
        val density: Float?,
        val background: Long?,
        val foreground: Long?,
    ) {
        fun slice(bytes: ByteArray): TextureSlice? {
            val tint = background?.takeIf { it and OPAQUE_WHITE != OPAQUE_WHITE }
            if (slicing == null && tint == null) return null
            val (w, h) = pngSize(bytes) ?: (null to null)
            val dpPerPixel = density?.takeIf { it > 0f }?.let { MDPI / it }
            return TextureSlice(
                left = slicing?.getOrNull(0) ?: 0f,
                top = slicing?.getOrNull(1) ?: 0f,
                right = slicing?.getOrNull(2) ?: 1f,
                bottom = slicing?.getOrNull(3) ?: 1f,
                widthDp = dpPerPixel?.let { d -> w?.let { it * d } },
                heightDp = dpPerPixel?.let { d -> h?.let { it * d } },
                tint = tint,
            ).normalized()
        }
    }

    private fun rules(toml: Map<String, Any?>, kind: String): List<Rule> =
        list(toml, "matchrules", kind).mapNotNull { table ->
            val selector = table["selector"] as? String ?: return@mapNotNull null
            val asset = table["asset"] as? String ?: return@mapNotNull null
            Rule(FutoSelector.parse(selector), asset)
        }

    private fun assets(toml: Map<String, Any?>, kind: String): Map<String, Asset> =
        list(toml, "asset", kind).mapNotNull { table ->
            val name = table["name"] as? String ?: return@mapNotNull null
            val slicing = (table["slicing"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                ?.takeIf { it.size == SLICE_EDGES }
            name to Asset(
                slicing = slicing,
                density = (table["target_density"] as? Number)?.toFloat(),
                background = colour(table["background_tint"] as? String),
                foreground = colour(table["foreground_tint"] as? String),
            )
        }.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun list(toml: Map<String, Any?>, table: String, kind: String): List<Map<String, Any?>> =
        ((toml[table] as? Map<String, Any?>)?.get(kind) as? List<*>).orEmpty()
            .filterIsInstance<Map<String, Any?>>()

    internal companion object {
        const val NORMAL = "normal"
        const val FUNCTIONAL = "functional"
        const val ACTION = "action"
        const val SPACEBAR = "spacebar"
        const val NO_BACKGROUND = "nobackground"
        const val POPUP = "popup"
        const val MORE_KEY = "morekey"
        const val MORE_KEYS_BOX = "morekeysbox"
        const val PRESSED = "pressed"
        const val STICKY = "stickyon"

        val CLASSES = setOf(NORMAL, FUNCTIONAL, ACTION, SPACEBAR, NO_BACKGROUND, POPUP, MORE_KEY, MORE_KEYS_BOX)
        val STATES = setOf(PRESSED, STICKY)

        const val ENTER_ID = "ENTER"
        const val SPACE_ID = "SPACE"
        private const val SHIFT_ID = "SHIFT"

        private const val SLICE_EDGES = 4
        private const val MDPI = 160f
        private const val OPAQUE_WHITE = 0xFFFFFFFFL

        /**
         * The keys every rule is asked about, by the single-key style id this
         * app gives them (`keyOverrideId`): a letter's lowercase label, an
         * action's name.
         */
        val PROBES: List<Probe> = buildList {
            for (c in 'a'..'z') add(Probe(c.toString(), setOf(NORMAL), label = c.toString(), kind = ProbeKind.LETTER))
            for (c in '0'..'9') add(Probe(c.toString(), setOf(NO_BACKGROUND), label = c.toString(), kind = ProbeKind.LETTER))
            add(Probe(",", setOf(FUNCTIONAL), label = ",", kind = ProbeKind.LETTER))
            add(Probe(".", setOf(FUNCTIONAL), label = ".", kind = ProbeKind.LETTER))
            add(Probe(SHIFT_ID, setOf(FUNCTIONAL), icon = "shift_key", kind = ProbeKind.MODIFIER))
            add(Probe("DELETE", setOf(FUNCTIONAL), icon = "delete_key", kind = ProbeKind.MODIFIER))
            add(Probe("SYMBOLS", setOf(FUNCTIONAL), label = "?123", kind = ProbeKind.MODIFIER))
            add(Probe("LETTERS", setOf(FUNCTIONAL), label = "ABC", kind = ProbeKind.MODIFIER))
            add(Probe("EMOJI", setOf(FUNCTIONAL), icon = "action_emoji", kind = ProbeKind.MODIFIER))
            add(Probe(ENTER_ID, setOf(ACTION), icon = "enter_key", kind = ProbeKind.ENTER))
            add(Probe(SPACE_ID, setOf(SPACEBAR), kind = ProbeKind.SPACE))
        }

        /** The key preview over an ordinary letter. */
        private val POPUP_PROBE = Probe("popup", setOf(POPUP, NORMAL), kind = ProbeKind.LETTER)

        private val SHIFTED = Probe(SHIFT_ID, setOf(FUNCTIONAL), icon = "shift_key_shifted", kind = ProbeKind.MODIFIER)

        /**
         * Icon slot ids (as `core.icons.IconSlots` spells them; see
         * [GboardIcons] for why they are plain strings), with the key and state
         * that wears each.
         */
        private val ICON_TARGETS: List<Triple<List<String>, Probe, Set<String>>> = listOf(
            Triple(listOf("key.shift"), PROBES.first { it.id == SHIFT_ID }, emptySet()),
            Triple(listOf("key.shift_on", "key.shift_lock"), SHIFTED, setOf(STICKY)),
            Triple(listOf("key.backspace"), PROBES.first { it.id == "DELETE" }, emptySet()),
            Triple(listOf("key.enter"), PROBES.first { it.id == ENTER_ID }, emptySet()),
            Triple(listOf("key.emoji"), PROBES.first { it.id == "EMOJI" }, emptySet()),
        )

        /** The width and height a PNG's header states, or null for anything else. */
        fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
            if (bytes.size < PNG_HEADER || bytes[1] != 'P'.code.toByte() || bytes[2] != 'N'.code.toByte()) return null
            fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            val w = int(PNG_WIDTH_AT)
            val h = int(PNG_WIDTH_AT + 4)
            return if (w > 0 && h > 0) w to h else null
        }

        private const val PNG_HEADER = 24
        private const val PNG_WIDTH_AT = 16
    }
}

/** How a probe key is drawn here when it has no picture of its own. */
internal enum class ProbeKind { LETTER, MODIFIER, ENTER, SPACE }

/** One key a selector is tried against. */
internal data class Probe(
    val id: String,
    val classes: Set<String>,
    val label: String? = null,
    val icon: String? = null,
    val kind: ProbeKind,
)

/** One FUTO selector, split into what it asks of a key. */
internal data class FutoSelector(
    val classes: Set<String>,
    val states: Set<String>,
    val label: String?,
    val icon: String?,
    /** Names a key by row, column or layer, which no layout here shares with FUTO's grid. */
    val positional: Boolean,
    /** Holds a word this reader does not know, so it is never taken to fit. */
    val unknown: Boolean,
) {
    /** Whether a key showing [probe] in [current] states is one this selector picks. */
    fun fits(probe: Probe, current: Set<String>): Boolean =
        !positional && !unknown &&
            current.containsAll(states) &&
            probe.classes.containsAll(classes) &&
            (label == null || label.equals(probe.label, ignoreCase = true)) &&
            (icon == null || icon == probe.icon)

    companion object {
        fun parse(text: String): FutoSelector {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val classes = linkedSetOf<String>()
            val states = linkedSetOf<String>()
            var label: String? = null
            var icon: String? = null
            var positional = false
            var unknown = words.isEmpty()
            var i = 0
            while (i < words.size) {
                val word = words[i++]
                when (word.lowercase()) {
                    in FutoMatchRules.CLASSES -> classes += word.lowercase()
                    in FutoMatchRules.STATES -> states += word.lowercase()
                    "label" -> label = words.getOrNull(i++) ?: run { unknown = true; null }
                    "icon" -> icon = words.getOrNull(i++)?.lowercase() ?: run { unknown = true; null }
                    "row", "col", "layer" -> { positional = true; i++ }
                    "rowmod", "colmod" -> { positional = true; i += 2 }
                    else -> unknown = true
                }
            }
            return FutoSelector(classes, states, label, icon, positional, unknown)
        }
    }
}

/** A FUTO picture's bytes, and the nine-slice cut its asset entry gives it. */
internal class FutoPicture(val bytes: ByteArray, val slice: TextureSlice?)

/**
 * The matchrules laid out as this app stores them; every value is a picture
 * name inside the ZIP.
 */
internal class FutoKeyPictures {
    /** Class texture slot ([ASSET_KEY_TEXTURE], …) to picture. */
    val classSlots = LinkedHashMap<String, String?>()

    /** Single-key style id to the picture it wears at rest, where that differs from its class. */
    val keyTextures = LinkedHashMap<String, String>()

    /** Single-key style id to the picture it wears held down, where the board's would be wrong. */
    val keyPressed = LinkedHashMap<String, String>()

    /** Every probe key's resting picture, for telling which faces the pictures cover. */
    val drawn = LinkedHashMap<String, String>()

    /** Picture to the label colour FUTO draws on it. */
    val foreground = LinkedHashMap<String, Long>()

    /** Icon slot id to picture. */
    val icons = LinkedHashMap<String, String>()
}
