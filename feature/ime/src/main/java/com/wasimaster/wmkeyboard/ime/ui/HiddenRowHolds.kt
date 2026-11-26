package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.leadsWithDigitRow
import com.wasimaster.wmkeyboard.ime.LayoutMode

/**
 * The rows the grid draws for a symbols page [mode] whose own rows are [rows].
 *
 * With [symbolsGiveUpDigits] (the number row is on and already shows the
 * digits), the `?123` page's digit row makes way for [fillRow]. Only when that
 * first row really is the digits: a custom symbols layer that leads with
 * something else would otherwise lose its top row outright, with nothing on
 * screen to explain where it went.
 *
 * Without it, the digits hold the fill row's characters instead, first; and
 * `=\<` without the number row over it ([numberRowShown] false) holds
 * [symbols2Row] on its top row, after the holds it had ([withHiddenRowHolds]).
 *
 * Out of the composable that draws the grid for ART's sake, like the extra row.
 */
internal fun symbolBodyRows(
    rows: List<List<Key>>,
    mode: LayoutMode,
    symbolsGiveUpDigits: Boolean,
    numberRowShown: Boolean,
    fillRow: List<Key>,
    symbols2Row: List<Key>,
): List<List<Key>> {
    if (rows.isEmpty()) return rows
    val digitsFirst = mode == LayoutMode.SYMBOLS && leadsWithDigitRow(rows)
    return when {
        digitsFirst && symbolsGiveUpDigits -> listOf(fillRow) + rows.drop(1)
        digitsFirst -> listOf(withHiddenRowHolds(rows.first(), fillRow, lead = true)) + rows.drop(1)
        mode == LayoutMode.SYMBOLS_SHIFTED && !numberRowShown ->
            listOf(withHiddenRowHolds(rows.first(), symbols2Row, lead = false)) + rows.drop(1)
        else -> rows
    }
}

/**
 * [row] with the characters of [hidden] added to its holds, column by column:
 * the row the number row would have put over (or in place of) it, when that
 * row is not shown (#1560).
 *
 * With the number row on, the `?123` page trades its digit row for the fill
 * row (`= \ < > [ ] { } | ~`) and the `=\<` page gets an arrow and comparison
 * row above it (`← → ↑ ↓ ± ∞ ≈ ≠ ≤ ≥`). With it off, those characters were
 * nowhere on the page they belong to. Each one goes on the key in its own
 * column, followed by that key's own holds (`←` brings `⟵` and `↔`), so the
 * key under the spot it would have occupied is the one to hold.
 *
 * [lead] puts them first, which is the default hold and the corner hint: right
 * for the `?123` digits, whose own first holds (`! @ # …`) are keys on the same
 * page. Otherwise they go after the key's own holds, so a key that already had
 * a hold keeps it as its default. Nothing is removed, and a character the key
 * already types or holds is not repeated. Only text keys take them.
 */
internal fun withHiddenRowHolds(row: List<Key>, hidden: List<Key>, lead: Boolean): List<Key> {
    if (hidden.isEmpty()) return row
    return row.mapIndexed { i, key ->
        val extra = hidden.getOrNull(i) ?: return@mapIndexed key
        if (key.action != KeyAction.Text || extra.action != KeyAction.Text) return@mapIndexed key
        val own = key.output ?: key.label
        val added = (listOf(extra.output ?: extra.label) + extra.longPress)
            .filter { it.isNotEmpty() && it != own && it !in key.longPress }
            .distinct()
        if (added.isEmpty()) {
            key
        } else {
            key.copy(longPress = if (lead) added + key.longPress else key.longPress + added)
        }
    }
}
