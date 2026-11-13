package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * A theme's own rule sheet: a few lines of FlorisBoard-style rules that the
 * user keeps on a theme and edits as text, compiled onto the theme's key
 * colours and single-key styles each time the theme is drawn.
 *
 * The language is FlorisBoard's snygg, read by the same parser the `.flex`
 * importer uses ([Stylesheet]), so a rule copied out of a FlorisBoard theme
 * works here unchanged and the editor is a JSON editor:
 *
 * ```json
 * {
 *   "@defines": { "--hot": "#FF3355" },
 *   "key": { "background": "#202124", "foreground": "#E8EAED" },
 *   "key[class=`modifier`]": { "background": "#2D2E31" },
 *   "key[code=10]": { "background": "var(--hot)", "shape": "circle()" },
 *   "key[code=10]:pressed": { "background": "#AA2233" },
 *   "key[output=`a`],key[output=`e`]": { "foreground": "var(--hot)" },
 *   "key-hint[code=113]": { "foreground": "#888888" }
 * }
 * ```
 *
 * A selector names one element (`key`, `key-hint`, `key-popup`), the keys it
 * styles, and at most one state:
 *
 * - `[code=…]` by FlorisBoard key code, lists and ranges allowed
 *   (`code=48..57`); `[output=…]` by what the key types or does
 *   (`a`, `action/enter`); `[id=…]` by this app's own single-key style name
 *   (`ENTER`, `EDIT_UNDO`, `TOOL_CLIPBOARD`), which reaches every key the
 *   style editor can pick.
 * - `[class=…]` by kind: `letter`, `modifier`, `enter`, `space`. A `key` with
 *   no brackets is the letters, as in a snygg sheet. A class rule sets the
 *   theme's own colour for that kind, so it reaches keys no code names.
 * - `:pressed` styles the key while it is held.
 *
 * Several selectors may share one body when they are separated by commas.
 * Later rules win. What a rule asks for that cannot land is reported, rule by
 * rule, in [RuleSheetResult.issues], so the editor can say why a line does
 * nothing rather than ignoring it.
 *
 * ### Why it is compiled at draw time and not saved into the theme
 *
 * The sheet is the user's text, kept as they wrote it in
 * [ThemeSpec.ruleSheet]. Writing its result into the stored colours would make
 * every edit in the theme editor fight the sheet, and a deleted rule would
 * leave its colour behind. Compiling on the way to the screen keeps the stored
 * theme and the sheet separate: the sheet always wins, and removing a line
 * removes its effect.
 */
object KeyRuleSheet {

    /** Longest sheet that is compiled; past this a sheet is more likely a paste accident than rules. */
    const val MAX_CHARS = 64 * 1024

    /**
     * [theme] with [ThemeSpec.ruleSheet] compiled onto it, and what could not
     * be. The palette is what `dynamic-dark-color(…)` resolves against.
     */
    fun compile(theme: ThemeSpec, palette: SnyggPalette = SnyggPalette.Baseline): RuleSheetResult {
        val text = theme.ruleSheet?.takeIf { it.isNotBlank() } ?: return RuleSheetResult(theme, 0, emptyList())
        if (text.length > MAX_CHARS) {
            return RuleSheetResult(theme, 0, listOf(RuleSheetIssue(RuleSheetIssue.Kind.TOO_LONG, "")))
        }
        val root = runCatching { FlexTheme.json.parseToJsonElement(expandSelectorLists(text) ?: text) }
            .getOrNull() as? JsonObject
            ?: return RuleSheetResult(theme, 0, listOf(RuleSheetIssue(RuleSheetIssue.Kind.UNREADABLE, "")))
        val defines = root[DEFINES]
        val issues = ArrayList<RuleSheetIssue>()
        var out = theme
        val overrides = LinkedHashMap(theme.keyOverrides)
        var applied = 0
        // Rule by rule through the snygg parser, so that every issue can quote
        // the selector as the author wrote it rather than the parser's alias.
        for ((raw, body) in root) {
            if (raw.startsWith('@')) continue
            val subject = raw.trim()
            if (body !is JsonObject) {
                issues += RuleSheetIssue(RuleSheetIssue.Kind.UNREADABLE, subject)
                continue
            }
            val single = JsonObject(buildMap {
                defines?.let { put(DEFINES, it) }
                put(raw, body)
            })
            val rule = Stylesheet.parse(single.toString(), palette, theme.dark)?.rules?.singleOrNull()
            if (rule == null || rule.element !in ELEMENTS) {
                issues += RuleSheetIssue(RuleSheetIssue.Kind.ELEMENT, subject)
                continue
            }
            if (rule.state != null && rule.state != PRESSED) {
                issues += RuleSheetIssue(RuleSheetIssue.Kind.STATE, subject)
                continue
            }
            if ((rule.attributes.keys - ATTRIBUTES).isNotEmpty()) {
                issues += RuleSheetIssue(RuleSheetIssue.Kind.ATTRIBUTE, subject)
                continue
            }
            val targets = targetsOf(rule, issues, subject)
            if (targets.isEmpty()) continue
            var landed = false
            for (target in targets) {
                val used = when (target) {
                    is Target.Key -> {
                        val before = overrides[target.id] ?: KeyOverride()
                        val (after, keys) = before.with(rule)
                        if (keys.isNotEmpty()) overrides[target.id] = after
                        keys
                    }
                    is Target.Kind -> {
                        val (after, keys) = out.with(rule, target.kind)
                        out = after
                        keys
                    }
                }
                if (used.isNotEmpty()) landed = true
                val unused = rule.properties.keys - used
                if (unused.isNotEmpty()) {
                    issues += RuleSheetIssue(RuleSheetIssue.Kind.PROPERTY, subject, unused.sorted())
                }
            }
            if (landed) applied++
        }
        return RuleSheetResult(out.copy(keyOverrides = overrides), applied, issues.distinct())
    }

    // ---- selectors ----

    private sealed interface Target {
        data class Key(val id: String) : Target
        data class Kind(val kind: KeyKind) : Target
    }

    /** The key kinds a `[class=…]` rule names, each with its own theme colours. */
    enum class KeyKind { LETTER, MODIFIER, ENTER }

    private fun targetsOf(rule: SnyggRule, issues: MutableList<RuleSheetIssue>, subject: String): List<Target> {
        if (rule.attributes.isEmpty()) return listOf(Target.Kind(KeyKind.LETTER))
        val out = ArrayList<Target>()
        var unknown = false
        for (code in rule.codes) {
            val id = SnyggMapper.overrideIdFor(code)
            if (id == null) unknown = true else out += Target.Key(id)
        }
        // A code that did not parse at all (`code=abc`) names no key either.
        if (ATTR_CODE in rule.attributes && rule.codes.isEmpty()) unknown = true
        for (output in rule.outputs) {
            val id = SnyggMapper.overrideIdForOutput(output)
            if (id == null) unknown = true else out += Target.Key(id)
        }
        for (raw in rule.attributes[ATTR_ID].orEmpty()) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            // Letters are styled by their lowercase label, everything else by
            // an upper-case action name; written either way, it finds its key.
            out += Target.Key(if (id.codePointCount(0, id.length) == 1) id.lowercase() else id.uppercase())
        }
        for (raw in rule.attributes[ATTR_CLASS].orEmpty()) {
            when (raw.trim().lowercase()) {
                "letter", "letters", "normal", "character" -> out += Target.Kind(KeyKind.LETTER)
                "modifier", "modifiers", "functional", "function" -> out += Target.Kind(KeyKind.MODIFIER)
                "enter", "action" -> out += Target.Kind(KeyKind.ENTER)
                "space", "spacebar" -> out += Target.Key(SPACE)
                else -> unknown = true
            }
        }
        if (unknown) issues += RuleSheetIssue(RuleSheetIssue.Kind.KEY, subject)
        return out.distinct()
    }

    /**
     * `a, b { … }` as two rules: CSS's selector list, which snygg's own grammar
     * lacks and a hand-written sheet reaches for first. Null when the text is
     * not a JSON object at all, so the parser can report it.
     */
    internal fun expandSelectorLists(text: String): String? {
        val root = runCatching { FlexTheme.json.parseToJsonElement(text) }.getOrNull()
            as? JsonObject ?: return null
        if (root.keys.none { ',' in it && !it.startsWith('@') && !insideBrackets(it) }) return text
        val expanded = LinkedHashMap<String, JsonElement>()
        for ((key, value) in root) {
            if (key.startsWith('@')) {
                expanded[key] = value
                continue
            }
            for (part in splitSelectors(key)) {
                // A selector stated twice keeps its place and takes the later body.
                expanded.remove(part)
                expanded[part] = value
            }
        }
        return JsonObject(expanded).toString()
    }

    /** Whether every comma in [selector] sits inside brackets, as in `code=1,2,3`. */
    private fun insideBrackets(selector: String): Boolean = splitSelectors(selector).size == 1

    private fun splitSelectors(selector: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var quote = false
        for (c in selector) {
            when {
                c == '`' -> { quote = !quote; current.append(c) }
                quote -> current.append(c)
                c == '[' -> { depth++; current.append(c) }
                c == ']' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { parts += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }

    // ---- properties ----

    /** This style with [rule] laid over it, and the properties that landed. */
    private fun KeyOverride.with(rule: SnyggRule): Pair<KeyOverride, Set<String>> {
        val used = linkedSetOf<String>()
        fun colour(property: String): Long? = snyggColor(rule.value(property))?.also { used += property }
        var next = this
        when (rule.element) {
            EL_KEY -> if (rule.state == PRESSED) {
                colour(PROP_BACKGROUND)?.let { next = next.copy(pressedBackground = it) }
            } else {
                colour(PROP_BACKGROUND)?.let { next = next.copy(background = it) }
                colour(PROP_FOREGROUND)?.let { next = next.copy(text = it) }
                colour(PROP_BORDER_COLOR)?.let { next = next.copy(border = it) }
                snyggShape(rule.value(PROP_SHAPE), mutableSetOf())?.let { (kind, radius) ->
                    used += PROP_SHAPE
                    next = next.copy(
                        shape = kind.name,
                        cornerRadiusDp = radius?.coerceIn(KEY_OVERRIDE_RADIUS_RANGE) ?: next.cornerRadiusDp,
                    )
                }
                labelScale(rule.value(PROP_FONT_SIZE))?.let {
                    used += PROP_FONT_SIZE
                    next = next.copy(labelScale = it)
                }
                rule.value(PROP_FONT_WEIGHT)?.let { weight ->
                    used += PROP_FONT_WEIGHT
                    next = next.copy(bold = isBold(weight))
                }
            }
            EL_HINT -> if (rule.state == null) colour(PROP_FOREGROUND)?.let { next = next.copy(hint = it) }
            EL_POPUP -> if (rule.state == null) {
                colour(PROP_BACKGROUND)?.let { next = next.copy(popupBackground = it) }
                colour(PROP_FOREGROUND)?.let { next = next.copy(popupText = it) }
            }
        }
        return next to used
    }

    /** This theme with a `[class=…]` rule laid over one kind of key, and the properties that landed. */
    private fun ThemeSpec.with(rule: SnyggRule, kind: KeyKind): Pair<ThemeSpec, Set<String>> {
        val used = linkedSetOf<String>()
        fun colour(property: String): Long? = snyggColor(rule.value(property))?.also { used += property }
        var next = this
        val letters = kind == KeyKind.LETTER
        when (rule.element) {
            EL_KEY -> if (rule.state == PRESSED) {
                // One held colour serves the whole board here.
                if (letters) colour(PROP_BACKGROUND)?.let { next = next.copy(pressedKeyBackground = it) }
            } else {
                colour(PROP_BACKGROUND)?.let {
                    next = when (kind) {
                        KeyKind.LETTER -> next.copy(keyBackground = it)
                        KeyKind.MODIFIER -> next.copy(modifierKeyBackground = it)
                        KeyKind.ENTER -> next.copy(enterKeyBackground = it)
                    }
                }
                colour(PROP_FOREGROUND)?.let {
                    next = when (kind) {
                        KeyKind.LETTER -> next.copy(keyText = it)
                        KeyKind.MODIFIER -> next.copy(modifierKeyText = it)
                        KeyKind.ENTER -> next.copy(enterKeyText = it)
                    }
                }
                if (letters) {
                    colour(PROP_BORDER_COLOR)?.let { next = next.copy(keyBorderColor = it) }
                    snyggShape(rule.value(PROP_SHAPE), mutableSetOf())?.let { (shape, radius) ->
                        used += PROP_SHAPE
                        next = next.copy(keyShape = shape, keyCornerRadiusDp = radius ?: next.keyCornerRadiusDp)
                    }
                    rule.value(PROP_FONT_WEIGHT)?.let { weight ->
                        used += PROP_FONT_WEIGHT
                        next = next.copy(boldKeyLabels = isBold(weight))
                    }
                }
            }
            EL_HINT -> if (letters && rule.state == null) colour(PROP_FOREGROUND)?.let { next = next.copy(hintText = it) }
            EL_POPUP -> if (letters && rule.state == null) {
                colour(PROP_BACKGROUND)?.let { next = next.copy(popupBackground = it) }
                colour(PROP_FOREGROUND)?.let { next = next.copy(popupText = it) }
            }
        }
        return next to used
    }

    /** A `font-size` in sp as a multiple of an ordinary label, or null at the default size. */
    private fun labelScale(raw: String?): Float? {
        val size = snyggDp(raw)?.takeIf { it > 0f } ?: return null
        val scale = size / LABEL_SP
        if (abs(scale - 1f) < SCALE_EPSILON) return null
        return scale.coerceIn(KEY_OVERRIDE_LABEL_SCALE_RANGE)
    }

    private fun isBold(weight: String): Boolean {
        val text = weight.trim().lowercase()
        return text.contains("bold") || (text.toIntOrNull() ?: 0) >= BOLD_WEIGHT
    }

    private const val PRESSED = "pressed"
    private const val ATTR_CLASS = "class"
    private const val ATTR_ID = "id"
    private const val SPACE = "SPACE"
    private val ATTRIBUTES = setOf(ATTR_CODE, ATTR_OUTPUT, ATTR_CLASS, ATTR_ID)
    private val ELEMENTS = setOf(EL_KEY, EL_HINT, EL_POPUP)
    private const val DEFINES = "@defines"

    /** The label size snygg's own key rule uses, which `font-size` is measured against. */
    private const val LABEL_SP = 22f
    private const val SCALE_EPSILON = 0.03f
    private const val BOLD_WEIGHT = 600
}

/** A compiled rule sheet. */
data class RuleSheetResult(
    /** The theme to draw: the stored one with the sheet laid over it. */
    val theme: ThemeSpec,
    /** How many rules changed something. */
    val applied: Int,
    val issues: List<RuleSheetIssue>,
)

/** Something one rule asked for that did not land, and the rule, written back out. */
data class RuleSheetIssue(
    val kind: Kind,
    val rule: String,
    /** For [Kind.PROPERTY], the properties that did nothing. */
    val properties: List<String> = emptyList(),
) {
    enum class Kind {
        /** Not a JSON object, or no rule in it the parser could read. */
        UNREADABLE,

        /** Past [KeyRuleSheet.MAX_CHARS]; nothing was compiled. */
        TOO_LONG,

        /** An element other than `key`, `key-hint` or `key-popup`. */
        ELEMENT,

        /** A state other than `pressed`. */
        STATE,

        /** A bracket other than `code`, `output`, `id` or `class`. */
        ATTRIBUTE,

        /** A code, output or class that names no key here. */
        KEY,

        /** Properties this element does not take here. */
        PROPERTY,
    }
}

/**
 * This theme as it should be drawn: its rule sheet compiled onto it, or the
 * same instance when it has none, so a caller remembering on the result
 * recomposes nothing extra.
 */
fun ThemeSpec.withRuleSheet(palette: SnyggPalette = SnyggPalette.Baseline): ThemeSpec =
    if (ruleSheet.isNullOrBlank()) this else KeyRuleSheet.compile(this, palette).theme
