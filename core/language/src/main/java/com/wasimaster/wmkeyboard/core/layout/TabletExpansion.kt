package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.DeviceForm

/**
 * Turns a phone key grid into the wider one a tablet has room for.
 *
 * ## Why a transform rather than tablet layouts
 *
 * The app ships 1,274 layouts — 18 built in, 1,256 as assets, plus whatever the
 * user has authored — and a [LayoutSpec] carries no device-shaped fields at all.
 * Hand-authoring a tablet grid for each was never on the table, and adding a
 * per-layout tablet layer would leave 1,274 of them empty and the feature only
 * working on QWERTY. So this runs at resolve time over the compiled grid, the
 * way `splitKeys` runs over a row, and every eligible layout gets it for free.
 *
 * ## The idea
 *
 * [sidePadFor] already centres a row narrower than the grid by padding it with
 * empty weight on both sides — that is how QWERTY's nine-key home row sits under
 * its ten-key top row. **This turns those spacers into keys.** Widen the grid by
 * one column each side, and the space that appears is exactly where a physical
 * keyboard puts Tab, backslash, caps lock, Enter and the second Shift. Nothing
 * here is hand-tuned to QWERTY; the widths fall out of the arithmetic, and they
 * land on the same 1.0/1.5 conventions the shipped layouts already use.
 *
 * The pleasant side effect is the point of the feature: flanked by utility keys,
 * the alphabet block stops spanning a 12-inch screen edge to edge and sits in the
 * middle, where two hands reach it.
 *
 * ## The two rules that are load-bearing
 *
 * **The row count never changes.** [LayoutSet.rowSpan] and `keyRowsHeight` are
 * computed from layer row counts, and the IME window is sized from them. A
 * transform that added a row would draw a keyboard taller than the window
 * measured. Every rule below moves keys between existing rows or widens them.
 *
 * **Backspace can never go missing.** Delete only relocates to the number row
 * when that row is actually being drawn — see `numberRowShown` on the caller's
 * side, and the `tail` rule in [expandRows]. Losing backspace is the one outcome
 * `LayoutRepair.repair` exists to prevent, and a wide grid must not reintroduce
 * it through the back door.
 *
 * ## Eligibility is all-or-nothing
 *
 * [tabletGridWidth] returns null unless the grid is unmistakably a standard
 * alphabetic keyboard, and then nothing is rewritten. A partly-applied transform
 * would be untestable and impossible to reason about, whereas bailing leaves the
 * phone grid — which is never wrong, only narrow. Across the shipped corpus this
 * declines fourteen layouts: the kana flick pads, braille, morse, the Chinese
 * shape-based and phonetic pads, and a handful of scripts whose grids have no
 * shift key at all. `TabletExpansionCorpusTest` pins that list.
 */

// Written as numbers rather than KeyEvent.KEYCODE_* for the same reason
// KeyActions.kt does it: this module is pure layout data with no android.view.
private const val KEYCODE_TAB = 61
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22

/**
 * Narrowest a minted flank key may be, in grid units. Below about three quarters
 * of a letter key it stops reading as a key and starts reading as a gap someone
 * forgot to fill.
 */
private const val MinFlank = 0.75f

/** Widest, so an unusual grid cannot produce a shift key half the row across. */
private const val MaxFlank = 2.5f

/** One column of new keys on each side. */
private const val WidenBy = 2f

/**
 * Most keys the tablet rebuild can add to a single row: a mirrored shift, and
 * backspace when the number row displaces it from its own. A row without that
 * much headroom under [MaxKeysPerRow] is not expanded at all.
 */
private const val MaxKeysAddedByExpansion = 2

private fun List<Key>.gridWidth(): Float = sumOf { it.width.toDouble() }.toFloat()

/**
 * The column count [letters] would be laid out against on [form], or null when
 * this grid is not one the expansion understands.
 *
 * Doubles as the eligibility test — a caller that gets a width back knows the
 * expansion applies, and the IME stores it on `LayoutSet.gridWidth` so every
 * layer is drawn on the letters layer's pitch. Without that, pressing `?123`
 * would visibly resize every key, because the symbols layer stays ten wide.
 */
fun tabletGridWidth(letters: KeyboardLayout, form: DeviceForm): Float? =
    locate(letters, form)?.target

/**
 * This grid widened for [form], or the receiver itself when it is not eligible.
 *
 * Returning the same instance rather than an equal copy is deliberate: the
 * render path downstream remembers on the layout, and a fresh-but-equal object
 * per resolution would defeat that.
 *
 * [numberRowShown] decides where backspace lives. With the digit row on it moves
 * up there and a mirrored Shift takes its place; with the row off it stays put
 * and simply widens into the space. The caller must pass the same answer the
 * renderer will use, or the keyboard loses its backspace.
 */
fun KeyboardLayout.expandForTablet(form: DeviceForm, numberRowShown: Boolean): KeyboardLayout {
    val plan = locate(this, form) ?: return this
    return copy(rows = plan.expandRows(form, numberRowShown))
}

/**
 * The digit row with a backtick prepended and backspace appended, matching what
 * [expandForTablet] took off the body.
 *
 * Both minted keys are width 1.0 on purpose. The digit row is laid out against
 * its own key *count* rather than the body's grid weight, so a 1.5-wide
 * backspace here would shift every digit off the column it belongs over.
 *
 * Idempotent in the ways that matter: a layout that authored its own digit row
 * with a backspace on it keeps that one, and a row that already opens with a
 * backtick does not get a second.
 */
fun List<Key>.expandNumberRowForTablet(): List<Key> {
    val head = if (firstOrNull()?.label == "`") emptyList() else listOf(backtickKey())
    val tail = if (any { it.action == KeyAction.Delete }) emptyList() else listOf(deleteKey())
    return head + this + tail
}

private fun tabKey() = Key("⇥", action = KeyAction.SendKey(KEYCODE_TAB))
private fun backslashKey() = Key("\\", longPress = listOf("|"))
private fun backtickKey() = Key("`", longPress = listOf("~"))
private fun capsLockKey() = Key("⇪", action = KeyAction.CapsLock)
private fun deleteKey() = Key("⌫", action = KeyAction.Delete)
private fun emojiKey() = Key("☺", action = KeyAction.Emoji)
private fun leftArrowKey() = Key("←", action = KeyAction.SendKey(KEYCODE_DPAD_LEFT))
private fun rightArrowKey() = Key("→", action = KeyAction.SendKey(KEYCODE_DPAD_RIGHT))

/** Where each key the transform moves currently sits, plus the target width. */
private class Plan(
    val rows: List<List<Key>>,
    val target: Float,
    val bottom: Int,
    val shiftAt: Pair<Int, Int>,
    val deleteAt: Pair<Int, Int>,
    val enterAt: Pair<Int, Int>,
    val symbolsAt: Pair<Int, Int>,
    val commaAt: Pair<Int, Int>,
    val periodAt: Pair<Int, Int>,
)

private operator fun List<List<Key>>.get(at: Pair<Int, Int>): Key = this[at.first][at.second]

/**
 * The gate. Every check has to pass, and the ones about key *counts* are what
 * make the rebuild below safe to write with no per-step fallbacks: by the time
 * it runs, each key it reaches for is known to exist exactly once.
 */
@Suppress("ReturnCount")
private fun locate(layout: KeyboardLayout, form: DeviceForm): Plan? {
    if (!form.isTablet) return null
    val rows = layout.rows
    // Four rows is what the rebuild rewrites, counting up from the bottom.
    // Anything above is left verbatim, which is how five-row Indic and Arabic
    // grids come through intact.
    if (rows.size < 4) return null

    // A row already near the cap cannot be widened. The rebuild can add two keys
    // to one row — a mirrored shift, and backspace when the number row displaces
    // it — so the headroom needed is two, not one.
    //
    // Our own grids top out around a dozen keys and are nowhere near this.
    // Converted Keyman grids run much wider: `laz` has a 23-key row that reaches
    // 25 with the number row shown, past MaxKeysPerRow, which repair would then
    // trim — silently dropping whichever key fell off the end.
    if (rows.any { it.size + MaxKeysAddedByExpansion > MaxKeysPerRow }) return null

    for (row in rows) {
        for (key in row) {
            // A flick pad is a 3x4 geometry where "the row's outer edge" means
            // nothing, and braille and morse are typed by position and timing
            // rather than by where a glyph sits. An unknown action is a layout
            // repair has not been through, so its shape is not trustworthy.
            if (key.flick.isNotEmpty()) return null
            // A grid with a key spanning rows is not a grid of independent rows,
            // and every rule below moves keys between rows and widens the flanks
            // a span is standing in. Declining leaves the phone arrangement,
            // which is the honest answer for a layout laid out by hand this way.
            if (key.rowSpan > 1) return null
            when (key.action) {
                KeyAction.KanaVariant, KeyAction.MorseDot, KeyAction.MorseDash -> return null
                is KeyAction.BrailleDot, is KeyAction.Unknown -> return null
                // A panel component has no place on a typing grid at all.
                is KeyAction.Field -> return null
                else -> Unit
            }
        }
    }

    val bottom = rows.lastIndex
    fun positionsOf(pred: (Int, Key) -> Boolean): List<Pair<Int, Int>> = buildList {
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, key -> if (pred(r, key)) add(r to c) }
        }
    }
    fun single(pred: (Int, Key) -> Boolean) = positionsOf(pred).singleOrNull()

    // The spacebar fixes which row is the bottom one, and every other position
    // check is relative to it.
    val spaceAt = single { _, key -> key.action == KeyAction.Space } ?: return null
    if (spaceAt.first != bottom) return null
    // One shift, one row above the bottom, is what "standard alphabetic
    // keyboard" means structurally — and it is also what rejects the symbols
    // and Fn layers, which carry a delete but no shift.
    val shiftAt = single { _, key -> key.action == KeyAction.Shift } ?: return null
    if (shiftAt.first != bottom - 1) return null
    val symbolsAt = single { _, key -> key.action == KeyAction.Symbols } ?: return null
    if (symbolsAt.first != bottom) return null
    // Both relocate, so a second copy would make "which one" ambiguous.
    val deleteAt = single { _, key -> key.action == KeyAction.Delete } ?: return null
    val enterAt = single { _, key -> key.action == KeyAction.Enter } ?: return null
    // The punctuation pair that fills the shift row's new middle.
    val commaAt = single { r, key -> key.roleIn(r, bottom) == KeyRole.Comma } ?: return null
    val periodAt = single { r, key -> key.roleIn(r, bottom) == KeyRole.Period } ?: return null
    if (commaAt.first != bottom || periodAt.first != bottom) return null

    // Widest, not just the modal grid weight: thirteen shipped layouts have a
    // top row that already overflows their grid, and sizing off the mode alone
    // would compute a negative flank for them and decline a layout that is
    // perfectly ordinary otherwise.
    val widest = rows.maxOf { it.gridWidth() }
    val target = maxOf(gridWeightOf(rows), widest) + WidenBy

    // Belt and braces: with `target` derived from the widest row, the symmetric
    // flanks are 1.0 at worst, so this cannot fire today. It is here so that a
    // future change to `target` fails by declining rather than by drawing a
    // sliver of a key.
    val flankA = (target - rows[bottom - 3].gridWidth()) / 2f
    val flankB = (target - rows[bottom - 2].gridWidth()) / 2f
    if (flankA < MinFlank || flankB < MinFlank) return null

    return Plan(rows, target, bottom, shiftAt, deleteAt, enterAt, symbolsAt, commaAt, periodAt)
}

/**
 * The rebuild.
 *
 * Every key that appears here is either copied from one the layout already had
 * or is a genuinely new utility key. Copying rather than constructing preserves
 * long-press alternates, roles, clipboard shortcuts, icons, a custom label and
 * the theme's per-key override id — all of which a positional rebuild would
 * quietly drop.
 */
private fun Plan.expandRows(form: DeviceForm, numberRowShown: Boolean): List<List<Key>> {
    val large = form == DeviceForm.LARGE_TABLET
    val shiftKey = rows[shiftAt]
    val deleteKey = rows[deleteAt]
    val enterKey = rows[enterAt]
    val symbolsKey = rows[symbolsAt]
    val commaKey = rows[commaAt]
    val periodKey = rows[periodAt]

    // A small tablet is *held*, so its bottom row is still thumb-typed and the
    // period stays under the right thumb where phone muscle memory has it. A
    // large one is put down and typed with both hands, so the period joins the
    // letters. That one sentence is the whole difference between the two forms.
    val moved = buildSet {
        add(deleteAt)
        add(enterAt)
        add(commaAt)
        if (large) add(periodAt)
    }
    val stripped = rows.mapIndexed { r, row ->
        row.filterIndexed { c, _ -> (r to c) !in moved }
    }

    return stripped.mapIndexed { index, row ->
        when (index) {
            bottom - 3 -> flanked(row, tabKey(), backslashKey())
            bottom - 2 -> flanked(row, capsLockKey(), enterKey)
            bottom - 1 -> {
                val out = row.toMutableList()
                out += commaKey.copy(role = KeyRole.Comma)
                if (large) out += periodKey.copy(role = KeyRole.Period)
                // With the digit row drawn, backspace has moved up to it and the
                // slot it left becomes the mirrored shift. With the row off,
                // backspace never went anywhere and just widens into the space.
                val tail = if (numberRowShown) shiftKey else deleteKey
                out += tail.copy(width = (target - out.gridWidth()).coerceIn(MinFlank, MaxFlank))
                out
            }
            bottom -> bottomRow(row, large, symbolsKey)
            else -> row
        }
    }
}

/**
 * [row] with a key on each side, both the width of the slack they fill. The
 * clamp's two edges are behaviours the grid already has: too wide and the row
 * overflows into narrower keys, which is what Dvorak's eleven-wide third row
 * does today; too narrow and [sidePadFor] centres what is left, which is what
 * QWERTY's nine-key home row does.
 */
private fun Plan.flanked(row: List<Key>, left: Key, right: Key): List<Key> {
    val flank = ((target - row.gridWidth()) / 2f).coerceIn(MinFlank, MaxFlank)
    return listOf(left.copy(width = flank)) + row + right.copy(width = flank)
}

/**
 * The bottom row: a dedicated emoji key beside `?123`, and on a large tablet the
 * arrow pair and the mirrored `?123` that a board too wide to thumb needs. The
 * spacebar absorbs whatever slack is left, so the row still totals the grid.
 */
private fun Plan.bottomRow(row: List<Key>, large: Boolean, symbolsKey: Key): List<Key> {
    val out = row.toMutableList()
    // The comma-as-emoji and globe-as-emoji preferences exist because a phone's
    // bottom row has no spare slot. A tablet's does, so the key goes in properly
    // and the renderer switches both preferences off for this grid.
    if (out.none { it.action == KeyAction.Emoji }) {
        val at = out.indexOfFirst { it.action == KeyAction.Symbols }
        if (at >= 0) out.add(at + 1, emojiKey())
    }
    if (large) {
        out += leftArrowKey()
        out += rightArrowKey()
        out += symbolsKey
    }
    val slack = target - out.gridWidth()
    if (slack > 0f) {
        val at = out.indexOfFirst { it.action == KeyAction.Space }
        if (at >= 0) out[at] = out[at].copy(width = out[at].width + slack)
    }
    return out
}
