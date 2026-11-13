package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.language.R
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import kotlin.math.roundToInt

/**
 * Reading a layout written for a *different* keyboard.
 *
 * Two formats, one parser between them. HeliBoard's JSON layouts are
 * FlorisBoard's: the same `{"$": "text_key", …}` key objects and the same
 * negative key-code numbering, because HeliBoard's parser package is a port of
 * it. HeliBoard's other format is a plain text file, one key per line.
 *
 * **Written from the published formats, not from either project's source.**
 * FlorisBoard is Apache-2.0 and HeliBoard is GPL-3.0; this app is MIT. The key
 * codes below are transcribed from the constants those projects document, which
 * is a fact about a file format, and no upstream code is copied. Test fixtures
 * are hand-written minimal files for the same reason.
 *
 * ### What comes out
 *
 * Only the letters layer. A foreign layout file carries one grid — the symbols
 * and numeric layers belong to the app that read it — and an omitted layer
 * inherits the shipped one (see [LayoutSpec.layers]), which is what a user
 * moving one grid across actually wants.
 *
 * ### Two rules that are not style
 *
 * **A key this app has no action for is dropped, never turned into
 * [KeyAction.Unknown].** That action exists for a layout written by a *newer
 * build of this app*, and its serializer throws on the way back out
 * (`UnknownKeyActionSerializer.serialize`), so a converted layout carrying one
 * would take the whole custom-layouts write down with it when saved. Every drop
 * is reported instead, with the code that caused it.
 *
 * **The result has already been through [LayoutSpec.repair].** The keyboard
 * repairs again on every layout compile and throws the notes away, so anything
 * left for it to fix would be a silent difference between the grid the user
 * approved and the grid they type on. Repairing here, once, keeps the two the
 * same and puts the fixes in front of the user. Repair is idempotent, so the
 * second pass is a no-op.
 */
enum class ForeignSource {
    /** HeliBoard's plain text layout: one key per line, blank line ends a row. */
    HELIBOARD_TEXT,

    /** The FlorisBoard `KeyData` JSON that HeliBoard also reads. */
    FLORIS_JSON,

    /** FUTO Keyboard's YAML layout. Read by [FutoLayouts]. */
    FUTO_YAML,

    /**
     * A Keyman `.keyman-touch-layout` file. Read by `:core:keyman`, not here:
     * that module depends on this one, so the converter cannot be called from
     * inside it. The import screen dispatches on the file's shape instead.
     */
    KEYMAN_TOUCH_LAYOUT,

    /**
     * A Samsung Keyboard grid shared from Keys Cafe (`.kcf`). Read by
     * [KeysCafeLayouts]. The one format that carries its own frame, so it is
     * the one that does not get this app's put round it.
     */
    KEYS_CAFE,

    /**
     * A Multiling O Keyboard layout: a DIY layout's JSON, a DIY+ layout's
     * `OK_Layout_Begin` text, or the layouts inside its settings export. Read by
     * [MultilingLayouts]. The only format that carries more than one page of
     * keys, so its symbols and AltGr pages come across too.
     */
    MULTILING,

    /**
     * An Unexpected Keyboard layout, the XML that CleverKeys reads and shares
     * too. Read by [UnexpectedLayouts]. Its keys' corner swipes come across as
     * this keyboard's flick arms.
     */
    UNEXPECTED,

    /** A Windows layout's MSKLC source, `.klc`. Read by [DesktopLayouts]. */
    WINDOWS_KLC,

    /** A macOS `.keylayout`, as Ukelele writes it. Read by [DesktopLayouts]. */
    MAC_KEYLAYOUT,

    /** A Linux XKB `symbols` file, one layout per variant. Read by [DesktopLayouts]. */
    XKB_SYMBOLS,

    /**
     * A CLDR keyboard3 (LDML) file: CLDR's own keyboards, and FlorisBoard's
     * layouts since it dropped its JSON. Read by [Keyboard3Layouts].
     */
    KEYBOARD3,
}

/** A foreign layout after conversion, with everything that was lost on the way. */
data class ConvertedLayout(
    val layout: LayoutSpec,
    /** Conversion notes first, then whatever [LayoutSpec.repair] had to change. */
    val notes: List<LayoutMessage>,
    /** Foreign key codes with no equivalent here. Those keys are not in [layout]. */
    val unmapped: List<Int>,
    /**
     * What the characters on the keys suggest the language is. Seeds the
     * picker and nothing else: neither format states a language, and
     * [LayoutCodec] turns a blank one into English without asking, which would
     * quietly give a Georgian grid an English dictionary.
     */
    val guessedLangId: String,
    val source: ForeignSource,
) {

    /**
     * The layout with a language on it, ready to store.
     *
     * The only supported way to take [layout] out of here. It is deliberately
     * handed over with a blank `langId`, and a blank one is not a neutral
     * value: `LayoutCodec` migrates it to English on the very next read, so a
     * Georgian grid saved as-is would come back with an English dictionary,
     * English autocorrect and Latin shift behaviour, with nothing on screen to
     * say why. Going through here means the language was decided by someone.
     */
    fun withLanguage(langId: String): LayoutSpec =
        layout.copy(langId = langId.ifBlank { guessedLangId })
}

object ForeignLayouts {

    /**
     * Converts [text], guessing which of the two formats it is from its first
     * character. Null when it is neither, or holds no keys.
     *
     * Not reachable from `WMFileTypes.identify()` as a whole: the text format
     * takes any text at all. [fromFlorisJson] is, behind a sniff for the key
     * types a FlorisBoard file names, since it takes any array of arrays.
     */
    fun convert(text: String, name: String): ConvertedLayout? =
        if (firstMeaningfulChar(text) in JSON_OPENERS) {
            fromFlorisJson(text, name)
        } else {
            fromSimpleText(text, name)
        }

    /**
     * The first character that is not blank space or a comment.
     *
     * Both formats allow `//` comments and both are usually headed by one, so
     * looking at the very first character of the file decides every commented
     * layout wrongly, in the direction that hurts: a JSON grid read as the text
     * format becomes one key per line of punctuation.
     */
    private fun firstMeaningfulChar(text: String): Char? =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith(COMMENT_PREFIX) }
            ?.firstOrNull()

    /**
     * HeliBoard's text format: `label popup1 popup2 …` per line, a blank line
     * starts the next row.
     *
     * The format has no way to spell a delete, space or enter key, so every
     * file is missing all three. That is not a defect in the file — the app
     * that reads it draws its own bottom row — and [withHouseStructure] adding
     * this app's is exactly the right outcome.
     */
    fun fromSimpleText(text: String, name: String): ConvertedLayout? {
        val rows = mutableListOf<List<Key>>()
        val row = mutableListOf<Key>()
        val report = Report()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (row.isNotEmpty()) rows += row.toList()
                row.clear()
                continue
            }
            if (trimmed.startsWith(COMMENT_PREFIX)) continue
            val tokens = trimmed.split(' ', '\t').filter { it.isNotEmpty() }
            val key = keyFromLabel(tokens.first(), report, obj = null) ?: continue
            val popups = popupsFrom(
                tokens.drop(1).map { token ->
                    if (parseForeignPopupMarker(token) != null) {
                        token
                    } else {
                        parseForeignLabel(token).let { normalizeForScript(it.output ?: it.label) }
                    }
                },
            )
            // The letters the line lists, or the ones the label itself implies
            // (the currency key's other signs) when it lists none.
            row += key.copy(
                longPress = popups.letters.ifEmpty { key.longPress },
                alternateColumns = popups.columns,
            )
        }
        if (row.isNotEmpty()) rows += row.toList()
        return finish(rows, name, ForeignSource.HELIBOARD_TEXT, report)
    }

    /**
     * The FlorisBoard/HeliBoard JSON grid: rows of key objects.
     *
     * Parsed leniently, with comments and trailing commas allowed, because
     * HeliBoard's own reader allows both and people hand-write these.
     */
    fun fromFlorisJson(text: String, name: String): ConvertedLayout? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val rowsJson = rowsOf(root) ?: return null
        val report = Report()
        val rows = rowsJson.mapNotNull { rowJson ->
            val row = (rowJson as? JsonArray) ?: return@mapNotNull null
            distributeExpandable(row.mapNotNull { keyFrom(it, report) })
        }
        return finish(rows, name, ForeignSource.FLORIS_JSON, report)
    }

    /**
     * The rows, whether the file is a bare array of them or an object that
     * names them. Floris ships the bare array; a hand-written file often wraps
     * it, and refusing those over a wrapper would be a poor trade.
     */
    private fun rowsOf(root: JsonElement): List<JsonElement>? = when (root) {
        is JsonArray -> root
        is JsonObject -> ROW_FIELDS.firstNotNullOfOrNull { root[it] as? JsonArray }
        is JsonPrimitive -> null
    }

    /**
     * Builds the spec, repairs it, and merges both sets of notes. Null when
     * there was nothing to convert — a file of comments, or an array of empty
     * arrays.
     */
    /**
     * The shared tail of every converter in this package: repair the grid,
     * merge the notes, and hand back something the import screen can show.
     *
     * Internal so [FutoLayouts] can reach it. That reader lives in its own file
     * because YAML brings a parser and a set of key types of its own, but it
     * must finish the same way, or a layout from one format would reach storage
     * unrepaired while the others did not.
     *
     * [langId] is for a format that states its language. The other two do not,
     * and pass null so the guess from the letters stands.
     *
     * [numberRow] is a digit row the file wrote, for the layout's own
     * number-row slot. [houseFrame] false keeps the grid exactly as the file
     * laid it out — no shift, delete or bottom row added or moved — and marks
     * the layer so the Bottom-row settings leave it alone too. Only a format
     * that carries its whole frame may say so; see [KeysCafeLayouts].
     *
     * [extraLayers] are the pages beyond the letters, keyed like
     * [LayoutSpec.layers], for the one format that has them ([MultilingLayouts]).
     * They are repaired with the rest. [bottomRowAsLaidOut] is for a reader that
     * puts the house frame on itself before handing the grid over, and so
     * passes [houseFrame] false while still wanting the Bottom-row settings to
     * apply.
     */
    internal fun assemble(
        rows: List<List<Key>>,
        name: String,
        source: ForeignSource,
        report: Report,
        langId: String? = null,
        numberRow: List<Key>? = null,
        houseFrame: Boolean = true,
        extraLayers: Map<String, LayerSpec> = emptyMap(),
        bottomRowAsLaidOut: Boolean = !houseFrame,
    ): ConvertedLayout? =
        finish(rows, name, source, report, langId, numberRow, houseFrame, extraLayers, bottomRowAsLaidOut)

    private fun finish(
        rows: List<List<Key>>,
        name: String,
        source: ForeignSource,
        report: Report,
        declaredLangId: String? = null,
        numberRow: List<Key>? = null,
        houseFrame: Boolean = true,
        extraLayers: Map<String, LayerSpec> = emptyMap(),
        bottomRowAsLaidOut: Boolean = !houseFrame,
    ): ConvertedLayout? {
        val kept = rows.filter { it.isNotEmpty() }
        if (kept.isEmpty()) return null
        val scaled = renormalizeWidths(kept)
        val layout = LayoutSpec(
            id = "foreign_${System.currentTimeMillis()}",
            name = layoutNameFrom(name),
            langId = "",
            // After the widths are in this app's unit, since the keys it adds
            // are written in it. See [withHouseStructure].
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(
                    rows = if (houseFrame) withHouseStructure(scaled) else scaled,
                    numberRow = numberRow,
                    // A grid kept as laid out stays that way on screen as well:
                    // the Bottom-row settings would otherwise move its 🌐 and
                    // comma about (issue #420).
                    bottomRowAsLaidOut = bottomRowAsLaidOut,
                ),
            ) + extraLayers,
        )
        val repaired = layout.repair()
        return ConvertedLayout(
            // The language is the caller's to set, from the picker this seeds.
            // Leaving it blank here rather than writing the guess in means the
            // guess can never reach storage without someone having seen it.
            layout = repaired.spec,
            notes = report.notes(scaled !== kept) + repaired.repairNotes,
            unmapped = report.unmapped.toList(),
            guessedLangId = declaredLangId ?: guessLangId(scaled),
            source = source,
        )
    }

    /** The file name without its extensions, or a plain fallback. */
    private fun layoutNameFrom(name: String): String =
        name.substringAfterLast('/')
            .substringBefore('.')
            .replace('_', ' ')
            .trim()
            .ifBlank { FallbackName }

    private fun keyFrom(element: JsonElement, report: Report): Key? {
        if (element is JsonPrimitive) {
            val label = element.content.takeIf { element.isString } ?: return null
            return keyFromLabel(label, report, obj = null)
        }
        val obj = element as? JsonObject ?: return null
        selectorKindOf(obj)?.let { return resolveSelector(obj, it, report) }
        if (obj.string("type").equals(PLACEHOLDER_TYPE, ignoreCase = true)) {
            return Key(label = "", action = KeyAction.None, width = obj.width())
        }
        val code = obj.int("code")
        return when {
            code == null || code == MULTIPLE_CODE_POINTS -> characterKey(obj, code, report)
            // Space and enter are ordinary code points over there — 32 and 10 —
            // and real actions here. Left as text keys they would commit a bare
            // space and a bare newline, losing auto-space, the double-space full
            // stop and the action the enter key takes from the field. Worse,
            // repair would find no space key and add a *second* spacebar.
            code in PositiveActions -> actionKey(obj, code, report)
            code < 0 -> actionKey(obj, code, report)
            else -> characterKey(obj, code, report)
        }
    }

    /**
     * Which conditional key class [obj] is, or null for an ordinary key.
     *
     * Matched on the normalized class name so `case_selector` and
     * `caseSelector` are the same thing, the way the rest of this file matches
     * the dialect.
     */
    private fun selectorKindOf(obj: JsonObject): SelectorKind? {
        val declared = obj.string(CLASS_FIELD) ?: return null
        val normalized = declared.lowercase().filter { it.isLetterOrDigit() }
        return SelectorKind.entries.firstOrNull { it.id == normalized }
    }

    /**
     * A conditional key, flattened to the branch this keyboard would draw.
     *
     * A selector says "this key is one thing while shift is down, another in a
     * password field, another in a right-to-left layout". Nothing here holds
     * more than one key per slot, so one branch is chosen — the resting one, in
     * the order the format lists its fallbacks — and the rest are counted.
     *
     * A `case_selector` is the exception, and the reason this is not simply a
     * drop: its two branches are exactly [Key.shiftLabel], so the upper branch
     * is *kept* rather than counted, and a Dvorak grid imports with both cases
     * intact. The label is read straight off the branch rather than converted
     * through [keyFrom], so the notes do not count the same letter twice.
     *
     * Before this, a selector key fell through the object reader with no label
     * and no code and was dropped with nothing said — and HeliBoard's own
     * `dvorak.json` is written with them.
     */
    private fun resolveSelector(obj: JsonObject, kind: SelectorKind, report: Report): Key? {
        val name = kind.branches.firstOrNull { obj[it] != null } ?: return null
        val resting = keyFrom(obj.getValue(name), report) ?: return null
        val others = obj.keys.count { it != CLASS_FIELD && it != name }
        if (kind != SelectorKind.CASE) {
            if (others > 0) report.selectors++
            return resting
        }
        val upper = CaseUpperBranches.firstNotNullOfOrNull { obj[it]?.let(::branchLabel) }
            ?.let(::normalizeForScript)
        val shifted = upper?.takeIf { it.isNotEmpty() && it != resting.label.uppercase() }
        if (shifted == null && others > 0) report.selectors++
        return resting.copy(shiftLabel = shifted)
    }

    /**
     * Whether [this] is an `auto_text_key`, whose case follows shift.
     *
     * Over there that key draws and types the lowercase of its code and label
     * until shift is down, whatever case they are written in. FlorisBoard's own
     * Rusyn grid writes ї with the code of Ї (and ы, ё likewise), which is
     * harmless on that keyboard and typed the capital on this one, where the
     * code is the output as written. Taking the resting case here is what the
     * file means; shift still gets the capital, from the null shiftLabel.
     */
    private fun JsonObject.isAutoCase(): Boolean =
        string(CLASS_FIELD)?.lowercase()?.filter { it.isLetterOrDigit() } == AUTO_TEXT_KEY

    /** The label a selector branch draws, without converting the whole key. */
    private fun branchLabel(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.content.takeIf { element.isString }
        is JsonObject -> element.string("label")
            ?: element.int("code")?.takeIf { it > 0 }?.let { String(Character.toChars(it)) }
        is JsonArray -> null
    }?.let { parseForeignLabel(it).label.ifEmpty { null } }

    /** A key that commits text: a code point, a run of them, or its own label. */
    private fun characterKey(obj: JsonObject, code: Int?, report: Report): Key? {
        val points = obj.intList("codePoints")
        val output = when {
            points.isNotEmpty() -> points.joinToString("") { String(Character.toChars(it)) }
            code != null && code > 0 -> String(Character.toChars(code))
            else -> null
        }
        val label = obj.string("label") ?: output ?: return null
        // A declared code wins over anything the label spells — the format says
        // so outright — so the label is only read for what it draws.
        if (output != null) {
            val drawn = parseForeignLabel(label).let { if (it.dropped) label else it.label }
            return textKey(drawn.ifEmpty { output }, output, report, obj)
        }
        return keyFromLabel(label, report, obj)
    }

    /**
     * A key from a HeliBoard label, with every special spelling resolved.
     *
     * The label field is the only field the simple text format has, and the
     * format loads it accordingly: functional keys, the local currency, an
     * explicit code and a label that differs from its output are all spelled
     * there. See [parseForeignLabel].
     */
    private fun keyFromLabel(raw: String, report: Report, obj: JsonObject?): Key? {
        val parsed = parseForeignLabel(raw)
        if (parsed.dropped) {
            report.droppedLabels += raw
            return null
        }
        if (parsed.approximate) report.approximated++
        if (parsed.restyled) report.restyled++
        if (parsed.action != KeyAction.Text) return actionKeyOf(parsed, obj, report)
        val key = textKey(
            label = parsed.label.ifEmpty { parsed.output.orEmpty() },
            output = parsed.output,
            report = report,
            obj = obj,
        ) ?: return null
        return key.copy(
            role = parsed.role ?: key.role,
            // The label's own letters only when the file gave none of its own:
            // an explicit popup list is the author speaking, and the currency
            // set below it is a default.
            longPress = key.longPress.ifEmpty { parsed.longPress.map(::normalizeForScript) },
        )
    }

    /** The action half of [keyFromLabel], sharing the object's width and flags. */
    private fun actionKeyOf(parsed: ForeignLabel, obj: JsonObject?, report: Report): Key {
        val flags = obj?.labelFlags(report) ?: LabelFlags.None
        return Key(
            label = labelFor(parsed.action, parsed.label.ifEmpty { null }),
            action = parsed.action,
            width = obj?.width() ?: 1f,
            hideHint = flags.hideHint,
            labelScale = flags.labelScale,
        )
    }

    private fun textKey(
        label: String,
        output: String?,
        report: Report,
        obj: JsonObject? = null,
    ): Key? {
        val restingCase = obj?.isAutoCase() == true
        val cleanLabel = normalizeForScript(label)
            .also { if (it != label) report.normalized++ }
            .let { if (restingCase) it.lowercaseByCodePoint() else it }
        val cleanOutput = output?.let { normalizeForScript(it) }
            ?.let { if (restingCase) it.lowercaseByCodePoint() else it }
        if (cleanLabel.isEmpty() && cleanOutput.isNullOrEmpty()) return null
        val flags = obj?.labelFlags(report) ?: LabelFlags.None
        val popups = obj?.let { popupsOf(it, report) } ?: Popups.None
        return Key(
            label = cleanLabel,
            // Only when it differs: a key whose output repeats its label is the
            // common case, and writing both doubles the file for nothing.
            output = cleanOutput?.takeIf { it != cleanLabel },
            // Never a materialized uppercase. A null shiftLabel already
            // uppercases at commit time for a cased script, which is what the
            // foreign `auto_text_key` means; writing one in would freeze the
            // wrong case for every script that has none.
            shiftLabel = null,
            width = obj?.width() ?: 1f,
            longPress = popups.letters,
            alternateColumns = popups.columns,
            // `groupId` names which of the other keyboard's automatic popup
            // sets a key takes: 1 is the comma slot, 2 the period slot. Both
            // are language-dependent over there and decided from the field
            // here, so the slot is tagged and this keyboard fills it — copying
            // one language's punctuation onto every layout would not.
            role = roleForGroup(obj?.int("groupId")),
            hideHint = flags.hideHint,
            labelScale = flags.labelScale,
        )
    }

    private fun roleForGroup(groupId: Int?): KeyRole? = when (groupId) {
        COMMA_GROUP -> KeyRole.Comma
        PERIOD_GROUP -> KeyRole.Period
        else -> null
    }

    /** A key that does something instead of typing. Null when nothing here does it. */
    private fun actionKey(obj: JsonObject, code: Int, report: Report): Key? {
        val action = PositiveActions[code] ?: keyActionForCode(code)
        if (action == null) {
            report.unmapped += code
            return null
        }
        if (code in ApproximateCodes) report.approximated++
        val flags = obj.labelFlags(report)
        return Key(
            label = labelFor(action, obj.string("label")),
            action = action,
            width = obj.width(),
            hideHint = flags.hideHint,
            // The flags matter most here. A mode key labelled with a word is
            // exactly what this keyboard shrinks on its own, and a file that
            // asked for the letter ratio is saying not to.
            labelScale = flags.labelScale,
        )
    }

    /**
     * What to draw on an action key.
     *
     * The keyboard draws shift, delete, enter and their neighbours from icon
     * slots, so their labels are theirs to decide and the file's is discarded —
     * a file that spells its shift key "shift" or "⇧" would otherwise put a word
     * or a second, different arrow on a key that is already an icon. The keys
     * drawn as text keep the file's label, because "?123" and "ABC" travel
     * perfectly well and the file may have better ones.
     */
    private fun labelFor(action: KeyAction, given: String?): String {
        val fallback = action.fallbackLabel()
        if (fallback.isEmpty() || action == KeyAction.Space) return fallback
        return given?.let(::normalizeForScript)?.ifEmpty { null } ?: fallback
    }

    /**
     * The long-press letters, from whichever shape the file uses.
     *
     * `main` first so it becomes the corner hint, then `relevant`. A key that
     * points at a popup *group* instead of listing its letters is counted as a
     * loss: those groups live in a separate file that a single exported layout
     * does not carry, so there is nothing here to resolve them against.
     *
     * A *negative* group is not a loss and must not be counted as one. HeliBoard
     * spells "add no popups at all" as `groupId: -1`, so counting it reported a
     * missing popup for every key that had deliberately asked for none — a note
     * telling the user something went wrong when nothing had.
     */
    private fun popupsOf(obj: JsonObject, report: Report): Popups {
        val popup = obj["popup"]
        val entries = when (popup) {
            is JsonArray -> popup.toList()
            is JsonObject -> listOfNotNull(popup["main"]) + (popup["relevant"] as? JsonArray).orEmpty()
            is JsonPrimitive, null -> emptyList()
        }
        val popups = popupsFrom(entries.mapNotNull(::popupLabel))
        val group = obj.int("groupId") ?: 0
        if (popups.letters.isEmpty() && group > PERIOD_GROUP) report.lostPopups++
        return popups
    }

    /**
     * The press-and-hold letters of a popup list, with its markers taken out.
     *
     * HeliBoard puts the popup's *options* in the popup list itself, wrapped in
     * exclamation marks: `!autoColumnOrder!4` asks for four columns,
     * `!hasLabels!` for smaller text. Read as letters they became keys that
     * typed `!hasLabels!`, which is what they used to do here. The one with a
     * field on this side — the column count — is applied; the rest are
     * swallowed. See [parseForeignPopupMarker].
     */
    private fun popupsFrom(raw: List<String>): Popups {
        var columns = 0
        val letters = mutableListOf<String>()
        for (entry in raw) {
            val marker = parseForeignPopupMarker(entry)
            when {
                marker != null -> if (marker.columns != 0) columns = marker.columns
                entry.isNotEmpty() -> letters += entry
            }
        }
        return Popups(letters.distinct(), columns)
    }

    /** A key's press-and-hold letters, and the shape the file asked them to take. */
    private class Popups(val letters: List<String>, val columns: Int) {
        companion object {
            val None = Popups(emptyList(), 0)
        }
    }

    /**
     * HeliBoard's `labelFlags`, as much of it as this keyboard has type for.
     *
     * This is the field the layout in issue #18 needed and had nowhere to land:
     * it is how the other keyboard is told that one key's label is a word rather
     * than a letter, and without it an imported grid draws its odd keys at the
     * wrong size with no way to say otherwise.
     *
     * **Transcribed from the format's published values** (HeliBoard's
     * `attrs.xml` declares them, and its layout guide names them), the same
     * footing as the key codes above — see the file header. Read as a `Long`
     * first because two of the flags are above `Int.MAX_VALUE` in decimal, and
     * hex is accepted because the documentation is written in it and people
     * hand-write these files.
     *
     * What comes across:
     * - the four "follow ratio" flags become a [Key.labelScale], as close to
     *   each ratio as this keyboard's own type scale goes;
     * - `disableKeyHintLabel` becomes [Key.hideHint], which already means
     *   exactly that;
     * - `autoScale` / `autoXScale` need nothing — every label here already
     *   steps down until it fits its key.
     *
     * Everything else is counted and reported rather than half-applied: a
     * monospace or off-centre label is a thing this keyboard does not draw, and
     * a note saying so is worth more than a key that quietly came across wrong.
     */
    private fun JsonObject.labelFlags(report: Report): LabelFlags {
        val raw = (this["labelFlags"] as? JsonPrimitive)?.content?.let(::parseFlags) ?: return LabelFlags.None
        if (raw == 0) return LabelFlags.None
        val font = raw and FLAG_FONT_DEFAULT
        val unsupported = (raw and HandledLabelFlags.inv()) != 0 ||
            (font != 0 && font != FLAG_FONT_DEFAULT)
        if (unsupported) report.restyled++
        return LabelFlags(
            labelScale = when (raw and RatioMask) {
                FLAG_FOLLOW_LARGE_LETTER_RATIO -> LargeLetterScale
                FLAG_FOLLOW_LETTER_RATIO -> 1f
                FLAG_FOLLOW_LABEL_RATIO -> ModeLabelScale
                FLAG_FOLLOW_HINT_LABEL_RATIO -> HintLabelScale
                else -> null
            },
            hideHint = (raw and FLAG_DISABLE_HINT_LABEL) != 0,
        )
    }

    /** Decimal or hex, and via `Long` so the top-bit flags parse rather than fail. */
    private fun parseFlags(text: String): Int? {
        val trimmed = text.trim()
        val hex = trimmed.removePrefix("-").startsWith("0x", ignoreCase = true)
        return if (hex) {
            trimmed.replace("0x", "", ignoreCase = true).toLongOrNull(HEX_RADIX)?.toInt()
        } else {
            trimmed.toLongOrNull()?.toInt()
        }
    }

    private fun popupLabel(element: JsonElement): String? {
        val raw = when (element) {
            is JsonPrimitive -> element.content.takeIf { element.isString }
            is JsonObject -> element.string("label")
                ?: element.int("code")?.takeIf { it > 0 }?.let { String(Character.toChars(it)) }
            is JsonArray -> null
        } ?: return null
        // Markers travel on as they are: they are read out one level up, where
        // the column count has somewhere to go.
        if (parseForeignPopupMarker(raw) != null) return raw
        // A popup entry is a letter, so what it *types* is the whole of it: a
        // `ä|a` entry that kept its label would draw ä and type ä anyway, since
        // this keyboard has one string per alternate.
        val parsed = parseForeignLabel(raw)
        val text = parsed.output ?: parsed.label
        return normalizeForScript(text)
    }

    /**
     * A row's expandable keys, given the width left over.
     *
     * HeliBoard spells "fill the rest of the row" as a width of -1, which is
     * how its spacebar is written. Resolved before [renormalizeWidths] so the
     * row is all real widths by the time anything is scaled, and floored so a
     * row that is already full cannot mint a zero-width key that repair would
     * then have to fix.
     */
    private fun distributeExpandable(row: List<Key>): List<Key> {
        val expandable = row.count { it.width == ExpandMarker }
        if (expandable == 0) return row
        val fixed = row.filter { it.width != ExpandMarker }.sumOf { it.width.toDouble() }
        val each = ((1.0 - fixed) / expandable).toFloat().coerceAtLeast(MinExpandWidth)
        return row.map { if (it.width == ExpandMarker) it.copy(width = each) else it }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    /**
     * The declared width, as the file's own unit. A width the file spells as a
     * word ("regular") rather than a number becomes 1: those names mean "the
     * ordinary key" in a grid whose ordinary key is what everything else is
     * measured against.
     */
    private fun JsonObject.width(): Float {
        val raw = (this["width"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: return 1f
        return when {
            raw == ExpandMarker -> ExpandMarker
            raw <= 0f || !raw.isFinite() -> 1f
            else -> raw
        }
    }

    private fun JsonObject.intList(key: String): List<Int> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            .orEmpty()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
    }

    /** What a file has to start with to be JSON rather than the text format. */
    private val JSON_OPENERS = setOf('[', '{')

    /** Object fields a wrapped layout keeps its rows under. */
    private val ROW_FIELDS = listOf("arrangement", "rows", "keys", "layout")

    private const val COMMENT_PREFIX = "//"
    private const val PLACEHOLDER_TYPE = "placeholder"

    /** The field naming a key's class, which is also what marks a selector. */
    private const val CLASS_FIELD = "$"

    /** `auto_text_key`, normalized the way [selectorKindOf] matches class names. */
    private const val AUTO_TEXT_KEY = "autotextkey"

    /** The two automatic popup sets that are punctuation slots on this side. */
    private const val COMMA_GROUP = 1
    private const val PERIOD_GROUP = 2

    /**
     * Where a `case_selector` keeps the shifted letter. `upper` is the format's
     * own name; `shifted` is what a file written as a shift-state selector and
     * relabelled uses, and reading both costs one lookup.
     */
    private val CaseUpperBranches = listOf("upper", "shifted")

    /**
     * The code a `multi_text_key` always carries. It says "read codePoints
     * instead", not "do something", so it must not reach the action table.
     */
    private const val MULTIPLE_CODE_POINTS = -902

    /**
     * Ordinary code points over there that are actions here: space, and the two
     * spellings of enter. Everything else positive is a character.
     */
    private val PositiveActions: Map<Int, KeyAction> = mapOf(
        0x20 to KeyAction.Space,
        0x0A to KeyAction.Enter,
        0x0D to KeyAction.Enter,
        0x09 to KeyAction.SendKey(KEYCODE_TAB),
    )
    private const val FallbackName = "Imported layout"

    /** The foreign width that means "take what is left of the row". */
    private const val ExpandMarker = -1f

    /** Narrowest an expanded key may end up, as a fraction of the row. */
    private const val MinExpandWidth = 0.1f

    private const val HEX_RADIX = 16
}

/**
 * The conditional key classes, and the branch order each one is read in.
 *
 * The branches are the format's own field names, listed resting state first:
 * what the key looks like with no shift held, in an ordinary text field, in a
 * left-to-right layout. A file that names only the later branches still
 * converts, which is why every fallback is listed rather than just the first.
 *
 * [id] is the class name with its separators removed, so one entry matches both
 * the `case_selector` the documentation writes and the `caseSelector` an editor
 * emits.
 */
private enum class SelectorKind(val id: String, val branches: List<String>) {
    CASE("caseselector", listOf("lower", "upper")),
    SHIFT_STATE(
        "shiftstateselector",
        listOf(
            "unshifted", "default", "shiftedAutomatic", "shiftedManual", "shifted",
            "manualOrLocked", "capsLock",
        ),
    ),
    VARIATION(
        "variationselector",
        listOf("normal", "default", "uri", "email", "datetime", "date", "time", "password"),
    ),
    KEYBOARD_STATE(
        "keyboardstateselector",
        listOf(
            "default", "alphabet", "symbols", "moreSymbols",
            "emojiKeyEnabled", "languageKeyEnabled", "dpad",
        ),
    ),
    LAYOUT_DIRECTION("layoutdirectionselector", listOf("ltr", "rtl")),

    /**
     * FlorisBoard's two Japanese selectors. HeliBoard states it does not
     * implement either; they are read here for the same reason the rest are,
     * and the wide branch is the one a grid draws.
     */
    CHAR_WIDTH("charwidthselector", listOf("full", "half")),
    KANA("kanaselector", listOf("hira", "kata")),
}

/** The part of a foreign key's `labelFlags` this keyboard can draw. */
private class LabelFlags(val labelScale: Float? = null, val hideHint: Boolean = false) {
    companion object {
        val None = LabelFlags()
    }
}

// HeliBoard's keyLabelFlags, by the values its attrs.xml declares. Transcribed
// from the published format, like the key codes above; see the file header.
private const val FLAG_FONT_NORMAL = 0x10
private const val FLAG_FONT_MONO_SPACE = 0x20

/** `fontNormal or fontMonoSpace`, which together mean "the default font". */
private const val FLAG_FONT_DEFAULT = FLAG_FONT_NORMAL or FLAG_FONT_MONO_SPACE
private const val FLAG_FOLLOW_LARGE_LETTER_RATIO = 0x40
private const val FLAG_FOLLOW_LETTER_RATIO = 0x80
private const val FLAG_FOLLOW_LABEL_RATIO = 0xC0
private const val FLAG_FOLLOW_HINT_LABEL_RATIO = 0x140
private const val FLAG_AUTO_SCALE = 0xC000
private const val FLAG_DISABLE_HINT_LABEL = 0x40000000

/**
 * The bits the four ratio flags live in. Wider than the three values below it
 * looks like it needs: `followKeyHintLabelRatio` is 0x140, so the mask has to
 * reach 0x100 or that flag reads as `followKeyLargeLetterRatio`.
 */
private const val RatioMask =
    FLAG_FOLLOW_LARGE_LETTER_RATIO or FLAG_FOLLOW_LETTER_RATIO or 0x100

/** Everything the converter either applies or knowingly needs nothing for. */
private const val HandledLabelFlags =
    RatioMask or FLAG_AUTO_SCALE or FLAG_DISABLE_HINT_LABEL or FLAG_FONT_DEFAULT

// The ratios, as multiples of this keyboard's own letter size. The other
// keyboard's numbers are ratios of the key height and cannot be carried over
// literally, so each lands on the nearest size this one actually draws: a
// mode label, and a corner hint.
private const val LargeLetterScale = 1.15f
private const val ModeLabelScale = 0.68f
private const val HintLabelScale = 0.44f

/**
 * The action a foreign key code means here, or null when nothing does.
 *
 * The numbering is shared: HeliBoard adopted FlorisBoard's constants, and added
 * its own from -10000 down. Codes that name something this keyboard reaches
 * another way — the settings screen, the clipboard panel, voice input, the
 * smartbar toggles — return null on purpose. They are not keys here, and a key
 * that silently does nothing is worse than a key that is honestly missing.
 *
 * Never returns [KeyAction.Unknown]: see the file header for why that would be
 * a crash rather than a compromise.
 */
@Suppress("CyclomaticComplexMethod")
internal fun keyActionForCode(code: Int): KeyAction? = when (code) {
    // Modifiers and layer keys.
    -1, -2 -> KeyAction.Mod(ModifierKey.CTRL)
    -3, -4 -> KeyAction.Mod(ModifierKey.ALT)
    -5, -6 -> KeyAction.Fn
    -11 -> KeyAction.Shift
    -13 -> KeyAction.CapsLock
    -10012, -10013 -> KeyAction.Mod(ModifierKey.META)
    -10044, -10045 -> KeyAction.Mod(ModifierKey.CTRL)
    -10046, -10047 -> KeyAction.Mod(ModifierKey.ALT)
    -10048, -10049 -> KeyAction.Mod(ModifierKey.META)

    // Editing. DELETE_WORD and FORWARD_DELETE_WORD have actions of their own
    // since #980.
    -7 -> KeyAction.Delete
    -8 -> KeyAction.Edit(TextEditAction.DELETE_WORD)
    -9 -> KeyAction.ForwardDelete
    -10 -> KeyAction.Edit(TextEditAction.DELETE_WORD_FORWARD)
    -10005 -> KeyAction.Enter

    // Views. SYMBOLS2 is the second symbols page, which the symbols key already
    // steps to, so it collapses onto the same action.
    -201 -> KeyAction.Letters
    -202, -203 -> KeyAction.Symbols
    -204, -205 -> KeyAction.Numpad
    -212 -> KeyAction.Emoji
    // -221 is Florisboard's system input-method picker and -227 the layout
    // cycle. They used to collapse onto the cycle, which was the closest thing
    // this keyboard had; -221 now lands on the key it always meant.
    -221 -> KeyAction.InputMethodPicker
    -227 -> KeyAction.LanguageSwitch

    // Cursor movement and shortcuts, as raw key events.
    -21 -> KeyAction.SendKey(KEYCODE_DPAD_LEFT)
    -22 -> KeyAction.SendKey(KEYCODE_DPAD_RIGHT)
    -23 -> KeyAction.SendKey(KEYCODE_DPAD_UP)
    -24 -> KeyAction.SendKey(KEYCODE_DPAD_DOWN)
    -25 -> KeyAction.SendKey(KEYCODE_MOVE_HOME, META_CTRL_ON)
    -26 -> KeyAction.SendKey(KEYCODE_MOVE_END, META_CTRL_ON)
    -27 -> KeyAction.SendKey(KEYCODE_MOVE_HOME)
    -28 -> KeyAction.SendKey(KEYCODE_MOVE_END)
    -31 -> KeyAction.SendKey(KEYCODE_COPY)
    -32 -> KeyAction.SendKey(KEYCODE_CUT)
    -33 -> KeyAction.SendKey(KEYCODE_PASTE)
    -35 -> KeyAction.SendKey(KEYCODE_A, META_CTRL_ON)
    -131 -> KeyAction.SendKey(KEYCODE_Z, META_CTRL_ON)
    -132 -> KeyAction.SendKey(KEYCODE_Z, META_CTRL_ON or META_SHIFT_ON)
    -10010 -> KeyAction.SendKey(KEYCODE_PAGE_UP)
    -10011 -> KeyAction.SendKey(KEYCODE_PAGE_DOWN)
    -10014 -> KeyAction.SendKey(KEYCODE_TAB)
    -10015 -> KeyAction.SendKey(KEYCODE_DPAD_LEFT, META_CTRL_ON)
    -10016 -> KeyAction.SendKey(KEYCODE_DPAD_RIGHT, META_CTRL_ON)
    -10017 -> KeyAction.SendKey(KEYCODE_ESCAPE)
    in FunctionKeyCodes -> KeyAction.SendKey(KEYCODE_F1 + (FunctionKeyCodes.last - code))

    // A deliberate gap in the grid, in both files and here.
    -999 -> KeyAction.None

    else -> null
}

/**
 * Codes that map onto something close rather than something equal. Counted so
 * the import can say how many keys were approximated, without claiming the
 * layout came across untouched.
 */
internal val ApproximateCodes: Set<Int> = setOf(-203, -10005)

/**
 * Foreign widths brought onto this app's grid.
 *
 * The two are not the same unit. A foreign width is a fraction of the keyboard,
 * so a normal key is 0.1; here it is a weight against `gridWeightOf`, the width
 * the most rows share, which is 10 for every shipped grid. Copying the numbers
 * across passes every check [LayoutSpec.repair] makes — they are positive, under
 * [MaxKeyWidth], and the row is under [MaxRowWidth] — so nothing warns, and the
 * grid simply renders wrong.
 *
 * Multiplying by ten puts a ten-key row on the same footing as QWERTY's, and
 * leaves a nine-key home row summing to 9, which is how it centres itself today.
 * A file whose rows already total more than [FractionRowCeiling] is writing grid
 * units, not fractions, and passes through untouched.
 */
internal fun renormalizeWidths(rows: List<List<Key>>): List<List<Key>> {
    if (rows.isEmpty()) return rows
    val widest = rows.maxOf { row -> row.sumOf { it.width.toDouble() } }
    if (widest > FractionRowCeiling) return rows
    return rows.map { row -> row.map { it.copy(width = scaleWidth(it.width)) } }
}

private fun scaleWidth(width: Float): Float {
    val scaled = (width * FractionToGrid).coerceIn(MinGridWidth, MaxKeyWidth)
    // Two decimals: these are accumulated floats, and a row of ten 0.1 keys
    // should read as ten 1.0 keys rather than ten 0.99999994s.
    return (scaled * 100f).roundToInt() / 100f
}

/**
 * The characters on the keys, in the spelling the rest of the app uses.
 *
 * Unicode has two ways to write a letter that carries a nukta or a similar mark,
 * and normalizing to NFC only picks one of them: the composition exclusions —
 * U+09DF and its Bengali neighbours, the Devanagari, Oriya and Gurmukhi
 * equivalents — are deliberately left decomposed by NFC. The cluster composer
 * and the backspace rules test those code points as single characters, so a key
 * that arrives decomposed shapes and deletes wrongly while looking correct.
 *
 * Latin text is returned untouched, byte for byte: nothing below U+0080 can
 * carry a combining mark, and a normalizer that rewrites ASCII is a normalizer
 * nobody can reason about.
 */
internal fun normalizeForScript(text: String): String {
    if (text.isEmpty() || text.all { it.code < FIRST_NON_ASCII }) return text
    val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
    if (nfc.none { it.code in NuktaSigns }) return nfc
    val out = StringBuilder(nfc.length)
    var i = 0
    while (i < nfc.length) {
        val composed = if (i + 1 < nfc.length) {
            NuktaCompositions[(nfc[i].code shl SHIFT_BASE) or nfc[i + 1].code]
        } else {
            null
        }
        if (composed != null) {
            out.append(composed)
            i += 2
        } else {
            out.append(nfc[i])
            i++
        }
    }
    return out.toString()
}

/**
 * Lowercase, one code point at a time. Not [String.lowercase]: that one maps
 * İ to two characters (i and a combining dot), and a key's resting case has to
 * stay the one character the file wrote.
 */
private fun String.lowercaseByCodePoint(): String = buildString(length) {
    this@lowercaseByCodePoint.codePoints().forEach { appendCodePoint(Character.toLowerCase(it)) }
}

/**
 * The language the characters on the keys suggest, or English when they suggest
 * nothing. A guess for a picker to start from, never an answer.
 */
internal fun guessLangId(rows: List<List<Key>>): String {
    val counts = HashMap<ScriptId, Int>()
    for (row in rows) {
        for (key in row) {
            if (key.action != KeyAction.Text) continue
            for (ch in (key.output ?: key.label)) {
                val script = scriptOf(ch) ?: continue
                counts[script] = (counts[script] ?: 0) + 1
            }
        }
    }
    val dominant = counts.maxByOrNull { it.value }?.key ?: return DefaultLangId
    return LanguageRegistry.all.firstOrNull { it.script == dominant }?.id ?: DefaultLangId
}

private fun scriptOf(ch: Char): ScriptId? =
    scriptDefs.firstOrNull { it.contains(ch.code) }?.id

/** Built once: `ScriptRegistry.all` copies its map on every read. */
private val scriptDefs: List<ScriptDef> by lazy {
    ScriptRegistry.all.filter { !it.unicodeRange.isEmpty() }
}

/** What the conversion had to give up, tallied as it goes. */
internal class Report(
    var normalized: Int = 0,
    var approximated: Int = 0,
    var lostPopups: Int = 0,
    /** Keys whose `labelFlags` asked for styling this keyboard has no form of. */
    var restyled: Int = 0,
    /** Conditional keys that lost a branch this keyboard has no slot for. */
    var selectors: Int = 0,
    val unmapped: MutableList<Int> = mutableListOf(),
    /** Keys named by a label this keyboard has nothing for, such as `dpad`. */
    val droppedLabels: MutableList<String> = mutableListOf(),
    /** The file drew its own number row, which this app draws from a setting. */
    var numberRowDropped: Boolean = false,
    /** The file's number row became the layout's own ([LayerSpec.numberRow]). */
    var numberRowKept: Boolean = false,
    /**
     * Keys whose shifted press-and-hold letters were not simply the capitals
     * of the unshifted ones. This keyboard holds one list per key.
     */
    var shiftedPopups: Int = 0,
    /**
     * Swipe directions on a key whose tap is something a swipe cannot share
     * here: a delete key's word swipe, a space bar's cursor swipe.
     */
    var gesturesDropped: Int = 0,
    /** Shifted pages laid out differently from the page they shift. */
    var shiftedPagesUnmerged: Int = 0,
    /** The file's own extra pages (AltGr, 123, Edit) became pages of the layout. */
    var pagesKept: Boolean = false,
    /** A desktop layout's AltGr characters went on press and hold. */
    var altGrOnHold: Boolean = false,
    /** Desktop dead keys whose accented letters went on press and hold. */
    var deadKeysSpread: Int = 0,
    /** Characters from desktop keys with no letters, put on the full stop's hold. */
    var symbolsOnPeriod: Int = 0,
    /** XKB keysym names this build cannot put a character to. */
    val unknownSymbols: MutableList<String> = mutableListOf(),
    /** Other XKB files a variant builds on, which one file cannot bring along. */
    val missingIncludes: MutableList<String> = mutableListOf(),
    /** keyboard3 rewrite rules that became this keyboard's dead keys. */
    var transformsAsDeadKeys: Int = 0,
    /** keyboard3 rewrite rules whose results went on press and hold. */
    var transformsOnHold: Int = 0,
    /** keyboard3 rewrite rules with nothing here to run them. */
    var transformsDropped: Int = 0,
    /** keyboard3 rules that put marks in their written order. */
    var reorderDropped: Int = 0,
    /** keyboard3 rules for what backspace deletes. */
    var backspaceDropped: Int = 0,
    /** Pages for a modifier this keyboard has no level for: Caps Lock, Ctrl. */
    var layersDropped: Int = 0,
    /** Swipes that change direction on the way, which arms here cannot. */
    var flickPathsDropped: Int = 0,
) {
    fun notes(scaled: Boolean): List<LayoutMessage> = buildList {
        if (droppedLabels.isNotEmpty()) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_labels_dropped,
                    quantity = droppedLabels.size,
                    args = listOf(droppedLabels.size, droppedLabels.distinct().joinToString(", ")),
                ),
            )
        }
        if (selectors > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_selectors_flattened,
                    quantity = selectors,
                    args = listOf(selectors),
                ),
            )
        }
        if (unmapped.isNotEmpty()) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_keys_dropped,
                    quantity = unmapped.size,
                    args = listOf(unmapped.size, unmapped.distinct().joinToString(", ")),
                ),
            )
        }
        if (approximated > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_keys_approximated,
                    quantity = approximated,
                    args = listOf(approximated),
                ),
            )
        }
        if (lostPopups > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_popups_missing,
                    quantity = lostPopups,
                    args = listOf(lostPopups),
                ),
            )
        }
        if (normalized > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_letters_normalized,
                    quantity = normalized,
                    args = listOf(normalized),
                ),
            )
        }
        if (restyled > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_labels_restyled,
                    quantity = restyled,
                    args = listOf(restyled),
                ),
            )
        }
        if (shiftedPopups > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_shifted_popups_dropped,
                    quantity = shiftedPopups,
                    args = listOf(shiftedPopups),
                ),
            )
        }
        if (gesturesDropped > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_gestures_dropped,
                    quantity = gesturesDropped,
                    args = listOf(gesturesDropped),
                ),
            )
        }
        if (shiftedPagesUnmerged > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_shifted_pages_dropped,
                    quantity = shiftedPagesUnmerged,
                    args = listOf(shiftedPagesUnmerged),
                ),
            )
        }
        if (pagesKept) add(LayoutMessage(stringRes = R.string.core_lang_foreign_pages_kept))
        if (missingIncludes.isNotEmpty()) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_desktop_includes_missing,
                    quantity = missingIncludes.size,
                    args = listOf(missingIncludes.size, missingIncludes.distinct().joinToString(", ")),
                ),
            )
        }
        if (unknownSymbols.isNotEmpty()) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_desktop_unknown_symbols,
                    quantity = unknownSymbols.size,
                    args = listOf(unknownSymbols.size, unknownSymbols.distinct().joinToString(", ")),
                ),
            )
        }
        if (altGrOnHold) add(LayoutMessage(stringRes = R.string.core_lang_foreign_desktop_altgr_on_hold))
        if (deadKeysSpread > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_desktop_dead_keys,
                    quantity = deadKeysSpread,
                    args = listOf(deadKeysSpread),
                ),
            )
        }
        if (symbolsOnPeriod > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_foreign_desktop_symbols_on_period,
                    quantity = symbolsOnPeriod,
                    args = listOf(symbolsOnPeriod),
                ),
            )
        }
        addCount(R.plurals.core_lang_foreign_k3_transforms_dead_keys, transformsAsDeadKeys)
        addCount(R.plurals.core_lang_foreign_k3_transforms_on_hold, transformsOnHold)
        addCount(R.plurals.core_lang_foreign_k3_transforms_dropped, transformsDropped)
        addCount(R.plurals.core_lang_foreign_k3_reorder_dropped, reorderDropped)
        addCount(R.plurals.core_lang_foreign_k3_backspace_dropped, backspaceDropped)
        addCount(R.plurals.core_lang_foreign_k3_layers_dropped, layersDropped)
        addCount(R.plurals.core_lang_foreign_k3_flick_paths_dropped, flickPathsDropped)
        if (numberRowDropped) {
            add(LayoutMessage(stringRes = R.string.core_lang_foreign_number_row_dropped))
        }
        if (numberRowKept) {
            add(LayoutMessage(stringRes = R.string.core_lang_foreign_number_row_kept))
        }
        if (scaled) add(LayoutMessage(stringRes = R.string.core_lang_foreign_widths_scaled))
    }

    /** A note whose only argument is its own count, when there is anything to count. */
    private fun MutableList<LayoutMessage>.addCount(plural: Int, n: Int) {
        if (n > 0) add(LayoutMessage(pluralsRes = plural, quantity = n, args = listOf(n)))
    }
}

/** Language a layout falls back to when its characters name no script. */
private const val DefaultLangId = "en"

/** Above this, a row is written in grid units already. */
private const val FractionRowCeiling = 2.0

private const val FractionToGrid = 10f
private const val MinGridWidth = 0.1f

private const val FIRST_NON_ASCII = 0x80
private const val SHIFT_BASE = 16

/**
 * Base letter plus nukta, and the single character Unicode also has for it.
 *
 * These are the composition exclusions: NFC will not join them, so they have to
 * be joined here. Keyed on `(base shl 16) or nukta` to keep the lookup to one
 * map read per character with nothing allocated.
 *
 * The base letters are written as code points on purpose. A composed letter and
 * its decomposed pair are indistinguishable on screen, so a table written
 * entirely in glyphs cannot be proof-read, and an entry that decomposed on its
 * way into the file would map a letter to itself and silently do nothing. The
 * composed values have to stay glyphs — they are `Char`, and a decomposed pair
 * would not compile — and a test asserts each one decomposes back to its key.
 */
private val NuktaCompositions: Map<Int, Char> = buildMap {
    fun put(base: Int, nukta: Int, composed: Char) {
        put((base shl SHIFT_BASE) or nukta, composed)
    }
    // Devanagari.
    put(0x0915, DEVANAGARI_NUKTA, 'क़')
    put(0x0916, DEVANAGARI_NUKTA, 'ख़')
    put(0x0917, DEVANAGARI_NUKTA, 'ग़')
    put(0x091C, DEVANAGARI_NUKTA, 'ज़')
    put(0x0921, DEVANAGARI_NUKTA, 'ड़')
    put(0x0922, DEVANAGARI_NUKTA, 'ढ़')
    put(0x0928, DEVANAGARI_NUKTA, 'ऩ')
    put(0x092B, DEVANAGARI_NUKTA, 'फ़')
    put(0x092F, DEVANAGARI_NUKTA, 'य़')
    put(0x0930, DEVANAGARI_NUKTA, 'ऱ')
    put(0x0933, DEVANAGARI_NUKTA, 'ऴ')
    // Bengali. These three matter most here: the cluster composer and the
    // backspace rules name the composed code points directly.
    put(0x09A1, BENGALI_NUKTA, 'ড়')
    put(0x09A2, BENGALI_NUKTA, 'ঢ়')
    put(0x09AF, BENGALI_NUKTA, 'য়')
    // Gurmukhi.
    put(0x0A16, GURMUKHI_NUKTA, 'ਖ਼')
    put(0x0A17, GURMUKHI_NUKTA, 'ਗ਼')
    put(0x0A1C, GURMUKHI_NUKTA, 'ਜ਼')
    put(0x0A2B, GURMUKHI_NUKTA, 'ਫ਼')
    put(0x0A32, GURMUKHI_NUKTA, 'ਲ਼')
    put(0x0A38, GURMUKHI_NUKTA, 'ਸ਼')
    // Oriya.
    put(0x0B21, ORIYA_NUKTA, 'ଡ଼')
    put(0x0B22, ORIYA_NUKTA, 'ଢ଼')
}

private const val DEVANAGARI_NUKTA = 0x093C
private const val BENGALI_NUKTA = 0x09BC
private const val GURMUKHI_NUKTA = 0x0A3C
private const val ORIYA_NUKTA = 0x0B3C

/** The four nukta signs, so text carrying none skips the scan entirely. */
private val NuktaSigns =
    setOf(DEVANAGARI_NUKTA, BENGALI_NUKTA, GURMUKHI_NUKTA, ORIYA_NUKTA)

/** F1 through F12, which HeliBoard numbers downwards. */
private val FunctionKeyCodes = -10039..-10028

// Written as numbers rather than KeyEvent.KEYCODE_* for the reason KeyActions.kt
// gives: this module is layout data and takes no android.view import.
private const val KEYCODE_A = 29
private const val KEYCODE_Z = 54
private const val KEYCODE_TAB = 61
private const val KEYCODE_ESCAPE = 111
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22
private const val KEYCODE_PAGE_UP = 92
private const val KEYCODE_PAGE_DOWN = 93
private const val KEYCODE_MOVE_HOME = 122
private const val KEYCODE_MOVE_END = 123
private const val KEYCODE_CUT = 277
private const val KEYCODE_COPY = 278
private const val KEYCODE_PASTE = 279
private const val KEYCODE_F1 = 131
private const val META_SHIFT_ON = 0x1
private const val META_CTRL_ON = 0x1000
