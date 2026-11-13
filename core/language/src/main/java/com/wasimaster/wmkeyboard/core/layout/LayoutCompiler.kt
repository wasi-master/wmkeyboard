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
fun LayoutSpec.compile(
    layer: LayoutLayer,
    /**
     * The page to borrow for [LayoutLayer.SYMBOLS] when this layout has none of
     * its own. Ignored for every other layer, and for a layout that wrote its
     * own symbols page.
     */
    symbolsPage: SymbolsPage = SymbolsPage.STANDARD,
): KeyboardLayout = synchronized(compileCache) {
    val cacheKey = CompileKey(id, layer, symbolsPage.takeIf { layer == LayoutLayer.SYMBOLS })
    compileCache[cacheKey]?.let { (spec, built) -> if (spec == this) return built }
    // Resolve the whole layer (not just its rows) so per-row heights travel with
    // the grid through the same fallback chain.
    val numberPad = layer == LayoutLayer.SYMBOLS && symbolsPage == SymbolsPage.NUMBER_PAD &&
        borrowsSymbolsPage()
    val own = layer(layer)?.takeUnless { numberPad }
    val resolved = own
        ?: NumberPadSymbols.layer.takeIf { numberPad }
        ?: BuiltInLayouts.default.layer(layer)
        ?: BuiltInLayouts.default.layer(LayoutLayer.LETTERS)
            ?: error("The default layout has no LETTERS layer")
    // A borrowed symbols page shows the layout's own currency where the shipped
    // one has `$`, as Gboard's does: ₹ under Hindi, ৳ under Bangla. A symbols
    // page the layout wrote itself is drawn as written. Japanese and Chinese
    // also get their own punctuation on both borrowed pages (issue #610).
    val rows = when {
        own != null -> resolved.rows
        layer == LayoutLayer.SYMBOLS -> withCjkPunctuation(withLocalCurrency(resolved.rows, langId), layer, langId)
        layer == LayoutLayer.SYMBOLS_SHIFTED -> withCjkPunctuation(resolved.rows, layer, langId)
        else -> resolved.rows
    }
    val built = KeyboardLayout(
        name = "$id/${layer.key}",
        borrowedSymbols = own == null && layer == LayoutLayer.SYMBOLS,
        // The spacebar absorbs whatever the bottom row is short of the grid, so
        // a board whose letters are eleven or twelve columns wide does not draw
        // its `?123` and Enter floating in from the edges. Done here rather than
        // at the row's draw so the keyboard, the theme preview and the layout
        // editor's preview all measure the same grid.
        rows = fillSpaceRows(rows, gridWeightOf(rows)),
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
        bottomRowAsLaidOut = resolved.bottomRowAsLaidOut,
        themeId = resolved.themeId ?: themeId,
        flickHints = resolved.flickHints,
        keymanFrames = resolved.keymanFrames.orEmpty(),
        direct = resolved.direct,
        digitPad = layer == LayoutLayer.SYMBOLS && carriesDigitPad(rows),
        // Only a grid this layout wrote: a borrowed page keeps its rows.
        hex = own != null && drawsHex(own),
    )
    compileCache[cacheKey] = this to built
    built
}

/**
 * The runtime grid for the layer keyed [name] — one of a layout's own extra
 * layers, which have no [LayoutLayer] of their own: a converted Keyman layout's
 * further pages, or the pages a paginated layout reaches with a
 * [KeyAction.LayerSwitch] key (issue #498) — or null when this layout does not
 * define it. No fallback: a layer the layout does not have is not one to draw a
 * borrowed grid for.
 */
fun LayoutSpec.compileNamed(name: String): KeyboardLayout? = synchronized(namedCompileCache) {
    val resolved = layers[name] ?: return null
    val cacheKey = id to name
    namedCompileCache[cacheKey]?.let { (spec, built) -> if (spec == this) return built }
    val built = KeyboardLayout(
        name = "$id/$name",
        rows = fillSpaceRows(resolved.rows, gridWeightOf(resolved.rows)),
        rowHeights = resolved.rowHeights,
        appearance = appearanceFor(resolved),
        persistent = resolved.persistent,
        bottomRowAsLaidOut = resolved.bottomRowAsLaidOut,
        themeId = resolved.themeId ?: themeId,
        flickHints = resolved.flickHints,
        keymanFrames = resolved.keymanFrames.orEmpty(),
        direct = resolved.direct,
        hex = drawsHex(resolved),
    )
    namedCompileCache[cacheKey] = this to built
    built
}

private val namedCompileCache = HashMap<Pair<String, String>, Pair<LayoutSpec, KeyboardLayout>>()

/** Whether [layer] of this layout is a honeycomb: its own [LayerSpec.grid], else the layout's. */
private fun LayoutSpec.drawsHex(layer: LayerSpec): Boolean = isHexGrid(layer.grid ?: grid)

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

/**
 * [rows] with the `$` key showing [langId]'s own currency, `$` first on its
 * hold and the rest of the hold as it was. Unchanged for a language that writes
 * dollars or that has no entry in [LocalCurrencies].
 */
internal fun withLocalCurrency(rows: List<List<Key>>, langId: String): List<List<Key>> {
    val local = LocalCurrencies[langId.substringBefore('_')] ?: return rows
    return rows.map { row -> row.map { it.showingCurrency(local) } }
}

/**
 * This key showing [local] if it is a plain `$` key, with `$` first on its hold
 * and the rest of the hold as it was. Any other key comes back as it is.
 */
fun Key.showingCurrency(local: String): Key =
    if (label == "$" && output == null && action == KeyAction.Text) {
        copy(label = local, longPress = listOf("$") + longPress.filter { it != local && it != "$" })
    } else {
        this
    }

/**
 * This key with [local] leading its hold if `$` does, `$` right behind it: the
 * letter keys' version of [showingCurrency]. A hold that leads with anything
 * else already says what it means, as AZERTY's € before $ on d does.
 */
fun Key.holdingCurrency(local: String): Key =
    if (longPress.firstOrNull() == "$") {
        copy(longPress = listOf(local, "$") + longPress.drop(1).filter { it != local })
    } else {
        this
    }

/**
 * The currency [langId] leads with on a phone in [regions], most telling region
 * first, or null where that is `$`. A language with one currency of its own
 * ignores the regions. English, Spanish and Portuguese go by the first region
 * they have an answer for.
 */
fun localCurrencyFor(langId: String, regions: List<String>): String? {
    val lang = langId.substringBefore('_')
    LocalCurrencies[lang]?.let { return it }
    val regional = RegionalCurrencies[lang] ?: return null
    val symbol = regions.firstNotNullOfOrNull { regional.byRegion[it] } ?: regional.elsewhere
    return symbol.takeIf { it != "$" }
}

/** Whether [key] is a `$` key [withLocalCurrency] turned into a local currency. */
fun isLocalCurrencyKey(key: Key): Boolean =
    key.output == null && key.action == KeyAction.Text && key.label in LocalCurrencySymbols

private val LocalCurrencySymbols: Set<String> by lazy {
    LocalCurrencies.values.toSet() +
        RegionalCurrencies.values.flatMap { it.byRegion.values + it.elsewhere } - "$"
}

/**
 * The currency a language's symbols page leads with, by base language id. Only
 * where one currency is plainly the language's own; a language written across
 * several currencies goes by the phone's region in [RegionalCurrencies], or keeps
 * `$` if it is not there either (Arabic).
 */
private val LocalCurrencies: Map<String, String> = buildMap {
    for (lang in listOf("hi", "mr", "gu", "pa", "or", "ta", "te", "kn", "ml", "as", "sa", "kok", "mai", "bho", "doi", "mni", "brx", "sat")) {
        put(lang, "₹")
    }
    put("bn", "৳")
    for (lang in listOf("de", "fr", "it", "nl", "el", "fi", "et", "lv", "lt", "sk", "sl", "mt", "ga", "lb", "eu", "ca", "gl", "br", "co", "fy", "hr", "bg")) {
        put(lang, "€")
    }
    put("ru", "₽")
    put("uk", "₴")
    put("tr", "₺")
    put("ko", "₩")
    put("ja", "¥")
    put("zh", "¥")
    put("vi", "₫")
    put("th", "฿")
    put("he", "₪")
    put("pl", "zł")
    put("kk", "₸")
    put("mn", "₮")
    put("km", "៛")
    put("lo", "₭")
    put("hy", "֏")
    put("ka", "₾")
    put("az", "₼")
    put("si", "රු")
    put("ne", "रु")
}

/** What a language written across several currencies leads with; see [localCurrencyFor]. */
private class RegionalCurrency(val byRegion: Map<String, String>, val elsewhere: String)

/**
 * `$` regions are listed too, so a US SIM answers before the time zone of the
 * country it is visiting. Portuguese and Spanish map only the regions that write
 * them, and elsewhere is €: someone in Britain typing Portuguese means euros,
 * not pounds.
 */
private val RegionalCurrencies: Map<String, RegionalCurrency> = mapOf(
    "en" to RegionalCurrency(
        buildMap {
            for (region in listOf("US", "CA", "AU", "NZ", "SG", "HK", "PR", "JM", "TT", "BS", "BB", "BZ", "GY", "FJ", "LR")) {
                put(region, "$")
            }
            for (region in listOf("GB", "IM", "JE", "GG", "GI")) put(region, "£")
            for (region in listOf(
                "IE", "MT", "CY", "DE", "FR", "ES", "IT", "NL", "BE", "AT", "PT", "GR",
                "FI", "LU", "EE", "LV", "LT", "SK", "SI", "HR", "BG",
            )) {
                put(region, "€")
            }
            put("IN", "₹")
            put("PK", "₨")
            put("BD", "৳")
            put("NG", "₦")
            put("GH", "₵")
            put("PH", "₱")
            put("ZA", "R")
            put("IL", "₪")
            put("JP", "¥")
            put("CN", "¥")
            put("KR", "₩")
            put("TR", "₺")
            put("RU", "₽")
            put("UA", "₴")
            put("TH", "฿")
            put("VN", "₫")
        },
        elsewhere = "$",
    ),
    "pt" to RegionalCurrency(mapOf("BR" to "R$", "PT" to "€"), elsewhere = "€"),
    "es" to RegionalCurrency(
        buildMap {
            for (region in listOf("MX", "AR", "CO", "CL", "UY", "DO", "CU", "EC", "SV", "PA", "PR", "US", "CA")) {
                put(region, "$")
            }
            put("ES", "€")
            put("PE", "S/")
            put("BO", "Bs")
            put("VE", "Bs")
            put("GT", "Q")
            put("HN", "L")
            put("NI", "C$")
            put("PY", "₲")
            put("CR", "₡")
        },
        elsewhere = "€",
    ),
)

/** The number row this layout shows above [layer], or null to use the default. */
fun LayoutSpec.numberRowFor(layer: LayoutLayer): List<Key>? = layer(layer)?.numberRow

/** The row this layout draws in place of [layer]'s digit row, or null for the default. */
fun LayoutSpec.fillRowFor(layer: LayoutLayer): List<Key>? = layer(layer)?.fillRow

/** The arrow keys row this layout draws under [layer], or null for the setting's arrows. */
fun LayoutSpec.arrowRowFor(layer: LayoutLayer): List<Key>? = layer(layer)?.arrowRow

/**
 * Whether [rows] opens with a row of plain digit keys: the row the symbols
 * layer gives up to its fill row while the number row shows the same digits.
 * Only then, so a custom symbols layer that leads with something else keeps
 * its top row.
 */
fun leadsWithDigitRow(rows: List<List<Key>>): Boolean =
    rows.firstOrNull()?.all { key ->
        val text = key.output ?: key.label
        key.action == KeyAction.Text && text.length == 1 && text[0].isDigit()
    } ?: false

/** What [compile] caches a grid under; [symbolsPage] is null for every layer but the symbols page. */
private data class CompileKey(val id: String, val layer: LayoutLayer, val symbolsPage: SymbolsPage?)

private val compileCache =
    HashMap<CompileKey, Pair<LayoutSpec, KeyboardLayout>>()

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
    val BSTI: KeyboardLayout get() = BuiltInLayouts.BSTI.compile(LayoutLayer.LETTERS)

    val SYMBOLS: KeyboardLayout get() = default.compile(LayoutLayer.SYMBOLS)
    val SYMBOLS_SHIFTED: KeyboardLayout get() = default.compile(LayoutLayer.SYMBOLS_SHIFTED)
    val NUMBER: KeyboardLayout get() = default.compile(LayoutLayer.NUMBER)
    val PHONE: KeyboardLayout get() = default.compile(LayoutLayer.PHONE)
    val DATE: KeyboardLayout get() = default.compile(LayoutLayer.DATE)
    val TIME: KeyboardLayout get() = default.compile(LayoutLayer.TIME)
    val DATETIME: KeyboardLayout get() = default.compile(LayoutLayer.DATETIME)
}
