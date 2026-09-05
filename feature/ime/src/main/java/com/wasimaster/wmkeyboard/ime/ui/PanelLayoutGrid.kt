package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeySlot
import com.wasimaster.wmkeyboard.core.layout.LayoutAppearance
import com.wasimaster.wmkeyboard.core.layout.MaxRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.MinRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.drawnFontScale
import com.wasimaster.wmkeyboard.core.layout.gridWeightOf
import com.wasimaster.wmkeyboard.core.layout.panelFlexRows
import com.wasimaster.wmkeyboard.core.layout.panelRowTops
import com.wasimaster.wmkeyboard.core.layout.rowScaledKeyHeight
import com.wasimaster.wmkeyboard.core.layout.spanSlots
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import kotlin.math.roundToInt

/**
 * A panel layout drawn into the key area: ordinary keys as real [KeyButton]s
 * — theme face, haptics, hold-to-repeat, alternates popup, TalkBack — and the
 * panel's components in their [KeyAction.Field] cells (issue #63).
 *
 * A hand-placed [Layout] in the shape of `KeyBand`, because a component and a
 * spanning key alike belong to more than one row. Row heights come from
 * [panelRowTops]: key rows at key height, component rows sharing the rest, so
 * the grid fills exactly the height its host gives it and the keyboard window
 * never moves when a panel opens. The host must give it a bounded height.
 *
 * Deliberately none of the key grid's board-level pointer handlers: no glide,
 * no smart hit remap, no handwriting, no modifier chord. Each key owns its
 * cell, and a component owns every gesture inside its own.
 */
@Composable
internal fun PanelLayoutGrid(
    state: KeyboardUiState,
    spec: PanelLayoutSpec,
    callbacks: PanelLayoutCallbacks,
    /** Leaves the panel; what a key bound to [KeyAction.Letters] does here. */
    onClose: () -> Unit,
    /** Draws the component for a field cell, filling the cell it is given. */
    fields: @Composable (PanelFieldKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val kb = LocalKbTheme.current
    val palette = remember(kb) { kb.keyPalette() }
    val rows = spec.grid.rows
    if (rows.isEmpty()) return
    val gridWeight = gridWeightOf(rows).takeIf { it > 0f } ?: 10f
    // The grid's own label size, over the layout-wide one, exactly as a typing
    // layer's is resolved.
    val fontScale = LayoutAppearance(
        fontId = spec.appearance?.fontId,
        fontScale = spec.grid.fontScale ?: spec.appearance?.fontScale,
    ).drawnFontScale()

    // One lambda for the grid's life, so every key sees the same value and a
    // recomposition of the grid does not restart every key's pointer input.
    val onPanelKey = remember(callbacks, onClose) {
        { key: Key -> routePanelKey(key, callbacks.onKey, onClose) }
    }

    // Everything a key's face reads, as `rememberKeyGrid` lists it: a keystroke
    // that changes none of these leaves every key identical and skipped.
    val slots = remember(
        spec, palette, settings, fontScale,
        state.shiftState, state.modifiers, state.effectiveEnterAction,
        state.enterActionLabel, state.language, state.script,
        state.composer.isClusterShaping, state.vowelForm, state.layoutId,
        state.activeFancyStyleId, state.selectingText,
    ) {
        spanSlots(rows, gridWeight).map { slot ->
            PanelSlot(
                slot = slot,
                field = (slot.key.action as? KeyAction.Field)?.kind,
                visual = if (slot.key.action is KeyAction.Field) null else keyVisual(slot.key, state, palette, fontScale),
            )
        }
    }
    val flex = remember(rows) { panelFlexRows(rows) }
    val weights = remember(spec) {
        FloatArray(rows.size) {
            (spec.grid.rowHeights?.getOrNull(it) ?: 1f).coerceIn(MinRowHeightScale, MaxRowHeightScale)
        }
    }
    val gapV = keyGapV(settings)
    val gapH = keyGapH(settings)
    val rowHeightsDp = remember(spec, settings.keyHeightDp) {
        IntArray(rows.size) { rowScaledKeyHeight(settings.keyHeightDp, spec.grid.rowHeights?.getOrNull(it)) }
    }
    val density = LocalDensity.current
    val fixedPx = remember(rowHeightsDp, gapV, density) {
        with(density) { IntArray(rows.size) { (rowHeightsDp[it].dp + gapV * 2).roundToPx() } }
    }

    val keyPreview = remember { KeyPreviewState() }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .padding(horizontal = KeyRowsPadHorizontal, vertical = KeyRowsPadVertical)
            .onGloballyPositioned {
                boxOrigin = it.positionInRoot()
                boxSize = it.size
            },
    ) {
        Layout(
            content = {
                for (panelSlot in slots) {
                    val field = panelSlot.field
                    if (field != null) {
                        // Inset by the key gaps so the component's edges line up
                        // with the faces of the keys beside it.
                        Box(Modifier.padding(horizontal = gapH, vertical = gapV)) { fields(field) }
                    } else {
                        KeyButton(
                            visual = panelSlot.visual!!,
                            settings = settings,
                            onKey = onPanelKey,
                            onText = callbacks.onText,
                            onCursorMove = callbacks.onCursorMove,
                            onLayoutSelect = callbacks.onLayoutSelect,
                            // What this key would be on a row of its own; the
                            // measure policy below hands it the real height.
                            heightDp = rowHeightsDp[panelSlot.slot.row],
                            layoutId = state.layoutId,
                            keyPreview = keyPreview,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val tops = panelRowTops(fixedPx, weights, flex, constraints.maxHeight)
            val unit = if (gridWeight > 0f) width / gridWeight else 0f
            val lefts = IntArray(measurables.size)
            val placeables = measurables.mapIndexed { index, measurable ->
                val slot = slots[index].slot
                // Each edge rounded on its own, so neighbours stay flush.
                val left = (unit * slot.x).roundToInt()
                val right = (unit * slot.end).roundToInt()
                lefts[index] = left
                val last = (slot.row + slot.span).coerceAtMost(rows.size)
                measurable.measure(
                    Constraints.fixed(
                        width = (right - left).coerceIn(0, width),
                        height = (tops[last] - tops[slot.row]).coerceAtLeast(0),
                    ),
                )
            }
            layout(width, tops.last()) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(lefts[index], tops[slots[index].slot.row])
                }
            }
        }
        KeyPreviewOverlay(keyPreview, settings, boxOrigin, boxSize)
    }
}

/** One cell of a panel grid: the key's resolved face, or the component it hosts. */
private class PanelSlot(
    val slot: KeySlot,
    val field: PanelFieldKind?,
    val visual: KeyVisual?,
)

/**
 * Where a panel key's press goes. `abc` ([KeyAction.Letters]) leaves the panel
 * — that is what it has always meant on the emoji panel's bottom row — rather
 * than reaching the service, whose Letters handler switches the typing grid's
 * layer and must keep doing exactly that under an emoji *search*, where the
 * real key rows sit below the panel. A component's cell never dispatches.
 */
internal fun routePanelKey(key: Key, onKey: (Key) -> Unit, onClose: () -> Unit) {
    when (key.action) {
        KeyAction.Letters -> onClose()
        is KeyAction.Field -> Unit
        else -> onKey(key)
    }
}
