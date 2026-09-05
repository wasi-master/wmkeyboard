package com.wasimaster.wmkeyboard.core.layout

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.language.R

/**
 * Checking a layout comes in two flavours, and keeping them apart is what makes
 * the editor usable.
 *
 * [LayoutSpec.repair] **rewrites** a layout so it cannot brick the keyboard, and
 * runs at the two moments where that matters: importing a file someone else
 * wrote, and turning a layout on. [validateLayout] only **reports**, and runs
 * continuously while editing.
 *
 * They must not be the same function. The editor auto-saves on every keystroke,
 * so a repair on the save path would append a delete key back the instant you
 * removed a row, and the undo stack would record the repaired layout rather than
 * the one you were building — an editor that fights you. Deferring the repair to
 * activation keeps the guarantee that matters (you can never *type on* a layout
 * you cannot backspace in) without taking the editor away.
 *
 * Both read the same rules below, so the warning the editor shows and the fix the
 * import applies can never drift apart.
 */

/** How much a finding matters. */
enum class LayoutSeverity {
    /** Worth telling the user about; the layout is still usable. */
    WARNING,

    /** The layout cannot be turned on until this is resolved. */
    BLOCKING,
}

/**
 * One line of user-facing text that this file produces, as a resource plus the
 * values that fill its placeholders.
 *
 * Nothing here holds a `Context`: the checker runs on the import thread and in
 * unit tests, and a line resolved that early would keep the language the device
 * had when the file was read. The screen that shows the line calls [format], so
 * the text always follows the current language.
 *
 * Set [stringRes] for a plain line, or [pluralsRes] together with [quantity]
 * when the wording depends on a count. [args] fills the placeholders in order,
 * and for a plural line the count is normally its first entry as well.
 */
data class LayoutMessage(
    @StringRes val stringRes: Int = 0,
    @PluralsRes val pluralsRes: Int = 0,
    val quantity: Int = 0,
    val args: List<Any> = emptyList(),
) {

    /**
     * The finished line, in the language [resources] is configured for.
     *
     * The arguments go in one by one rather than spread out of [args]: a spread
     * copies the array on every call, and the build reports that as a
     * performance finding. The longest line here takes four.
     */
    fun format(resources: Resources): String {
        val a = args
        if (pluralsRes != 0) {
            return when (a.size) {
                0 -> resources.getQuantityString(pluralsRes, quantity)
                1 -> resources.getQuantityString(pluralsRes, quantity, a[0])
                2 -> resources.getQuantityString(pluralsRes, quantity, a[0], a[1])
                3 -> resources.getQuantityString(pluralsRes, quantity, a[0], a[1], a[2])
                else -> resources.getQuantityString(pluralsRes, quantity, a[0], a[1], a[2], a[3])
            }
        }
        return when (a.size) {
            0 -> resources.getString(stringRes)
            1 -> resources.getString(stringRes, a[0])
            2 -> resources.getString(stringRes, a[0], a[1])
            3 -> resources.getString(stringRes, a[0], a[1], a[2])
            else -> resources.getString(stringRes, a[0], a[1], a[2], a[3])
        }
    }
}

/** One thing wrong with a layout, in words the user can act on. */
data class LayoutFinding(
    val severity: LayoutSeverity,
    val text: LayoutMessage,
    /** The layer it was found in, or null when it is about the layout as a whole. */
    val layer: LayoutLayer? = null,
)

/**
 * A layout after the repair pass, with a plain-English line per fix so the import
 * sheet can say what it changed rather than silently rewriting the user's file.
 * Repairing rather than rejecting is deliberate: the raw-JSON editor means
 * hand-written layouts, and a file that is ninety-five percent right should cost
 * its author a note, not a retype.
 */
data class RepairedLayout(val spec: LayoutSpec, val repairNotes: List<LayoutMessage>)

// Public rather than private because they are the shape contract every grid has
// to satisfy, not an implementation detail of the repair pass: the tablet
// expansion mints keys and must stay inside them, and its corpus test asserts
// against these names so a cap that moves moves the test with it.

/** Widest a single key may be, in grid units — wider than the whole 10-column grid. */
const val MaxKeyWidth = 12f

/** Widest a row may total before every key in it is scaled down to fit. */
const val MaxRowWidth = 40f

/** Below roughly 2 mm per key on a phone, a row stops being tappable. */
const val MaxKeysPerRow = 24

/** Taller than any phone shows without eating the whole screen. */
const val MaxRowsPerLayer = 8

/**
 * Most rows one key may cover ([Key.rowSpan]). A key cannot usefully be taller
 * than the tallest layer the grid is allowed to be, and a file asking for more
 * is broken rather than ambitious. A span reaching past the *actual* last row is
 * a separate, milder thing: the renderer clamps it (see [Key.spanFrom]) and
 * [validateLayout] only warns.
 */
const val MaxKeySpan = MaxRowsPerLayer

/**
 * Everything wrong with this layout, worst first. Pure — it never rewrites the
 * layout, so the editor can call it on every keystroke and show the result
 * without the layout changing under the user.
 *
 * Only [LayoutSeverity.BLOCKING] findings stop a layout being enabled. A missing
 * shift key is deliberately not one of them: a symbols-forward grid may
 * legitimately not want one, and it stays recoverable because long-press still
 * reaches the alternates.
 */
fun validateLayout(spec: LayoutSpec): List<LayoutFinding> {
    val findings = mutableListOf<LayoutFinding>()

    val letters = spec.layer(LayoutLayer.LETTERS)
    if (letters == null) {
        // An absent letters layer inherits the built-in default grid at compile
        // time (LayoutCompiler), which already has letters plus delete/space/
        // enter — so it is fully typeable and nothing here should block it. This
        // keeps validate consistent with repair(), which drops an all-unusable
        // letters layer to null and promises the result can still be enabled.
    } else if (letters.rows.all { it.isEmpty() }) {
        findings += LayoutFinding(
            LayoutSeverity.BLOCKING,
            LayoutMessage(R.string.core_lang_layout_letters_empty_error),
            LayoutLayer.LETTERS,
        )
    } else {
        val keys = letters.rows.flatten()
        if (keys.none { it.action == KeyAction.Delete }) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(R.string.core_lang_layout_no_delete_error),
                LayoutLayer.LETTERS,
            )
        }
        if (keys.none { it.action == KeyAction.Enter }) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(R.string.core_lang_layout_no_enter_error),
                LayoutLayer.LETTERS,
            )
        }
        if (keys.none { it.action == KeyAction.Space }) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(R.string.core_lang_layout_no_space_error),
                LayoutLayer.LETTERS,
            )
        }
        if (keys.none { it.action == KeyAction.Shift }) {
            findings += LayoutFinding(
                LayoutSeverity.WARNING,
                LayoutMessage(R.string.core_lang_layout_no_shift_warning),
                LayoutLayer.LETTERS,
            )
        }
    }

    for (layer in LayoutLayer.entries) {
        val rows = spec.layer(layer)?.rows ?: continue
        val label = layer.key

        if (rows.size > MaxRowsPerLayer) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_layout_too_many_rows_error,
                    quantity = rows.size,
                    args = listOf(label, rows.size, MaxRowsPerLayer),
                ),
                layer,
            )
        }
        if (layer != LayoutLayer.LETTERS && layer.isCycled && rows.isNotEmpty() &&
            rows.flatten().none { it.action == KeyAction.Letters || it.action == KeyAction.Symbols }
        ) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(
                    R.string.core_lang_layout_no_way_back_error,
                    args = listOf(label),
                ),
                layer,
            )
        }

        val gridWeight = gridWeightOf(rows)
        val spanWidths = spanRowWidths(rows)
        rows.forEachIndexed { index, row ->
            val number = index + 1
            if (row.isEmpty()) {
                findings += LayoutFinding(
                    LayoutSeverity.WARNING,
                    LayoutMessage(
                        R.string.core_lang_layout_empty_row_warning,
                        args = listOf(number, label),
                    ),
                    layer,
                )
                return@forEachIndexed
            }
            if (row.size > MaxKeysPerRow) {
                findings += LayoutFinding(
                    LayoutSeverity.BLOCKING,
                    LayoutMessage(
                        pluralsRes = R.plurals.core_lang_layout_too_many_keys_error,
                        quantity = row.size,
                        args = listOf(number, label, row.size, MaxKeysPerRow),
                    ),
                    layer,
                )
            }
            if (row.any { !it.width.isFinite() || it.width <= 0f }) {
                findings += LayoutFinding(
                    LayoutSeverity.BLOCKING,
                    LayoutMessage(
                        R.string.core_lang_layout_key_no_width_error,
                        args = listOf(number, label),
                    ),
                    layer,
                )
            }
            if (row.any { it.rowSpan < 1 || it.rowSpan > MaxKeySpan }) {
                findings += LayoutFinding(
                    LayoutSeverity.BLOCKING,
                    LayoutMessage(
                        R.string.core_lang_layout_key_span_error,
                        args = listOf(number, label, MaxKeySpan),
                    ),
                    layer,
                )
            } else if (row.any { index + it.rowSpan > rows.size }) {
                // Only reported once the span is otherwise sane, so a file with a
                // nonsense span gets one line rather than two. Not blocking: this
                // is what deleting the bottom row of a grid leaves behind, and the
                // renderer stops the key at the last row.
                findings += LayoutFinding(
                    LayoutSeverity.WARNING,
                    LayoutMessage(
                        R.string.core_lang_layout_span_past_end_warning,
                        args = listOf(number, label),
                    ),
                    layer,
                )
            }
            // Counting the columns held over this row by a spanning key above, so
            // the row under a two-row Enter is not reported as over-wide for
            // filling the width it is actually given.
            val total = spanWidths[index]
            if (total > gridWeight + 0.01f) {
                findings += LayoutFinding(
                    LayoutSeverity.WARNING,
                    LayoutMessage(
                        R.string.core_lang_layout_wide_row_warning,
                        args = listOf(number, label),
                    ),
                    layer,
                )
            }
        }

        val badDots = rows.sumOf { row ->
            row.count { key ->
                val action = key.action
                action is KeyAction.BrailleDot && action.dot !in 1..6
            }
        }
        if (badDots > 0) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_layout_bad_braille_dots_error,
                    quantity = badDots,
                    args = listOf(badDots, label),
                ),
                layer,
            )
        }

        // The one thing repair *deletes* silently, and the editor never said so:
        // a text key with nothing to type is gone the moment the layout is turned
        // on, which reads from the keyboard as a key that does nothing (issue
        // #16). A warning rather than blocking, for the same reason the whole
        // file exists — the editor saves mid-edit, and a key you have not
        // labelled yet must not lock the layout you are building.
        val blank = rows.sumOf { row -> row.count { it.typesNothing() } }
        if (blank > 0) {
            findings += LayoutFinding(
                LayoutSeverity.WARNING,
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_layout_blank_key_warning,
                    quantity = blank,
                    args = listOf(blank, label),
                ),
                layer,
            )
        }

        val unknown = rows.sumOf { row -> row.count { it.action is KeyAction.Unknown } }
        if (unknown > 0) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_layout_unknown_action_error,
                    quantity = unknown,
                    args = listOf(unknown, label),
                ),
                layer,
            )
        }

        // A panel component belongs to a panel layout; on a typing grid it is a
        // cell nothing draws. Blocking, and `repair` drops it, like an unknown
        // action — which is what an older build sees it as anyway.
        val fields = rows.sumOf { row -> row.count { it.action is KeyAction.Field } }
        if (fields > 0) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(
                    pluralsRes = R.plurals.core_lang_layout_field_key_error,
                    quantity = fields,
                    args = listOf(fields, label),
                ),
                layer,
            )
        }
    }

    return findings.sortedByDescending { it.severity == LayoutSeverity.BLOCKING }
}

/**
 * A text key with neither a label nor an output: nothing to draw and nothing to
 * type. [Key.repairKey] deletes these and [validateLayout] warns about them, off
 * this one definition so the warning and the deletion can never disagree.
 *
 * Emptiness, not blankness — a key labelled with a space types a space, which is
 * a key doing its job.
 */
internal fun Key.typesNothing(): Boolean =
    action == KeyAction.Text && label.isEmpty() && output.isNullOrEmpty()

/** A popup entry that does something when tapped; see [Key.actionAlternates]. */
internal fun KeyAlternate.isUsable(): Boolean = when (action) {
    is KeyAction.Unknown -> false
    // A component cannot be a popup entry.
    is KeyAction.Field -> false
    KeyAction.Text -> label.isNotEmpty()
    else -> true
}

/** Whether this layout is safe to turn on. */
fun LayoutSpec.canBeEnabled(): Boolean =
    validateLayout(this).none { it.severity == LayoutSeverity.BLOCKING }

/**
 * Rewrites this layout into one that cannot leave the user unable to type, and
 * says what it changed.
 *
 * Run on import and on activation, never on an ordinary save — see the note at
 * the top of this file for why. The delete/enter/space rules are the ones that
 * earn the whole design: they are the difference between "I imported a layout
 * and now I cannot type well enough to uninstall it" and "I imported a layout,
 * it told me it added a backspace key, fine."
 */
fun LayoutSpec.repair(): RepairedLayout {
    val repairs = mutableListOf<LayoutMessage>()

    val layers = layers.mapNotNull { (key, layerSpec) ->
        val layer = LayoutLayer.entries.firstOrNull { it.key == key }
        val label = layer?.key ?: key

        var rows = layerSpec.rows.map { row -> row.mapNotNull { it.repairKey(label, repairs) } }
            .filter { it.isNotEmpty() }

        if (rows.size > MaxRowsPerLayer) {
            repairs += LayoutMessage(
                pluralsRes = R.plurals.core_lang_repair_rows_trimmed,
                quantity = rows.size,
                args = listOf(label, rows.size, MaxRowsPerLayer),
            )
            rows = rows.take(MaxRowsPerLayer)
        }
        rows = rows.map { row -> row.repairRow(label, repairs) }

        // An empty layer is dropped rather than kept: a layout inherits the
        // shipped grid for anything it does not define, so dropping it restores
        // a working layer instead of leaving a blank one.
        if (rows.isEmpty()) {
            repairs += LayoutMessage(
                R.string.core_lang_repair_layer_replaced,
                args = listOf(label),
            )
            return@mapNotNull null
        }
        // Per-row heights are positional, so they are only still valid if repair
        // left the row count untouched (it never reorders, only drops/adds). Drop
        // them otherwise rather than let them land on the wrong rows.
        val heights = layerSpec.rowHeights?.takeIf { rows.size == layerSpec.rows.size }
        key to layerSpec.copy(rows = rows, rowHeights = heights)
    }.toMap().toMutableMap()

    val lettersKey = LayoutLayer.LETTERS.key
    layers[lettersKey]?.let { letters ->
        var rows = letters.rows
        rows = rows.ensuring(KeyAction.Delete, Key("⌫", action = KeyAction.Delete, width = 1.5f)) {
            repairs += LayoutMessage(R.string.core_lang_repair_delete_key_added)
        }
        rows = rows.ensuring(KeyAction.Space, Key(" ", action = KeyAction.Space, width = 4f)) {
            repairs += LayoutMessage(R.string.core_lang_repair_space_key_added)
        }
        rows = rows.ensuring(KeyAction.Enter, Key("⏎", action = KeyAction.Enter, width = 1.5f)) {
            repairs += LayoutMessage(R.string.core_lang_repair_enter_key_added)
        }
        // Same positional guard: a missing space/enter/delete key adds a row,
        // which would shift every per-row height down one.
        val heights = letters.rowHeights?.takeIf { rows.size == letters.rows.size }
        layers[lettersKey] = letters.copy(rows = rows, rowHeights = heights)
    }

    for (layer in LayoutLayer.entries) {
        if (layer == LayoutLayer.LETTERS || !layer.isCycled) continue
        val spec = layers[layer.key] ?: continue
        val hasWayBack = spec.rows.flatten()
            .any { it.action == KeyAction.Letters || it.action == KeyAction.Symbols }
        if (!hasWayBack) {
            repairs += LayoutMessage(
                R.string.core_lang_repair_letters_key_added,
                args = listOf(layer.key),
            )
            layers[layer.key] = spec.copy(
                rows = spec.rows.appendToLastRow(
                    Key("ABC", action = KeyAction.Letters, width = 1.5f),
                ),
            )
        }
    }

    return RepairedLayout(copy(layers = layers), repairs)
}

/**
 * Fixes what can be fixed about one key, or drops it. Returning null rather than
 * a placeholder lets the row re-flow around the gap, which is less confusing
 * than a button that is visibly there and silently does nothing.
 */
internal fun Key.repairKey(
    label: String,
    repairs: MutableList<LayoutMessage>,
    fieldsAllowed: Boolean = false,
): Key? {
    if (action is KeyAction.Unknown) {
        repairs += LayoutMessage(
            R.string.core_lang_repair_unknown_key_deleted,
            args = listOf(label, (action as KeyAction.Unknown).tag),
        )
        return null
    }
    // A panel component on a typing grid: nothing on the board would draw it,
    // so the cell would be a dead patch the width of a key. Panel layouts pass
    // [fieldsAllowed] and check the cell's kind themselves.
    if (!fieldsAllowed && action is KeyAction.Field) {
        repairs += LayoutMessage(
            R.string.core_lang_repair_field_key_deleted,
            args = listOf(label),
        )
        return null
    }
    if (typesNothing()) {
        repairs += LayoutMessage(
            R.string.core_lang_repair_blank_key_deleted,
            args = listOf(label),
        )
        return null
    }

    var fixed = this
    // The one blocking finding repair used to leave behind, which broke the
    // promise the rest of this file makes: [validateLayout] refuses a dot
    // outside 1..6, so an imported layout carrying one came through the repair
    // pass "clean" — no note, nothing to act on — and then could not be turned
    // on. Clamped rather than dropped: a braille grid is chorded by position, so
    // removing a dot key leaves the remaining six unable to spell anything,
    // where a nearest-valid dot leaves a usable grid and a line saying so.
    (action as? KeyAction.BrailleDot)?.takeIf { it.dot !in 1..6 }?.let { bad ->
        val dot = bad.dot.coerceIn(1, 6)
        repairs += LayoutMessage(
            R.string.core_lang_repair_braille_dot_clamped,
            args = listOf(label, bad.dot, dot),
        )
        fixed = fixed.copy(action = KeyAction.BrailleDot(dot))
    }
    if (!width.isFinite() || width <= 0f) {
        repairs += LayoutMessage(
            R.string.core_lang_repair_key_width_set,
            args = listOf(label),
        )
        fixed = fixed.copy(width = 1f)
    } else if (width > MaxKeyWidth) {
        repairs += LayoutMessage(
            R.string.core_lang_repair_key_width_capped,
            args = listOf(label, width, MaxKeyWidth),
        )
        fixed = fixed.copy(width = MaxKeyWidth)
    }
    // Same shape as the width clamp above, and for the same reason: a span
    // outside the range [validateLayout] accepts would come through repair
    // "clean" and then refuse to enable. A key covering no rows is not a key.
    if (rowSpan < 1 || rowSpan > MaxKeySpan) {
        val span = rowSpan.coerceIn(1, MaxKeySpan)
        repairs += LayoutMessage(
            R.string.core_lang_repair_key_span_clamped,
            args = listOf(label, rowSpan, span),
        )
        fixed = fixed.copy(rowSpan = span)
    }
    if (fixed.longPress.any { it.isEmpty() }) {
        fixed = fixed.copy(longPress = fixed.longPress.filter { it.isNotEmpty() })
    }
    // The same treatment for the action alternates, and quietly for the same
    // reason: an entry that cannot do anything is a popup button that is visibly
    // there and silently dead. Unusable means either an action from a newer
    // build (the popup twin of the Unknown key dropped above) or a Text
    // alternate with nothing to type — the popup commits an alternate's label,
    // so a blank one commits nothing.
    if (fixed.actionAlternates.any { !it.isUsable() }) {
        fixed = fixed.copy(actionAlternates = fixed.actionAlternates.filter { it.isUsable() })
    }
    return fixed
}

/** Truncates an over-long row and scales an over-wide one back into the grid. */
internal fun List<Key>.repairRow(label: String, repairs: MutableList<LayoutMessage>): List<Key> {
    var row = this
    if (row.size > MaxKeysPerRow) {
        repairs += LayoutMessage(
            pluralsRes = R.plurals.core_lang_repair_row_keys_trimmed,
            quantity = row.size,
            args = listOf(label, row.size, MaxKeysPerRow),
        )
        row = row.take(MaxKeysPerRow)
    }
    val total = row.sumOf { it.width.toDouble() }.toFloat()
    if (total > MaxRowWidth) {
        val scale = MaxRowWidth / total
        repairs += LayoutMessage(
            R.string.core_lang_repair_row_scaled,
            args = listOf(label, total),
        )
        row = row.map { it.copy(width = it.width * scale) }
    }
    return row
}

/** Appends [fallback] to the last row unless [action] is already somewhere in the grid. */
private inline fun List<List<Key>>.ensuring(
    action: KeyAction,
    fallback: Key,
    onAdded: () -> Unit,
): List<List<Key>> {
    if (flatten().any { it.action == action }) return this
    onAdded()
    return appendToLastRow(fallback)
}

/**
 * Puts [key] at the end of the grid without breaking the size limits repair has
 * just brought it inside.
 *
 * Appending blindly was a hole in repair's one promise. The row trim runs before
 * the delete/space/enter keys are added, so a layer whose last row was trimmed
 * to [MaxKeysPerRow] came back out at 27 keys — over the limit, still blocking,
 * "repaired" into a layout that would not enable and with no note saying why.
 *
 * A new row when the layer has space for one. When it has neither — every row
 * full and every row slot used — the last key gives way instead: a grid at both
 * limits at once is pathological, and being able to backspace is worth more than
 * the twenty-fourth key of the eighth row.
 */
internal fun List<List<Key>>.appendToLastRow(key: Key): List<List<Key>> = when {
    isEmpty() -> listOf(listOf(key))
    last().size < MaxKeysPerRow -> dropLast(1) + listOf(last() + key)
    size < MaxRowsPerLayer -> this + listOf(listOf(key))
    else -> dropLast(1) + listOf(last().dropLast(1) + key)
}
