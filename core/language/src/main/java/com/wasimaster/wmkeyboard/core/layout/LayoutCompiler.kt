package com.wasimaster.wmkeyboard.core.layout

/**
 * The runtime grid for [layer].
 *
 * A layer this layout does not define falls back to the default layout's, so a
 * custom layout that only rearranges the letters still gets the shipped symbol
 * layers and the five numeric pads without restating three hundred keys. The
 * last fallback is the default's letter grid: a layer that resolves to nothing
 * would draw a blank keyboard with no way for the user to get back out.
 *
 * Results are cached because this runs on every recomposition, and rebuilding
 * forty [Key] objects per frame is exactly the allocation churn the old
 * singleton `Layouts` object avoided by accident. Cache validity is structural
 * equality against the spec that produced the entry, not reference identity —
 * a settings emission re-decodes the stored JSON into fresh instances every
 * time, so an identity check would never hit.
 */
fun LayoutSpec.compile(layer: LayoutLayer): KeyboardLayout = synchronized(compileCache) {
    val cacheKey = id to layer
    compileCache[cacheKey]?.let { (spec, built) -> if (spec == this) return built }
    // Resolve the whole layer (not just its rows) so per-row heights travel with
    // the grid through the same fallback chain.
    val resolved = layer(layer)
        ?: BuiltInLayouts.default.layer(layer)
        ?: BuiltInLayouts.default.layer(LayoutLayer.LETTERS)
            ?: error("The default layout has no LETTERS layer")
    val built = KeyboardLayout(
        name = "$id/${layer.key}",
        rows = resolved.rows,
        rowHeights = resolved.rowHeights,
        // From this layout, never from whichever layout the *grid* was inherited
        // from: the appearance belongs to the board the user is typing on, so a
        // custom letters layer with a borrowed symbols page keeps one font
        // across both.
        //
        // The layer's own label size is folded in here rather than carried
        // beside the layout's, so everything downstream reads one number and
        // cannot resolve the pair differently. This is also what makes the
        // per-layer size work at all: the renderer is handed the compiled grid
        // for the layer on screen, so the number arrives already correct for it,
        // and pressing ?123 changes it without anything having to be told.
        appearance = appearanceFor(resolved),
        persistent = resolved.persistent,
    )
    compileCache[cacheKey] = this to built
    built
}

/**
 * This layout's appearance as it applies to one compiled layer: the layout's
 * own, with [LayerSpec.fontScale] standing in for its label size where the layer
 * sets one.
 *
 * A layer that sets a size on a layout that sets nothing else still produces an
 * appearance, so "the layout says nothing" and "this layer says something" are
 * both expressible.
 */
private fun LayoutSpec.appearanceFor(layer: LayerSpec): LayoutAppearance? {
    val layerScale = layer.fontScale ?: return appearance
    return (appearance ?: LayoutAppearance()).copy(fontScale = layerScale)
}

/** The number row this layout shows above [layer], or null to use the default. */
fun LayoutSpec.numberRowFor(layer: LayoutLayer): List<Key>? = layer(layer)?.numberRow

private val compileCache =
    HashMap<Pair<String, LayoutLayer>, Pair<LayoutSpec, KeyboardLayout>>()

/**
 * The compiled built-in grids under the names the keyboard view has always used.
 *
 * Getters rather than vals so an edited built-in is picked up; the compile cache
 * keeps them to a map lookup, and a data class `equals` short-circuits on
 * identity, so a built-in never pays for the structural comparison.
 *
 * This is scaffolding. Once the service resolves the active layout into a
 * `LayoutSet` these call sites take their grids from that instead, and the only
 * ones left will be the two that genuinely want a built-in without a spec in
 * hand — the number row borrowing the symbol layer's first row, and the numeric
 * keypads picked by field kind.
 */
object Layouts {
    private val default: LayoutSpec get() = BuiltInLayouts.default

    val QWERTY: KeyboardLayout get() = BuiltInLayouts.QWERTY.compile(LayoutLayer.LETTERS)
    val AZERTY: KeyboardLayout get() = BuiltInLayouts.AZERTY.compile(LayoutLayer.LETTERS)
    val DVORAK: KeyboardLayout get() = BuiltInLayouts.DVORAK.compile(LayoutLayer.LETTERS)
    val QWERTZ: KeyboardLayout get() = BuiltInLayouts.GERMAN.compile(LayoutLayer.LETTERS)
    val SPANISH_QWERTY: KeyboardLayout get() = BuiltInLayouts.SPANISH.compile(LayoutLayer.LETTERS)
    val PROBHAT: KeyboardLayout get() = BuiltInLayouts.PROBHAT.compile(LayoutLayer.LETTERS)
    val JATIYA: KeyboardLayout get() = BuiltInLayouts.JATIYA.compile(LayoutLayer.LETTERS)

    val SYMBOLS: KeyboardLayout get() = default.compile(LayoutLayer.SYMBOLS)
    val SYMBOLS_SHIFTED: KeyboardLayout get() = default.compile(LayoutLayer.SYMBOLS_SHIFTED)
    val NUMBER: KeyboardLayout get() = default.compile(LayoutLayer.NUMBER)
    val PHONE: KeyboardLayout get() = default.compile(LayoutLayer.PHONE)
    val DATE: KeyboardLayout get() = default.compile(LayoutLayer.DATE)
    val TIME: KeyboardLayout get() = default.compile(LayoutLayer.TIME)
    val DATETIME: KeyboardLayout get() = default.compile(LayoutLayer.DATETIME)
}
