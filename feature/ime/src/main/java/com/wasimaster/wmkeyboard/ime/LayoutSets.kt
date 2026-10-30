package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.keyman.KeymanLayers
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.forScreen
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.compileNamed
import com.wasimaster.wmkeyboard.core.layout.expandForTablet
import com.wasimaster.wmkeyboard.core.layout.fillRowFor
import com.wasimaster.wmkeyboard.core.layout.arrowRowFor
import com.wasimaster.wmkeyboard.core.layout.gridForTelevision
import com.wasimaster.wmkeyboard.core.layout.isShippedLayoutId
import com.wasimaster.wmkeyboard.core.layout.numberRowFor
import com.wasimaster.wmkeyboard.core.layout.panelLayers
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.layout.tabletGridWidth
import com.wasimaster.wmkeyboard.core.layout.televisionGridColumns
import com.wasimaster.wmkeyboard.core.layout.withQwertyHolds
import com.wasimaster.wmkeyboard.core.settings.DeviceForm

/**
 * The grids the keyboard draws for [spec], repaired and compiled for a field of
 * [fieldKind] on a screen of [form].
 *
 * One function for every host of [com.wasimaster.wmkeyboard.ime.ui.KeyboardScreen]:
 * the service, which caches the result per field, and the settings app's layout
 * previews, which have to draw exactly what the service would. A second copy of
 * this in the settings app would be a preview that drifts from the keyboard the
 * first time either side learns a new layer.
 *
 * [secondaries] rides along by reference (see [compileSecondaryGrids]), and
 * [television] swaps the tablet widening for the remote-friendly grid.
 */
fun compileLayoutSet(
    spec: LayoutSpec,
    fieldKind: FieldKind,
    form: DeviceForm,
    numberRowShown: Boolean,
    secondaries: Map<String, KeyboardLayout>,
    television: Boolean,
    landscape: Boolean = false,
): LayoutSet {
    val safe = spec.repair().spec
    val compiled = safe.compile(LayoutLayer.LETTERS).forScreen(landscape)
    val authored = authoredHolds(spec, compiled)
    val letters = compiled.withQwertyHolds(keep = authored)
    // Only the letters layer widens. The symbols and Fn layers have no shift
    // key and so decline on their own, and the numeric keypads must never be
    // stretched to twelve columns — a four-column PIN pad at that width is
    // not a keypad any more.
    // A television takes the grid transform instead of the tablet one, even
    // though its screen reports as a large tablet's: the two want opposite
    // things. A tablet widens the board so two hands can reach more keys at
    // once; a remote wants the *fewest* presses between keys, which is an
    // even rectangle. `tabletExpand` gates both — a layout whose geometry is
    // authored (T9, the kana pads) says no to being rebuilt at all.
    val reflow = television && safe.tabletExpand && televisionGridColumns(letters) != null
    val expand = !reflow && form.isTablet && safe.tabletExpand
    val gridWidth = if (expand) tabletGridWidth(letters, form) else null
    return LayoutSet(
        letters = when {
            reflow -> letters.gridForTelevision()
            gridWidth != null -> letters.expandForTablet(form, numberRowShown)
            else -> letters
        },
        symbols = safe.compile(LayoutLayer.SYMBOLS).forScreen(landscape),
        symbolsShifted = safe.compile(LayoutLayer.SYMBOLS_SHIFTED).forScreen(landscape),
        // Only when the layout actually defines one: compile() falls back
        // to the shipped grid for a missing layer, which would give every
        // layout an Fn layer that is really a second copy of the letters.
        fn = safe.layer(LayoutLayer.FN)?.let { safe.compile(LayoutLayer.FN) },
        numeric = fieldKind.numericLayer?.let(safe::compile),
        numericAuthored = fieldKind.numericLayer?.let(safe::layer) != null,
        // Same "only when authored" rule as Fn: the Numpad panel draws its
        // own hardcoded pad otherwise, with the calculator-order setting.
        number = safe.layer(LayoutLayer.NUMBER)?.let { safe.compile(LayoutLayer.NUMBER) },
        // The layout's own panel grids, already through the panel repair
        // as part of `safe`. Not through the panel-layout cache: they are
        // this layout's, so they live and die with its set.
        panels = safe.panelLayers.mapValues { (kind, grid) ->
            PanelLayoutSpec(kind, grid, appearance = safe.appearance)
        },
        numberRows = buildMap {
            safe.numberRowFor(LayoutLayer.LETTERS)?.let { put(LayoutMode.LETTERS, it) }
            safe.numberRowFor(LayoutLayer.SYMBOLS)?.let { put(LayoutMode.SYMBOLS, it) }
            safe.numberRowFor(LayoutLayer.SYMBOLS_SHIFTED)
                ?.let { put(LayoutMode.SYMBOLS_SHIFTED, it) }
            safe.numberRowFor(LayoutLayer.FN)?.let { put(LayoutMode.FN, it) }
        },
        symbolsFillRow = safe.fillRowFor(LayoutLayer.SYMBOLS),
        arrowRows = buildMap {
            safe.arrowRowFor(LayoutLayer.LETTERS)?.let { put(LayoutMode.LETTERS, it) }
            safe.arrowRowFor(LayoutLayer.SYMBOLS)?.let { put(LayoutMode.SYMBOLS, it) }
            safe.arrowRowFor(LayoutLayer.SYMBOLS_SHIFTED)?.let { put(LayoutMode.SYMBOLS_SHIFTED, it) }
            safe.arrowRowFor(LayoutLayer.FN)?.let { put(LayoutMode.FN, it) }
        },
        gridWidth = gridWidth,
        secondaries = secondaries,
        themeId = safe.themeId,
        keymanShift = safe.compileNamed(KeymanLayers.SHIFT),
        keymanCaps = safe.compileNamed(KeymanLayers.CAPS),
        // Every grid this layout defines that is not one of the fixed layers:
        // a converted Keyman layout's further pages, and the pages a paginated
        // layout reaches with a `KeyAction.LayerSwitch` key (issue #498). Both
        // are drawn by `LayoutMode.NAMED`, so both are compiled here. Keyman's
        // shift and caps pages are left out because they are our shift state
        // rather than places, and have their own slots above.
        named = safe.layers.keys
            .filter { it.isAnExtraGrid() }
            .mapNotNull { name -> safe.compileNamed(name)?.let { name to it } }
            .toMap(),
        keymanLayerKeys = safe.layers.keys.takeIf { keys ->
            safe.keyman != null || keys.any { it.startsWith(KeymanLayers.PREFIX) }
        },
        handwriting = safe.handwriting,
        authoredHolds = authored,
    )
}

/**
 * The letter keys whose long-press list the user wrote, which the keyboard
 * then draws in the order written (issue #622). Every key of a layout of the
 * user's own; on an edit of a shipped layout, only the keys whose popup no
 * longer matches the shipped key typing the same text. Empty for a shipped
 * layout as it ships.
 *
 * A key with no popup counts too: a popup left empty, or emptied, is one the
 * user wrote, and [withQwertyHolds] must not lend it QWERTY's symbol (an @
 * nobody put on their own layout's a, reported in the Telegram group).
 *
 * Compared against the shipped grid rather than recorded by the editor, so an
 * edit made before this existed, an imported file and a hand-written JSON
 * layout all count the same way.
 */
fun authoredHolds(spec: LayoutSpec, letters: KeyboardLayout): Set<Key> {
    val keys = letters.rows.flatten().filter { it.action == KeyAction.Text }
    if (keys.isEmpty()) return emptySet()
    val shipped = if (isShippedLayoutId(spec.id)) {
        BuiltInLayouts.byId(spec.id) ?: AssetLayouts.byId(spec.id)
    } else {
        null
    }
    if (shipped == spec) return emptySet()
    if (shipped == null) return keys.toSet()
    val shippedHolds = shipped.repair().spec.compile(LayoutLayer.LETTERS).rows.flatten()
        .associate { (it.output ?: it.label) to it.longPress }
    return keys.filterTo(HashSet()) { shippedHolds[it.output ?: it.label] != it.longPress }
}

/** The layer keys that already have a slot of their own on [LayoutSet]. */
private val RESERVED_LAYER_KEYS: Set<String> =
    LayoutLayer.entries.mapTo(mutableSetOf()) { it.key }
        .apply { addAll(PanelKind.entries.map { it.layerKey }) }
        .apply {
            add(KeymanLayers.SHIFT)
            add(KeymanLayers.CAPS)
        }

/**
 * Whether this layer key names a grid [LayoutSet.named] should hold: one of the
 * layout's own further pages, rather than a fixed layer, a panel or Keyman's
 * shift and caps grids.
 */
private fun String.isAnExtraGrid(): Boolean = this !in RESERVED_LAYER_KEYS

/**
 * The user's secondary layouts (issue #62), each compiled to its letters grid,
 * by id. Callers cache the map against the custom list's identity: every
 * [LayoutSet] shares the one instance, which is how a cached set can tell an
 * unchanged secondary list from a re-decoded one.
 */
fun compileSecondaryGrids(customs: List<LayoutSpec>): Map<String, KeyboardLayout> =
    secondaryLayouts(customs).associate { spec ->
        spec.id to spec.repair().spec.compile(LayoutLayer.LETTERS)
    }
