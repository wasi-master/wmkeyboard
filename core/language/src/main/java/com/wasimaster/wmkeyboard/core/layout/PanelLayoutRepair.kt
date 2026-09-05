package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R

/**
 * Checking a panel layout, in the two flavours `LayoutRepair.kt` explains:
 * [validatePanelLayout] only reports and runs on every editor keystroke;
 * [PanelLayoutSpec.repair] rewrites, and is what the keyboard reads through, so
 * a panel can never fail to draw.
 *
 * The rules a panel adds to the key rules it shares with a typing layout are
 * about its components: the panel *is* one of them, so that one must be there
 * exactly once; a component from another panel, or from a newer build, has
 * nothing to draw it here; and a few key actions — shift, caps lock, the Fn
 * layer, the chorded and timed typing keys — mean nothing on a panel and are
 * dropped rather than drawn as buttons that do nothing.
 *
 * There is no "way back" rule. The toolbar toggle and the system back button
 * both leave a panel, and two of the four shipped panels have never had an
 * abc key; a warning is enough for the two that do.
 */

/** A panel layout after the repair pass, with a line per fix. */
data class RepairedPanelLayout(val spec: PanelLayoutSpec, val repairNotes: List<LayoutMessage>)

/**
 * Key actions a panel layout will not carry: layer and case state that belongs
 * to the typing grid, and keys typed by chord or timing.
 */
fun KeyAction.isAllowedOnPanel(): Boolean = when (this) {
    KeyAction.Shift, KeyAction.CapsLock, KeyAction.Fn, KeyAction.KanaVariant,
    KeyAction.MorseDot, KeyAction.MorseDash,
    -> false
    is KeyAction.BrailleDot, is KeyAction.KeymanKey -> false
    // A layer switch of the typing grid; the panel would have to close first,
    // and a panel's own abc key already does that.
    is KeyAction.Layout -> false
    else -> true
}

/** A field cell this panel can draw: one of its own kinds, and a real one. */
private fun PanelLayoutSpec.owns(kind: PanelFieldKind): Boolean =
    kind.isReal && kind.panel == panel

/** Everything wrong with this panel layout, worst first. Never mutates. */
fun validatePanelLayout(spec: PanelLayoutSpec): List<LayoutFinding> {
    val findings = mutableListOf<LayoutFinding>()
    val rows = spec.grid.rows
    val label = spec.panel.name.lowercase()

    if (rows.all { it.isEmpty() }) {
        findings += LayoutFinding(
            LayoutSeverity.BLOCKING,
            LayoutMessage(R.string.core_lang_panel_empty_error),
        )
        return findings
    }

    val keys = rows.flatten()
    val fields = keys.mapNotNull { (it.action as? KeyAction.Field)?.kind }

    spec.panel.requiredField?.let { required ->
        if (required !in fields) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(R.string.core_lang_panel_required_field_error),
            )
        }
    }
    val duplicates = fields.filter { spec.owns(it) }.groupingBy { it }.eachCount().count { it.value > 1 }
    if (duplicates > 0) {
        findings += LayoutFinding(
            LayoutSeverity.BLOCKING,
            LayoutMessage(
                pluralsRes = R.plurals.core_lang_panel_duplicate_field_error,
                quantity = duplicates,
                args = listOf(duplicates),
            ),
        )
    }
    val foreign = fields.count { !spec.owns(it) }
    if (foreign > 0) {
        findings += LayoutFinding(
            LayoutSeverity.BLOCKING,
            LayoutMessage(
                pluralsRes = R.plurals.core_lang_panel_wrong_field_error,
                quantity = foreign,
                args = listOf(foreign),
            ),
        )
    }
    val disallowed = keys.count { !it.action.isAllowedOnPanel() }
    if (disallowed > 0) {
        findings += LayoutFinding(
            LayoutSeverity.BLOCKING,
            LayoutMessage(
                pluralsRes = R.plurals.core_lang_panel_key_not_allowed_error,
                quantity = disallowed,
                args = listOf(disallowed),
            ),
        )
    }
    // Only the panels whose shipped grid has an abc key: the clipboard and the
    // text-editing pad never had one, and a warning on the standard panel would
    // teach the user to ignore warnings.
    val shippedHasLetters = BuiltInPanelLayouts.default(spec.panel).grid.rows.flatten()
        .any { it.action == KeyAction.Letters }
    if (shippedHasLetters && keys.none { it.action == KeyAction.Letters }) {
        findings += LayoutFinding(
            LayoutSeverity.WARNING,
            LayoutMessage(R.string.core_lang_panel_no_letters_warning),
        )
    }

    // The shape rules every grid shares, with the panel's name where a typing
    // layout would name the layer.
    if (rows.size > MaxRowsPerLayer) {
        findings += LayoutFinding(
            LayoutSeverity.BLOCKING,
            LayoutMessage(
                pluralsRes = R.plurals.core_lang_layout_too_many_rows_error,
                quantity = rows.size,
                args = listOf(label, rows.size, MaxRowsPerLayer),
            ),
        )
    }
    val gridWeight = gridWeightOf(rows)
    val spanWidths = spanRowWidths(rows)
    rows.forEachIndexed { index, row ->
        val number = index + 1
        if (row.isEmpty()) {
            findings += LayoutFinding(
                LayoutSeverity.WARNING,
                LayoutMessage(R.string.core_lang_layout_empty_row_warning, args = listOf(number, label)),
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
            )
        }
        if (row.any { !it.width.isFinite() || it.width <= 0f }) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(R.string.core_lang_layout_key_no_width_error, args = listOf(number, label)),
            )
        }
        if (row.any { it.rowSpan < 1 || it.rowSpan > MaxKeySpan }) {
            findings += LayoutFinding(
                LayoutSeverity.BLOCKING,
                LayoutMessage(R.string.core_lang_layout_key_span_error, args = listOf(number, label, MaxKeySpan)),
            )
        } else if (row.any { index + it.rowSpan > rows.size }) {
            findings += LayoutFinding(
                LayoutSeverity.WARNING,
                LayoutMessage(R.string.core_lang_layout_span_past_end_warning, args = listOf(number, label)),
            )
        }
        if (spanWidths[index] > gridWeight + 0.01f) {
            findings += LayoutFinding(
                LayoutSeverity.WARNING,
                LayoutMessage(R.string.core_lang_layout_wide_row_warning, args = listOf(number, label)),
            )
        }
    }
    val blank = keys.count { it.typesNothing() }
    if (blank > 0) {
        findings += LayoutFinding(
            LayoutSeverity.WARNING,
            LayoutMessage(
                pluralsRes = R.plurals.core_lang_layout_blank_key_warning,
                quantity = blank,
                args = listOf(blank, label),
            ),
        )
    }
    val unknown = keys.count { it.action is KeyAction.Unknown }
    if (unknown > 0) {
        findings += LayoutFinding(
            LayoutSeverity.BLOCKING,
            LayoutMessage(
                pluralsRes = R.plurals.core_lang_layout_unknown_action_error,
                quantity = unknown,
                args = listOf(unknown, label),
            ),
        )
    }

    return findings.sortedByDescending { it.severity == LayoutSeverity.BLOCKING }
}

/** Whether this panel layout draws without repair. */
fun PanelLayoutSpec.canBeEnabled(): Boolean =
    validatePanelLayout(this).none { it.severity == LayoutSeverity.BLOCKING }

/**
 * Rewrites this panel layout into one the keyboard can draw, and says what it
 * changed. Every blocking finding of [validatePanelLayout] is one this clears.
 */
fun PanelLayoutSpec.repair(): RepairedPanelLayout {
    val repairs = mutableListOf<LayoutMessage>()
    val label = panel.name.lowercase()
    val seen = HashSet<PanelFieldKind>()

    var rows = grid.rows.map { row ->
        row.mapNotNull { key -> key.repairPanelKey(this, label, seen, repairs) }
    }.filter { it.isNotEmpty() }

    if (rows.isEmpty()) {
        repairs += LayoutMessage(R.string.core_lang_panel_repair_reset)
        return RepairedPanelLayout(BuiltInPanelLayouts.default(panel), repairs)
    }
    if (rows.size > MaxRowsPerLayer) {
        repairs += LayoutMessage(
            pluralsRes = R.plurals.core_lang_repair_rows_trimmed,
            quantity = rows.size,
            args = listOf(label, rows.size, MaxRowsPerLayer),
        )
        rows = rows.take(MaxRowsPerLayer)
    }
    rows = rows.map { row -> row.repairRow(label, repairs) }

    panel.requiredField?.let { required ->
        if (rows.flatten().none { (it.action as? KeyAction.Field)?.kind == required }) {
            repairs += LayoutMessage(R.string.core_lang_panel_repair_field_added)
            val cell = Key(label = "", action = KeyAction.Field(required), width = 10f)
            // A row of its own so it flexes over the height the keys leave,
            // rather than being squeezed onto the end of a fixed key row.
            rows = if (rows.size < MaxRowsPerLayer) rows + listOf(listOf(cell)) else rows.appendToLastRow(cell)
        }
    }

    // Per-row heights are positional; drop them if the row count moved.
    val heights = grid.rowHeights?.takeIf { rows.size == grid.rows.size }
    return RepairedPanelLayout(copy(grid = grid.copy(rows = rows, rowHeights = heights)), repairs)
}

/**
 * The shared key repair, then the panel's own rules: a component this panel
 * cannot draw, a second copy of one it already has, or an action that means
 * nothing here, each dropped with a note.
 */
private fun Key.repairPanelKey(
    spec: PanelLayoutSpec,
    label: String,
    seen: MutableSet<PanelFieldKind>,
    repairs: MutableList<LayoutMessage>,
): Key? {
    val fixed = repairKey(label, repairs, fieldsAllowed = true) ?: return null
    val action = fixed.action
    if (action is KeyAction.Field) {
        if (!spec.owns(action.kind)) {
            repairs += LayoutMessage(R.string.core_lang_panel_repair_field_dropped)
            return null
        }
        if (!seen.add(action.kind)) {
            repairs += LayoutMessage(R.string.core_lang_panel_repair_duplicate_dropped)
            return null
        }
        return fixed
    }
    if (!action.isAllowedOnPanel()) {
        repairs += LayoutMessage(R.string.core_lang_panel_repair_key_dropped)
        return null
    }
    return fixed
}
