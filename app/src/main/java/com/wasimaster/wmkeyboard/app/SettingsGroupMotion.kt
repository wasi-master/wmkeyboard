package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.roundToInt

/*
 * The rows of a [SettingsGroup] arriving and leaving.
 *
 * A group is a plain `Column`, not a lazy list — see [WmScreen] — so it has no
 * `animateItem` to lean on. A word added to the dictionary, a snippet swiped
 * away, a pack deleted: each used to be one frame with the row and the next
 * without it, and every row under it jumped by a row's height. A row the
 * caller gives a key to now opens into its place and closes out of it instead,
 * and the rows under it slide rather than jump, because what moves them is the
 * row's own height animating.
 *
 * Opt-in, per row, through `item(key = …)`. A row with no key is drawn exactly
 * as it always was, so the hundreds of fixed settings rows pay nothing for any
 * of this; neither does a keyed row once it has arrived, which is drawn bare
 * until the next time it moves.
 */

/** How long a keyed row takes to open into its group, or to close out of it. */
private const val RowPresenceMs = 180

/**
 * More rows than this arriving, or leaving, in one pass is a different list
 * rather than an edit to this one: the next page of a dictionary, a search
 * narrowing three hundred words to four, "Clear all". Those land in one frame,
 * as they did before. Opening a hundred rows at once would be a hundred
 * animations for the eye to read as noise, and a frame budget spent on them.
 */
private const val RowMotionBulkLimit = 8

private val RowPresenceSpec = tween<Float>(RowPresenceMs, easing = FastOutSlowInEasing)

/** One row as the group draws it on this pass: in the builder's list, or just out of it and on its way. */
private class DrawnRow(val entry: SettingsGroupScope.Entry, val leaving: Boolean) {
    /** Whether the row counts as one of the group's cards for its corners. */
    val shows: Boolean get() = !leaving && entry.visible
}

/**
 * The key an unkeyed row is composed under: its place among the group's other
 * unkeyed rows. A class of its own, so no caller's key can equal it.
 */
private data class PlainRow(val ordinal: Int)

/** What a pass draws, and whether a key new to it opens or simply appears. */
private class GroupPass(val rows: List<DrawnRow>, val animateArrivals: Boolean)

/**
 * How much of its room a keyed row is taking, from 0 (none: it has not opened
 * yet, or has finished closing) to 1 (all of it).
 *
 * [partial] is the only thing composition reads, and it changes twice per
 * move: the frames in between are relayouts and redraws of this row alone.
 */
@Stable
private class RowPresence(shown: Boolean) {
    val amount = Animatable(if (shown) 1f else 0f)
    val partial: Boolean by derivedStateOf { amount.value < 1f }
}

/**
 * What the group remembers between passes: what it drew, and which of the rows
 * on their way out have got there.
 *
 * Remembered by [GroupRows], which a fold composes afresh each time it opens,
 * so an opened fold's rows are its first pass and arrive in place rather than
 * each opening into a fold that is opening already.
 */
private class GroupRowLedger {
    /** False until the group has drawn once. Rows there from the first pass arrive in place. */
    var settled = false

    /** The last committed pass, leaving rows included. Written in a SideEffect, never in composition. */
    var drawn: List<DrawnRow> = emptyList()

    /** Keys whose way out has finished, to be left out of the next pass. */
    val gone = HashSet<Any>()

    /** Bumped when a row finishes leaving, so the group draws once more without it. */
    var departures by mutableIntStateOf(0)

    fun depart(key: Any) {
        gone += key
        departures++
    }
}

/**
 * Lines this pass's rows up with the last one's: every row the builder gave,
 * plus each keyed row that was drawn last time and is not in the builder's list
 * now, kept where it was — after whichever of its old neighbours is still
 * here — so it can close in place rather than vanish.
 *
 * A keyed row that is hidden (`item(visible = false, key = …)`) is treated as
 * gone, which gives it the same way out. An unkeyed conditional row stays in
 * the list hidden, because that is how its own `AnimatedVisibility` knows to
 * close.
 */
private fun planRows(items: List<SettingsGroupScope.Entry>, ledger: GroupRowLedger, motion: Boolean): GroupPass {
    val current = items.mapNotNull { entry ->
        if (entry.key == null || entry.visible) DrawnRow(entry, leaving = false) else null
    }
    if (!motion || !ledger.settled) return GroupPass(current, animateArrivals = false)
    val before = ledger.drawn
    val keysNow = current.mapNotNullTo(HashSet()) { it.entry.key }
    val keysBefore = before.mapNotNullTo(HashSet()) { row -> row.entry.key?.takeIf { !row.leaving } }
    val arrivals = keysNow.count { it !in keysBefore }
    val departing = before.filter { row ->
        val k = row.entry.key
        k != null && k !in keysNow && k !in ledger.gone
    }
    val animateArrivals = arrivals <= RowMotionBulkLimit
    // Rows already on their way out finish going; it is the fresh departures
    // that decide whether this pass is an edit or a different list.
    if (departing.isEmpty() || departing.count { !it.leaving } > RowMotionBulkLimit) {
        return GroupPass(current, animateArrivals)
    }
    val out = ArrayList<DrawnRow>(current.size + departing.size)
    out.addAll(current)
    // Where in [out] the last row walked past landed. Unkeyed rows have no
    // name to find them by; they are matched by order among themselves, which
    // is how they are composed too.
    var anchor = -1
    var plainSeen = 0
    for (row in before) {
        val k = row.entry.key
        if (k == null) {
            var nth = -1
            var at = -1
            for (i in out.indices) {
                if (out[i].entry.key == null && ++nth == plainSeen) {
                    at = i
                    break
                }
            }
            plainSeen++
            if (at >= 0) anchor = at
            continue
        }
        when (k) {
            in keysNow -> anchor = out.indexOfFirst { !it.leaving && it.entry.key == k }
            in ledger.gone -> Unit
            else -> {
                out.add(anchor + 1, DrawnRow(row.entry, leaving = true))
                anchor++
            }
        }
    }
    return GroupPass(out, animateArrivals)
}

/**
 * A keyed row while it is opening or closing: laid out at [RowPresence.amount]
 * of its height, clipped to that, and faded.
 *
 * The words come in once the row has begun to open and are gone before it has
 * finished closing, so the reader never has to make out a half-height row.
 * Read in layout and draw only: the travel costs this row a relayout a frame,
 * and the rows below simply follow the height it reports. One layer for the
 * fade and the clip together, modulating rather than compositing so nothing
 * overflowing the card — a ripple, a shadow — is cut by an offscreen buffer.
 */
private fun Modifier.rowPresence(presence: RowPresence, leaving: Boolean): Modifier = this
    .graphicsLayer {
        val amount = presence.amount.value
        alpha = if (leaving) {
            ((amount - 0.4f) / 0.6f).coerceIn(0f, 1f)
        } else {
            ((amount - 0.25f) / 0.75f).coerceIn(0f, 1f)
        }
        clip = true
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * presence.amount.value.coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
    .then(if (leaving) InertRow else Modifier)

/**
 * A row that has already been deleted takes no more taps and is not read out.
 *
 * It is still composed for the few frames it takes to close, with the buttons
 * it had: a second press on its bin would delete a row that is already gone,
 * and its swipe would offer to undo the wrong thing. Swallowed on the way down,
 * before any control inside sees the press.
 */
private val InertRow: Modifier = Modifier
    .clearAndSetSemantics { }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }

/**
 * Draws a group's rows, each through [card].
 *
 * [card] is handed the modifier for the row's outermost node, whether there is
 * a card above it (and so a gap to leave), and whether it is the first or last
 * of the group's cards, which decides its corners. A row on its way out is
 * never first or last: the rows still in the group take their ends as it goes,
 * and their corners animate to match — see `GroupCard`.
 *
 * - **Unkeyed rows** are drawn as they always were: there, or behind the
 *   `AnimatedVisibility` an `item(visible = …)` asks for.
 * - **Keyed rows** keep their node by key, so a row's remembered state (a
 *   swipe, an open menu) stays with the row and not with its position, and
 *   open and close as they come and go after the group's first pass.
 *
 * Under Reduce motion a keyed row is simply there or not.
 */
@Composable
internal fun GroupRows(
    items: List<SettingsGroupScope.Entry>,
    /**
     * True while the rows arriving are the rest of a group being let in a
     * few at a time ([rememberRowStage]) rather than rows the list gained:
     * they appear in place, as a group's first pass does.
     */
    quietArrivals: Boolean = false,
    card: @Composable (
        modifier: Modifier,
        gapAbove: Boolean,
        first: Boolean,
        last: Boolean,
        content: @Composable () -> Unit,
    ) -> Unit,
) {
    val still = LocalReduceMotion.current
    val ledger = remember { GroupRowLedger() }
    // Read here so a row finishing its way out redraws the group without it.
    @Suppress("UNUSED_EXPRESSION")
    ledger.departures
    val pass = planRows(items, ledger, motion = !still).let { planned ->
        if (quietArrivals && planned.animateArrivals) GroupPass(planned.rows, animateArrivals = false) else planned
    }
    SideEffect {
        ledger.settled = true
        ledger.drawn = pass.rows
        ledger.gone.retainAll { k -> pass.rows.any { it.leaving && it.entry.key == k } }
    }
    val rows = pass.rows
    val firstShown = rows.indexOfFirst { it.shows }
    val lastShown = rows.indexOfLast { it.shows }
    var plainSeen = 0
    rows.forEachIndexed { index, row ->
        val entry = row.entry
        val gapAbove = index > 0
        val first = if (row.leaving) firstShown < 0 || index < firstShown else index == firstShown
        val last = if (row.leaving) index > lastShown else index == lastShown
        val rowKey = entry.key
        // Every row is keyed here, outside the branch below, and that is what
        // lets a keyed row move (#665). The compiler wraps each branch of an
        // `if` in a group of its own, so a `key` inside one has no siblings to
        // be found among: when a row left, each row under it met the next
        // one's slot, was composed afresh, and opened as if it had just been
        // added. Unkeyed rows are named by their order among themselves, which
        // is how planRows matches them and how they were composed before.
        key(rowKey ?: PlainRow(plainSeen++)) {
            if (rowKey == null) {
                when {
                    !entry.conditional -> card(Modifier, gapAbove, first, last, entry.content)
                    // Reduced motion has no still version of a reveal, so the row
                    // is or is not there.
                    still -> if (entry.visible) card(Modifier, gapAbove, first, last, entry.content)
                    else -> AnimatedVisibility(
                        visible = entry.visible,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        card(Modifier, gapAbove, first, last, entry.content)
                    }
                }
            } else {
                val leaving = row.leaving
                // Decided once, in the pass that brings the row in, so a row
                // that is going to open draws its first frame closed rather
                // than flashing in at full height first.
                val presence = remember { RowPresence(shown = !pass.animateArrivals) }
                val moving = leaving || presence.partial
                if (moving) {
                    LaunchedEffect(leaving) {
                        if (leaving) {
                            presence.amount.animateTo(0f, RowPresenceSpec)
                            ledger.depart(rowKey)
                        } else {
                            // Arriving, or brought back (an undo) while still closing.
                            presence.amount.animateTo(1f, RowPresenceSpec)
                        }
                    }
                }
                card(
                    if (moving) Modifier.rowPresence(presence, leaving) else Modifier,
                    gapAbove,
                    first,
                    last,
                    entry.content,
                )
            }
        }
    }
}
