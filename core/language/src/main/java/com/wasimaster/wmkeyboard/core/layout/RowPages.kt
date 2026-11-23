package com.wasimaster.wmkeyboard.core.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * What a [RowPage] fills itself with when the keyboard draws it, rather than
 * keys its author wrote (issue #1526's follow-up): the row of emoji a swipe
 * across the top letters brings in, the way some keyboards do.
 */
@Serializable
enum class RowPageFill {
    /** The emoji used most, then the latest, then a starter set. */
    @SerialName("emoji") EMOJI,

    /** One of the symbol row's sets, named by [RowPage.symbolSet]. */
    @SerialName("symbols") SYMBOLS,

    /** 1 to 0. */
    @SerialName("digits") DIGITS,
}

/**
 * One more page of a row: drawn in the row's place after a swipe across it,
 * until the next swipe or until the keyboard leaves the layer.
 *
 * Either [keys] the author wrote, laid out like any row of the grid, or a
 * [fill] the keyboard works out each time it draws the page. A page with a
 * fill ignores its keys.
 */
@Serializable
data class RowPage(
    val keys: List<Key> = emptyList(),
    val fill: RowPageFill? = null,
    /** The symbol set a [RowPageFill.SYMBOLS] page shows, by id; null is the Math set. */
    val symbolSet: String? = null,
)

/**
 * The extra pages of one row of a layer, by the row's place in
 * [LayerSpec.rows], top row 0. A list rather than a map with number keys, so a
 * hand-written file that gets the number wrong loses that one entry rather than
 * the whole layout.
 */
@Serializable
data class RowPages(
    val row: Int,
    val pages: List<RowPage> = emptyList(),
)

/** The most pages one row keeps besides its own; the repair pass trims the rest. */
const val MaxRowPages = 8

/** The most keys a [RowPage.fill] draws, however wide its row. */
const val MaxFilledRowKeys = 12

/**
 * Whether a key taller than one row stands in row [r] of [rows], its own or
 * one reaching down from above. Such a row cannot change pages: its keys share
 * columns with the rows next to it, and a page of other widths would tear the
 * tall key away from them.
 */
fun rowTouchedBySpan(rows: List<List<Key>>, r: Int): Boolean {
    if (r !in rows.indices) return true
    if (rows[r].any { it.rowSpan > 1 }) return true
    for (above in 0 until r) {
        for (key in rows[above]) {
            if (above + key.spanFrom(above, rows.size) - 1 >= r) return true
        }
    }
    return false
}

/**
 * This layer's row pages by row, each row's pages in order, for the rows that
 * can show them: one that exists, with no tall key in it, with at least one
 * page. The first entry for a row wins over a repeat of it.
 */
fun LayerSpec.pagesByRow(): Map<Int, List<RowPage>> {
    val entries = rowPages ?: return emptyMap()
    val out = LinkedHashMap<Int, List<RowPage>>()
    for (entry in entries) {
        if (entry.pages.isEmpty() || entry.row in out) continue
        if (rowTouchedBySpan(rows, entry.row)) continue
        out[entry.row] = entry.pages.take(MaxRowPages)
    }
    return out
}

/** How many pages row [row] has, its own included: 1 for a row with none. */
fun KeyboardLayout.rowPageCount(row: Int): Int = 1 + (rowPages[row]?.size ?: 0)

/**
 * The page a swipe [step]s to from [current], wrapping round: one more swipe
 * the same way past the last page is the row's own keys again.
 */
fun nextRowPage(current: Int, step: Int, count: Int): Int =
    if (count <= 1) 0 else (current + step).mod(count)

/**
 * This grid with each row in [at] drawing that page instead of its own keys;
 * page 0 is the row itself. [fillOf] answers what a filled page shows, as the
 * texts its keys type. The same grid when no row is on another page.
 */
fun KeyboardLayout.withRowPages(at: Map<Int, Int>, fillOf: (RowPage) -> List<String>): KeyboardLayout {
    if (at.isEmpty() || rowPages.isEmpty()) return this
    var changed = false
    val paged = rows.mapIndexed { r, row ->
        val page = at[r] ?: 0
        if (page <= 0) return@mapIndexed row
        val chosen = rowPages[r]?.getOrNull(page - 1) ?: return@mapIndexed row
        if (rowTouchedBySpan(rows, r)) return@mapIndexed row
        val keys = chosen.keysFor(row, fillOf)
        if (keys.isEmpty()) return@mapIndexed row
        changed = true
        keys
    }
    return if (changed) copy(rows = paged) else this
}

/**
 * The keys [this] page draws in place of [row]. Written keys come as they are,
 * one row tall. A fill takes the row's width in as many keys as it is wide,
 * so the page lines up with the rows around it, and stops at the entries it
 * has.
 */
fun RowPage.keysFor(row: List<Key>, fillOf: (RowPage) -> List<String>): List<Key> {
    if (fill == null) return keys.map { if (it.rowSpan > 1) it.copy(rowSpan = 1) else it }
    val width = row.sumOf { it.width.toDouble() }.toFloat().takeIf { it > 0f } ?: DefaultRowWidth
    val count = width.roundToInt().coerceIn(1, MaxFilledRowKeys)
    val texts = fillOf(this).filter { it.isNotEmpty() }.distinct().take(count)
    val each = width / count
    return texts.map { Key(label = it, output = it, width = each) }
}

/** What [RowPageFill.DIGITS] draws. */
val RowPageDigits: List<String> = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

/** What [RowPageFill.EMOJI] draws before any emoji has been typed. */
val RowPageStarterEmoji: List<String> =
    listOf("😀", "😂", "😍", "🥲", "😭", "👍", "🙏", "❤️", "🔥", "✨", "🎉", "😅")

/**
 * [this] layer's row pages following its rows through a reorder, a copy or a
 * delete: the new row `i` was row `sourceIndices[i]`, and takes its pages. A
 * row whose source was not a row (a new one) has none. Null once none are left.
 */
fun List<RowPages>?.reindexed(sourceIndices: List<Int>): List<RowPages>? {
    if (this.isNullOrEmpty()) return this
    val byRow = associateBy { it.row }
    return sourceIndices.mapIndexedNotNull { i, src -> byRow[src]?.copy(row = i) }.takeIf { it.isNotEmpty() }
}

/** [this] layer with row [row]'s pages set to [pages]; an empty list removes them. */
fun LayerSpec.withRowPagesAt(row: Int, pages: List<RowPage>): LayerSpec {
    val others = rowPages.orEmpty().filter { it.row != row }
    val next = if (pages.isEmpty()) others else (others + RowPages(row, pages)).sortedBy { it.row }
    return copy(rowPages = next.takeIf { it.isNotEmpty() })
}

private const val DefaultRowWidth = 10f
