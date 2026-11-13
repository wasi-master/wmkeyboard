package com.wasimaster.wmkeyboard.core.layout

import java.text.Normalizer

/*
 * Reading the three desktop keyboard layout formats into one shape.
 *
 * Plenty of minority-language layouts exist only for a computer: an MSKLC
 * `.klc` for Windows, an Ukelele `.keylayout` for macOS, an XKB `symbols` file
 * for Linux. All three describe the same thing, a PC keyboard's keys and what
 * each one types with shift and AltGr held, so all three read into a
 * [DesktopKeymap], and `DesktopLayouts` turns that into a grid.
 *
 * Nothing in this file knows about the grid, Android or this app's resources,
 * on purpose: the parsers are the part with the most to get wrong, and keeping
 * them free of everything else is what lets them be tested on their own.
 *
 * Written from the formats as documented (MSKLC's own help, Apple's TN2056 and
 * the `KeyboardLayout.dtd`, the XKB specification) and from files people
 * publish, not from any tool's code.
 */

/** Which desktop format a [DesktopKeymap] was read from. */
internal enum class DesktopFormat {
    KLC,
    MAC_KEYLAYOUT,
    XKB,

    /** A keyboard3 file with only hardware layers, read by [Keyboard3Layouts]. */
    KEYBOARD3,
}

/**
 * A key on a PC keyboard, by the name XKB gives it, which is the one name all
 * three formats can be mapped onto. [row] is 0 for the row of digits, then the
 * three letter rows top to bottom. Declared in reading order, row by row.
 *
 * [BKSL] is the key above Enter on an ANSI keyboard and the one beside it on
 * an ISO one. It is filed with the home row, where ISO keyboards have it and
 * where the layouts that put a letter on it expect it to be.
 */
internal enum class DesktopKeyPos(val row: Int) {
    TLDE(0), AE01(0), AE02(0), AE03(0), AE04(0), AE05(0), AE06(0),
    AE07(0), AE08(0), AE09(0), AE10(0), AE11(0), AE12(0),
    AD01(1), AD02(1), AD03(1), AD04(1), AD05(1), AD06(1),
    AD07(1), AD08(1), AD09(1), AD10(1), AD11(1), AD12(1),
    AC01(2), AC02(2), AC03(2), AC04(2), AC05(2), AC06(2),
    AC07(2), AC08(2), AC09(2), AC10(2), AC11(2), BKSL(2),
    LSGT(3), AB01(3), AB02(3), AB03(3), AB04(3), AB05(3),
    AB06(3), AB07(3), AB08(3), AB09(3), AB10(3),
    ;

    /**
     * Whether this is one of the 26 keys that hold the Latin letters on a
     * QWERTY keyboard. Those are kept on the grid whatever they type; the keys
     * around them only when they type a letter.
     */
    val isLetterKey: Boolean get() = this in LetterKeys

    companion object {
        private val LetterKeys: Set<DesktopKeyPos> =
            (AD01 through AD10) + (AC01 through AC09) + (AB01 through AB07)

        private infix fun DesktopKeyPos.through(other: DesktopKeyPos): Set<DesktopKeyPos> =
            entries.subList(ordinal, other.ordinal + 1).toSet()
    }
}

/** What one shift level of one key types. */
internal sealed interface DesktopOutput {
    data class Text(val text: String) : DesktopOutput

    /**
     * A dead key: it types nothing, and changes the next key instead.
     *
     * [compositions] is the table the file gives (`a` to `á`). XKB files give
     * none, since that table lives in the system's Compose file, so an XKB dead
     * key carries only its [mark] and composes through Unicode instead. [mark]
     * is the combining mark the key amounts to, when it amounts to one; this
     * keyboard's own dead keys are exactly those marks (see `DeadKeys`).
     * [standalone] is what the key types when nothing follows it.
     */
    data class Dead(
        val name: String,
        val compositions: Map<String, String>,
        val mark: Char?,
        val standalone: String?,
    ) : DesktopOutput {

        /** What this dead key makes of [base], or null when it makes nothing. */
        fun compose(base: String): String? {
            compositions[base]?.let { return it }
            val m = mark ?: return null
            if (compositions.isNotEmpty()) return null
            val composed = Normalizer.normalize(base + m, Normalizer.Form.NFC)
            return composed.takeIf { it.codePointCount(0, it.length) == 1 && it != base }
        }
    }
}

/**
 * One desktop layout, read. [keys] holds up to four levels per key: plain,
 * shift, AltGr and shift with AltGr, null where the file gives nothing.
 */
internal class DesktopKeymap(
    val format: DesktopFormat,
    val name: String?,
    /** A BCP 47 tag the file states, which only `.klc` files do. */
    val languageTag: String?,
    val keys: Map<DesktopKeyPos, List<DesktopOutput?>>,
    /** Key symbols an XKB file used that this reader cannot put a character to. */
    val unknownSymbols: List<String> = emptyList(),
    /** Other XKB files this one builds on, which a single file cannot bring along. */
    val missingIncludes: List<String> = emptyList(),
)

internal const val LEVEL_BASE = 0
internal const val LEVEL_SHIFT = 1
internal const val LEVEL_ALTGR = 2
internal const val LEVEL_SHIFT_ALTGR = 3
private const val LEVELS = 4

// ---------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------

/**
 * [bytes] as text, in the encoding the file says or plainly is.
 *
 * MSKLC saves `.klc` files as UTF-16 with a byte order mark, and older Apple
 * `.keylayout` files are UTF-16 too. Read as UTF-8, either is a string of
 * NULs that no reader recognises. A UTF-8 byte order mark is dropped as well.
 */
internal fun decodeDesktopText(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == BOM_FF && bytes[1] == BOM_FE) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == BOM_FE && bytes[1] == BOM_FF) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    if (bytes.size >= 3 && bytes[0] == UTF8_BOM_0 && bytes[1] == UTF8_BOM_1 && bytes[2] == UTF8_BOM_2) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    // UTF-16 with no mark: text in a Latin script has a NUL in every other
    // byte, which no UTF-8 text file has at all.
    if (bytes.size >= UTF16_SNIFF_MIN) {
        val sample = minOf(bytes.size, UTF16_SNIFF_BYTES) and 1.inv()
        var oddNuls = 0
        var evenNuls = 0
        for (i in 0 until sample) {
            if (bytes[i].toInt() != 0) continue
            if (i % 2 == 0) evenNuls++ else oddNuls++
        }
        val half = sample / 2
        if (oddNuls * 2 > half && evenNuls == 0) return String(bytes, Charsets.UTF_16LE)
        if (evenNuls * 2 > half && oddNuls == 0) return String(bytes, Charsets.UTF_16BE)
    }
    return bytes.decodeToString()
}

private const val BOM_FF = 0xFF.toByte()
private const val BOM_FE = 0xFE.toByte()
private const val UTF8_BOM_0 = 0xEF.toByte()
private const val UTF8_BOM_1 = 0xBB.toByte()
private const val UTF8_BOM_2 = 0xBF.toByte()
private const val UTF16_SNIFF_MIN = 8
private const val UTF16_SNIFF_BYTES = 512

/** Whether [text] is something a key can type: no control characters. */
private fun typeable(text: String): Boolean =
    text.isNotEmpty() && text.none { it.code < SPACE || it.code == DEL || it.code in C1_CONTROLS }

private const val SPACE = 0x20
private const val DEL = 0x7F
private val C1_CONTROLS = 0x80..0x9F

/**
 * The combining mark most of a dead key's table amounts to, so that the key
 * can be this keyboard's own kind of dead key. Null when no one mark explains
 * at least half of the table: a Greek or Vietnamese dead key that stacks two
 * marks, or one that maps letters to unrelated letters.
 */
internal fun markOf(compositions: Map<String, String>): Char? {
    if (compositions.isEmpty()) return null
    var best: Char? = null
    var bestCount = 0
    for (code in COMBINING_FIRST..COMBINING_LAST) {
        val mark = code.toChar()
        var count = 0
        for ((base, composed) in compositions) {
            if (Normalizer.normalize(base + mark, Normalizer.Form.NFC) == composed) count++
        }
        if (count > bestCount) {
            best = mark
            bestCount = count
        }
    }
    return best.takeIf { bestCount * 2 >= compositions.size }
}

private const val COMBINING_FIRST = 0x0300
private const val COMBINING_LAST = 0x036F

/** `AD`, 1 is [DesktopKeyPos.AD01]. Not `String.format`, which writes digits in the default locale's. */
internal fun keyPos(row: String, index: Int): DesktopKeyPos =
    DesktopKeyPos.valueOf(row + index.toString().padStart(2, '0'))

private fun codePointText(code: Int): String? =
    if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else null

// ---------------------------------------------------------------------------
// Windows: MSKLC .klc
// ---------------------------------------------------------------------------

/**
 * The Microsoft Keyboard Layout Creator's source file.
 *
 * A text file of tab-separated sections. `SHIFTSTATE` lists the modifier
 * combinations the `LAYOUT` table has a column for (0 plain, 1 shift, 6
 * Ctrl+Alt, which is AltGr, 7 shift with AltGr). Each `LAYOUT` line is a scan
 * code, a virtual key, a caps flag and one cell per column. A cell is the
 * character itself when it is a letter or digit, four hex digits otherwise,
 * `-1` for nothing, `%%` for a run of characters listed under `LIGATURE`, and
 * any of those with `@` after it for a dead key, whose table is the `DEADKEY`
 * section headed by its character. Comments start with `//` or `;`.
 */
internal object KlcReader {

    fun looksLikeKlc(text: String): Boolean {
        var kbd = false
        var layout = false
        var shiftState = false
        for (line in text.lineSequence().take(SNIFF_LINES)) {
            val word = line.trimStart().substringBefore('\t').substringBefore(' ').trim()
            when (word) {
                "KBD" -> kbd = true
                "LAYOUT" -> layout = true
                "SHIFTSTATE" -> shiftState = true
            }
            if (kbd && layout && shiftState) return true
        }
        return false
    }

    fun read(text: String): DesktopKeymap? {
        var section = ""
        var name: String? = null
        var shortName: String? = null
        var locale: String? = null
        val columns = mutableListOf<Int>()
        val rows = mutableListOf<List<String>>()
        val ligatures = HashMap<Pair<String, Int>, String>()
        val deadTables = HashMap<String, MutableMap<String, String>>()
        var deadChar: String? = null

        for (raw in text.lineSequence()) {
            val line = stripComment(raw).trim()
            if (line.isEmpty()) continue
            val tokens = tokens(line)
            val head = tokens.first()
            if (head in Sections) {
                section = head
                when (head) {
                    "KBD" -> {
                        shortName = tokens.getOrNull(1)?.unquote()
                        name = tokens.getOrNull(2)?.unquote()?.ifBlank { null }
                    }
                    "LOCALENAME" -> locale = tokens.getOrNull(1)?.unquote()?.ifBlank { null }
                    "DEADKEY" -> {
                        deadChar = tokens.getOrNull(1)?.let(::cellText)
                        deadChar?.let { deadTables.getOrPut(it) { LinkedHashMap() } }
                    }
                }
                continue
            }
            when (section) {
                "SHIFTSTATE" -> head.toIntOrNull()?.let(columns::add)
                "LAYOUT" -> rows += tokens
                "LIGATURE" -> if (tokens.size >= 3) {
                    val col = tokens[1].toIntOrNull() ?: continue
                    val chars = tokens.drop(2).mapNotNull(::cellText).joinToString("")
                    if (chars.isNotEmpty()) ligatures[tokens[0].uppercase() to col] = chars
                }
                "DEADKEY" -> {
                    val table = deadChar?.let { deadTables[it] } ?: continue
                    val base = tokens.getOrNull(0)?.let(::cellText) ?: continue
                    val composed = tokens.getOrNull(1)?.removeSuffix("@")?.let(::cellText) ?: continue
                    if (typeable(composed)) table[base] = composed
                }
            }
        }
        if (columns.isEmpty()) columns += listOf(0, 1)

        val dead = deadTables.mapValues { (char, table) ->
            DesktopOutput.Dead(char, table, markOf(table), char.takeIf(::typeable))
        }
        val keys = LinkedHashMap<DesktopKeyPos, List<DesktopOutput?>>()
        for (tokens in rows) {
            // A caps-lock continuation line (SGCap) starts with -1 -1; it
            // describes caps lock, which a touch keyboard spells as shift.
            val pos = tokens[0].toIntOrNull(HEX)?.let(ScanCodes::get) ?: continue
            val vk = tokens.getOrNull(1)?.uppercase() ?: continue
            val levels = arrayOfNulls<DesktopOutput>(LEVELS)
            columns.forEachIndexed { col, state ->
                val level = LevelOfShiftState[state] ?: return@forEachIndexed
                val cell = tokens.getOrNull(CELLS_START + col) ?: return@forEachIndexed
                levels[level] = cellOutput(cell, ligatures[vk to col], dead)
            }
            if (levels.any { it != null }) keys[pos] = levels.toList()
        }
        if (keys.isEmpty()) return null
        return DesktopKeymap(
            format = DesktopFormat.KLC,
            name = name ?: shortName,
            languageTag = locale,
            keys = keys,
        )
    }

    private fun cellOutput(
        cell: String,
        ligature: String?,
        dead: Map<String, DesktopOutput.Dead>,
    ): DesktopOutput? {
        if (cell == NONE) return null
        if (cell == LIGATURE) return ligature?.takeIf(::typeable)?.let { DesktopOutput.Text(it) }
        val isDead = cell.endsWith("@")
        val text = cellText(cell.removeSuffix("@")) ?: return null
        if (isDead) return dead[text] ?: DesktopOutput.Dead(text, emptyMap(), null, text.takeIf(::typeable))
        return text.takeIf(::typeable)?.let { DesktopOutput.Text(it) }
    }

    /** A cell's character: itself when it is one character, else four hex digits. */
    private fun cellText(cell: String): String? = when {
        cell == NONE -> null
        cell.length == 1 -> cell
        cell.length in HEX_CELL && cell.all { it.isHexDigit() } -> codePointText(cell.toInt(HEX))
        else -> null
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** [line] up to a `//` or `;` comment, leaving quoted text alone. */
    private fun stripComment(line: String): String {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '"' -> quoted = !quoted
                quoted -> Unit
                c == ';' -> return line.substring(0, i)
                c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
        }
        return line
    }

    /** Whitespace-separated, with a quoted string kept as one token. */
    private fun tokens(line: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            if (line[i].isWhitespace()) {
                i++
                continue
            }
            val end = if (line[i] == '"') {
                line.indexOf('"', i + 1).let { if (it < 0) line.length else it + 1 }
            } else {
                var j = i
                while (j < line.length && !line[j].isWhitespace()) j++
                j
            }
            out += line.substring(i, end)
            i = end
        }
        return out
    }

    private fun String.unquote(): String = trim().removePrefix("\"").removeSuffix("\"").trim()

    private val Sections = setOf(
        "KBD", "COPYRIGHT", "COMPANY", "LOCALENAME", "LOCALEID", "VERSION", "ATTRIBUTES",
        "SHIFTSTATE", "LAYOUT", "LIGATURE", "DEADKEY", "KEYNAME", "KEYNAME_EXT", "KEYNAME_DEAD",
        "DESCRIPTIONS", "LANGUAGENAMES", "ENDKBD",
    )

    /** SHIFTSTATE numbers: 0 plain, 1 shift, 6 Ctrl+Alt (AltGr), 7 shift with it. */
    private val LevelOfShiftState = mapOf(0 to LEVEL_BASE, 1 to LEVEL_SHIFT, 6 to LEVEL_ALTGR, 7 to LEVEL_SHIFT_ALTGR)

    /** Set 1 scan codes of the keys a grid is made from. */
    private val ScanCodes: Map<Int, DesktopKeyPos> = buildMap {
        put(0x29, DesktopKeyPos.TLDE)
        for (i in 0 until 12) put(0x02 + i, keyPos("AE", i + 1))
        for (i in 0 until 12) put(0x10 + i, keyPos("AD", i + 1))
        for (i in 0 until 11) put(0x1E + i, keyPos("AC", i + 1))
        put(0x2B, DesktopKeyPos.BKSL)
        put(0x56, DesktopKeyPos.LSGT)
        for (i in 0 until 10) put(0x2C + i, keyPos("AB", i + 1))
    }

    private const val NONE = "-1"
    private const val LIGATURE = "%%"
    private const val CELLS_START = 3
    private const val HEX = 16
    private val HEX_CELL = 4..6
    private const val SNIFF_LINES = 400
}

// ---------------------------------------------------------------------------
// macOS: .keylayout
// ---------------------------------------------------------------------------

/**
 * Apple's keyboard layout XML, as Ukelele writes it.
 *
 * `<layouts>` picks a `<keyMapSet>` and a `<modifierMap>` per hardware type;
 * the modifier map says which `<keyMap>` index each combination of modifiers
 * uses; a key map gives each virtual key code an `output`, or an `action`
 * whose `<when state="none">` either outputs or moves to a dead-key state.
 * The other `<when>`s of every action are the dead keys' tables.
 *
 * Read with a small tag scanner rather than an XML parser. These files are
 * XML 1.1, they name a DTD on the local disk of a Mac, and they spell control
 * characters as `&#x0008;`, which XML 1.0 parsers reject. All the reader needs
 * is elements and attributes.
 */
internal object MacKeylayoutReader {

    fun looksLikeKeylayout(text: String): Boolean =
        text.contains("<keyboard") && text.contains("<keyMapSet") && text.contains("<keyMap ")

    private class KeyMapRaw(val baseSet: String?, val baseIndex: Int?, val keys: MutableMap<Int, Pair<String?, String?>>)

    private class When(val state: String, val output: String?, val next: String?)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun read(text: String): DesktopKeymap? {
        var name: String? = null
        val layouts = mutableListOf<Map<String, String>>()
        val modifierMaps = HashMap<String, Pair<Int, MutableList<Pair<Int, String>>>>()
        val keyMapSets = HashMap<String, MutableMap<Int, KeyMapRaw>>()
        val actions = HashMap<String, MutableList<When>>()
        val terminators = HashMap<String, String>()

        var modifierMap: String? = null
        var mapIndex: Int? = null
        var keyMapSet: String? = null
        var keyMap: KeyMapRaw? = null
        var action: String? = null
        var inTerminators = false

        for (tag in xmlTags(text)) {
            val a = tag.attrs
            if (tag.closing) {
                when (tag.name) {
                    "modifierMap" -> modifierMap = null
                    "keyMapSelect" -> mapIndex = null
                    "keyMapSet" -> keyMapSet = null
                    "keyMap" -> keyMap = null
                    "action" -> action = null
                    "terminators" -> inTerminators = false
                }
                continue
            }
            when (tag.name) {
                "keyboard" -> name = a["name"]?.trim()?.ifEmpty { null }
                "layout" -> layouts += a
                "modifierMap" -> {
                    val id = a["id"] ?: continue
                    modifierMap = id
                    modifierMaps[id] = (a["defaultIndex"]?.toIntOrNull() ?: 0) to mutableListOf()
                }
                "keyMapSelect" -> mapIndex = a["mapIndex"]?.toIntOrNull()
                "modifier" -> {
                    val map = modifierMap?.let(modifierMaps::get) ?: continue
                    val index = mapIndex ?: continue
                    map.second += index to a["keys"].orEmpty()
                }
                "keyMapSet" -> keyMapSet = a["id"]
                "keyMap" -> {
                    val set = keyMapSet ?: continue
                    val index = a["index"]?.toIntOrNull() ?: continue
                    val raw = KeyMapRaw(a["baseMapSet"], a["baseIndex"]?.toIntOrNull(), HashMap())
                    keyMapSets.getOrPut(set) { HashMap() }[index] = raw
                    keyMap = raw.takeIf { !tag.selfClosing }
                }
                "key" -> {
                    val map = keyMap ?: continue
                    val code = a["code"]?.toIntOrNull() ?: continue
                    map.keys[code] = a["output"] to a["action"]
                }
                "action" -> action = a["id"].takeIf { !tag.selfClosing }
                "terminators" -> inTerminators = !tag.selfClosing
                "when" -> {
                    val state = a["state"] ?: continue
                    if (inTerminators) {
                        a["output"]?.let { terminators[state] = it }
                    } else {
                        val id = action ?: continue
                        actions.getOrPut(id) { mutableListOf() } += When(state, a["output"], a["next"])
                    }
                }
            }
        }

        val layout = layouts.firstOrNull { it["first"] == "0" } ?: layouts.firstOrNull() ?: return null
        val set = layout["mapSet"]?.let(keyMapSets::get) ?: return null
        val modifiers = layout["modifiers"]?.let(modifierMaps::get)

        fun keysOf(setId: String?, index: Int, depth: Int = 0): Map<Int, Pair<String?, String?>> {
            if (depth > MAX_BASE_DEPTH) return emptyMap()
            val raw = (setId?.let(keyMapSets::get) ?: set)[index] ?: return emptyMap()
            val base = raw.baseIndex?.let { keysOf(raw.baseSet ?: setId, it, depth + 1) }.orEmpty()
            return base + raw.keys
        }

        val setId = layout["mapSet"]
        val levelMaps = Targets.map { target -> indexFor(modifiers, target) }

        // Every dead-key state's table: the base character an action types on
        // its own, and what it types instead in that state.
        val stateTables = HashMap<String, MutableMap<String, String>>()
        for (whens in actions.values) {
            val base = whens.firstOrNull { it.state == NONE_STATE }?.output ?: continue
            for (w in whens) {
                if (w.state == NONE_STATE) continue
                val out = w.output ?: continue
                if (typeable(out)) stateTables.getOrPut(w.state) { LinkedHashMap() }[base] = out
            }
        }
        val deadStates = HashMap<String, DesktopOutput.Dead>()
        fun deadFor(state: String): DesktopOutput.Dead = deadStates.getOrPut(state) {
            val table = stateTables[state].orEmpty()
            DesktopOutput.Dead(state, table, markOf(table), terminators[state]?.takeIf(::typeable))
        }

        fun outputOf(entry: Pair<String?, String?>?): DesktopOutput? {
            val (output, actionId) = entry ?: return null
            if (output != null) return output.takeIf(::typeable)?.let { DesktopOutput.Text(it) }
            val none = actionId?.let(actions::get)?.firstOrNull { it.state == NONE_STATE } ?: return null
            none.output?.let { return it.takeIf(::typeable)?.let { t -> DesktopOutput.Text(t) } }
            return none.next?.let(::deadFor)
        }

        val levelKeys = levelMaps.map { index -> index?.let { keysOf(setId, it) } }
        val keys = LinkedHashMap<DesktopKeyPos, List<DesktopOutput?>>()
        for ((code, pos) in KeyCodes) {
            val levels = levelKeys.map { map -> map?.let { outputOf(it[code]) } }
            if (levels.any { it != null }) keys[pos] = levels
        }
        if (keys.isEmpty()) return null
        return DesktopKeymap(DesktopFormat.MAC_KEYLAYOUT, name, languageTag = null, keys = keys)
    }

    /**
     * The key map index [target] selects: the first `<keyMapSelect>` with a
     * `<modifier>` that matches, in document order, as the system reads them.
     * The plain state falls back to the map's default; any other state that
     * nothing matches has no map of its own, and reads as nothing.
     */
    private fun indexFor(modifiers: Pair<Int, List<Pair<Int, String>>>?, target: Set<String>): Int? {
        if (modifiers == null) return if (target.isEmpty()) 0 else null
        val (default, selects) = modifiers
        selects.firstOrNull { (_, keys) -> matches(keys, target) }?.let { return it.first }
        return default.takeIf { target.isEmpty() }
    }

    /**
     * Whether a `keys="anyShift caps? anyOption"` list matches [held]: every
     * modifier it requires is held, and every one held is named, required or
     * optional (`?`).
     */
    private fun matches(keys: String, held: Set<String>): Boolean {
        val named = HashSet<String>()
        for (token in keys.split(' ', '\t', '\n').filter { it.isNotEmpty() }) {
            val optional = token.endsWith("?")
            val family = ModifierFamilies[token.removeSuffix("?")] ?: return false
            if (!optional && family !in held) return false
            named += family
        }
        return held.all { it in named }
    }

    private val Targets: List<Set<String>> = listOf(
        emptySet(),
        setOf(SHIFT),
        setOf(OPTION),
        setOf(SHIFT, OPTION),
    )

    private const val SHIFT = "shift"
    private const val OPTION = "option"

    private val ModifierFamilies: Map<String, String> = mapOf(
        "anyShift" to SHIFT, "shift" to SHIFT, "rightShift" to SHIFT,
        "anyOption" to OPTION, "option" to OPTION, "rightOption" to OPTION,
        "anyControl" to "control", "control" to "control", "rightControl" to "control",
        "caps" to "caps", "command" to "command",
    )

    /** Apple's virtual key codes for the keys a grid is made from (ANSI and ISO). */
    private val KeyCodes: List<Pair<Int, DesktopKeyPos>> = listOf(
        50 to DesktopKeyPos.TLDE, 18 to DesktopKeyPos.AE01, 19 to DesktopKeyPos.AE02,
        20 to DesktopKeyPos.AE03, 21 to DesktopKeyPos.AE04, 23 to DesktopKeyPos.AE05,
        22 to DesktopKeyPos.AE06, 26 to DesktopKeyPos.AE07, 28 to DesktopKeyPos.AE08,
        25 to DesktopKeyPos.AE09, 29 to DesktopKeyPos.AE10, 27 to DesktopKeyPos.AE11,
        24 to DesktopKeyPos.AE12,
        12 to DesktopKeyPos.AD01, 13 to DesktopKeyPos.AD02, 14 to DesktopKeyPos.AD03,
        15 to DesktopKeyPos.AD04, 17 to DesktopKeyPos.AD05, 16 to DesktopKeyPos.AD06,
        32 to DesktopKeyPos.AD07, 34 to DesktopKeyPos.AD08, 31 to DesktopKeyPos.AD09,
        35 to DesktopKeyPos.AD10, 33 to DesktopKeyPos.AD11, 30 to DesktopKeyPos.AD12,
        0 to DesktopKeyPos.AC01, 1 to DesktopKeyPos.AC02, 2 to DesktopKeyPos.AC03,
        3 to DesktopKeyPos.AC04, 5 to DesktopKeyPos.AC05, 4 to DesktopKeyPos.AC06,
        38 to DesktopKeyPos.AC07, 40 to DesktopKeyPos.AC08, 37 to DesktopKeyPos.AC09,
        41 to DesktopKeyPos.AC10, 39 to DesktopKeyPos.AC11, 42 to DesktopKeyPos.BKSL,
        10 to DesktopKeyPos.LSGT, 6 to DesktopKeyPos.AB01, 7 to DesktopKeyPos.AB02,
        8 to DesktopKeyPos.AB03, 9 to DesktopKeyPos.AB04, 11 to DesktopKeyPos.AB05,
        45 to DesktopKeyPos.AB06, 46 to DesktopKeyPos.AB07, 43 to DesktopKeyPos.AB08,
        47 to DesktopKeyPos.AB09, 44 to DesktopKeyPos.AB10,
    )

    private const val NONE_STATE = "none"
    private const val MAX_BASE_DEPTH = 8
}

/** One XML tag: its name, its attributes with entities decoded, and its kind. */
internal class XmlTag(
    val name: String,
    val attrs: Map<String, String>,
    val closing: Boolean,
    val selfClosing: Boolean,
)

/**
 * The tags of [xml], in order, comments, declarations and text skipped.
 * Tolerant by design: anything that is not a well-formed tag is passed over.
 */
internal fun xmlTags(xml: String): Sequence<XmlTag> = sequence {
    var i = 0
    while (true) {
        val open = xml.indexOf('<', i)
        if (open < 0) break
        when {
            xml.startsWith("<!--", open) -> {
                val end = xml.indexOf("-->", open + 4)
                i = if (end < 0) xml.length else end + 3
                continue
            }
            xml.startsWith("<!", open) || xml.startsWith("<?", open) -> {
                i = skipDeclaration(xml, open)
                continue
            }
        }
        val end = tagEnd(xml, open + 1)
        if (end < 0) break
        val body = xml.substring(open + 1, end)
        i = end + 1
        val closing = body.startsWith("/")
        val selfClosing = body.endsWith("/")
        val inner = body.removePrefix("/").removeSuffix("/")
        val nameEnd = inner.indexOfFirst { it.isWhitespace() }.let { if (it < 0) inner.length else it }
        val name = inner.substring(0, nameEnd)
        if (name.isEmpty()) continue
        val attrs = HashMap<String, String>()
        for (match in AttributePattern.findAll(inner.substring(nameEnd))) {
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            attrs[match.groupValues[1]] = decodeEntities(value)
        }
        yield(XmlTag(name, attrs, closing, selfClosing))
    }
}

/** Where a tag starting at [from] ends: the first `>` outside a quoted value. */
private fun tagEnd(xml: String, from: Int): Int {
    var quote: Char? = null
    for (j in from until xml.length) {
        val c = xml[j]
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '>' -> return j
        }
    }
    return -1
}

/** Past a `<!DOCTYPE …>` or `<?xml …?>`, including a DOCTYPE's `[…]` subset. */
private fun skipDeclaration(xml: String, open: Int): Int {
    var depth = 0
    for (j in open + 1 until xml.length) {
        when (xml[j]) {
            '[' -> depth++
            ']' -> depth--
            '>' -> if (depth <= 0) return j + 1
        }
    }
    return xml.length
}

private val AttributePattern = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

private val EntityPattern = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);")

internal fun decodeEntities(value: String): String {
    if ('&' !in value) return value
    return EntityPattern.replace(value) { match ->
        val ref = match.groupValues[1]
        when {
            ref.startsWith("#x") || ref.startsWith("#X") -> ref.substring(2).toIntOrNull(16)?.let(::codePointText)
            ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let(::codePointText)
            else -> NamedEntities[ref]
        } ?: match.value
    }
}

private val NamedEntities = mapOf("lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'")

// ---------------------------------------------------------------------------
// Linux: XKB symbols
// ---------------------------------------------------------------------------

/**
 * An XKB `symbols` file, the kind under `/usr/share/X11/xkb/symbols/`.
 *
 * One file holds several variants, each an `xkb_symbols "name" { … };` block.
 * A block names keys by position (`key <AD01> { [ q, Q, at, Greek_OMEGA ] };`,
 * levels plain, shift, AltGr, shift with AltGr) and very often starts from
 * another variant with `include "us(basic)"`. An include within the same file
 * is followed; one into another file can only be followed for the plain US and
 * Latin bases, which are built in; anything else is named back as missing, so
 * the keys it would have brought are known to be absent rather than silently
 * absent. Includes that only set up modifiers (`level3(ralt_switch)`) carry no
 * characters and are passed over.
 *
 * Characters come as keysym names, translated through the table generated
 * from `keysymdef.h` (see `XkbKeysyms.kt`), as `U0915`, or as `0x1000915`.
 * Dead keys (`dead_acute`) become the combining mark they put on; XKB keeps
 * the compositions themselves in the system's Compose file, so Unicode's own
 * composition stands in for it.
 */
internal object XkbSymbolsReader {

    fun looksLikeXkb(text: String): Boolean = BlockHeader.containsMatchIn(text)

    /** Every variant in [text] that is a whole layout, the default one first. */
    fun read(text: String, fileName: String): List<DesktopKeymap> {
        val tokens = tokenize(text)
        val blocks = blocks(tokens)
        if (blocks.isEmpty()) return emptyList()
        val ownName = fileName.substringAfterLast('/').substringBefore('.').lowercase()
        val resolver = Resolver(blocks, ownName)
        return blocks
            .filter { !it.hidden }
            .sortedByDescending { it.isDefault }
            .mapNotNull { block ->
                val resolved = resolver.resolve(block)
                val letters = resolved.keys.count { (pos, levels) -> pos.isLetterKey && levels.firstOrNull() != null }
                if (letters < MIN_LETTER_KEYS) return@mapNotNull null
                val unknown = LinkedHashSet<String>()
                val keys = LinkedHashMap<DesktopKeyPos, List<DesktopOutput?>>()
                for (pos in DesktopKeyPos.entries) {
                    val levels = resolved.keys[pos] ?: continue
                    val outputs = (0 until LEVELS).map { level -> levels.getOrNull(level)?.let { symbolOutput(it, unknown) } }
                    if (outputs.any { it != null }) keys[pos] = outputs
                }
                DesktopKeymap(
                    format = DesktopFormat.XKB,
                    name = resolved.name ?: block.name,
                    languageTag = null,
                    keys = keys,
                    unknownSymbols = unknown.toList(),
                    missingIncludes = resolved.missing.toList(),
                )
            }
    }

    // ---- tokens ----

    private sealed interface Tok {
        data class Word(val text: String) : Tok
        data class Str(val text: String) : Tok
        data class KeyName(val name: String) : Tok
        data class Punct(val c: Char) : Tok
    }

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && text.getOrNull(i + 1) == '/' || c == '#' -> {
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                }
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    while (j < text.length && text[j] != '"') {
                        if (text[j] == '\\' && j + 1 < text.length) j++
                        sb.append(text[j])
                        j++
                    }
                    out += Tok.Str(sb.toString())
                    i = j + 1
                }
                c == '<' -> {
                    val end = text.indexOf('>', i)
                    if (end < 0) {
                        out += Tok.Punct(c)
                        i++
                    } else {
                        out += Tok.KeyName(text.substring(i + 1, end).trim())
                        i = end + 1
                    }
                }
                c.isLetterOrDigit() || c == '_' -> {
                    var j = i
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] in WordChars)) j++
                    out += Tok.Word(text.substring(i, j))
                    i = j
                }
                else -> {
                    out += Tok.Punct(c)
                    i++
                }
            }
        }
        return out
    }

    // ---- blocks ----

    private class Block(
        val name: String,
        val isDefault: Boolean,
        val hidden: Boolean,
        val body: List<Tok>,
    )

    private fun blocks(tokens: List<Tok>): List<Block> {
        val out = mutableListOf<Block>()
        var flags = mutableSetOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Tok.Word && t.text == "xkb_symbols") {
                val name = (tokens.getOrNull(i + 1) as? Tok.Str)?.text ?: ""
                val open = (i + 1 until tokens.size).firstOrNull { tokens[it] == OpenBrace } ?: break
                val close = matching(tokens, open)
                out += Block(name, "default" in flags, "hidden" in flags, tokens.subList(open + 1, close))
                flags = mutableSetOf()
                i = close + 1
                continue
            }
            if (t is Tok.Word) flags += t.text else flags.clear()
            i++
        }
        return out
    }

    private fun matching(tokens: List<Tok>, open: Int): Int {
        var depth = 0
        for (j in open until tokens.size) {
            when (tokens[j]) {
                OpenBrace -> depth++
                CloseBrace -> if (--depth == 0) return j
                else -> Unit
            }
        }
        return tokens.size
    }

    // ---- resolving a variant ----

    private class Resolved(
        val keys: MutableMap<DesktopKeyPos, MutableList<String?>> = LinkedHashMap(),
        var name: String? = null,
        val missing: MutableSet<String> = LinkedHashSet(),
    )

    private enum class Merge { OVERRIDE, AUGMENT, REPLACE }

    private class Resolver(val blocks: List<Block>, val ownName: String) {
        private val cache = HashMap<Block, Resolved>()
        private val inProgress = HashSet<Block>()

        fun resolve(block: Block): Resolved {
            cache[block]?.let { return it }
            if (!inProgress.add(block)) return Resolved()
            val out = Resolved()
            val body = block.body
            var i = 0
            while (i < body.size) {
                val t = body[i]
                val word = (t as? Tok.Word)?.text
                when {
                    word != null && word in MergeWords && body.getOrNull(i + 1) is Tok.Str -> {
                        val spec = (body[i + 1] as Tok.Str).text
                        val mode = if (word == "augment") Merge.AUGMENT else Merge.OVERRIDE
                        include(spec, out, mode)
                        i += 2
                        continue
                    }
                    word == "name" -> {
                        val end = statementEnd(body, i)
                        if (out.name == null) out.name = body.subList(i, end).firstNotNullOfOrNull { (it as? Tok.Str)?.text }
                        i = end + 1
                        continue
                    }
                    word == "key" || (word != null && word in MergeWords && (body.getOrNull(i + 1) as? Tok.Word)?.text == "key") -> {
                        val mode = when (word) {
                            "augment" -> Merge.AUGMENT
                            "replace" -> Merge.REPLACE
                            else -> Merge.OVERRIDE
                        }
                        val keyAt = if (word == "key") i else i + 1
                        val end = statementEnd(body, keyAt)
                        keyStatement(body.subList(keyAt + 1, end), out, mode)
                        i = end + 1
                        continue
                    }
                }
                i = statementEnd(body, i) + 1
            }
            inProgress -= block
            cache[block] = out
            return out
        }

        private fun include(spec: String, out: Resolved, mode: Merge) {
            for (part in spec.split('+', '|').map { it.trim() }.filter { it.isNotEmpty() }) {
                val file = part.substringBefore('(').trim().lowercase()
                val variant = part.substringAfter('(', "").removeSuffix(")").trim().ifEmpty { null }
                val local = when {
                    file == ownName -> blocks.firstOrNull { b -> if (variant == null) b.isDefault else b.name == variant }
                        ?: blocks.firstOrNull().takeIf { variant == null }
                    file in IgnoredIncludes || builtIn(file, variant) != null -> null
                    variant != null -> blocks.firstOrNull { it.name == variant }
                    else -> null
                }
                when {
                    local != null -> merge(out, resolve(local).keys, mode)
                    file in IgnoredIncludes -> Unit
                    else -> {
                        val base = builtIn(file, variant)
                        if (base != null) merge(out, base, mode) else out.missing += part
                    }
                }
            }
        }

        private fun keyStatement(tokens: List<Tok>, out: Resolved, mode: Merge) {
            val pos = (tokens.firstOrNull() as? Tok.KeyName)?.name?.let(::positionOf) ?: return
            val levels = groupOneSymbols(tokens) ?: return
            merge(out, mapOf(pos to levels.toMutableList()), mode)
        }

        private fun merge(
            out: Resolved,
            from: Map<DesktopKeyPos, List<String?>>,
            mode: Merge,
        ) {
            for ((pos, levels) in from) {
                val current = out.keys[pos]
                if (current == null || mode == Merge.REPLACE) {
                    out.keys[pos] = levels.toMutableList()
                    continue
                }
                for ((level, sym) in levels.withIndex()) {
                    while (current.size <= level) current += null
                    val keep = current[level] != null && mode == Merge.AUGMENT
                    if (sym != null && sym != NO_SYMBOL && !keep) current[level] = sym
                }
            }
        }
    }

    /** The end of the statement starting at [from]: its `;` at brace depth 0. */
    private fun statementEnd(tokens: List<Tok>, from: Int): Int {
        var depth = 0
        for (j in from until tokens.size) {
            when (tokens[j]) {
                OpenBrace, OpenBracket -> depth++
                CloseBrace, CloseBracket -> depth--
                Semicolon -> if (depth <= 0) return j
                else -> Unit
            }
        }
        return tokens.size
    }

    /**
     * A key statement's first group: the first bare `[…]` in its body, or
     * `symbols[Group1] = […]`. Null when it has neither (a key that only sets
     * its type or its actions).
     */
    private fun groupOneSymbols(tokens: List<Tok>): List<String?>? {
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Tok.Word && t.text.equals("symbols", ignoreCase = true)) {
                val close = (i until tokens.size).firstOrNull { tokens[it] == CloseBracket } ?: return null
                val group = tokens.subList(i, close).filterIsInstance<Tok.Word>().drop(1).firstOrNull()?.text
                val open = (close until tokens.size).firstOrNull { tokens[it] == OpenBracket } ?: return null
                val end = bracketEnd(tokens, open)
                if (group == null || group.equals("Group1", ignoreCase = true) || group == "1") {
                    return symbolList(tokens.subList(open + 1, end))
                }
                i = end + 1
                continue
            }
            if (t is Tok.Word && (t.text == "type" || t.text == "actions" || t.text == "vmods" || t.text == "virtualMods")) {
                // `type[Group1] = "…"` and `actions[Group1] = [ … ]`: skip
                // past the value, whose brackets are not symbols.
                val eq = (i until tokens.size).firstOrNull { tokens[it] == Equals } ?: return null
                val next = tokens.getOrNull(eq + 1)
                i = if (next == OpenBracket) bracketEnd(tokens, eq + 1) + 1 else eq + 2
                continue
            }
            if (t == OpenBracket) return symbolList(tokens.subList(i + 1, bracketEnd(tokens, i)))
            i++
        }
        return null
    }

    private fun bracketEnd(tokens: List<Tok>, open: Int): Int {
        var depth = 0
        for (j in open until tokens.size) {
            when (tokens[j]) {
                OpenBracket -> depth++
                CloseBracket -> if (--depth == 0) return j
                else -> Unit
            }
        }
        return tokens.size
    }

    /** `a, A, aacute` as levels; `{ a, b }` on one level is both, in order. */
    private fun symbolList(tokens: List<Tok>): List<String?> {
        val levels = mutableListOf<String?>()
        var current = StringBuilder()
        var empty = true
        var inGroup = false
        for (t in tokens) {
            when {
                t == OpenBrace -> inGroup = true
                t == CloseBrace -> inGroup = false
                t == Comma && !inGroup -> {
                    levels += current.toString().takeIf { !empty }
                    current = StringBuilder()
                    empty = true
                }
                t is Tok.Word -> {
                    if (!empty) current.append(MULTI_SEPARATOR)
                    current.append(t.text)
                    empty = false
                }
            }
        }
        levels += current.toString().takeIf { !empty }
        return levels
    }

    // ---- keysyms ----

    /** What a level's keysym (or keysyms, joined) types, or null. */
    private fun symbolOutput(raw: String, unknown: MutableSet<String>): DesktopOutput? {
        val parts = raw.split(MULTI_SEPARATOR)
        if (parts.size == 1) {
            val sym = parts[0]
            if (sym.startsWith(DEAD_PREFIX)) {
                val name = sym.removePrefix(DEAD_PREFIX)
                val mark = DeadMarks[name]
                if (mark == null) {
                    unknown += sym
                    return null
                }
                return DesktopOutput.Dead(sym, emptyMap(), mark, null)
            }
        }
        val text = parts.joinToString("") { keysymText(it, unknown).orEmpty() }
        return text.takeIf(::typeable)?.let { DesktopOutput.Text(it) }
    }

    private fun keysymText(sym: String, unknown: MutableSet<String>): String? {
        if (sym == NO_SYMBOL || sym == VOID_SYMBOL) return null
        if (sym.length == 1) return sym
        if (sym.length in UNICODE_NAME && sym[0] == 'U' && sym.drop(1).all { it.isHexDigitChar() }) {
            return codePointText(sym.drop(1).toInt(HEX))
        }
        if (sym.startsWith("0x") || sym.startsWith("0X")) {
            val value = sym.drop(2).toLongOrNull(HEX) ?: return null
            return when {
                value >= UNICODE_KEYSYM_BASE -> codePointText((value - UNICODE_KEYSYM_BASE).toInt())
                value in LATIN1_PRINTABLE_LOW || value in LATIN1_PRINTABLE_HIGH -> codePointText(value.toInt())
                else -> {
                    unknown += sym
                    null
                }
            }
        }
        Keysyms[sym]?.let { return codePointText(it) }
        if (FunctionKeysym.matches(sym)) return null
        unknown += sym
        return null
    }

    private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Name to code point, built once from the generated table. */
    private val Keysyms: Map<String, Int> by lazy {
        val map = HashMap<String, Int>(KEYSYM_CAPACITY)
        for (line in XKB_KEYSYMS.lineSequence()) {
            val space = line.indexOf(' ')
            if (space <= 0) continue
            line.substring(space + 1).toIntOrNull(HEX)?.let { map[line.substring(0, space)] = it }
        }
        map
    }

    private fun positionOf(name: String): DesktopKeyPos? = when (name) {
        "AC12", "AD13" -> DesktopKeyPos.BKSL
        else -> runCatching { DesktopKeyPos.valueOf(name) }.getOrNull()
    }

    /**
     * The two bases nearly every Latin layout builds on, levels one and two,
     * so a file that says `include "us"` or `include "latin"` and then changes
     * a handful of keys still has the rest of its keys. Their AltGr levels are
     * left out: those vary between the variants and are what a file usually
     * replaces anyway.
     */
    private fun builtIn(file: String, variant: String?): Map<DesktopKeyPos, List<String?>>? = when {
        file == "latin" -> UsBasic
        file == "us" && (variant == null || variant in UsBasicVariants) -> UsBasic
        else -> null
    }

    private val UsBasicVariants = setOf("basic", "euro", "intl", "alt-intl", "altgr-intl", "mac", "olpc")

    private val UsBasic: Map<DesktopKeyPos, List<String?>> by lazy {
        val pairs = listOf(
            "TLDE grave asciitilde", "AE01 1 exclam", "AE02 2 at", "AE03 3 numbersign", "AE04 4 dollar",
            "AE05 5 percent", "AE06 6 asciicircum", "AE07 7 ampersand", "AE08 8 asterisk",
            "AE09 9 parenleft", "AE10 0 parenright", "AE11 minus underscore", "AE12 equal plus",
            "AD11 bracketleft braceleft", "AD12 bracketright braceright",
            "AC10 semicolon colon", "AC11 apostrophe quotedbl", "BKSL backslash bar",
            "LSGT less greater", "AB08 comma less", "AB09 period greater", "AB10 slash question",
        )
        val letters = listOf("AD" to "qwertyuiop", "AC" to "asdfghjkl", "AB" to "zxcvbnm")
        buildMap {
            for (line in pairs) {
                val (pos, base, shift) = line.split(' ')
                put(DesktopKeyPos.valueOf(pos), listOf(base, shift))
            }
            for ((row, chars) in letters) {
                chars.forEachIndexed { i, c ->
                    put(keyPos(row, i + 1), listOf("$c", "${c.uppercaseChar()}"))
                }
            }
        }
    }

    /**
     * Files whose variants set up modifiers or add a key or two outside the
     * grid. They carry nothing a grid would show, so an include of one is
     * neither followed nor reported.
     */
    private val IgnoredIncludes = setOf(
        "level3", "level5", "pc", "keypad", "kpdl", "nbsp", "compose", "eurosign", "rupeesign",
        "capslock", "ctrl", "altwin", "shift", "lv3", "lv5", "group", "inet", "srvr_ctrl",
        "terminate", "mod_led", "grp_led", "typo", "currencysign", "parens", "japan", "sun_vndr",
    )

    /** dead_ keysyms and the combining mark each one puts on. */
    private val DeadMarks: Map<String, Char> = mapOf(
        "grave" to '̀', "acute" to '́', "circumflex" to '̂', "tilde" to '̃',
        "perispomeni" to '͂', "macron" to '̄', "breve" to '̆', "abovedot" to '̇',
        "diaeresis" to '̈', "abovering" to '̊', "doubleacute" to '̋', "caron" to '̌',
        "cedilla" to '̧', "ogonek" to '̨', "iota" to 'ͅ', "belowdot" to '̣',
        "hook" to '̉', "horn" to '̛', "abovecomma" to '̓', "psili" to '̓',
        "abovereversedcomma" to '̔', "dasia" to '̔', "doublegrave" to '̏',
        "belowring" to '̥', "belowmacron" to '̱', "belowcircumflex" to '̭',
        "belowtilde" to '̰', "belowbreve" to '̮', "belowdiaeresis" to '̤',
        "invertedbreve" to '̑', "belowcomma" to '̦', "lowline" to '̲',
        "aboveverticalline" to '̍', "belowverticalline" to '̩',
    )

    /**
     * Keysyms for keys that type nothing a grid shows: modifiers, editing and
     * function keys. Left out without a note, unlike a name that is not known.
     */
    private val FunctionKeysym = Regex(
        "(?:.*_[LR]|ISO_.*|Mode_switch|Multi_key|Caps_Lock|Num_Lock|Scroll_Lock|Shift_Lock|BackSpace|Tab|Return|" +
            "Escape|Delete|Insert|Home|End|Prior|Next|Left|Right|Up|Down|Print|Menu|KP_.*|F\\d+|XF86.*|Pointer_.*|" +
            "Hyper_.*|Super_.*|Meta_.*|Alt_.*|Control_.*|Linefeed|Clear|Pause|Sys_Req|Break|Cancel|Execute|Undo|" +
            "Redo|Find|Help|Select)",
    )

    private val BlockHeader = Regex("""xkb_symbols\s+"[^"]*"\s*\{""")

    private val MergeWords = setOf("include", "augment", "override", "replace")
    private const val WordChars = "_-."
    private const val MULTI_SEPARATOR = '\u0000'
    private const val DEAD_PREFIX = "dead_"
    private const val NO_SYMBOL = "NoSymbol"
    private const val VOID_SYMBOL = "VoidSymbol"
    private const val HEX = 16
    private val UNICODE_NAME = 5..7
    private const val UNICODE_KEYSYM_BASE = 0x1000000L
    private val LATIN1_PRINTABLE_LOW = 0x20L..0x7EL
    private val LATIN1_PRINTABLE_HIGH = 0xA0L..0xFFL
    private const val KEYSYM_CAPACITY = 2048

    /** Fewer of the 26 letter keys than this and a variant is a fragment, not a layout. */
    private const val MIN_LETTER_KEYS = 13

    private val OpenBrace = Tok.Punct('{')
    private val CloseBrace = Tok.Punct('}')
    private val OpenBracket = Tok.Punct('[')
    private val CloseBracket = Tok.Punct(']')
    private val Semicolon = Tok.Punct(';')
    private val Comma = Tok.Punct(',')
    private val Equals = Tok.Punct('=')
}
