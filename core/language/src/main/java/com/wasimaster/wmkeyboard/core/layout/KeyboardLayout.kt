package com.wasimaster.wmkeyboard.core.layout

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The four flick directions a 12-key kana pad reads off a key. The centre
 * (a plain tap) commits the key's own [Key.output]/[Key.label]; a directional
 * flick commits the matching entry of [Key.flick]. Serialised lowercase so a
 * hand-authored layout reads `"flick": { "left": "い", "up": "う", … }`.
 */
@Serializable
enum class FlickDirection {
    @SerialName("left") LEFT,

    @SerialName("up") UP,

    @SerialName("right") RIGHT,

    @SerialName("down") DOWN,
}

/**
 * One key of a layout grid.
 *
 * A key either outputs text ([output], falling back to [label]) or triggers an
 * [action]. [longPress] holds the alternate characters shown in the long-press
 * popup, whose first entry doubles as the corner hint. [width] is relative: 1.0
 * is a standard key, the spacebar is wider, shift/delete slightly wider.
 *
 * A text key can carry an [icon] instead of a glyph and an [iconHint] instead
 * of the character hint — both are named lookups resolved to a vector at render
 * time (see the icon registry in the ime layer), so this stays a plain
 * serializable value type with no Compose dependency. They sit alongside the
 * character system: a key with no icon renders exactly as before.
 *
 * This is both the stored and the rendered type. A parallel KeySpec DTO was the
 * alternative and was rejected: it would have been field-identical, so the only
 * thing it bought was a mapping function to forget to update. The stored/runtime
 * split that does exist is one level up, at [LayoutSpec], which is where layers
 * inherit and identity lives.
 *
 * Because this is a Compose parameter, everything reachable from it has to stay
 * stable — see the `@Immutable` annotation on [KeyAction].
 */
@Serializable
data class Key(
    val label: String,
    val output: String? = null,
    val shiftLabel: String? = null,
    val action: KeyAction = KeyAction.Text,
    val width: Float = 1f,
    /**
     * How many rows this key covers, counting its own: 1 (the usual case) is an
     * ordinary key, 2 makes it twice as tall and pushes the row below to flow
     * around the column it occupies. The vertical twin of [width], and the thing
     * an arrangement like ClearFlow's tall Enter cannot be written without.
     *
     * See [spanSlots] for the geometry and `KeyBand` in the ime layer for the
     * render. A span that runs past the last row is clamped rather than rejected,
     * so a grid the editor has just had a row deleted from still draws.
     */
    val rowSpan: Int = 1,
    val longPress: List<String> = emptyList(),
    /**
     * Long-press alternates that run an action instead of typing: Tab, the voice
     * tool, the editing pad (issue #21). Drawn in the same popup, after the
     * [longPress] characters, and never the corner hint — the hint is one glyph
     * and these are mostly icons.
     *
     * See [KeyAlternate] for why the two lists stay separate.
     */
    val actionAlternates: List<KeyAlternate> = emptyList(),
    /** Clipboard shortcut fired on long press instead of the alternates popup. */
    val clipboardAction: ClipboardKeyAction? = null,
    /** What this key means to field adaptation; null infers it from position. */
    val role: KeyRole? = null,
    /**
     * Named icon drawn as the key's main glyph in place of [label] (the label is
     * still used for accessibility and, unless [output] overrides it, for what
     * the key commits). Resolved through the ime-layer icon registry; an
     * unknown name falls back to drawing the text label.
     */
    val icon: String? = null,
    /**
     * Named icon drawn as the small corner hint in place of the character hint.
     * Shown under the same [KeyboardSettings.longPressHints] toggle as the text
     * hint. Resolved through the ime-layer icon registry.
     */
    val iconHint: String? = null,
    /**
     * Draws no corner hint on this key, whatever it would otherwise have shown —
     * the first long-press alternate, or an [iconHint].
     *
     * Per-key rather than per-layout because the case it exists for is one key in
     * an otherwise hinted grid: an author who wants the alternates *reachable*
     * (so clearing [longPress] is not the answer) but the corner left clean.
     * A key's own field beats the global [KeyboardSettings.longPressHints]
     * toggle, which is the other direction — it turns every hint on the board off
     * at once.
     */
    val hideHint: Boolean = false,
    /**
     * Draws this key's corner hint even when the global
     * [KeyboardSettings.longPressHints] toggle is off (issue #33).
     *
     * The other half of [hideHint], and it exists for the mirror-image author:
     * someone who turned every hint off because a full grid of them is noise,
     * but wants the two or three keys whose alternates nobody would guess to
     * keep saying so. Per key for the same reason [hideHint] is.
     *
     * [hideHint] wins when both are set. The two are separate booleans rather
     * than one tri-state because that is what keeps a file written by an older
     * build reading the same: `hideHint` already means what it meant, and a
     * key that says nothing gets the toggle's answer, exactly as before.
     */
    val forceHint: Boolean = false,
    /**
     * Directional flick outputs for a 12-key kana pad: a flick left/up/right/down
     * from this key commits the matching kana instead of the centre tap. Empty
     * (the usual case) means the key has no flick behaviour and a drag off it just
     * cancels the press, exactly as before.
     */
    val flick: Map<FlickDirection, String> = emptyMap(),
    /**
     * How big this one key's label is drawn, as a multiple of an ordinary
     * letter's size — and null, the normal case, means the keyboard decides,
     * which is a letter's size for a letter and a smaller one for a
     * multi-character mode label like `?123`.
     *
     * Setting it *replaces* that decision rather than scaling it, so 1.0 is the
     * way to say "draw this multi-character label at full letter size" — which
     * is the thing the automatic rule cannot be talked out of, and the thing a
     * layout imported from a keyboard with per-key label flags needs (see
     * `ForeignLayouts`, and issue #18). [LayoutAppearance.fontScale] then scales
     * the result, the same as it scales every other key.
     *
     * Per key rather than per row because that is the granularity the formats
     * this has to read use, and because the case it exists for is one odd key in
     * an otherwise ordinary grid. Clamped at draw time to [KeyLabelScaleRange]
     * — the same treatment [LayerSpec.rowHeights] gets, and for the same reason:
     * a value from a file is not a value from a control.
     */
    val labelScale: Float? = null,
)

/**
 * Whether a press and hold on this key opens the alternates popup.
 *
 * Three things have to agree on the answer: the pointer handler that opens the
 * popup, the draw that puts the first alternate in the key's corner, and the
 * layout editor's key sheet, which offers the alternates fields only for a key
 * that can actually show them. They used to agree by accident — every site
 * tested `action == Text` — and that is what kept alternates off the enter key
 * for no reason anyone had chosen (issue #22).
 *
 * A clipboard shortcut takes the long press over outright, and the keys that
 * repeat or chord under a held finger never get one; see [holdIsSpokenFor].
 */
fun Key.opensAlternatesPopup(): Boolean =
    clipboardAction == null &&
        !action.holdIsSpokenFor() &&
        (longPress.isNotEmpty() || actionAlternates.isNotEmpty())

/**
 * Whether this key *could* hold alternates, whether or not it has any — the
 * question the layout editor asks before drawing the fields that author them.
 */
fun Key.canHoldAlternates(): Boolean = clipboardAction == null && !action.holdIsSpokenFor()

/**
 * What a [Key.labelScale] is honoured at.
 *
 * The floor is below the corner-hint size, so a layout can genuinely annotate a
 * key rather than only shrink it a little; the ceiling is where a label stops
 * fitting a key it shares a row with.
 */
val KeyLabelScaleRange = 0.3f..2f

/**
 * This key's [Key.labelScale] as a drawer should use it: clamped, and null when
 * the key wants the automatic size.
 *
 * Both the keyboard and the layout editor's preview go through here so a key
 * cannot be shown at one size and typed on at another, which is the same
 * arrangement [rowScaledKeyHeight] exists for.
 */
fun Key.drawnLabelScale(): Float? =
    labelScale?.takeIf { it.isFinite() }?.coerceIn(KeyLabelScaleRange)

/**
 * The multiplier a layout's [LayoutAppearance.fontScale] puts on every label of
 * its grid: clamped to [LayoutFontScaleRange], and 1.0 for the layouts (all the
 * shipped ones) that ask for nothing.
 */
fun LayoutAppearance?.drawnFontScale(): Float =
    this?.fontScale?.takeIf { it.isFinite() }?.coerceIn(LayoutFontScaleRange) ?: 1f

/**
 * A compiled grid, ready to render. Produced by [LayoutSpec.compile] and never
 * stored — deliberately *not* `@Serializable`, so the type system says which of
 * the two is the wire format.
 */
data class KeyboardLayout(
    val name: String,
    val rows: List<List<Key>>,
    /**
     * Per-row height multipliers, positionally aligned with [rows]; null (the
     * usual case) means every row uses the standard key height. A missing or
     * short entry defaults to 1.0. Carried over verbatim from [LayerSpec].
     */
    val rowHeights: List<Float>? = null,
    /**
     * The label font and size the layout this grid came from asked for, carried
     * here for the same reason [rowHeights] is: the renderer is handed the
     * compiled grid, not the spec, and the editor's preview has to be able to
     * draw the same thing the keyboard will.
     */
    val appearance: LayoutAppearance? = null,
    /**
     * Whether this grid stays on screen across a close and reopen of the
     * keyboard; see [LayerSpec.persistent]. Carried on the compiled grid for
     * the same reason [rowHeights] is: the service decides what to do when a
     * field opens by looking at the grid it is showing, not at the spec.
     */
    val persistent: Boolean = false,
    /**
     * The theme this grid asks to be drawn in: the layer's own, else the
     * layout's, else null for "whatever is set" (issue #61). Resolved here so
     * the one place that picks the board's theme reads one field of the grid
     * on screen rather than re-deriving the layer-beats-layout rule.
     */
    val themeId: String? = null,
)

/**
 * The width every row is laid out against: the width the most rows share.
 *
 * Rows narrower than this are centred with equal padding on both sides, which
 * is how QWERTY's nine-key home row sits under its ten-key top row. Rows *wider*
 * than it deliberately overflow into narrower keys rather than being scaled —
 * Dvorak's third row is eleven wide against a grid weight of ten and has shipped
 * that way.
 *
 * Keying off the *most common* width rather than the first row's makes a lone
 * outlier row an outlier and nothing more. The first row was tempting but
 * breaks the moment a narrow row is inserted at the top: a single width-1 key
 * would set the grid weight to 1 and then fill the whole width. The maximum
 * breaks the other way, padding Dvorak's ten-key top rows to match its eleven-
 * key third row. The mode leaves every shipped layout on its historical grid
 * (each has a clear majority of ten-wide rows, Dvorak included) while treating
 * both a narrow top insert and a wide overflow row as the outliers they are.
 *
 * Widths are bucketed on a rounded key so accumulated-float jitter can't split
 * one width into two buckets; ties between equally common widths resolve to the
 * wider one.
 *
 * A row under a spanning key is measured *including* the column that key holds
 * over it — see [spanRowWidths]. Without that, the row below a two-row Enter
 * reads as one column narrower than it is drawn, and a grid with two such rows
 * would elect the wrong width for every other row on the board.
 */
fun gridWeightOf(rows: List<List<Key>>): Float {
    if (rows.isEmpty()) return 0f
    val widths = spanRowWidths(rows).toList()
    return widths
        .groupBy { (it * 100f).roundToInt() }
        .entries
        .maxWith(compareBy({ it.value.size }, { it.key }))
        .value.first()
}

/**
 * Half the slack between [gridWeight] and this row's own width, as a layout
 * weight. Negative for an over-wide row; callers drop the spacer below a small
 * epsilon rather than testing for zero, because these are accumulated floats.
 *
 * For rows a spanning key reaches into, [spanSlots] does this and the flow around
 * the span in one pass — a row cannot be centred from its own contents alone once
 * something above it is standing in one of its columns.
 */
fun sidePadFor(row: List<Key>, gridWeight: Float): Float =
    (gridWeight - row.sumOf { it.width.toDouble() }.toFloat()) / 2f

/**
 * The resolution a [Key.width] or a [LayerSpec.rowHeights] entry is edited at:
 * two decimals.
 *
 * The editor used to move both in quarter steps, which cannot express the width
 * a row of seven keys needs to fill a ten-wide grid (10 ÷ 7 = 1.43). Two
 * decimals can, and it is also the precision every one of these numbers is
 * *shown* at, so what the screen says is what the file holds. A free float is
 * the thing this is not: people hand-edit these layouts as JSON, and 1.4285715
 * in a file is noise no one can read or type back.
 *
 * One hundredth of a grid unit is about a third of a dp on a phone, so nothing
 * is lost by rounding to it.
 */
const val GridUnitStep = 0.01f

/**
 * [value] at [GridUnitStep] resolution.
 *
 * Non-finite and absurd values pass through untouched rather than saturating
 * through `Int`: a layout from a file can hold anything, and clamping it is
 * `repair`'s job, not this function's.
 */
fun roundGridUnit(value: Float): Float =
    if (!value.isFinite() || value > 1e6f || value < -1e6f) {
        value
    } else {
        (value * 100f).roundToInt() / 100f
    }

/**
 * [row] scaled so its keys add up to [gridWeight], keeping every key's share of
 * the row.
 *
 * The fix for the one thing quarter steps made unreachable: a row of keys of
 * *different* sizes that has to land exactly on the grid. Scaling keeps the
 * proportions the author chose, where widening one key to fill the slack (the
 * key sheet's "Fill the row") changes them.
 *
 * The row has to land *exactly*, or the warning this is the button for stays on
 * screen after pressing it. Rounding each key on its own does not: twenty-four
 * keys each losing up to half a hundredth leave the row an eighth of a key
 * short. So the arithmetic is done in whole hundredths and the leftover ones are
 * handed out largest-fraction-first, the way seats are apportioned — every key
 * ends within one hundredth of its exact share, and the row sums to the target
 * by construction rather than by luck.
 */
fun fitRowToGrid(row: List<Key>, gridWeight: Float): List<Key> {
    if (row.isEmpty() || !gridWeight.isFinite() || gridWeight <= 0f) return row
    // Far past MaxRowWidth. A grid weight this size is a broken file rather than
    // a wide layout, and scaling to it would write widths repair has to undo.
    if (gridWeight > MaxRowWidth * 10f) return row
    val total = row.sumOf { it.width.toDouble() }
    if (!total.isFinite() || total <= 0.0) return row
    val target = (gridWeight.toDouble() * 100.0).roundToInt()
    // Not enough to give every key the hundredth that keeps it from validating
    // as a zero-width key.
    if (target < row.size) return row

    val scale = target / total
    val exact = row.map { it.width.toDouble() * scale }
    val units = exact.mapTo(ArrayList(row.size)) { floor(it).toInt().coerceAtLeast(1) }
    var slack = target - units.sum()

    // The hundredths that fell off the floor go to the keys that lost the most.
    if (slack > 0) {
        val byFraction = exact.indices.sortedByDescending { exact[it] - floor(exact[it]) }
        var i = 0
        while (slack > 0) {
            units[byFraction[i % byFraction.size]]++
            slack--
            i++
        }
    }
    // The other direction: keys floored up to one hundredth can overshoot the
    // target, and the widest keys are where a hundredth shows least.
    if (slack < 0) {
        val byWidth = exact.indices.sortedByDescending { exact[it] }
        var i = 0
        while (slack < 0 && i < byWidth.size * (target + 1)) {
            val index = byWidth[i % byWidth.size]
            if (units[index] > 1) {
                units[index]--
                slack++
            }
            i++
        }
    }
    return row.mapIndexed { index, key -> key.copy(width = units[index] / 100f) }
}

/**
 * A row's key height in dp after applying its optional [scale] multiplier,
 * clamped to a sane range and rounded to whole dp. A null or 1.0 scale (the
 * common case) returns [baseKeyHeightDp] untouched.
 *
 * Lives here rather than beside the render loop because three places need the
 * same answer: the loop that draws the row, the pass that reserves height for
 * it, and the layout editor's preview. The editor is in another module, and a
 * preview that disagreed with the keyboard about how tall a row is would be
 * showing the user a keyboard they do not have.
 */
fun rowScaledKeyHeight(baseKeyHeightDp: Int, scale: Float?): Int =
    if (scale == null || scale == 1f) {
        baseKeyHeightDp
    } else {
        (baseKeyHeightDp * scale.coerceIn(MinRowHeightScale, MaxRowHeightScale))
            .roundToInt()
            .coerceAtLeast(1)
    }

/** The shortest a per-row height multiplier is honoured at. */
const val MinRowHeightScale = 0.4f

/** The tallest a per-row height multiplier is honoured at. */
const val MaxRowHeightScale = 2.5f
