package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec

/**
 * The read-side folding of the two settings the panel layouts replaced.
 *
 * Before issue #63 the text-editing pad had its own grid type ([TextEditLayout],
 * stored under `text_edit_layout`) and the clipboard panel had a boolean
 * (`clipboard_bottom_row`) that switched its abc / space / backspace row on.
 * Both are now panel layouts. Rather than a one-shot migration behind a flag,
 * the repository folds the old keys in on every read while no panel layout of
 * that kind is stored — the same shape as `readOneHandedProfile` and the
 * legacy AI-prompt merge — and the first write of that kind's layout clears
 * the old key. That also covers a settings backup taken on an older build and
 * restored onto this one: the old key comes back, and is folded in again.
 *
 * Pure, so the rule is testable without a DataStore.
 */
fun foldLegacyPanelPrefs(
    stored: List<PanelLayoutSpec>,
    legacyTextEdit: TextEditLayout?,
    legacyClipboardBottomRow: Boolean?,
): List<PanelLayoutSpec> {
    val have = stored.mapTo(HashSet()) { it.panel }
    return stored + listOfNotNull(
        legacyTextEdit
            ?.takeIf { PanelKind.TEXT_EDIT !in have }
            ?.let(::migrateTextEditLayout),
        BuiltInPanelLayouts.clipboard(bottomRow = true)
            .takeIf { PanelKind.CLIPBOARD !in have && legacyClipboardBottomRow == true },
    )
}

/**
 * The old text-editing grid as a panel layout, key for key.
 *
 * A [TextEditKey] becomes a [Key] whose action is [KeyAction.Edit]; its
 * [TextEditKey.longPress] becomes a long-press alternate, except on a key
 * whose action repeats, where the old panel never read it and the new one
 * never opens the popup (see `holdIsSpokenFor`). Widths, spans and the
 * per-row heights carry straight over: both grids measure them the same way.
 */
fun migrateTextEditLayout(legacy: TextEditLayout): PanelLayoutSpec = PanelLayoutSpec(
    panel = PanelKind.TEXT_EDIT,
    grid = LayerSpec(
        rows = legacy.rows.map { row ->
            row.map { key ->
                Key(
                    label = "",
                    action = KeyAction.Edit(key.action),
                    width = key.width,
                    rowSpan = key.rowSpan,
                    actionAlternates = key.longPress
                        ?.takeUnless { key.action.repeats || it == key.action }
                        ?.let { listOf(KeyAlternate(KeyAction.Edit(migratedHold(key.action, it)))) }
                        .orEmpty(),
                )
            }
        },
        rowHeights = legacy.rowHeights,
    ),
)

/**
 * The old pad's shipped pairing sent Home and End to the ends of the *text*
 * with [TextEditAction.PAGE_UP] / [TextEditAction.PAGE_DOWN], which then meant
 * exactly that. Issue #59 gave the ends of the text their own operations and
 * made Page Up a real page, so that pairing keeps its meaning by moving to
 * them; any other hold carries over as written.
 */
private fun migratedHold(action: TextEditAction, hold: TextEditAction): TextEditAction = when {
    action == TextEditAction.HOME && hold == TextEditAction.PAGE_UP -> TextEditAction.DOC_START
    action == TextEditAction.END && hold == TextEditAction.PAGE_DOWN -> TextEditAction.DOC_END
    else -> hold
}
