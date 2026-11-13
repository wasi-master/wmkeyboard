package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import java.text.Normalizer

/**
 * Reading a layout made for a computer keyboard: a Windows `.klc` from the
 * Microsoft Keyboard Layout Creator, a macOS `.keylayout` made in Ukelele, or
 * an XKB `symbols` file from Linux.
 *
 * Many minority-language layouts exist only in these forms, written by one
 * person for their own language and posted on a university page or a forum
 * long before phones had keyboards worth the name. The files are read by the
 * parsers in `DesktopKeymaps.kt`; this turns what they read into a grid.
 *
 * ### A desktop keyboard is not a phone keyboard
 *
 * A desktop layout has 47 character keys and four levels on each (plain,
 * shift, AltGr, shift with AltGr). A phone grid has room for about 30 keys
 * and two levels. So:
 *
 * - **The 26 letter keys are kept**, in their places, whatever they type.
 *   Around them, the keys that type a letter are kept too, at the end of their
 *   row (Lithuanian ą on the number row, Danish æ ø å beside L and P); the
 *   ones that type only punctuation are left to this keyboard's symbols pages.
 * - **The number row** becomes a row of the grid only when it has five or more
 *   letter keys, the way the Czech, Slovak and Lithuanian layouts are written.
 *   With fewer, each of its letters goes on press and hold of the letter it is
 *   built on (é on e, as AZERTY users already reach it on a phone), or onto the
 *   end of the shortest row when it is built on nothing (German ß).
 * - **AltGr** goes on press and hold of the same key, first, so the corner
 *   hint shows what the key's AltGr level was engraved with.
 * - **Dead keys** become press and hold too: every letter on the grid gets the
 *   accented letters the file's dead keys make of it. A dead key on a letter
 *   key keeps its place and becomes this keyboard's own kind of dead key, a
 *   combining mark, when it amounts to one.
 * - **Characters on keys that were left out**, other than ASCII and digits,
 *   go on press and hold of the full stop, so a script's own punctuation
 *   (Armenian ՝, Ethiopic ።) is never lost.
 *
 * Shift, delete and the bottom row are this app's, as for every foreign
 * layout that does not carry its own frame (see [withHouseStructure]).
 */
object DesktopLayouts {

    /** MSKLC's source file extension. Claimed so a shared file opens from a file manager. */
    const val KLC_EXTENSION = "klc"

    /** Apple's keyboard layout extension, claimed for the same reason. */
    const val KEYLAYOUT_EXTENSION = "keylayout"

    /** Far above a real layout: an XKB file holding twenty variants is 60 KB. */
    private const val MAX_LENGTH = 2_000_000

    /**
     * [bytes] as text. A `.klc` is saved as UTF-16 by the program that makes
     * them, and read as UTF-8 it is unrecognisable, so every reader of these
     * files goes through here.
     */
    fun decodeText(bytes: ByteArray): String = decodeDesktopText(bytes)

    /** True when [text] is shaped like one of the three. Cheap, and parses nothing. */
    fun looksLikeDesktopLayout(text: String): Boolean =
        text.length <= MAX_LENGTH && (
            KlcReader.looksLikeKlc(text) ||
                MacKeylayoutReader.looksLikeKeylayout(text) ||
                XkbSymbolsReader.looksLikeXkb(text)
            )

    /**
     * Every layout in [text]: one for a `.klc` or a `.keylayout`, one per
     * variant for an XKB file, the default variant first. [name] is the file
     * name; XKB uses it to tell its own includes from other files'. Empty when
     * there are none. Never throws.
     */
    fun convertAll(text: String, name: String): List<ConvertedLayout> = runCatching {
        if (text.length > MAX_LENGTH) return emptyList()
        val maps = when {
            KlcReader.looksLikeKlc(text) -> listOfNotNull(KlcReader.read(text))
            MacKeylayoutReader.looksLikeKeylayout(text) -> listOfNotNull(MacKeylayoutReader.read(text))
            XkbSymbolsReader.looksLikeXkb(text) -> XkbSymbolsReader.read(text, name)
            else -> emptyList()
        }
        maps.mapNotNull { convert(it, name) }
    }.getOrDefault(emptyList())

    // ---- one keymap to one grid ----

    /** One desktop key, its four levels as text, as far as the grid needs them. */
    private class Slot(
        val pos: DesktopKeyPos,
        val base: String?,
        val shift: String?,
        val altGr: String?,
        val shiftAltGr: String?,
        /** What the key types plain when that is a dead key: its mark, or its own character. */
        val deadOutput: String?,
    ) {
        /** Whether the key types a letter plain or shifted. A dead key does not count. */
        val holdsLetter: Boolean = listOfNotNull(base.takeIf { deadOutput == null }, shift).any(::hasLetter)
        val texts: List<String> get() = listOfNotNull(base.takeIf { deadOutput == null }, shift, altGr, shiftAltGr)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    internal fun convert(map: DesktopKeymap, fileName: String): ConvertedLayout? {
        val report = Report()
        val deads = LinkedHashSet<DesktopOutput.Dead>()

        fun textOf(output: DesktopOutput?): String? = when (output) {
            is DesktopOutput.Text -> normalizeForScript(output.text).also { if (it != output.text) report.normalized++ }
            is DesktopOutput.Dead -> {
                deads += output
                null
            }
            null -> null
        }

        val slots = DesktopKeyPos.entries.mapNotNull { pos ->
            val levels = map.keys[pos] ?: return@mapNotNull null
            val dead = levels.getOrNull(LEVEL_BASE) as? DesktopOutput.Dead
            val deadOutput = dead?.let { it.mark?.toString() ?: it.standalone }
            Slot(
                pos = pos,
                base = textOf(levels.getOrNull(LEVEL_BASE)) ?: deadOutput,
                shift = textOf(levels.getOrNull(LEVEL_SHIFT)),
                altGr = textOf(levels.getOrNull(LEVEL_ALTGR)),
                shiftAltGr = textOf(levels.getOrNull(LEVEL_SHIFT_ALTGR)),
                deadOutput = deadOutput,
            )
        }

        val rows = List(ROWS) { mutableListOf<Key>() }
        val keepNumberRow = slots.count { it.pos.row == NUMBER_ROW && it.holdsLetter } >= MIN_NUMBER_ROW_LETTERS
        val strays = mutableListOf<Slot>()
        val leftover = mutableListOf<String>()
        for (slot in slots) {
            val placed = when {
                slot.pos.row == NUMBER_ROW -> keepNumberRow && slot.holdsLetter
                slot.pos.isLetterKey -> slot.base != null || slot.shift != null
                else -> slot.holdsLetter
            }
            when {
                placed -> keyOf(slot, report)?.let { rows[slot.pos.row] += it }
                slot.holdsLetter -> strays += slot
                else -> leftover += slot.texts
            }
        }
        if (rows.drop(1).all { it.isEmpty() }) return null

        // Letters with no key of their own: on the letter they are built on,
        // or a key at the end of the shortest row.
        val collected = LinkedHashSet<String>()
        for (slot in strays) {
            val letters = listOfNotNull(slot.base, slot.shift?.takeIf { it != slot.base?.uppercase() }).filter(::hasLetter)
            val homes = letters.map { findHome(rows, it) }
            if (homes.all { it != null }) {
                letters.zip(homes).forEach { (letter, home) -> addAlternates(rows, home!!, listOf(letter)) }
                slot.altGr?.let(leftover::add)
                slot.shiftAltGr?.let(leftover::add)
            } else {
                keyOf(slot, report)?.let { key -> rows.drop(1).filter { it.isNotEmpty() }.minByOrNull { it.size }?.add(key) }
            }
        }
        for (text in leftover.distinct()) {
            when {
                hasLetter(text) -> findHome(rows, text)?.let { addAlternates(rows, it, listOf(text)) } ?: collected.add(text)
                text.all { it.code < ASCII_END } -> Unit
                text.all { Character.isDigit(it) } -> Unit
                else -> collected += text
            }
        }

        // Dead keys, spread onto the letters they make something of.
        for (dead in deads) {
            var used = false
            for (r in rows.indices) {
                for (k in rows[r].indices) {
                    val key = rows[r][k]
                    if (key.action != KeyAction.Text) continue
                    val base = key.output ?: key.label
                    val composed = dead.compose(base)?.let(::normalizeForScript) ?: continue
                    if (composed == base || composed in key.longPress) continue
                    addAlternates(rows, r to k, listOf(composed))
                    used = true
                }
            }
            if (used) report.deadKeysSpread++
        }

        // The characters nothing else took, on the full stop: the grid's own
        // when it has one, else one in a bottom row of the file's, as far as
        // this keyboard reads one (a spacebar and a full stop). The house
        // frame fills in the rest of that row round them.
        var bottom: List<Key>? = null
        if (collected.isNotEmpty()) {
            report.symbolsOnPeriod = collected.size
            val period = findKey(rows) { (it.output ?: it.label) == "." }
            if (period != null) {
                addAlternates(rows, period, collected.toList())
            } else {
                bottom = listOf(
                    Key(" ", action = KeyAction.Space, width = SPACE_WIDTH),
                    Key(".", role = KeyRole.Period, longPress = (PeriodAlternates + collected).distinct()),
                )
            }
        }
        val grid = rows.filter { it.isNotEmpty() }.map { it.toList() } + listOfNotNull(bottom)
        map.unknownSymbols.forEach { report.unknownSymbols += it }
        map.missingIncludes.forEach { report.missingIncludes += it }

        val source = when (map.format) {
            DesktopFormat.KLC -> ForeignSource.WINDOWS_KLC
            DesktopFormat.MAC_KEYLAYOUT -> ForeignSource.MAC_KEYLAYOUT
            DesktopFormat.XKB -> ForeignSource.XKB_SYMBOLS
            DesktopFormat.KEYBOARD3 -> ForeignSource.KEYBOARD3
        }
        val converted = ForeignLayouts.assemble(
            rows = grid,
            name = fileName,
            source = source,
            report = report,
            langId = declaredLanguage(map.languageTag),
        ) ?: return null
        val title = map.name?.trim()?.ifEmpty { null } ?: return converted
        return converted.copy(layout = converted.layout.copy(name = title))
    }

    /**
     * A grid key from [slot]: the plain level drawn and typed, shift as the
     * shift label when it is more than the capital, AltGr on press and hold.
     */
    private fun keyOf(slot: Slot, report: Report): Key? {
        val label = slot.base ?: slot.shift ?: return null
        val shiftLabel = slot.shift?.takeIf { slot.base != null && it != label && it != label.uppercase() }
        val alternates = listOfNotNull(slot.altGr, slot.shiftAltGr?.takeIf { it != slot.altGr?.uppercase() })
            .filter { it != label && it != shiftLabel && it != slot.shift }
            .distinct()
        if (alternates.isNotEmpty()) report.altGrOnHold = true
        val dead = slot.deadOutput
        if (dead != null && label == dead) {
            // A dead key on a letter key. A combining mark is this keyboard's
            // own dead key; it is drawn as its spacing accent.
            val mark = dead.singleOrNull()?.takeIf { it.code in COMBINING_MARKS }
            return Key(
                label = mark?.let { SpacingAccents[it] ?: "◌$it" } ?: dead,
                output = mark?.toString(),
                shiftLabel = shiftLabel,
                longPress = alternates,
            )
        }
        return Key(label = label, shiftLabel = shiftLabel, longPress = alternates)
    }

    /**
     * The grid key [letter] is built on: é goes home to e. Null when the
     * letter is not built on another (ß, æ) or that letter has no key.
     */
    private fun findHome(rows: List<List<Key>>, letter: String): Pair<Int, Int>? {
        val decomposed = Normalizer.normalize(letter, Normalizer.Form.NFD)
        val base = decomposed.takeIf { it.length > 1 && it != letter }?.let { String(Character.toChars(it.codePointAt(0))) }
            ?: return null
        return findKey(rows) { key ->
            key.action == KeyAction.Text && (key.output ?: key.label).equals(base, ignoreCase = true)
        }
    }

    private fun findKey(rows: List<List<Key>>, predicate: (Key) -> Boolean): Pair<Int, Int>? {
        for (r in rows.indices) {
            val k = rows[r].indexOfFirst(predicate)
            if (k >= 0) return r to k
        }
        return null
    }

    private fun addAlternates(rows: List<MutableList<Key>>, at: Pair<Int, Int>, more: List<String>) {
        val (r, k) = at
        val key = rows[r][k]
        val own = key.output ?: key.label
        rows[r][k] = key.copy(longPress = (key.longPress + more).filter { it != own }.distinct())
    }

    /**
     * The language a `.klc` or a keyboard3 file states, when this app has it. `lt-LT` is tried
     * whole, then as `lt-LT` with its region, then `lt`.
     */
    internal fun declaredLanguage(tag: String?): String? {
        val clean = tag?.trim()?.replace('_', '-')?.ifEmpty { null } ?: return null
        val parts = clean.split('-')
        val lang = parts.first()
        val script = parts.drop(1).firstOrNull { it.length == SCRIPT_SUBTAG }
        val region = parts.drop(1).firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
        val candidates = listOfNotNull(clean, region?.let { "$lang-$it" }, script?.let { "$lang-$it" }, lang)
        fun norm(id: String) = id.lowercase().replace('_', '-')
        return candidates.firstNotNullOfOrNull { candidate ->
            LanguageRegistry.all.firstOrNull { norm(it.id) == norm(candidate) }?.id
        }
    }

    private fun hasLetter(text: String): Boolean = text.any { c ->
        Character.isLetter(c) || Character.getType(c).toByte() in MarkTypes
    }

    private val MarkTypes = setOf(
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
    )

    /** The spacing forms of the common marks, to draw a dead key with. */
    private val SpacingAccents = mapOf(
        '̀' to "`", '́' to "´", '̂' to "^", '̃' to "~", '̄' to "¯",
        '̆' to "˘", '̇' to "˙", '̈' to "¨", '̊' to "˚", '̋' to "˝",
        '̌' to "ˇ", '̧' to "¸", '̨' to "˛",
    )

    private val COMBINING_MARKS = 0x0300..0x036F
    private const val ROWS = 4
    private const val NUMBER_ROW = 0
    private const val MIN_NUMBER_ROW_LETTERS = 5
    private const val ASCII_END = 0x80
    private const val SPACE_WIDTH = 4f
    private const val SCRIPT_SUBTAG = 4
}
