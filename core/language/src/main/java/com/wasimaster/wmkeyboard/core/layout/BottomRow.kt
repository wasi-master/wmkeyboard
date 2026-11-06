package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlin.math.abs

/**
 * What the Bottom row settings do to a grid, as one value the keyboard and the
 * layout editor both hand to [arrangedBy] (issue #420).
 *
 * The settings act on every layout at draw time, which is what they are for: a
 * layout imported from Keyman gets its 🌐 key where every other layout has it,
 * and the emoji key takes the 🌐 key's seat on all of them at once. The editor
 * used to show the grid as authored and nothing else, so its bottom row and the
 * keyboard's disagreed, and a key moved there went straight back to where the
 * settings put it. Both draw from here now, so they cannot disagree again.
 *
 * Every flag is the caller's whole answer, exceptions included: the keyboard
 * leaves the 🌐 key on a secondary layout and on an expanded tablet grid, and
 * only the letters layer's comma turns into the emoji key. Those are facts about
 * where the grid is drawn, which only the caller knows.
 */
data class BottomRowRules(
    /** Take the 🌐 key off the bottom row and give its width to the spacebar (#139). */
    val hideGlobe: Boolean = false,
    /** Put the 🌐 key in the slot the built-in bottom row gives it (#310). */
    val globeInOnePlace: Boolean = false,
    /** Trade the comma and 🌐 keys' places, so the comma sits by the spacebar. */
    val swapCommaAndGlobe: Boolean = false,
    /** The 🌐 key opens the emoji panel instead, or [globeTool]. */
    val globeAsEmoji: Boolean = false,
    /** The tool [globeAsEmoji] puts in the 🌐 key's seat in place of the emoji panel (#1126); null for emoji. */
    val globeTool: ToolbarTool? = null,
    /** The comma key opens the emoji panel, with the comma on its hold. */
    val commaAsEmoji: Boolean = false,
)

/** One of [BottomRowRules], named, for saying which of them changed a grid. */
enum class BottomRowRule {
    HIDE_GLOBE,
    GLOBE_IN_ONE_PLACE,
    SWAP_COMMA_AND_GLOBE,
    GLOBE_AS_EMOJI,
    COMMA_AS_EMOJI,
}

/**
 * A grid as the Bottom row settings draw it, beside the grid as it was laid out,
 * and which drawn key came from which authored one.
 *
 * The editor needs the second half: it draws [layout], but every edit is written
 * to the authored grid, so a tap on a drawn key has to find the key it stands for.
 */
class ArrangedGrid internal constructor(
    /** What the keyboard draws. The same object as [source] when nothing changed. */
    val layout: KeyboardLayout,
    /** The grid as laid out. */
    val source: KeyboardLayout,
    private val columns: List<List<Int>>,
    /** The rules that changed something here, and only those. */
    val applied: Set<BottomRowRule>,
) {
    /** The column in [source] the key drawn at [row], [col] came from. */
    fun sourceColumn(row: Int, col: Int): Int? = columns.getOrNull(row)?.getOrNull(col)

    /** Whether [row] is drawn in any way other than as it is laid out. */
    fun rowChanged(row: Int): Boolean = layout.rows.getOrNull(row) != source.rows.getOrNull(row)

    /**
     * Whether the key drawn at [row], [col] is anything but its authored self in
     * its authored order: a different width or action, or a key that has changed
     * sides with another one. A key that only shifted along because the 🌐 key
     * before it was hidden is still itself.
     */
    fun rewritten(row: Int, col: Int): Boolean {
        val from = sourceColumn(row, col) ?: return false
        if (layout.rows[row][col] != source.rows[row][from]) return true
        return columns[row].withIndex().any { (i, c) -> (i < col && c > from) || (i > col && c < from) }
    }
}

/** A key, and the column it has in the grid as laid out. */
private data class Seat(val key: Key, val from: Int)

/**
 * This grid as the Bottom row settings in [rules] draw it.
 *
 * The order is load-bearing. The 🌐 key is hidden before anything else reads the
 * row, because both the placement and the swap find it by where it is. The
 * placement comes next and, under the swap, already leaves the comma after the
 * key, so the swap is not run on a row it has placed; swapping again would undo
 * it. The emoji keys come last, because they are found by what the keys were.
 *
 * A grid with [KeyboardLayout.bottomRowAsLaidOut] set comes back untouched.
 */
fun KeyboardLayout.arrangedBy(rules: BottomRowRules): ArrangedGrid {
    val untouched = ArrangedGrid(this, this, rows.map { it.indices.toList() }, emptySet())
    if (bottomRowAsLaidOut || rows.isEmpty()) return untouched
    val applied = mutableSetOf<BottomRowRule>()
    var seats = rows.map { row -> row.mapIndexed { i, key -> Seat(key, i) } }
    val lastRow = seats.lastIndex

    if (rules.hideGlobe) {
        seatsWithoutGlobe(seats.last())?.let {
            seats = seats.dropLast(1) + listOf(it)
            applied += BottomRowRule.HIDE_GLOBE
        }
    }

    val placed = if (rules.globeInOnePlace) seatsWithGlobeInPlace(seats, rules.swapCommaAndGlobe) else null
    if (placed != null && placed != seats.last()) {
        val before = seats.last()
        seats = seats.dropLast(1) + listOf(placed)
        // A row that only needed the swap reads as the swap's doing, which is
        // the setting that would put it back. The built-in rows are all this.
        val swappedOnly = rules.swapCommaAndGlobe &&
            seatsSwapped(before, lastRow, lastRow)?.let { sameSeats(placed, it) } == true
        if (!swappedOnly) applied += BottomRowRule.GLOBE_IN_ONE_PLACE
        if (rules.swapCommaAndGlobe && commaFollowsGlobe(placed, lastRow)) {
            applied += BottomRowRule.SWAP_COMMA_AND_GLOBE
        }
    }

    // Not scoped to the bottom row: the row would otherwise reshuffle on the
    // way into ?123, which is worse than either order.
    if (rules.swapCommaAndGlobe) {
        seats = seats.mapIndexed { r, row ->
            if (r == lastRow && placed != null) {
                row
            } else {
                seatsSwapped(row, r, lastRow)?.also { applied += BottomRowRule.SWAP_COMMA_AND_GLOBE } ?: row
            }
        }
    }

    if (rules.globeAsEmoji || rules.commaAsEmoji) {
        seats = seats.mapIndexed { r, row ->
            row.map { seat ->
                val key = seat.key
                when {
                    rules.commaAsEmoji && key.roleIn(r, lastRow) == KeyRole.Comma -> {
                        applied += BottomRowRule.COMMA_AS_EMOJI
                        seat.copy(key = key.asEmojiKey())
                    }
                    rules.globeAsEmoji && key.action == KeyAction.LanguageSwitch -> {
                        applied += BottomRowRule.GLOBE_AS_EMOJI
                        seat.copy(key = key.asGlobeReplacement(rules.globeTool))
                    }
                    else -> seat
                }
            }
        }
    }

    if (applied.isEmpty()) return untouched
    return ArrangedGrid(
        layout = copy(rows = seats.map { row -> row.map { it.key } }),
        source = this,
        columns = seats.map { row -> row.map { it.from } },
        applied = applied,
    )
}

/**
 * The 🌐 key as the key the user put in its seat (#1126): the emoji key, or
 * [tool]'s key, which draws the tool's icon, so the 🌐 label goes. Kept a copy
 * for the reason [asEmojiKey] is.
 */
fun Key.asGlobeReplacement(tool: ToolbarTool?): Key =
    if (tool == null || tool == ToolbarTool.EMOJI) {
        asEmojiKey()
    } else {
        copy(action = KeyAction.Tool(tool), label = "", shiftLabel = null)
    }

/**
 * This key as the emoji key: the 🌐 key simply opens the panel instead, and a
 * comma keeps its character at the front of its hold.
 *
 * A copy rather than a fresh key, so a width, a span or a label size the layout
 * gave the slot survives; building one from scratch made the bottom row jump
 * whenever the preference was on.
 */
fun Key.asEmojiKey(): Key = if (action == KeyAction.LanguageSwitch) {
    copy(action = KeyAction.Emoji)
} else {
    copy(action = KeyAction.Emoji, longPress = listOf(output ?: label) + longPress)
}

/**
 * This grid with the 🌐 key taken off its bottom row, and the width the key
 * used given to the spacebar (issue #139).
 *
 * The spacebar gets all of it rather than every key a share: a bigger spacebar
 * is what the setting is for, and an even share would also widen `?123` and
 * enter. A row with no spacebar shares the width out instead, so it still fills
 * the board rather than leaving a gap at one end. Either way the row keeps its
 * total, which is what leaves a key spanning down into it where it was.
 *
 * Only the bottom row, because that is the key the setting names; a 🌐 key an
 * author placed anywhere else was placed on purpose. A row of nothing but 🌐
 * keys is left alone, since removing them would leave a row with no keys.
 */
fun KeyboardLayout.withoutGlobeKey(): KeyboardLayout {
    val bottom = rows.lastOrNull() ?: return this
    val row = seatsWithoutGlobe(bottom.mapIndexed { i, key -> Seat(key, i) }) ?: return this
    return copy(rows = rows.dropLast(1) + listOf(row.map { it.key }))
}

private fun seatsWithoutGlobe(bottom: List<Seat>): List<Seat>? {
    val globes = bottom.count { it.key.action == KeyAction.LanguageSwitch }
    if (globes == 0 || globes == bottom.size) return null
    val freed = bottom.filter { it.key.action == KeyAction.LanguageSwitch }
        .sumOf { it.key.width.toDouble() }.toFloat()
    val kept = bottom.filterNot { it.key.action == KeyAction.LanguageSwitch }
    val space = kept.indexOfFirst { it.key.action == KeyAction.Space }
    return if (space >= 0) {
        kept.mapIndexed { index, seat ->
            if (index == space) seat.withWidth(seat.key.width + freed) else seat
        }
    } else {
        val total = kept.sumOf { it.key.width.toDouble() }.toFloat()
        kept.map { it.withWidth(it.key.width * (total + freed) / total) }
    }
}

/**
 * This grid with its 🌐 key in the slot the built-in bottom row gives it
 * (#310), or null when the bottom row cannot take that without damage — in
 * which case it is drawn as its author made it.
 *
 * The slot is a share of the row, not a count of keys, because rows differ in
 * both: `?123 , 🌐 ␣ . ⏎` puts the key's left edge at 25% of the row and makes
 * it 10% wide. With [outer] (the comma swap, on by default) the key and the
 * comma trade, so the key starts at 15% and the comma follows it. Everything
 * before the spacebar other than those two is scaled to fill what is left of
 * the slot's start; the spacebar gives or takes whatever the row's width needs,
 * and the keys after it keep their widths. The row keeps its total and the
 * order of every other key.
 *
 * A row with nothing before the 🌐 key widens the key to fill the space up to
 * the slot, so a thumb aimed where the key is on every other layout lands on
 * it here too, rather than on the spacebar.
 *
 * Returns null, leaving the grid alone, when:
 * - the bottom row has no spacebar, or more or fewer than one 🌐 key;
 * - any key spans rows, since moving keys would slide them under it;
 * - the keys before the slot would shrink below half or grow past two and a
 *   half times their width (a row with a long run of keys by the spacebar);
 * - the spacebar would end up under a fifth of the row.
 *
 * A row already in place comes back as the same grid. The built-in row is
 * already in place, and under [outer] it comes out exactly as the comma swap
 * would have left it, so the built-in layouts look as they always did. Null
 * is only for the rows it will not touch, which the caller then swaps the
 * old way.
 */
fun KeyboardLayout.withGlobeInPlace(outer: Boolean): KeyboardLayout? {
    val seats = rows.map { row -> row.mapIndexed { i, key -> Seat(key, i) } }
    val row = seatsWithGlobeInPlace(seats, outer) ?: return null
    return if (row == seats.last()) this else copy(rows = rows.dropLast(1) + listOf(row.map { it.key }))
}

private fun seatsWithGlobeInPlace(rows: List<List<Seat>>, outer: Boolean): List<Seat>? {
    val bottom = rows.lastOrNull() ?: return null
    if (rows.any { row -> row.any { it.key.rowSpan > 1 } }) return null
    if (bottom.count { it.key.action == KeyAction.LanguageSwitch } != 1) return null
    val globe = bottom.first { it.key.action == KeyAction.LanguageSwitch }
    val others = bottom.filterNot { it.key.action == KeyAction.LanguageSwitch }
    val space = others.indexOfFirst { it.key.action == KeyAction.Space }
    if (space < 0) return null
    val lastRow = rows.lastIndex
    val before = others.subList(0, space)
    val after = others.subList(space + 1, others.size)
    // Under the swap the comma is the key that follows the globe, so it leaves
    // the run before the slot. The last one, the one nearest the spacebar.
    val commaIndex = if (outer) {
        before.indexOfLast { it.key.roleIn(lastRow, lastRow) == KeyRole.Comma }
    } else {
        -1
    }
    val comma = before.getOrNull(commaIndex)
    val lead = if (comma == null) before else before.filterIndexed { i, _ -> i != commaIndex }

    val total = bottom.sumOf { it.key.width.toDouble() }.toFloat()
    val slotStart = total * (if (outer) GLOBE_SLOT_OUTER else GLOBE_SLOT_INNER)
    val slotWidth = total * GLOBE_SLOT_WIDTH
    val leadWidth = lead.sumOf { it.key.width.toDouble() }.toFloat()
    val leadScale = if (lead.isEmpty()) 1f else slotStart / leadWidth
    if (leadScale < GLOBE_LEAD_MIN_SCALE || leadScale > GLOBE_LEAD_MAX_SCALE) return null
    val globeWidth = if (lead.isEmpty()) slotStart + slotWidth else slotWidth
    val commaWidth = if (comma == null) 0f else slotWidth
    val afterWidth = after.sumOf { it.key.width.toDouble() }.toFloat()
    val spaceWidth = total - slotStart - slotWidth - commaWidth - afterWidth
    if (spaceWidth < total * GLOBE_MIN_SPACE_SHARE) return null

    // Each width snapped back to the key's own where they differ by float noise
    // alone, so a row already in place keeps 1.5 rather than 1.5000001, and a
    // row the editor writes down as drawn does not fill its file with noise.
    val row = buildList {
        lead.forEach { add(it.withSnappedWidth(it.key.width * leadScale)) }
        add(globe.withSnappedWidth(globeWidth))
        comma?.let { add(it.withSnappedWidth(commaWidth)) }
        add(others[space].withSnappedWidth(spaceWidth))
        addAll(after)
    }
    // Already there, give or take float rounding: hand back the row itself, so
    // an unchanged layout stays the same object and nothing downstream redraws.
    return if (sameSeats(row, bottom)) bottom else row
}

/** Whether two rows hold the same keys in the same order, widths give or take float noise. */
private fun sameSeats(a: List<Seat>, b: List<Seat>): Boolean =
    a.size == b.size && a.indices.all { i ->
        a[i].from == b[i].from &&
            a[i].key.copy(width = b[i].key.width) == b[i].key &&
            abs(a[i].key.width - b[i].key.width) < GLOBE_WIDTH_EPSILON
    }

/**
 * Trades the comma key and the 🌐 key's places in one row, leaving every other
 * key where it was. Null for a row that hasn't got both.
 */
private fun seatsSwapped(row: List<Seat>, rowIndex: Int, lastRow: Int): List<Seat>? {
    val comma = row.indexOfFirst { it.key.roleIn(rowIndex, lastRow) == KeyRole.Comma }
    val globe = row.indexOfFirst { it.key.action == KeyAction.LanguageSwitch }
    if (comma < 0 || globe < 0) return null
    return row.toMutableList().also {
        val held = it[comma]
        it[comma] = it[globe]
        it[globe] = held
    }
}

/** Whether a comma sits right after the 🌐 key in [row], as the swap leaves it. */
private fun commaFollowsGlobe(row: List<Seat>, lastRow: Int): Boolean {
    val globe = row.indexOfFirst { it.key.action == KeyAction.LanguageSwitch }
    return globe >= 0 && row.getOrNull(globe + 1)?.key?.roleIn(lastRow, lastRow) == KeyRole.Comma
}

private fun Seat.withWidth(width: Float): Seat = copy(key = key.copy(width = width))

private fun Seat.withSnappedWidth(width: Float): Seat =
    if (abs(width - key.width) < GLOBE_WIDTH_EPSILON) this else withWidth(width)

/** Where the 🌐 key starts in the built-in row, as a share of it: after `?123 ,`. */
private const val GLOBE_SLOT_INNER = 0.25f

/** The same under the comma swap: after `?123`, with the comma after the key. */
private const val GLOBE_SLOT_OUTER = 0.15f

/** The 🌐 key's width in the built-in row, as a share of it. */
private const val GLOBE_SLOT_WIDTH = 0.10f

/** The keys before the slot may not be squeezed below half their width… */
private const val GLOBE_LEAD_MIN_SCALE = 0.5f

/** …or stretched past two and a half times it (a lone `?123` at 1.0 just fits). */
private const val GLOBE_LEAD_MAX_SCALE = 2.5f

/** The spacebar keeps at least this share of the row, or the row is left alone. */
private const val GLOBE_MIN_SPACE_SHARE = 0.2f

/** Width differences below this are float noise, not a move. */
private const val GLOBE_WIDTH_EPSILON = 0.001f
