package com.wasimaster.wmkeyboard.core.theme

import com.wasimaster.wmkeyboard.core.theme.FlexTheme.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * FlorisBoard's style language, as much of it as maps onto a [ThemeSpec].
 *
 * A stylesheet is a JSON object of rule to declarations: `"key": {"background":
 * "#2C2C2C"}`. A rule names an element, optionally attributes in brackets
 * (`key[code=32]`, and more than one bracket may be chained) and optionally a
 * state after a colon (`key[code=10]:pressed`). `@defines` holds variables that
 * the rest reference as `var(--name)`.
 *
 * ### Both dialects are read
 *
 * FlorisBoard 0.4 and 0.5 spell several elements differently — `keyboard`
 * became `window`, `key-popup` became `key-popup-box`, `smartbar-key` became
 * `smartbar-action-key`, `emoji-key` became `media-emoji-key` — but the
 * property names, the value syntax and the selector grammar are the same in
 * both. An earlier version of this file refused the older dialect outright;
 * measured against the themes people actually have (Dracula, Methone, Lurux and
 * the 0.3-era Catppuccin are all the older dialect) that refusal threw away
 * themes that convert perfectly well. Both are read, and the element table
 * below carries both spellings.
 *
 * ### Why the matching is deliberately loose
 *
 * The dialect is still moving upstream: element names have been renamed between
 * releases, and property spellings vary between the editor's output and
 * hand-written files. Rules and properties are therefore normalized —
 * lowercased, separators collapsed — and matched against alias sets rather than
 * exact strings. A rename upstream costs one entry in a list here instead of a
 * silently empty theme.
 */
internal class Stylesheet(
    val rules: List<SnyggRule>,
    val ruleCount: Int,
    val mappedCount: Int,
    val dropped: Set<FlexUnsupported>,
    /**
     * The element names the sheet styles that this app has no counterpart for,
     * in the file's own words.
     *
     * Carried so the import can *name* them. "Some parts of the file name
     * keyboard elements that this app does not have" was true and told the user
     * nothing they could act on; for the theme packs most people install the
     * whole of it is the landscape text editor, which Android draws rather than
     * this app, and FlorisBoard's own smartbar actions editor.
     */
    val unknownElements: Set<String> = emptySet(),
    /**
     * The fonts the sheet declares, family name to the path inside the archive.
     *
     * A theme that ships its own typeface writes `"@font `ndot`": [{"src":
     * "uri(`flex:/fonts/Ndot57-Regular.otf`)"}]` and then points a rule at it
     * with `font-family: `ndot``. Two of the six themes on the addon store do
     * exactly that, and one of them is a dot-matrix face the whole theme is
     * built around.
     */
    val fontSources: Map<String, String> = emptyMap(),
    /** The family a rule asks for, if any; the key to [fontSources]. */
    val requestedFont: String? = null,
    /**
     * Every Material You role the sheet named, keyed by [wallpaperRoleKey], with
     * the colour it resolved to. Stored on the converted theme so that it can
     * follow the wallpaper later; see [ThemeSpec.wallpaperRoles].
     */
    val wallpaperRoles: Map<String, Long> = emptyMap(),
) {

    /**
     * The style for an element in a state, with nothing else qualifying it.
     *
     * Rules merge in file order, later winning, which is what reads a sheet
     * that states a colour once and refines it further down. Only unqualified
     * rules take part: `key[mode=`numeric`]` describes the number pad and must
     * not leak into the letter keys.
     */
    fun base(element: String, state: String? = null): SnyggRule? {
        val matching = rules.filter {
            it.element == element && it.state == state && it.attributes.isEmpty()
        }
        return when (matching.size) {
            0 -> null
            1 -> matching[0]
            else -> matching.reduce { acc, rule -> acc.mergedWith(rule) }
        }
    }

    /** The first of [elements] that the sheet styles in [state], or null. */
    fun firstOf(vararg elements: String, state: String? = null): SnyggRule? =
        elements.firstNotNullOfOrNull { base(it, state) }

    /**
     * The style an element takes under one named attribute, such as the
     * clipboard filter chip's `[state=`active`]`.
     *
     * Narrower than [base] on purpose: this is how a sheet spells "selected",
     * which this app keeps as its own colour rather than a state of the first.
     */
    fun withAttribute(element: String, name: String, value: String): SnyggRule? =
        rules.filter {
            it.element == element && it.state == null &&
                it.attributes[name]?.any { v -> v.equals(value, ignoreCase = true) } == true
        }.reduceOrNull { acc, rule -> acc.mergedWith(rule) }

    /**
     * Rules that style one element by key code and nothing else, in [state].
     *
     * A rule carrying any other attribute — `[mode=`numeric`]` for the number
     * pad, `[shiftstate=`caps_lock`]` for the caps-lock look — describes a
     * situation rather than a key, and this keyboard has one colour per key. It
     * is excluded rather than flattened onto the key's resting style: flattening
     * is how the caps-lock tint used to end up painted on shift permanently.
     */
    fun byCode(element: String, state: String? = null): List<SnyggRule> =
        rules.filter {
            it.element == element && it.state == state &&
                it.attributes.keys == setOf(ATTR_CODE)
        }

    /** The style a rule gives one key code, merged in file order. */
    fun forCode(element: String, code: Int, state: String? = null): SnyggRule? =
        byCode(element, state).filter { code in it.codes }
            .reduceOrNull { acc, rule -> acc.mergedWith(rule) }

    /** Every key code the sheet styles individually, in the order it names them. */
    fun styledCodes(element: String): List<Int> =
        byCode(element).flatMap { it.codes }.distinct()

    /**
     * Rules that style one element by what the key *outputs* and nothing else.
     *
     * The snygg v2 way of naming a key: `key[output=`@k3:action/enter`]` beside
     * (or instead of) the older `key[code=10]`. FlorisBoard's own default themes
     * write both, but a theme written against v2 alone names only the output,
     * and reading only codes is how such a theme used to come across with no
     * enter key colour at all.
     *
     * Narrowed the same way [byCode] is, and for the same reason: a rule
     * carrying another attribute describes a situation rather than a key.
     */
    fun byOutput(element: String, state: String? = null): List<SnyggRule> =
        rules.filter {
            it.element == element && it.state == state &&
                it.attributes.keys == setOf(ATTR_OUTPUT)
        }

    /** The style a rule gives one output id, merged in file order. */
    fun forOutput(element: String, output: String, state: String? = null): SnyggRule? =
        byOutput(element, state).filter { output in it.outputs }
            .reduceOrNull { acc, rule -> acc.mergedWith(rule) }

    /** Every output the sheet styles individually, in the order it names them. */
    fun styledOutputs(element: String): List<String> =
        byOutput(element).flatMap { it.outputs }.distinct()

    companion object {

        fun parse(text: String, palette: SnyggPalette, night: Boolean): Stylesheet? {
            val root = runCatching { FlexTheme.json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return null
            val defines = (root[DEFINES] as? JsonObject)
                ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
                .orEmpty()

            val dropped = linkedSetOf<FlexUnsupported>()
            val unknown = linkedSetOf<String>()
            val fontSources = linkedMapOf<String, String>()
            val roles = linkedMapOf<String, Long>()
            var requestedFont: String? = null
            val rules = mutableListOf<SnyggRule>()
            var count = 0
            for ((raw, value) in root) {
                if (raw.startsWith('@')) {
                    // `@font `ndot`` — the name is in the rule, the file in its
                    // body. Matching the bare word `@font` never fired, because
                    // no sheet writes one: the family name is always part of
                    // the rule, so a theme that shipped a typeface said nothing.
                    if (raw.normalizeName().startsWith(FONT_RULE)) {
                        fontNameOf(raw)?.let { name ->
                            fontPathOf(value)?.let { path -> fontSources[name] = path }
                        }
                    }
                    continue
                }
                val declarations = (value as? JsonObject) ?: continue
                count++
                requestedFont = requestedFont ?: fontFamilyOf(declarations)
                val rule = ruleOf(raw, declarations, Resolver(defines, palette, night, dropped, roles))
                if (rule == null) {
                    dropped += FlexUnsupported.UNKNOWN_ELEMENT
                    parseSelector(raw)?.element?.let { unknown += it }
                } else {
                    rules += rule
                }
            }
            if (rules.isEmpty()) return null
            // What the user is told is "the app can use N of M rules", so N has
            // to mean a rule that actually reached a field. Counting every rule
            // whose *element name* was recognised was the flattering number,
            // not the true one: a rule setting only `text-overflow` names an
            // element this app has and still changes nothing it draws. See
            // [SNYGG_CONSUMED], which is the same contract the mapper keeps.
            return Stylesheet(
                rules,
                count,
                rules.count { it.lands() },
                dropped,
                unknown,
                fontSources,
                requestedFont,
                roles,
            )
        }

        /** What a declaration's value is resolved against, and where it reports to. */
        private class Resolver(
            val defines: Map<String, String>,
            val palette: SnyggPalette,
            val night: Boolean,
            val dropped: MutableSet<FlexUnsupported>,
            val roles: MutableMap<String, Long>,
        )

        private fun ruleOf(raw: String, declarations: JsonObject, resolver: Resolver): SnyggRule? {
            val selector = parseSelector(raw) ?: return null
            val element = ELEMENTS[selector.element] ?: return null
            val properties = declarations.mapNotNull { (name, value) ->
                val key = name.normalizeName()
                val text = valueOf(value, resolver)
                // After resolving, because what a declaration *says* decides
                // whether anything was lost: `font-family: inherit` is the
                // sheet declining to change the font, not a font going missing.
                noteUnsupported(key, text, resolver.dropped)
                if (text == null) return@mapNotNull null
                PROPERTIES[key]?.let { it to text }
            }.toMap()
            return SnyggRule(element, selector.state, selector.attributes, properties)
        }

        /**
         * A selector split into its element, its state and its attributes.
         *
         * Written as a scan rather than a regex because the grammar nests:
         * `key[code=-11][shiftstate=`caps_lock`]:pressed` chains two bracket
         * groups and then a state, and attribute values may be backtick-quoted
         * strings that are allowed to contain the characters used as
         * separators. The earlier `substringBefore`/`substringAfter` pair read
         * that selector as `key[code=-11]` with **no state**, which is how a
         * pressed colour ended up painted on a resting key.
         */
        internal fun parseSelector(raw: String): ParsedSelector? {
            val element = StringBuilder()
            var state: String? = null
            val attributes = linkedMapOf<String, List<String>>()
            var index = 0
            var inState = false
            while (index < raw.length) {
                when (val ch = raw[index]) {
                    '[' -> {
                        val close = raw.indexOf(']', index)
                        if (close < 0) return null
                        parseAttribute(raw.substring(index + 1, close))?.let { (name, values) ->
                            // Chained brackets narrow, so a repeated name adds
                            // to the set it already carries rather than
                            // replacing it.
                            attributes[name] = (attributes[name].orEmpty() + values).distinct()
                        }
                        index = close + 1
                    }

                    ':' -> {
                        inState = true
                        index++
                    }

                    else -> {
                        if (inState) state = (state.orEmpty() + ch) else element.append(ch)
                        index++
                    }
                }
            }
            val name = element.toString().normalizeName()
            if (name.isEmpty()) return null
            return ParsedSelector(name, state?.normalizeName()?.ifEmpty { null }, attributes)
        }

        /**
         * One bracket group: `code=32`, `code=-11,-203,-7`, `code=48..57`,
         * `mode=`numeric`,`phone``.
         */
        private fun parseAttribute(body: String): Pair<String, List<String>>? {
            val name = body.substringBefore('=', "").normalizeName()
            if (name.isEmpty() || '=' !in body) return null
            val values = body.substringAfter('=')
                .split(',')
                .map { it.trim().trim('`', '\'', '"') }
                .filter { it.isNotEmpty() }
            return if (values.isEmpty()) null else name to values
        }

        /**
         * A declaration's value as text. An object-shaped value hands over the
         * one field that carries the colour, which is how the theme editor
         * writes a value it also wants to remember the picker state for.
         */
        private fun valueOf(value: JsonElement, resolver: Resolver): String? {
            val raw = when (value) {
                is JsonPrimitive -> value.content
                is JsonObject -> value.string("value") ?: value.string("color") ?: value.string("\$")
                is JsonArray -> null
            } ?: return null
            return resolveDynamic(resolve(raw, resolver.defines), resolver)
        }

        /**
         * `var(--name)` against `@defines`, following a define that names
         * another one.
         *
         * Chained defines are how the larger packs are written
         * (`--key-bg: var(--surface-2)`), and the depth cap is what stops a
         * define that names itself from spinning.
         */
        internal fun resolve(raw: String, defines: Map<String, String>): String {
            var trimmed = raw.trim()
            var depth = 0
            while (trimmed.startsWith(VAR_PREFIX, ignoreCase = true) && depth++ < MAX_VAR_DEPTH) {
                val name = trimmed.substring(VAR_PREFIX.length).substringBefore(')').trim()
                val next = defines[name] ?: defines["--$name"] ?: return trimmed
                if (next.trim() == trimmed) return trimmed
                trimmed = next.trim()
            }
            return trimmed
        }

        /**
         * `dynamic-light-color(primary)` / `dynamic-dark-color(surfaceDim)`
         * against the device palette.
         *
         * Reported as [FlexUnsupported.DYNAMIC_COLOR] whether or not it
         * resolves, so the import can say the theme follows the wallpaper. The
         * role and the colour it resolved to are recorded in
         * [Resolver.roles]: that record is what lets the stored theme move
         * with the next wallpaper instead of keeping today's colours.
         */
        private fun resolveDynamic(raw: String, resolver: Resolver): String {
            val night = resolver.night
            if (!raw.startsWith(DYNAMIC, ignoreCase = true)) return raw
            resolver.dropped += FlexUnsupported.DYNAMIC_COLOR
            val head = raw.substringBefore('(').lowercase()
            val role = raw.substringAfter('(', "").substringBefore(')').trim()
            if (role.isEmpty()) return raw
            // `dynamic-color(...)` with no scheme named follows the theme's own
            // day/night flag, which is what FlorisBoard's own resolver does.
            val scheme = when {
                head.contains("light") -> false
                head.contains("dark") -> true
                else -> night
            }
            val resolved = resolver.palette.resolve(scheme, role) ?: return raw
            resolver.roles.putIfAbsent(wallpaperRoleKey(scheme, role), resolved)
            // Written in the order the parser reads eight hex digits in, which
            // is CSS's `#RRGGBBAA` and not the ARGB the palette holds. Emitting
            // ARGB here rotated every dynamic colour's channels by one byte.
            return "#%08X".format(((resolved and 0xFFFFFFL) shl 8) or (resolved ushr 24))
        }

        /** The family in `@font `ndot``, or null when the rule names none. */
        private fun fontNameOf(raw: String): String? =
            raw.substringAfter('`', "").substringBefore('`', "").trim().takeIf { it.isNotEmpty() }

        /**
         * The archive path in `[{"src": "uri(`flex:/fonts/X.otf`)"}]`.
         *
         * The body is an array of sources — a family may ship a face per
         * weight — and the first is the one a single [ThemeSpec.fontId] can
         * hold.
         */
        private fun fontPathOf(value: JsonElement): String? {
            val first = (value as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val src = (first[FONT_SRC] as? JsonPrimitive)?.content ?: return null
            val inside = src.substringAfter('`', "").substringBefore('`', "")
            return inside.removePrefix(FLEX_SCHEME).trim('/').takeIf { it.isNotEmpty() }
        }

        /** The family a rule asks for, with the backticks taken off. */
        private fun fontFamilyOf(declarations: JsonObject): String? {
            for ((name, value) in declarations) {
                if (!name.normalizeName().contains("font family")) continue
                val text = (value as? JsonPrimitive)?.content?.trim()?.trim('`') ?: continue
                if (text.isNotEmpty() && !text.equals(INHERIT, ignoreCase = true)) return text
            }
            return null
        }

        private fun noteUnsupported(
            property: String,
            value: String?,
            dropped: MutableSet<FlexUnsupported>,
        ) {
            // `inherit` is a declaration that asks for nothing, so it can never
            // be a loss. Every font-family in both of the theme packs most
            // people install is exactly that, and reporting them told users a
            // font had been dropped from a theme that never named one.
            if (value != null && value.trim().equals(INHERIT, ignoreCase = true)) return
            when {
                // Both halves of a shadow land now: the lift and, on the keys,
                // its colour (ThemeSpec.keyShadowColor).
                property.contains("margin") || property.contains("padding") ->
                    dropped += FlexUnsupported.PER_ELEMENT_SPACING
                // `font family` is deliberately absent: whether the font is a
                // loss depends on whether the archive carries it, which only
                // the mapper can see. See `SnyggMapper.fontOf`.
            }
        }

        /** Lowercase, separators collapsed to single spaces. */
        internal fun String.normalizeName(): String =
            trim().lowercase().replace('-', ' ').replace('_', ' ').split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        private const val DEFINES = "@defines"
        private const val FONT_RULE = "@font"
        private const val FONT_SRC = "src"
        private const val FLEX_SCHEME = "flex:"

        /** A value that asks for whatever the element would have had anyway. */
        private const val INHERIT = "inherit"
        private const val VAR_PREFIX = "var("
        private const val DYNAMIC = "dynamic"
        private const val MAX_VAR_DEPTH = 8

        /**
         * Element aliases. The keys are normalized rule names as they appear in
         * a stylesheet — both dialects' spellings — and the values are the names
         * the mapper below uses.
         */
        @Suppress("LongMethod")
        private val ELEMENTS: Map<String, String> = buildMap {
            // The board itself. `keyboard` is the 0.4 name, `window` the 0.5
            // one, and `window-inner` is the area inside a floating window's
            // own frame — the board again, as far as anything here draws.
            for (name in listOf("window", "window inner", "keyboard", "root")) put(name, EL_BOARD)
            put("system nav bar", EL_NAV_BAR)
            for (name in listOf("one handed panel", "one handed panel button")) put(name, EL_ONE_HANDED)
            // The floating keyboard's own furniture. This keyboard draws its
            // handle bar out of the toolbar's colours, so that is where these
            // land rather than in fields of their own.
            for (name in listOf(
                "window move handle", "window resize handle", "window resize action",
                "window resize overlay fixed", "floating dock to fixed indicator",
            )) {
                put(name, EL_TOOL_TOGGLE)
            }

            // Keys.
            for (name in listOf("key", "key background")) put(name, EL_KEY)
            for (name in listOf("key hint", "key hint text", "keyhint")) put(name, EL_HINT)
            for (name in listOf("key popup box", "key popup", "popup")) put(name, EL_POPUP)
            // The item inside a long-press row. Its `:focus` state is the
            // highlight under the finger, which this keyboard draws too.
            for (name in listOf(
                "key popup element", "media emoji key popup element", "subtype panel list item",
            )) {
                put(name, EL_POPUP_ITEM)
            }

            // The bar above the keys. 0.4 called the tool buttons `smartbar-key`
            // and `smartbar-quick-action`; 0.5 calls them `smartbar-action-key`.
            put("smartbar", EL_TOOLBAR)
            for (name in listOf(
                "smartbar primary row", "smartbar secondary row", "smartbar action row",
                // The v2 spellings, taken from FlorisBoard's own `FlorisImeUi`.
                // The rows had been guessed at from the 0.4 names and the two
                // sets never overlapped, so a v2 sheet's toolbar fill was read
                // from nothing at all.
                "smartbar shared actions row", "smartbar extended actions row",
            )) {
                put(name, EL_TOOLBAR)
            }
            for (name in listOf(
                "smartbar action key", "smartbar key", "smartbar quick action", "smartbar action button",
            )) {
                put(name, EL_TOOL)
            }
            for (name in listOf(
                "smartbar shared actions toggle",
                "smartbar extended actions toggle",
                "smartbar primary actions toggle",
                "smartbar secondary actions toggle",
                "smartbar primary action row toggle",
                "smartbar primary secondary row toggle",
            )) {
                put(name, EL_TOOL_TOGGLE)
            }
            for (name in listOf(
                "smartbar candidate word", "smartbar candidate", "candidate", "smartbar candidate word text",
            )) {
                put(name, EL_CANDIDATE)
            }
            // `smartbar-candidates-row` is the spelling upstream actually ships;
            // the singular was a guess and matched no sheet ever written.
            put("smartbar candidates row", EL_CANDIDATE)
            put("smartbar candidate spacer", EL_DIVIDER)

            // The quieter text beside the main text. Upstream splits it across
            // a subheading, a timestamp, a kind label and a second word; here
            // they are all the same colour, so they are the same element.
            // Only what genuinely means "quieter text beside the main text".
            // A section heading is not that — several themes paint theirs in
            // the accent colour, and folding it in here tinted every
            // suggestion's second line orange.
            for (name in listOf(
                "smartbar candidate word secondary text",
                "clipboard subheader",
                "clipboard item description",
                "clipboard item timestamp",
            )) {
                put(name, EL_SECONDARY_TEXT)
            }

            // Chips: the clip suggestion, the action tiles, the autofill chip and
            // the clipboard's filter row all draw the same kind of pill here.
            for (name in listOf(
                "smartbar candidate clip",
                "inline autofill chip",
                "clipboard filter chip",
                // Sub-parts of the same pill. They carry typography, and
                // occasionally the colour the pill itself left unsaid.
                "smartbar candidate clip text",
                "smartbar candidate clip icon",
                "clipboard filter chip text",
            )) {
                put(name, EL_CHIP)
            }
            for (name in listOf(
                "smartbar action tile",
                "smartbar actions editor tile",
                "smartbar action tile icon",
                "smartbar action tile text",
                "smartbar actions editor tile grid",
                "media bottom row button",
            )) {
                put(name, EL_TILE)
            }

            // Panel cards and the sheets they sit in. A card's own popup is the
            // same card lifted, so it is the same surface here.
            // The card only. `clipboard-item-popup` is the same card lifted and
            // usually a shade lighter, and merging the two let that lighter
            // shade become the resting card colour.
            put("clipboard item", EL_CARD)
            // The same card lifted, and the strip of actions that slides out of
            // it. A shade lighter as a rule, so they answer only where the card
            // itself said nothing.
            for (name in listOf(
                "clipboard item popup", "clipboard item actions", "clipboard item popup action",
                "clipboard item action", "clipboard item action icon", "clipboard item action text",
            )) {
                put(name, EL_CARD_LIFTED)
            }
            put("incognito mode indicator", EL_INCOGNITO)
            for (name in listOf(
                "key popup extended indicator", "media emoji key popup extended indicator",
            )) {
                put(name, EL_POPUP_MORE)
            }
            put("media emoji subheader", EL_SECONDARY_TEXT)
            for (name in listOf(
                "smartbar actions editor", "subtype panel", "clipboard grid", "clipboard filter row",
                "clipboard content", "clipboard clear all dialog", "clipboard clear all dialog buttons",
                "media", "media bottom row",
                // The panel the overflow toggle opens; the same kind of surface
                // as the actions editor beside it.
                "smartbar actions overflow",
            )) {
                put(name, EL_SHEET)
            }
            // A panel's own heading. This keyboard draws panel chrome with the
            // suggestion strip's colour, so that is what these answer for.
            // Panel headings and the titles of the clipboard's own notices.
            // This keyboard draws all of them with the strip's colour, through
            // the Material scheme it builds from the theme (`onSurface`).
            for (name in listOf(
                "clipboard header", "clipboard header text",
                "smartbar actions editor header", "subtype panel header",
                // A section heading inside the editor, not quieter text: several
                // themes paint it in the accent (see the secondary text list).
                "smartbar actions editor subheader",
                "clipboard history disabled title", "clipboard history locked title",
            )) {
                put(name, EL_PANEL_HEADER)
            }
            // The body of those notices is the quieter text (`onSurfaceVariant`).
            for (name in listOf(
                "clipboard history disabled message", "clipboard history locked message",
                "clipboard clear all dialog message",
            )) {
                put(name, EL_SECONDARY_TEXT)
            }
            // A button inside a panel draws in the accent (`primary`).
            for (name in listOf(
                "clipboard history disabled button", "clipboard clear all dialog button",
                "smartbar actions overflow customize button",
            )) {
                put(name, EL_PANEL_BUTTON)
            }
            // Icon buttons on a panel's own chrome take the toolbar's colours.
            for (name in listOf(
                "clipboard header button", "smartbar actions editor header button",
                "clipboard filter chip icon",
            )) {
                put(name, EL_PANEL_TOOL)
            }
            // The rows of the language picker draw with the popup's text.
            for (name in listOf(
                "subtype panel list", "subtype panel list item",
                "subtype panel list item text", "subtype panel list item icon leading",
            )) {
                put(name, EL_MENU_ROW)
            }
            // The emoji board's keys sit on the board and take its text colour.
            for (name in listOf("media emoji key", "emoji key")) put(name, EL_EMOJI_KEY)

            // The emoji board. Its long-press bubble is the same bubble the
            // keys use, so it stands in where a sheet styles one and not the
            // other.
            for (name in listOf("media emoji key popup box", "emoji key popup")) put(name, EL_EMOJI_POPUP)
            for (name in listOf("media emoji tab", "emoji tab")) put(name, EL_EMOJI_TAB)

            for (name in listOf("glide trail", "glide")) put(name, EL_GLIDE)
        }

        /** Property aliases, normalized name to the one the mapper reads. */
        private val PROPERTIES: Map<String, String> = buildMap {
            for (name in listOf("background", "background color", "bg")) put(name, PROP_BACKGROUND)
            for (name in listOf("foreground", "color", "text color", "fg")) put(name, PROP_FOREGROUND)
            put("shape", PROP_SHAPE)
            for (name in listOf("border color", "outline color")) put(name, PROP_BORDER_COLOR)
            for (name in listOf("border width", "outline width")) put(name, PROP_BORDER_WIDTH)
            put("font weight", PROP_FONT_WEIGHT)
            put("font size", PROP_FONT_SIZE)
            for (name in listOf("font variation", "font variation settings")) put(name, PROP_FONT_VARIATION)
            for (name in listOf("shadow elevation", "elevation")) put(name, PROP_ELEVATION)
            put("shadow color", PROP_SHADOW_COLOR)
            for (name in listOf("background image", "image")) put(name, PROP_IMAGE)
            put("content scale", PROP_CONTENT_SCALE)
        }
    }
}

/** A selector's three parts, before the element name is resolved to an alias. */
internal data class ParsedSelector(
    val element: String,
    val state: String?,
    val attributes: Map<String, List<String>>,
)

/** One rule: an element, optionally a state and attributes, and its properties. */
internal data class SnyggRule(
    val element: String,
    val state: String?,
    val attributes: Map<String, List<String>>,
    val properties: Map<String, String>,
) {
    fun value(property: String): String? = properties[property]

    /** [other]'s properties over this one's; used to fold rules in file order. */
    fun mergedWith(other: SnyggRule): SnyggRule = copy(properties = properties + other.properties)

    /**
     * The key codes this rule names, ranges expanded.
     *
     * `key[code=-11,-203,-7]` styles three keys, not one. Reading only the
     * first is why a theme that painted shift, delete and the symbol key alike
     * used to come across painting shift alone.
     */
    val codes: List<Int> by lazy {
        attributes[ATTR_CODE].orEmpty().flatMap { value ->
            val range = value.split(RANGE).map { it.trim() }
            if (range.size == 2) {
                val from = range[0].toIntOrNull()
                val to = range[1].toIntOrNull()
                if (from != null && to != null && to >= from && to - from <= MAX_RANGE) {
                    (from..to).toList()
                } else {
                    emptyList()
                }
            } else {
                listOfNotNull(value.toIntOrNull())
            }
        }
    }

    /**
     * The outputs this rule names, namespaces stripped.
     *
     * `@k3:action/enter`, `@floris:action/noop_spacer` and `@fl:action/...` are
     * the same ids under three extension namespaces, which have changed between
     * releases; what stays put is the part after the colon. A literal output —
     * a character the key types — is kept as it stands.
     */
    val outputs: List<String> by lazy {
        attributes[ATTR_OUTPUT].orEmpty().map(::snyggOutputId)
    }

    private companion object {
        const val RANGE = ".."

        /** A range wider than a keyboard is a typo, not a selector. */
        const val MAX_RANGE = 512
    }
}

/**
 * An `output=` value with its extension namespace taken off; see
 * [SnyggRule.outputs].
 *
 * Quotes come off, whitespace does not: the space bar's rule is
 * ``key[output=` `]`` and trimming it leaves nothing to match on.
 */
internal fun snyggOutputId(raw: String): String {
    val text = raw.trim('`', '\'', '"')
    if (!text.startsWith('@')) return text
    val colon = text.indexOf(':')
    return if (colon < 0) text else text.substring(colon + 1)
}

internal const val ATTR_CODE = "code"
internal const val ATTR_OUTPUT = "output"

internal const val EL_BOARD = "board"
internal const val EL_NAV_BAR = "navBar"
internal const val EL_ONE_HANDED = "oneHanded"
internal const val EL_KEY = "key"
internal const val EL_HINT = "hint"
internal const val EL_POPUP = "popup"
internal const val EL_POPUP_ITEM = "popupItem"
internal const val EL_PANEL_HEADER = "panelHeader"
internal const val EL_PANEL_BUTTON = "panelButton"
internal const val EL_PANEL_TOOL = "panelTool"
internal const val EL_MENU_ROW = "menuRow"
internal const val EL_EMOJI_KEY = "emojiKey"
internal const val EL_TOOLBAR = "toolbar"
internal const val EL_TOOL = "tool"
internal const val EL_TOOL_TOGGLE = "toolToggle"
internal const val EL_CANDIDATE = "candidate"
internal const val EL_SECONDARY_TEXT = "secondaryText"
internal const val EL_DIVIDER = "divider"
internal const val EL_CHIP = "chip"
internal const val EL_TILE = "tile"
internal const val EL_CARD = "card"
internal const val EL_CARD_LIFTED = "cardLifted"
internal const val EL_INCOGNITO = "incognito"
internal const val EL_POPUP_MORE = "popupMore"
internal const val EL_SHEET = "sheet"
internal const val EL_EMOJI_POPUP = "emojiPopup"
internal const val EL_EMOJI_TAB = "emojiTab"
internal const val EL_GLIDE = "glide"

internal const val PROP_BACKGROUND = "background"
internal const val PROP_FOREGROUND = "foreground"
internal const val PROP_SHAPE = "shape"
internal const val PROP_BORDER_COLOR = "borderColor"
internal const val PROP_BORDER_WIDTH = "borderWidth"
internal const val PROP_FONT_WEIGHT = "fontWeight"
internal const val PROP_FONT_SIZE = "fontSize"
/** Variable-font axes, in CSS's `font-variation-settings` form: `'wght' 700, 'wdth' 80`. */
internal const val PROP_FONT_VARIATION = "fontVariation"
internal const val PROP_ELEVATION = "elevation"
internal const val PROP_SHADOW_COLOR = "shadowColor"
internal const val PROP_IMAGE = "image"
internal const val PROP_CONTENT_SCALE = "contentScale"

/**
 * Snygg's `content-scale` as the fit this app stores on [ThemeSpec.keyTextureScale].
 *
 * Seven values upstream against three here, so several collapse. `fill-bounds`
 * is the only one that distorts the picture, which is what `STRETCH` means;
 * every other value preserves the aspect ratio one way or another, and `CROP`
 * — fill the box and clip the overflow — is the nearest of the three to all of
 * them. `TILE` has no counterpart upstream at all, so it is never the answer.
 */
internal fun snyggContentScale(raw: String?): String? = when (raw?.trim()?.lowercase()) {
    null -> null
    "fill-bounds", "fill_bounds", "fill-width", "fill-height" -> KeyTextureScale.STRETCH.name
    "crop", "fit", "inside", "none" -> KeyTextureScale.CROP.name
    else -> null
}

/**
 * A snygg colour as ARGB, or null when the value is not a colour this
 * understands.
 *
 * Accepts what stylesheets actually carry: `#RGB`, `#RRGGBB`, `#RRGGBBAA`,
 * `rgb(…)`, `rgba(…)` with the alpha as either 0..1 or 0..255, and
 * `transparent`. A value it cannot read returns null, and null means the field
 * is left at its default rather than set to black.
 */
@Suppress("ReturnCount")
internal fun snyggColor(raw: String?): Long? {
    val text = raw?.trim()?.lowercase() ?: return null
    if (text == "transparent" || text == "none") return 0x00000000L
    if (text.startsWith("#")) return hexColor(text.removePrefix("#"))
    if (!text.startsWith("rgb")) return null
    val parts = text.substringAfter('(').substringBefore(')').split(',', ' ', '/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (parts.size < 3) return null
    val r = parts[0].toFloatOrNull()?.roundToInt() ?: return null
    val g = parts[1].toFloatOrNull()?.roundToInt() ?: return null
    val b = parts[2].toFloatOrNull()?.roundToInt() ?: return null
    val a = parts.getOrNull(3)?.toFloatOrNull()?.let { if (it <= 1f) (it * 255f).roundToInt() else it.roundToInt() }
        ?: 255
    return argb(a, r, g, b)
}

private fun hexColor(hex: String): Long? {
    val digits = when (hex.length) {
        // #RGB is shorthand for #RRGGBB.
        3 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    if (digits.any { it !in "0123456789abcdef" }) return null
    val value = digits.toLongOrNull(16) ?: return null
    // Eight digits are RRGGBBAA in CSS, which is not the order stored here.
    return if (digits.length == 8) {
        ((value and 0xFFL) shl 24) or (value ushr 8)
    } else {
        0xFF000000L or value
    }
}

private fun argb(a: Int, r: Int, g: Int, b: Int): Long =
    ((a.coerceIn(0, 255).toLong() shl 24) or
        (r.coerceIn(0, 255).toLong() shl 16) or
        (g.coerceIn(0, 255).toLong() shl 8) or
        b.coerceIn(0, 255).toLong())

/**
 * [over] painted on top of [under], as the opaque colour the eye actually sees.
 *
 * Borderless themes are built out of transparent surfaces: their keys are
 * `transparent` and the board shows through. Every judgement about a colour —
 * is this label readable, is this accent visible — has to be made against what
 * is behind it, or a transparent key reads as black and a perfectly legible
 * theme gets "corrected" into an unreadable one. That was the bug: a borderless
 * theme's dark labels were measured against transparent-black, failed the
 * contrast floor, and were replaced with white on a light board.
 */
internal fun composite(over: Long, under: Long): Long {
    val alpha = ((over ushr 24) and 0xFFL).toFloat() / 255f
    if (alpha >= 1f) return over
    if (alpha <= 0f) return 0xFF000000L or (under and 0xFFFFFFL)
    fun channel(shift: Int): Long {
        val top = ((over ushr shift) and 0xFFL).toFloat()
        val bottom = ((under ushr shift) and 0xFFL).toFloat()
        return (top * alpha + bottom * (1f - alpha)).roundToInt().toLong().coerceIn(0L, 255L)
    }
    return 0xFF000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** Whether a colour is see-through enough that what is behind it decides the look. */
internal fun Long.isTranslucent(): Boolean = ((this ushr 24) and 0xFFL) < 0xFF

/**
 * A snygg shape as a key shape and its corner radius.
 *
 * `rounded-corner(8dp)`, `cut-corner(4dp)`, `circle()`, `rectangle()`. A shape
 * that gives a different size per corner cannot be carried — there is one
 * radius field — so it reports [FlexUnsupported.PER_CORNER_RADIUS] and the
 * first size is used.
 *
 * A percentage is a share of the element's own height, not a dp:
 * `rounded-corner(50%)` is a pill and `rounded-corner(8%)` is a slight
 * softening. Reading the number as dp turned every 50% chip into a 50 dp brick,
 * so a percentage resolves against a nominal key height — and a percentage at
 * or past half becomes [KeyShapeKind.PILL], which stays a pill at any height.
 *
 * Only a **percentage** promotes to a pill. A dp radius does not, however
 * large: `rounded-corner(24dp, 24dp, 0dp, 0dp)` is the bottom sheet every
 * theme rounds off at the top, and reading its first corner as "pill" turned
 * the language menu into a lozenge.
 */
internal fun snyggShape(raw: String?, dropped: MutableSet<FlexUnsupported>): Pair<KeyShapeKind, Int?>? {
    val text = raw?.trim()?.lowercase() ?: return null
    val name = text.substringBefore('(').replace('-', ' ').replace('_', ' ').trim()
    val args = text.substringAfter('(', "").substringBefore(')')
        .split(',', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val sizes = args.mapNotNull { arg ->
        val number = arg.removeSuffix("dp").removeSuffix("sp").removeSuffix("%").toFloatOrNull()
        when {
            number == null -> null
            arg.endsWith("%") -> number / 100f * NOMINAL_KEY_HEIGHT_DP
            else -> number
        }
    }
    if (sizes.size > 1 && sizes.distinct().size > 1) dropped += FlexUnsupported.PER_CORNER_RADIUS
    val radius = sizes.firstOrNull()?.roundToInt()
    val pill = args.firstOrNull()?.endsWith("%") == true &&
        (sizes.firstOrNull() ?: 0f) >= NOMINAL_KEY_HEIGHT_DP / 2f
    return when {
        name.startsWith("circle") -> KeyShapeKind.CIRCLE to null
        name.startsWith("cut") -> KeyShapeKind.CUT to radius
        name.startsWith("rectangle") || name.startsWith("sharp") -> KeyShapeKind.SHARP to 0
        name.startsWith("rounded") -> if (pill) KeyShapeKind.PILL to null else KeyShapeKind.ROUNDED to radius
        else -> null
    }
}

/**
 * The element height a percentage shape is resolved against.
 *
 * FlorisBoard sizes a percentage corner against the element it is on, which is
 * not known until the board is laid out. A converted theme stores one number,
 * so it is resolved against a typical key — close enough that an 8% corner
 * stays a subtle one and a 50% corner stays a pill.
 */
private const val NOMINAL_KEY_HEIGHT_DP = 48f

/** A dp size from `8dp`, `8`, `8.0dp` or `14sp`. */
internal fun snyggDp(raw: String?): Float? =
    raw?.trim()?.removeSuffix("dp")?.removeSuffix("sp")?.trim()?.toFloatOrNull()

/**
 * Contrast between two ARGB colours, as the WCAG ratio (1.0 to 21.0).
 *
 * Written out rather than taken from Compose so that it is plain arithmetic on
 * two longs: this runs in a unit test, and the check it guards — a text colour
 * scraped from the wrong surface — is exactly the kind of thing that has to be
 * tested rather than eyeballed.
 */
internal fun contrastRatio(foreground: Long, background: Long): Float {
    val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
    val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun relativeLuminance(color: Long): Float {
    fun channel(shift: Int): Float {
        val raw = ((color ushr shift) and 0xFFL).toFloat() / 255f
        return if (raw <= 0.03928f) raw / 12.92f else Math.pow(((raw + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
}
