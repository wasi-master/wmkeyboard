package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/**
 * Reading a layout written for Unexpected Keyboard, or for CleverKeys, which
 * reads and shares the same files.
 *
 * Unexpected Keyboard has no layout editor beyond a text box: a custom layout
 * is an XML document pasted into it, and that is how people share them, in the
 * project's own community repository, in gists and in issue threads. A layout
 * looks like this:
 *
 * ```xml
 * <keyboard name="QWERTY (US)" script="latin">
 *   <row>
 *     <key c="q" ne="1" se="loc esc"/>
 *     <key shift="0.5" c="a" nw="tab" ne="`"/>
 *     <key width="1.5" c="shift"/>
 *   </row>
 *   <modmap><shift a="i" b="İ"/></modmap>
 * </keyboard>
 * ```
 *
 * **Written from the published format, not from either project's code.** The
 * project documents the format in `doc/Custom-layouts.md` and its key values in
 * `doc/Possible-key-values.md`; what is transcribed here is the shape of a
 * document and the meaning of its names, the same footing as the FlorisBoard,
 * FUTO and Multiling readers beside it. Both projects are GPL-3.0; this app is
 * MIT, and nothing of theirs is copied.
 *
 * ### A key is a tap and eight swipes
 *
 * `c` (or the older `key0`) is what a tap types, and `n`, `ne`, `e`, `se`, `s`,
 * `sw`, `w`, `nw` (or `key1`…`key8`) are what a swipe from the key towards
 * that corner types. That is this keyboard's flick key exactly (see
 * [Key.flick]), so the swipes come across as swipes, drawn on the key's face as
 * they were there. A swipe that types text is also put on the key's press and
 * hold, where a user who does not know the swipes are there can find them.
 *
 * Text arms work only on a key whose tap types text, and action arms only where
 * the key's own drag or hold is free (the delete key's `ne="delete"`, a space
 * bar's cursor swipes). The rest are counted and named in the notes.
 *
 * ### Key values
 *
 * A value is a named key (`shift`, `backspace`, `switch_numeric`, `copy`,
 * `accent_aigu`, `f5`…), `legend:definition` (a key with a label of its own),
 * a macro (`legend:ctrl,a`), `keyevent:<code>`, or plain text. A value starting
 * with `loc ` is a slot the other keyboard fills only when a setting asks it
 * to, and is hidden otherwise, so it is skipped without a note. A named key
 * this keyboard has nothing for is removed and named.
 *
 * The accent keys are dead keys there, and become dead keys here: a key that
 * types the combining mark, which is what this keyboard's composer treats as a
 * dead key (see `DeadKeys` in `:core:input`).
 *
 * ### The frame
 *
 * Unexpected Keyboard adds a bottom row of its own unless the file says
 * `bottom_row="false"`, so a file that leaves it on gets this keyboard's bottom
 * row in its place, the way every other foreign format does (see
 * [withHouseStructure]). A file that turned it off and wrote its own, with a
 * space bar and a delete key in it, keeps its grid as it laid it out. The
 * layout's number row is this keyboard's to draw, except for a file that says
 * `embedded_number_row="true"`, whose row of digits becomes the layout's own.
 *
 * A key's `shift` is empty space to its left, and becomes a gap. Rows there
 * are left-aligned, and a row this keyboard would centre instead, one that
 * starts with such a gap, is padded on the right so the keys land where the
 * author put them.
 *
 * CleverKeys adds one attribute: `language` on `<keyboard>`, the language the
 * layout is meant for. It seeds the language here.
 */
object UnexpectedLayouts {

    /** Far above any real layout: the biggest built-in one is under 10 KB. */
    const val MAX_LENGTH = 512 * 1024

    /**
     * True when [text] is shaped like one of these: a `<keyboard>` element with
     * at least one `<key>` in it. String-only and cheap, so it can sit in front
     * of the other readers; no other format this app reads is XML with those
     * two elements.
     */
    fun looksLikeUnexpected(text: String): Boolean {
        if (text.isEmpty() || text.length > MAX_LENGTH) return false
        return KeyboardOpen.containsMatchIn(text) && KeyOpen.containsMatchIn(text)
    }

    /**
     * Converts [text]. Null when it is not one of these, or holds no keys.
     * Never throws.
     *
     * [languageTag] is a language the caller knows the layout is for, such as
     * the binding a CleverKeys backup stores beside it. It wins over the file's
     * own `language` attribute, which wins over a guess from the letters.
     */
    fun convert(text: String, name: String, languageTag: String? = null): ConvertedLayout? = runCatching {
        if (text.length > MAX_LENGTH) return null
        // No DOCTYPE, no DTD, no entities: the rule every XML reader here keeps.
        if (text.contains("<!DOCTYPE", ignoreCase = true)) return null
        val doc = parseDocument(text) ?: return null
        build(doc, name, languageTag)
    }.getOrNull()

    // ---- the document ----

    private class Document(
        val attrs: Map<String, String>,
        val rows: List<RawRow>,
        val shiftMap: List<Pair<String, String>>,
    )

    private class RawRow(val attrs: Map<String, String>, val keys: List<Map<String, String>>)

    private fun parseDocument(text: String): Document? {
        var keyboard: Map<String, String>? = null
        val rows = mutableListOf<RawRow>()
        val shiftMap = mutableListOf<Pair<String, String>>()
        var row: Pair<Map<String, String>, MutableList<Map<String, String>>>? = null
        var inModmap = false
        for (tag in scanTags(text)) {
            val name = tag.name.lowercase()
            if (keyboard == null) {
                if (name == "keyboard" && !tag.closing) keyboard = tag.attrs
                continue
            }
            when {
                name == "keyboard" && tag.closing -> break
                name == "row" && tag.closing -> {
                    row?.let { (attrs, keys) -> rows += RawRow(attrs, keys) }
                    row = null
                }
                name == "row" -> {
                    if (tag.selfClosing) {
                        rows += RawRow(tag.attrs, emptyList())
                    } else {
                        row = tag.attrs to mutableListOf()
                    }
                }
                name == "key" && !tag.closing -> row?.second?.add(tag.attrs)
                name == "modmap" -> inModmap = !tag.closing && !tag.selfClosing
                // Only Shift has a place here. The Fn and Ctrl mappings change
                // what a key does under modifiers this keyboard applies its own
                // way, and are left out without a note: they are not keys.
                inModmap && name == "shift" && !tag.closing -> {
                    val a = tag.attrs["a"]
                    val b = tag.attrs["b"]
                    if (a != null && b != null) shiftMap += a to b
                }
            }
        }
        val attrs = keyboard ?: return null
        return Document(attrs, rows, shiftMap)
    }

    // ---- building the grid ----

    private class BuiltRow(val keys: List<Key>, val height: Float, val leadingGap: Boolean)

    private fun build(doc: Document, fileName: String, languageTag: String?): ConvertedLayout? {
        val report = Report()
        val shifted = doc.shiftMap.mapNotNull { (a, b) ->
            val from = (parseValue(a, Report()) as? Value.Text)?.output ?: return@mapNotNull null
            val target = (parseValue(b, Report()) as? Value.Text)?.output ?: return@mapNotNull null
            from to target
        }.toMap()

        val built = doc.rows.map { buildRow(it, shifted, report) }.filter { it.keys.isNotEmpty() }
        if (built.isEmpty() || built.all { row -> row.keys.all { it.action == KeyAction.None } }) return null
        val widest = doc.attrs.float("width")?.takeIf { it > 0f }
            ?: built.maxOf { it.keys.totalWidth() }
        var rows = built.map { row -> alignedLeft(row, widest) }
        var heights = built.map { it.height }

        // The attribute says the layout has a number row, not which row it is:
        // the first, when it is digits and there is a grid left under it.
        val numberRow = if (doc.attrs.bool("embedded_number_row") == true && rows.size > 1) {
            rows.first().takeIf { it.isDigitRow() }
        } else {
            null
        }
        if (numberRow != null) {
            rows = rows.drop(1)
            heights = heights.drop(1)
            report.numberRowKept = true
        }
        if (rows.isEmpty()) return null

        val keys = rows.flatten()
        val complete = keys.any { it.action == KeyAction.Space } && keys.any { it.action == KeyAction.Delete }
        val ownBottomRow = doc.attrs.bool("bottom_row") == false
        val converted = ForeignLayouts.assemble(
            rows = rows,
            name = fileName,
            source = ForeignSource.UNEXPECTED,
            report = report,
            langId = languageTag?.let(::languageId) ?: doc.attrs["language"]?.let(::languageId),
            numberRow = numberRow,
            houseFrame = !(ownBottomRow && complete),
        ) ?: return null
        val title = doc.attrs["name"]?.trim()?.ifEmpty { null }
        return converted.withRowHeights(heights).let { c ->
            if (title == null) c else c.copy(layout = c.layout.copy(name = title))
        }
    }

    private fun buildRow(row: RawRow, shifted: Map<String, String>, report: Report): BuiltRow {
        val keys = mutableListOf<Key>()
        var leadingGap = false
        row.attrs.float("shift")?.takeIf { it >= MIN_GAP }?.let {
            keys += gapKey(it)
            leadingGap = true
        }
        for (attrs in row.keys) {
            attrs.float("shift")?.takeIf { it >= MIN_GAP }?.let {
                if (keys.isEmpty()) leadingGap = true
                keys += gapKey(it)
            }
            keys += keyOf(attrs, shifted, report)
        }
        // `scale` asks for the row's widths to be stretched to that total.
        val scale = row.attrs.float("scale")?.takeIf { it > 0f }
        val total = keys.totalWidth()
        val scaled = if (scale != null && total > 0f) {
            keys.map { it.copy(width = (it.width * scale / total).coerceIn(MIN_WIDTH, MaxKeyWidth)) }
        } else {
            keys
        }
        val height = row.attrs.float("height")?.takeIf { it > 0f } ?: 1f
        return BuiltRow(scaled, height, leadingGap)
    }

    /**
     * A row that starts with a gap and is narrower than the keyboard, padded on
     * the right. There rows are left-aligned; here a short row is centred, which
     * would move a hand-placed home row by half its slack.
     */
    private fun alignedLeft(row: BuiltRow, widest: Float): List<Key> {
        val slack = widest - row.keys.totalWidth()
        return if (row.leadingGap && slack >= MIN_GAP) row.keys + gapKey(slack) else row.keys
    }

    private fun List<Key>.totalWidth(): Float = sumOf { it.width.toDouble() }.toFloat()

    private fun List<Key>.isDigitRow(): Boolean {
        val keys = filter { it.action != KeyAction.None }
        return keys.isNotEmpty() &&
            keys.all { key -> key.action == KeyAction.Text && (key.output ?: key.label).all { it.isDigit() } }
    }

    /**
     * The file's row heights, on the rows they belong to. The house frame only
     * ever adds a row at the bottom or rebuilds the last one, so the letter rows
     * keep their indices; a row it adds takes the ordinary height.
     */
    private fun ConvertedLayout.withRowHeights(heights: List<Float>): ConvertedLayout {
        if (heights.all { it == 1f }) return this
        val key = LayoutLayer.LETTERS.key
        val layer = layout.layers[key] ?: return this
        val clamped = heights.map { it.coerceIn(MIN_ROW_HEIGHT, MAX_ROW_HEIGHT) }
        return copy(layout = layout.copy(layers = layout.layers + (key to layer.copy(rowHeights = clamped))))
    }

    // ---- keys ----

    private fun keyOf(attrs: Map<String, String>, shifted: Map<String, String>, report: Report): Key {
        val width = attrs.float("width")?.takeIf { it > 0f && it.isFinite() } ?: 1f
        val tapValue = slot(attrs, "c", "key0")?.let { parseValue(it, report) }
        val arms = Directions.mapNotNull { (direction, names) ->
            slot(attrs, *names)?.let { parseValue(it, report) }?.let { direction to it }
        }
        // A key with swipes and no tap is a swipe-only key, here as there
        // (#410): no centre, and a tap types nothing.
        if (tapValue == null && arms.isEmpty()) return gapKey(width)
        val base = tapValue?.let { keyFrom(it, width) } ?: Key(label = "", width = width)

        val textArms = arms.mapNotNull { (d, v) -> (v as? Value.Text)?.let { d to normalizeForScript(it.output) } }
            .filter { it.second.isNotEmpty() }.toMap()
        val actionArms = arms.mapNotNull { (d, v) -> (v as? Value.Action)?.let { d to KeyAlternate(v.action, v.label) } }
            .toMap()
        val keptText = if (base.action == KeyAction.Text) textArms else emptyMap()
        val keptActions = if (base.takesFlickActions()) actionArms else emptyMap()
        report.gesturesDropped += (textArms.size - keptText.size) + (actionArms.size - keptActions.size)

        val tapText = base.output ?: base.label
        val shiftLabel = shifted[tapText]?.let(::normalizeForScript)
            ?.takeIf { base.action == KeyAction.Text && it.isNotEmpty() && it != tapText.uppercase() }
        val flickShift = keptText.mapNotNull { (d, text) ->
            shifted[text]?.let(::normalizeForScript)?.takeIf { it.isNotEmpty() && it != text.uppercase() }?.let { d to it }
        }.toMap()
        val longPress = keptText.values.filter { it != tapText }.distinct()
        return base.copy(
            shiftLabel = shiftLabel ?: base.shiftLabel,
            flick = keptText,
            flickShift = flickShift,
            flickActions = keptActions,
            longPress = longPress,
            // The swipes are drawn on the face; a corner hint would draw one
            // of them a second time.
            hideHint = keptText.isNotEmpty(),
        )
    }

    /** The first of [names] the key sets. The format allows either spelling. */
    private fun slot(attrs: Map<String, String>, vararg names: String): String? =
        names.firstNotNullOfOrNull { attrs[it] }?.takeIf { it.isNotEmpty() }

    private fun keyFrom(value: Value, width: Float): Key = when (value) {
        is Value.Text -> {
            val label = normalizeForScript(value.label)
            val output = normalizeForScript(value.output)
            Key(label = label.ifEmpty { output }, output = output.takeIf { it != label }, width = width)
        }
        is Value.Action -> Key(label = value.label, action = value.action, width = width)
    }

    private fun gapKey(width: Float): Key = Key(label = "", action = KeyAction.None, width = width)

    // ---- key values ----

    /** What one key value means: text to type, or an action to run. */
    private sealed interface Value {
        data class Text(val output: String, val label: String = output) : Value
        data class Action(val action: KeyAction, val label: String) : Value
    }

    /** A named key's meaning, or [Miss] when the name is not one. */
    private sealed interface Lookup {
        /** A name the format knows. [value] is null when it has no key here. */
        data class Found(val value: Value?) : Lookup
        data object Miss : Lookup
    }

    /**
     * What [raw] means, or null when it has no key here (reported where it is
     * a loss). A value that does not parse is the text it is made of, which is
     * the other keyboard's own rule and the reason a bare `:` key works there.
     */
    private fun parseValue(raw: String, report: Report): Value? {
        if (raw.isEmpty() || raw.startsWith(LOC_PREFIX)) return null
        (special(raw, legend = null, report) as? Lookup.Found)?.let { return it.value }
        if (raw.startsWith(':')) return oldSyntax(raw) ?: Value.Text(raw)
        val colon = raw.indexOf(':')
        if (colon < 0) return Value.Text(raw)
        val legend = raw.substring(0, colon)
        val defs = splitDefinitions(raw.substring(colon + 1))
        if (defs.isNullOrEmpty()) return Value.Text(raw)
        if (defs.size == 1) return definition(defs.single(), legend, report)
        return macro(defs, legend, raw, report)
    }

    /** One `key_def`: a quoted string, a key event, or a name (else literal text). */
    private fun definition(def: Def, legend: String?, report: Report): Value? = when (def) {
        is Def.Str -> Value.Text(def.text, legend?.ifEmpty { null } ?: def.text)
        is Def.Event -> {
            val action = KeyAction.SendKey(def.code)
            Value.Action(action, legend?.ifEmpty { null } ?: action.fallbackLabel())
        }
        is Def.Name -> when (val found = special(def.name, legend, report)) {
            is Lookup.Found -> found.value
            Lookup.Miss -> Value.Text(def.name, legend?.ifEmpty { null } ?: def.name)
        }
    }

    /**
     * `legend:a,b,c`: a key that acts as if each were pressed in turn. Text
     * all the way through is one run of text; modifiers then a letter or digit
     * is a shortcut, sent as one key press. Anything else (a sequence with a
     * key event in the middle, a word deletion after a selection) has no key
     * here and is named.
     */
    private fun macro(defs: List<Def>, legend: String, raw: String, report: Report): Value? {
        val values = defs.map { definition(it, null, Report()) }
        if (values.all { it is Value.Text }) {
            val text = values.joinToString("") { (it as Value.Text).output }
            return Value.Text(text, legend.ifEmpty { text })
        }
        val metas = values.dropLast(1).map { it.modifierMeta() }
        val last = values.last() as? Value.Text
        val code = last?.output?.singleOrNull()?.let(::keyCodeFor)
        if (code != null && metas.isNotEmpty() && metas.all { it != null }) {
            val meta = metas.filterNotNull().fold(0) { acc, m -> acc or m }
            return Value.Action(KeyAction.SendKey(code, meta), legend.ifEmpty { last.output })
        }
        report.droppedLabels += raw
        return null
    }

    private fun Value?.modifierMeta(): Int? {
        val action = (this as? Value.Action)?.action ?: return null
        return when {
            action == KeyAction.Shift -> META_SHIFT_ON
            action is KeyAction.Mod -> when (action.key) {
                ModifierKey.CTRL -> META_CTRL_ON
                ModifierKey.ALT -> META_ALT_ON
                ModifierKey.META -> META_META_ON
            }
            else -> null
        }
    }

    /**
     * The older `:kind attributes:payload` spelling, which the other keyboard
     * still reads: `:str symbol='x':'text'`, `:char …:c` and `:keyevent …:85`.
     * Null when it is not that shape, and the value is then its own text.
     */
    private fun oldSyntax(raw: String): Value? {
        val kind = raw.drop(1).takeWhile { it.isLetter() }
        val payloadStart = raw.lastIndexOf(':')
        if (kind.isEmpty() || payloadStart <= kind.length) return null
        val symbol = OldSymbol.find(raw)?.groupValues?.get(1)?.let(::unescape)?.ifEmpty { null }
        val payload = raw.substring(payloadStart + 1)
        return when (kind) {
            "str" -> payload.takeIf { it.length >= 2 && it.startsWith('\'') && it.endsWith('\'') }
                ?.let { unescape(it.substring(1, it.length - 1)) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { Value.Text(it, symbol ?: it) }
            "char" -> payload.takeIf { it.codePointCount(0, it.length) == 1 }?.let { Value.Text(it, symbol ?: it) }
            "keyevent" -> payload.toIntOrNull()?.let { code ->
                Value.Action(KeyAction.SendKey(code), symbol ?: code.toString())
            }
            else -> null
        }
    }

    private sealed interface Def {
        data class Str(val text: String) : Def
        data class Event(val code: Int) : Def
        data class Name(val name: String) : Def
    }

    /**
     * The definitions after a legend: `'quoted strings'` (where `\'` is a
     * quote), `keyevent:<n>`, and bare names, separated by commas. A backslash
     * takes the next character literally. Null when the text is malformed,
     * the way the other keyboard refuses it.
     */
    private fun splitDefinitions(text: String): List<Def>? {
        val out = mutableListOf<Def>()
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '\'' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '\'') {
                        if (text[i] == '\\' && i + 1 < text.length) i++
                        sb.append(text[i])
                        i++
                    }
                    if (i >= text.length) return null
                    i++
                    out += Def.Str(sb.toString())
                }
                text.startsWith(KEYEVENT_PREFIX, i) -> {
                    i += KEYEVENT_PREFIX.length
                    val digits = text.substring(i).takeWhile { it.isDigit() }
                    out += Def.Event(digits.toIntOrNull() ?: return null)
                    i += digits.length
                }
                else -> {
                    val sb = StringBuilder()
                    while (i < text.length && text[i] != ',' && text[i] != '\'') {
                        if (text[i] == '\\' && i + 1 < text.length) i++
                        sb.append(text[i])
                        i++
                    }
                    if (sb.isEmpty()) return null
                    out += Def.Name(sb.toString())
                }
            }
            if (i < text.length) {
                if (text[i] != ',') return null
                i++
                if (i >= text.length) return null
            }
        }
        return out
    }

    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) i++
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    /**
     * The named key [name] is, with [legend] as its label where the file gave
     * one. The names and what they do are the format's published list.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun special(name: String, legend: String?, report: Report): Lookup {
        val given = legend?.ifEmpty { null }
        fun act(action: KeyAction, label: String? = null) =
            Lookup.Found(Value.Action(action, actionLabel(action, given ?: label)))
        fun near(action: KeyAction): Lookup {
            report.approximated++
            return act(action)
        }
        fun text(output: String, label: String = output) = Lookup.Found(Value.Text(output, given ?: label))
        fun edit(op: TextEditAction) = act(KeyAction.Edit(op))
        fun send(code: Int, label: String? = null) = act(KeyAction.SendKey(code), label)

        FunctionKeys[name]?.let { n -> return send(KEYCODE_F1 + n - 1, "F$n") }
        Accents[name]?.let { (mark, label) -> return text(mark.toString(), label) }
        CombiningMarks[name]?.let { mark -> return text(mark.toString(), "◌$mark") }
        HebrewMarks[name]?.let { (mark, label) -> return text(mark.toString(), label) }
        Mirrored[name]?.let { (output, label) -> return text(output, label) }
        if (name in Silent || name.endsWith(PLACEHOLDER_SUFFIX)) return Lookup.Found(null)
        if (name in Unsupported) {
            report.droppedLabels += name
            return Lookup.Found(null)
        }
        return when (name) {
            "shift" -> act(KeyAction.Shift)
            "capslock" -> act(KeyAction.CapsLock)
            "ctrl" -> act(KeyAction.Mod(ModifierKey.CTRL))
            "alt" -> act(KeyAction.Mod(ModifierKey.ALT))
            "meta" -> act(KeyAction.Mod(ModifierKey.META))
            "fn" -> act(KeyAction.Fn)
            "backspace" -> act(KeyAction.Delete)
            "delete" -> act(KeyAction.ForwardDelete)
            "delete_word" -> edit(TextEditAction.DELETE_WORD)
            "forward_delete_word" -> edit(TextEditAction.DELETE_WORD_FORWARD)
            "space" -> act(KeyAction.Space)
            "enter" -> act(KeyAction.Enter)
            "action" -> act(KeyAction.EditorAction)
            "esc" -> send(KEYCODE_ESCAPE)
            "tab" -> send(KEYCODE_TAB)
            "up" -> send(KEYCODE_DPAD_UP)
            "down" -> send(KEYCODE_DPAD_DOWN)
            "left" -> send(KEYCODE_DPAD_LEFT)
            "right" -> send(KEYCODE_DPAD_RIGHT)
            "page_up" -> send(KEYCODE_PAGE_UP, "PgUp")
            "page_down" -> send(KEYCODE_PAGE_DOWN, "PgDn")
            "home" -> send(KEYCODE_MOVE_HOME, "Home")
            "end" -> send(KEYCODE_MOVE_END, "End")
            "insert" -> send(KEYCODE_INSERT, "Ins")
            "scroll_lock" -> send(KEYCODE_SCROLL_LOCK, "ScrLk")
            "menu" -> send(KEYCODE_MENU, "Menu")
            // The slider keys move the caret as the finger slides; one step
            // per press is the nearest thing a key does here.
            "cursor_left" -> near(KeyAction.Edit(TextEditAction.LEFT))
            "cursor_right" -> near(KeyAction.Edit(TextEditAction.RIGHT))
            "cursor_up" -> near(KeyAction.Edit(TextEditAction.UP))
            "cursor_down" -> near(KeyAction.Edit(TextEditAction.DOWN))
            "copy" -> edit(TextEditAction.COPY)
            "paste" -> edit(TextEditAction.PASTE)
            "cut" -> edit(TextEditAction.CUT)
            "selectAll" -> edit(TextEditAction.SELECT_ALL)
            "pasteAsPlainText" -> near(KeyAction.Edit(TextEditAction.PASTE))
            "undo" -> act(KeyAction.Tool(ToolbarTool.UNDO))
            "redo" -> act(KeyAction.Tool(ToolbarTool.REDO))
            "switch_text", "switch_back_emoji", "switch_back_clipboard" -> act(KeyAction.Letters)
            // Their numeric page is digits and symbols together, which is what
            // this keyboard's ?123 page is.
            "switch_numeric" -> act(KeyAction.Symbols)
            "switch_emoji" -> act(KeyAction.Emoji)
            "switch_clipboard" -> act(KeyAction.Tool(ToolbarTool.CLIPBOARD))
            "switch_forward" -> act(KeyAction.LanguageSwitch)
            "switch_backward" -> near(KeyAction.LanguageSwitch)
            "change_method" -> act(KeyAction.InputMethodPicker)
            "change_method_prev", "change_method_next" -> near(KeyAction.InputMethodPicker)
            "config" -> act(KeyAction.Tool(ToolbarTool.SETTINGS))
            "voice_typing", "voice_typing_chooser" -> act(KeyAction.Tool(ToolbarTool.VOICE))
            "hide_self" -> act(KeyAction.Tool(ToolbarTool.HIDE_KEYBOARD))
            "selection_mode" -> near(KeyAction.Tool(ToolbarTool.SELECT_MODE))
            "\\n" -> act(KeyAction.Newline)
            "\\t" -> text("\t", "⇥")
            "\\?" -> text("?")
            "\\#" -> text("#")
            "\\@" -> text("@")
            "\\\\" -> text("\\")
            "nbsp" -> text(" ", "⍽")
            "nnbsp" -> text(" ", "␣")
            "zwj" -> text("‍", "ZWJ")
            "zwnj", "halfspace" -> text("‌", "ZWNJ")
            "lrm" -> text("‎", "↱")
            "rlm" -> text("‏", "↰")
            else -> Lookup.Miss
        }
    }

    /**
     * The label an action key draws. The keys this keyboard draws from icon
     * slots keep their icons whatever the file calls them, the rule the
     * HeliBoard reader keeps; the rest draw the file's legend when it has one.
     */
    private fun actionLabel(action: KeyAction, given: String?): String {
        val fallback = action.fallbackLabel()
        if (action in IconActions || given == null) return fallback
        return normalizeForScript(given).ifEmpty { fallback }
    }

    private fun languageId(tag: String): String? {
        val clean = tag.trim().replace('-', '_').ifEmpty { return null }
        return LanguageRegistry.all.firstOrNull { it.id.equals(clean, ignoreCase = true) }?.id
            ?: LanguageRegistry.byLocale(clean)?.id
    }

    /** The key code a shortcut letter or digit sends, or null for anything else. */
    private fun keyCodeFor(c: Char): Int? = when (val lower = c.lowercaseChar()) {
        in 'a'..'z' -> KEYCODE_A + (lower - 'a')
        in '0'..'9' -> KEYCODE_0 + (lower - '0')
        else -> null
    }

    // ---- the XML ----

    private class Tag(
        val name: String,
        val attrs: Map<String, String>,
        val closing: Boolean,
        val selfClosing: Boolean,
    )

    /**
     * The document's tags, in order. A scan rather than an XML parser: these
     * files are three elements deep, Android's parsers cannot be told to refuse
     * a DOCTYPE (that is checked before this runs), and a hand-pasted layout
     * with a stray ampersand should still come across rather than fail whole.
     */
    private fun scanTags(text: String): List<Tag> {
        val out = mutableListOf<Tag>()
        var i = 0
        while (true) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            val skipTo = when {
                text.startsWith("<!--", lt) -> text.indexOf("-->", lt + 4).let { if (it < 0) -1 else it + 3 }
                text.startsWith("<![CDATA[", lt) -> text.indexOf("]]>", lt).let { if (it < 0) -1 else it + 3 }
                text.startsWith("<?", lt) -> text.indexOf("?>", lt).let { if (it < 0) -1 else it + 2 }
                text.startsWith("<!", lt) -> text.indexOf('>', lt).let { if (it < 0) -1 else it + 1 }
                else -> null
            }
            if (skipTo != null) {
                if (skipTo < 0) break
                i = skipTo
                continue
            }
            val end = tagEnd(text, lt)
            if (end < 0) break
            parseTag(text.substring(lt + 1, end))?.let(out::add)
            i = end + 1
        }
        return out
    }

    /** The `>` that closes the tag opened at [open], skipping quoted values. */
    private fun tagEnd(text: String, open: Int): Int {
        var quote: Char? = null
        for (i in open + 1 until text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
        }
        return -1
    }

    private fun parseTag(inner: String): Tag? {
        val closing = inner.startsWith('/')
        val body = inner.removePrefix("/").trimEnd()
        val selfClosing = body.endsWith('/')
        val content = body.removeSuffix("/")
        val name = content.takeWhile { !it.isWhitespace() }
        if (name.isEmpty()) return null
        val attrs = LinkedHashMap<String, String>()
        for (match in Attribute.findAll(content, name.length)) {
            val value = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
            attrs[match.groupValues[1]] = decodeEntities(value)
        }
        return Tag(name, attrs, closing, selfClosing)
    }

    private fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return Entity.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(HEX)?.codePointText()
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.codePointText()
                else -> NamedEntities[body]
            } ?: match.value
        }
    }

    private fun Int.codePointText(): String? =
        takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }

    private fun Map<String, String>.float(key: String): Float? =
        this[key]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun Map<String, String>.bool(key: String): Boolean? = when (this[key]?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    // ---- constants ----

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 58 KB across this file's patterns.
    private val KeyboardOpen by lazy { Regex("""<keyboard[\s>/]""") }
    private val KeyOpen by lazy { Regex("""<key[\s/]""") }
    private val Attribute by lazy { Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""") }
    private val Entity by lazy { Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);""") }
    private val OldSymbol by lazy { Regex("""symbol='((?:[^'\\]|\\.)*)'""") }
    private val NamedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

    private const val LOC_PREFIX = "loc "
    private const val KEYEVENT_PREFIX = "keyevent:"
    private const val PLACEHOLDER_SUFFIX = "_placeholder"
    private const val HEX = 16

    /** Narrower than this, a `shift` is a rounding error rather than a gap. */
    private const val MIN_GAP = 0.05f
    private const val MIN_WIDTH = 0.1f
    private const val MIN_ROW_HEIGHT = 0.5f
    private const val MAX_ROW_HEIGHT = 2f

    /**
     * The swipe attributes, by the direction each one points, compass name
     * first and the older numbered name after. Also the order a key's swipes
     * are listed on its press and hold.
     */
    private val Directions: List<Pair<FlickDirection, Array<String>>> = listOf(
        FlickDirection.UP_LEFT to arrayOf("nw", "key1"),
        FlickDirection.UP_RIGHT to arrayOf("ne", "key2"),
        FlickDirection.DOWN_LEFT to arrayOf("sw", "key3"),
        FlickDirection.DOWN_RIGHT to arrayOf("se", "key4"),
        FlickDirection.LEFT to arrayOf("w", "key5"),
        FlickDirection.RIGHT to arrayOf("e", "key6"),
        FlickDirection.UP to arrayOf("n", "key7"),
        FlickDirection.DOWN to arrayOf("s", "key8"),
    )

    private val IconActions: Set<KeyAction> = setOf(
        KeyAction.Shift, KeyAction.CapsLock, KeyAction.Delete, KeyAction.ForwardDelete,
        KeyAction.Space, KeyAction.Enter, KeyAction.EditorAction,
    )

    private val FunctionKeys: Map<String, Int> = (1..12).associateBy { "f$it" }

    /**
     * The dead keys, as the combining mark this keyboard's composer arms on, and
     * the label drawn for each. Written as code points: a table of combining
     * marks in glyphs cannot be proof-read.
     */
    private val Accents: Map<String, Pair<Char, String>> = mapOf(
        "accent_aigu" to ('́' to "´"),
        "accent_caron" to ('̌' to "ˇ"),
        "accent_cedille" to ('̧' to "¸"),
        "accent_circonflexe" to ('̂' to "^"),
        "accent_grave" to ('̀' to "`"),
        "accent_macron" to ('̄' to "¯"),
        "accent_ring" to ('̊' to "˚"),
        "accent_tilde" to ('̃' to "~"),
        "accent_trema" to ('̈' to "¨"),
        "accent_ogonek" to ('̨' to "˛"),
        "accent_dot_above" to ('̇' to "˙"),
        "accent_double_aigu" to ('̋' to "˝"),
        "accent_breve" to ('̆' to "˘"),
        "accent_dot_below" to ('̣' to "◌̣"),
        "accent_horn" to ('̛' to "◌̛"),
        "accent_hook_above" to ('̉' to "◌̉"),
        "accent_double_grave" to ('̏' to "◌̏"),
    )

    /** The keys that type one combining mark after the letter, by name. */
    private val CombiningMarks: Map<String, Char> = mapOf(
        "combining_dot_above" to '̇',
        "combining_double_aigu" to '̋',
        "combining_slash" to '̷',
        "combining_arrow_right" to '⃗',
        "combining_breve" to '̆',
        "combining_bar" to '̵',
        "combining_aigu" to '́',
        "combining_caron" to '̌',
        "combining_cedille" to '̧',
        "combining_circonflexe" to '̂',
        "combining_grave" to '̀',
        "combining_macron" to '̄',
        "combining_ring" to '̊',
        "combining_tilde" to '̃',
        "combining_trema" to '̈',
        "combining_ogonek" to '̨',
        "combining_dot_below" to '̣',
        "combining_horn" to '̛',
        "combining_hook_above" to '̉',
        "combining_vertical_tilde" to '̾',
        "combining_inverted_breve" to '̑',
        "combining_pokrytie" to '҇',
        "combining_slavonic_psili" to '҆',
        "combining_slavonic_dasia" to '҅',
        "combining_payerok" to '꙽',
        "combining_titlo" to '҃',
        "combining_vzmet" to '꙯',
        "combining_arabic_v" to 'ٚ',
        "combining_arabic_inverted_v" to 'ٛ',
        "combining_shaddah" to 'ّ',
        "combining_sukun" to 'ْ',
        "combining_fatha" to 'َ',
        "combining_dammah" to 'ُ',
        "combining_kasra" to 'ِ',
        "combining_hamza_above" to 'ٔ',
        "combining_hamza_below" to 'ٕ',
        "combining_alef_above" to 'ٰ',
        "combining_fathatan" to 'ً',
        "combining_kasratan" to 'ٍ',
        "combining_dammatan" to 'ٌ',
        "combining_alef_below" to 'ٖ',
        "combining_kavyka" to '꙼',
        "combining_palatalization" to '҄',
    )

    /** The Hebrew points, each drawn on a letter so the mark can be seen. */
    private val HebrewMarks: Map<String, Pair<Char, String>> = mapOf(
        "qamats" to ('ָ' to "קָ"),
        "patah" to ('ַ' to "פַ"),
        "sheva" to ('ְ' to "שְ"),
        "dagesh" to ('ּ' to "דּ"),
        "hiriq" to ('ִ' to "חִ"),
        "segol" to ('ֶ' to "סֶ"),
        "tsere" to ('ֵ' to "צֵ"),
        "holam" to ('ֹ' to "וֹ"),
        "qubuts" to ('ֻ' to "קֻ"),
        "hataf_patah" to ('ֲ' to "חֲ"),
        "hataf_qamats" to ('ֳ' to "חֳ"),
        "hataf_segol" to ('ֱ' to "חֱ"),
        "shindot" to ('ׁ' to "שׁ"),
        "sindot" to ('ׂ' to "שׂ"),
        "geresh" to ('׳' to "׳"),
        "gershayim" to ('״' to "״"),
        "maqaf" to ('־' to "־"),
        "rafe" to ('ֿ' to "פֿ"),
        "ole" to ('֫' to "ע֫"),
        "meteg" to ('ֽ' to "מֽ"),
    )

    /**
     * The right-to-left bracket keys: each types the mirror of what it draws,
     * which a right-to-left field then draws the way round the key shows.
     */
    private val Mirrored: Map<String, Pair<String, String>> = mapOf(
        "b(" to (")" to "("),
        "b)" to ("(" to ")"),
        "b[" to ("]" to "["),
        "b]" to ("[" to "]"),
        "b{" to ("}" to "{"),
        "b}" to ("{" to "}"),
        "blt" to (">" to "<"),
        "bgt" to ("<" to ">"),
    )

    /** Names that draw nothing there unless a setting asks, so drop without a note. */
    private val Silent: Set<String> = setOf("removed")

    /** Names with no key here. Each is named in the notes. */
    private val Unsupported: Set<String> = setOf(
        "switch_greekmath", "compose", "compose_cancel", "shareText", "replaceText", "textAssist",
        "autofill", "change_dictionary", "selection_cancel", "selection_cursor_left",
        "selection_cursor_right", "complete_first", "complete_second", "complete_third",
        "complete_emoji", "superscript", "subscript", "ordinal", "arrows", "box",
        "accent_slash", "accent_bar", "accent_small_caps", "accent_arrow_right",
    )
}

// Android key codes and meta flags, as numbers: this module is layout data and
// takes no android.view import (see KeyActions.kt).
private const val KEYCODE_0 = 7
private const val KEYCODE_A = 29
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22
private const val KEYCODE_TAB = 61
private const val KEYCODE_MENU = 82
private const val KEYCODE_PAGE_UP = 92
private const val KEYCODE_PAGE_DOWN = 93
private const val KEYCODE_ESCAPE = 111
private const val KEYCODE_SCROLL_LOCK = 116
private const val KEYCODE_MOVE_HOME = 122
private const val KEYCODE_MOVE_END = 123
private const val KEYCODE_INSERT = 124
private const val KEYCODE_F1 = 131
private const val META_SHIFT_ON = 0x1
private const val META_ALT_ON = 0x2
private const val META_CTRL_ON = 0x1000
private const val META_META_ON = 0x10000
