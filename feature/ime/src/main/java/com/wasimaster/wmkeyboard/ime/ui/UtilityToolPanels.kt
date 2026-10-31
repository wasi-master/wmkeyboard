package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.tools.CalcEngine
import com.wasimaster.wmkeyboard.core.tools.CompoundUnits
import com.wasimaster.wmkeyboard.core.tools.CryptoCatalog
import com.wasimaster.wmkeyboard.core.tools.CurrencyClient
import com.wasimaster.wmkeyboard.core.tools.SymbolCatalog
import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.core.tools.ToolPrefill
import com.wasimaster.wmkeyboard.core.tools.UnitConvert
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.CurrencyUi
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

// Shared building blocks for the utility tool panels (symbols, calculator,
// unit & currency converters): a pill chip and a compact keypad button.

@Composable
internal fun ToolPanelChip(
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Drawn in place of the words, which are then what TalkBack reads.
    icon: ImageVector? = null,
    // What holding the chip says while it is greyed out; see [IconHint].
    disabledReason: DisabledReason? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    // Dimmed by scaling whatever alpha the theme already chose, not by pinning
    // a new one: some themes draw chips translucent to begin with, and no theme
    // defines a "disabled" colour pair that would stay legible across all of them.
    fun Color.dim() = if (enabled) this else copy(alpha = alpha * DISABLED_ALPHA)
    val shape = kb.chipShape()
    IconHint(null, modifier, disabledReason = disabledReason.takeUnless { enabled }) {
        Box(
            modifier = Modifier
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background((if (selected) kb.chipActive else kb.chip).dim())
                .chipBorder(kb, shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            val content = (if (selected) kb.chipActiveText else kb.chipText).dim()
            if (icon != null) {
                Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(16.dp))
            } else {
                Text(
                    label,
                    color = content,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** How far [ToolPanelChip] fades a chip that cannot be tapped. */
private const val DISABLED_ALPHA = 0.4f

@Composable
private fun KeypadButton(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    // These are keys, so they dress like the key grid: the theme's key
    // shape and border, not the chip pair the panel buttons wear. With no key
    // shape that means no face, the accented = key included, the way the
    // grid's own enter key loses its colour.
    val shape = kb.keyShape()
    val lit = accent && kb.keysHaveFaces
    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(shape)
            // Glass like the board's own keys, and only where those have faces.
            .then(if (kb.keysHaveFaces) Modifier.frostGlass(kb, shape, FrostSurface.KEYS) else Modifier)
            .background(if (lit) kb.toolCircleActive else kb.keyFace(kb.key))
            .panelKeyBorder(kb, shape)
            .clickable { feedback(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val content = if (lit) kb.toolCircleActiveIcon else kb.keyText
        // The keypads spell backspace as ⌫ so their `when` can match it; drawn,
        // it is the same glyph the key grid's backspace wears.
        if (label == KEYPAD_BACKSPACE) {
            Icon(
                Icons.AutoMirrored.Outlined.Backspace,
                contentDescription = stringResource(CommonR.string.common_delete),
                tint = content,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                label,
                color = content,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** The label a keypad gives its backspace key; [KeypadButton] draws it as an icon. */
private const val KEYPAD_BACKSPACE = "⌫"

// ---- symbols tool ----

/**
 * The id of the pseudo-category that holds the symbols used most recently.
 * It is a stored selection key, never shown, and never translated.
 */
internal const val SYMBOL_RECENTS_ID = "Recents"

/** The category ids the picker shows, with Recents first when it has any. */
private fun symbolCategoryIds(hasRecents: Boolean): List<String> = buildList {
    if (hasRecents) add(SYMBOL_RECENTS_ID)
    SymbolCatalog.categories.forEach { add(it.id) }
    add(SYMBOL_UNICODE_ID)
}

/** The category that is every character there is (#1038), browsed by block. */
internal const val SYMBOL_UNICODE_ID = "Unicode"

/** [id]'s symbols: the user's own where they gave some, else the built-in ones. */
private fun symbolsOf(state: KeyboardUiState, id: String): List<String> {
    if (id == SYMBOL_RECENTS_ID) return state.settings.symbolRecents
    state.settings.symbolOverrides[id]?.let { own -> return own.split(' ').filter(String::isNotEmpty) }
    return SymbolCatalog.categories.firstOrNull { it.id == id }?.symbols.orEmpty()
}

/** The chip label for a category id. */
@Composable
private fun symbolCategoryName(id: String): String {
    if (id == SYMBOL_RECENTS_ID) return stringResource(R.string.ime_symbols_recents_label)
    if (id == SYMBOL_UNICODE_ID) return stringResource(R.string.ime_symbols_unicode_label)
    val category = SymbolCatalog.categories.firstOrNull { it.id == id } ?: return id
    return stringResource(category.nameRes)
}

/**
 * One-tap picker for the special characters people otherwise hunt down
 * online — fractions, math operators, Greek, arrows and friends. Tapping a
 * symbol types it and files it under Recents.
 */
/**
 * Category chips for the symbols tool, living in the [FullBleedTool] header
 * next to the back button (the reclaimed toolbar row's blank space does the
 * navigation work).
 */
@Composable
internal fun RowScope.SymbolCategoryChips(
    state: KeyboardUiState,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val categoryIds = symbolCategoryIds(state.settings.symbolRecents.isNotEmpty())
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        categoryIds.forEachIndexed { index, id ->
            ToolPanelChip(
                symbolCategoryName(id),
                selected = id == selected,
                modifier = Modifier.focusRing(index == focusedChip, RoundedCornerShape(12.dp)),
            ) { onSelect(id) }
        }
    }
}

@Composable
internal fun SymbolsPanel(
    state: KeyboardUiState,
    onSymbol: (String) -> Unit,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit = {},
) {
    val kb = LocalKbTheme.current
    val recents = state.settings.symbolRecents
    val symbols = symbolsOf(state, selectedCategory)

    val gridState = rememberLazyGridState()
    // The grid is Adaptive, so its width in cells is only knowable after layout.
    val columns by remember {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column }?.plus(1) ?: 1
        }
    }
    PanelFocusTarget(
        panel = PanelMode.SYMBOLS,
        count = symbols.size,
        columns = columns,
        onActivate = { index -> symbols.getOrNull(index)?.let(onSymbol) },
    )
    // Tab reaches the category chips, which are drawn in the panel header by
    // [SymbolCategoryChips] — the ids are recomputed here rather than hoisted,
    // so the header keeps owning its own selection.
    val categoryIds = symbolCategoryIds(recents.isNotEmpty())
    PanelFocusTarget(
        panel = PanelMode.SYMBOLS,
        region = FocusRegion.CHIPS,
        count = categoryIds.size,
        columns = categoryIds.size.coerceAtLeast(1),
        onActivate = { index -> categoryIds.getOrNull(index)?.let(onSelectCategory) },
    )
    val focused = state.focusedIndex()
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }

    // A swipe sideways is the next category, and the chips follow it (#1038).
    val pager = rememberPagerState(
        initialPage = categoryIds.indexOf(selectedCategory).coerceAtLeast(0),
        pageCount = { categoryIds.size },
    )
    LaunchedEffect(selectedCategory) {
        val page = categoryIds.indexOf(selectedCategory)
        if (page >= 0 && page != pager.currentPage) pager.animateScrollToPage(page)
    }
    LaunchedEffect(pager, categoryIds) {
        snapshotFlow { pager.settledPage }.collect { page ->
            categoryIds.getOrNull(page)?.let { if (it != selectedCategory) onSelectCategory(it) }
        }
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        val id = categoryIds[page]
        if (id == SYMBOL_UNICODE_ID) {
            UnicodeBrowser(onSymbol)
            return@HorizontalPager
        }
        val shown = id == selectedCategory
        val pageSymbols = symbolsOf(state, id)
        LazyVerticalGrid(
            state = if (shown) gridState else rememberLazyGridState(),
            columns = GridCells.Adaptive(minSize = 44.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            itemsIndexed(pageSymbols, key = { _, symbol -> symbol }) { index, symbol ->
                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .focusRing(shown && index == focused)
                        .clickable { onSymbol(symbol) },
                    contentAlignment = Alignment.Center,
                ) {
                    val label = SymbolCatalog.label(symbol)
                    Text(
                        label,
                        color = kb.keyText,
                        fontSize = if (label.length > 2) 11.sp else 20.sp,
                    )
                }
            }
        }
    }
}

/** One Unicode block in [UnicodeBrowser]: its name, and where it starts in the list of characters. */
private class UnicodeBlockStart(val name: String, val index: Int)

private class UnicodeTable(val codePoints: IntArray, val blocks: List<UnicodeBlockStart>)

/** Every character this phone's Java knows, in order, with no controls, surrogates or private use. */
private fun unicodeTable(): UnicodeTable {
    val points = ArrayList<Int>(160_000)
    val blocks = ArrayList<UnicodeBlockStart>(340)
    var block: Character.UnicodeBlock? = null
    for (cp in 0x20..0x3FFFF) {
        if (!Character.isDefined(cp)) continue
        when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.SURROGATE, Character.PRIVATE_USE -> continue
        }
        val here = Character.UnicodeBlock.of(cp)
        if (here != null && here != block) {
            block = here
            val name = here.toString().split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
            blocks.add(UnicodeBlockStart(name, points.size))
        }
        points.add(cp)
    }
    return UnicodeTable(points.toIntArray(), blocks)
}

/** The Unicode tab: a chip per block over a grid of everything, loaded off the main thread. */
@Composable
private fun UnicodeBrowser(onSymbol: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val table by produceState<UnicodeTable?>(null) { value = withContext(Dispatchers.Default) { unicodeTable() } }
    val loaded = table ?: return
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(loaded.blocks.size) { i ->
                val block = loaded.blocks[i]
                ToolPanelChip(block.name, selected = false) { scope.launch { grid.scrollToItem(block.index) } }
            }
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(minSize = 44.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            items(loaded.codePoints.size) { i ->
                val text = String(Character.toChars(loaded.codePoints[i]))
                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSymbol(text) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, color = kb.keyText, fontSize = 20.sp)
                }
            }
        }
    }
}

// ---- calculator tool ----

/**
 * The calculator/converter panels' service callbacks, bundled into one
 * [KeyboardScreen] parameter on purpose: its caller sits against the JVM's
 * 64K method-size ceiling, where every added parameter costs bytecode.
 */
data class ConverterCallbacks(
    /** The calculator expression changed — soft keypad edits route here. */
    val onCalcEdit: (String) -> Unit = {},
    val onCalcToggleDegrees: () -> Unit = {},
    /** The converters' typed amount changed. */
    val onConverterEdit: (String) -> Unit = {},
    val onUnitSelection: (String) -> Unit = {},
    val onCurrencyPairChange: (String, String) -> Unit = { _, _ -> },
    val onCurrencyRefresh: () -> Unit = {},
)

/**
 * Scientific calculator in the tool viewbox. The result is evaluated live
 * as the expression grows; `=` collapses the expression to its result and
 * Insert types the result at the cursor.
 *
 * The expression lives on [KeyboardUiState.calcExpression], not in panel
 * memory: a physical keyboard types into it through the service's buffer
 * routing, and the chip prefill is seeded there by the service on open.
 */
@Composable
internal fun CalculatorPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    callbacks: ConverterCallbacks = ConverterCallbacks(),
) {
    val kb = LocalKbTheme.current
    val expression = state.calcExpression
    val degrees = state.settings.calcDegrees
    val precision = state.settings.calcPrecision
    val result = remember(expression, degrees, precision) {
        if (expression.isBlank()) null
        else runCatching { CalcEngine.format(CalcEngine.evaluate(expression, degrees), precision) }
    }

    fun append(text: String) {
        callbacks.onCalcEdit(expression + text)
    }

    // The keypad has no focus region on purpose — physical keys type the
    // expression directly. The ring covers the scientific row and Insert.
    val sciTokens = listOf(
        "sin(", "cos(", "tan(", "π", "e", "^", "√", "ln(", "log(", "abs(", "mod ",
    )
    PanelFocusTarget(
        panel = PanelMode.CALCULATOR,
        region = FocusRegion.CHIPS,
        count = sciTokens.size + 2,
        columns = sciTokens.size + 2,
    ) { index ->
        when {
            index == 0 -> callbacks.onCalcEdit("")
            index <= sciTokens.size -> sciTokens.getOrNull(index - 1)?.let(::append)
            else -> callbacks.onCalcToggleDegrees()
        }
    }
    PanelFocusTarget(
        panel = PanelMode.CALCULATOR,
        region = FocusRegion.ACTIONS,
        count = if (result?.isSuccess == true) 1 else 0,
        columns = 1,
    ) { result?.getOrNull()?.let(onInsert) }
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        // Display: expression, live result, insert.
        val displayShape = kb.cardShape()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(displayShape)
                .frostGlass(kb, displayShape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, displayShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The focus switch (#665): the physical keys and the key rows type
            // into the app's field while it is on; this keypad still edits here.
            val caretHandle = LocalCaptureCaret.current
            FieldFocusSwitch(caretHandle, Modifier.padding(end = 6.dp))
            Column(Modifier.weight(1f).then(if (caretHandle.toApp) Modifier.alpha(FieldToAppAlpha) else Modifier)) {
                Text(
                    expression.ifEmpty { "0" },
                    color = kb.chipText,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The engine reports a failure as a string resource plus the
                // piece of the expression it names, so the wording follows the
                // language the keyboard is read in.
                val failure = result?.exceptionOrNull() as? CalcEngine.CalcException
                val failureText = failure?.let {
                    stringResource(it.messageRes, it.detail)
                }
                val resultText = result?.fold(
                    onSuccess = { "= $it" },
                    onFailure = { failureText ?: "…" },
                )
                // The line is always laid out, blank on an empty expression,
                // so the first key pressed does not grow the display and push
                // the keypad down (issue #546).
                Text(
                    resultText ?: " ",
                    color = if (result?.isSuccess == true) kb.accent else kb.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (result?.isSuccess == true) {
                Spacer(Modifier.width(8.dp))
                ToolPanelChip(
                    stringResource(R.string.ime_calc_insert_action),
                    modifier = Modifier.focusRing(
                        state.focusedIndex(FocusRegion.ACTIONS) == 0,
                    ),
                ) {
                    onInsert(result.getOrThrow())
                }
            }
        }
        // Tapping the unit chip flips the trig unit and persists it — the
        // same switch the tool's settings page carries, within reach of `sin(`.
        val trigUnit = if (degrees) {
            stringResource(R.string.ime_calc_degrees_label)
        } else {
            stringResource(R.string.ime_calc_radians_label)
        }
        // Keypad. Desk-calculator order by default; the phone layout puts
        // 1 2 3 on top to match the numpad's dialer order (issue #294).
        // Only the digits swap — the operator columns stay where they are.
        val phone = state.settings.calcPhoneLayout
        val rows = listOf(
            listOf(if (phone) "1" else "7", if (phone) "2" else "8", if (phone) "3" else "9", "÷", "⌫"),
            listOf("4", "5", "6", "×", "("),
            listOf(if (phone) "7" else "1", if (phone) "8" else "2", if (phone) "9" else "3", "−", ")"),
            listOf("0", ".", "%", "+", "="),
        )
        val keypad = @Composable { modifier: Modifier ->
            Column(modifier) {
                for (row in rows) {
                    Row(Modifier.weight(1f)) {
                        for (label in row) {
                            KeypadButton(
                                label,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                accent = label == "=",
                            ) {
                                when (label) {
                                    "⌫" -> callbacks.onCalcEdit(expression.dropLast(1))
                                    "=" -> result?.onSuccess { callbacks.onCalcEdit(it) }
                                    else -> append(label)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (shortLandscape()) {
            // Sideways the panel is wide and short, and a row of chips over
            // the keypad cost it a row it could not spare (issue #370): the
            // functions go beside the keypad instead, as keys in rows of
            // their own. Ring indices as in the chip row: C, the tokens, then
            // the trig-unit toggle.
            val functions = listOf("C") + sciTokens + trigUnit
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 3.dp),
            ) {
                Column(Modifier.weight(CalcFunctionsWeight)) {
                    functions.chunked(CalcFunctionColumns).forEachIndexed { rowIndex, row ->
                        Row(Modifier.weight(1f)) {
                            row.forEachIndexed { column, label ->
                                val index = rowIndex * CalcFunctionColumns + column
                                KeypadButton(
                                    label.trimEnd('(', ' '),
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .focusRing(focusedChip == index),
                                ) {
                                    when (index) {
                                        0 -> callbacks.onCalcEdit("")
                                        functions.lastIndex -> callbacks.onCalcToggleDegrees()
                                        else -> append(label)
                                    }
                                }
                            }
                        }
                    }
                }
                keypad(Modifier.weight(1f))
            }
        } else {
            // Scientific row. Ring indices mirror the CHIPS publisher above:
            // C, the tokens, then the trig-unit toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ToolPanelChip("C", modifier = Modifier.focusRing(focusedChip == 0)) {
                    callbacks.onCalcEdit("")
                }
                sciTokens.forEachIndexed { index, token ->
                    ToolPanelChip(
                        token.trimEnd('(', ' '),
                        modifier = Modifier.focusRing(focusedChip == index + 1),
                    ) { append(token) }
                }
                ToolPanelChip(
                    trigUnit,
                    selected = true,
                    modifier = Modifier.focusRing(focusedChip == sciTokens.size + 1),
                ) { callbacks.onCalcToggleDegrees() }
            }
            keypad(Modifier.weight(1f))
        }
    }
}

/**
 * Sideways, the calculator's functions sit beside its keypad in rows of this
 * many — C, eleven functions and the trig unit make four rows, the keypad's
 * own four.
 */
private const val CalcFunctionColumns = 4

/** The functions' share of the row beside the keypad, whose share is 1. */
private const val CalcFunctionsWeight = 0.8f

// ---- shared converter scaffolding ----

/** Digits-and-dot keypad used by the unit and currency converters. */
@Composable
private fun ConverterKeypad(
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
    value: String,
) {
    val rows = listOf(
        listOf("7", "8", "9"),
        listOf("4", "5", "6"),
        listOf("1", "2", "3"),
        listOf(".", "0", "⌫"),
    )
    Column(modifier) {
        for (row in rows) {
            Row(Modifier.weight(1f)) {
                for (label in row) {
                    KeypadButton(
                        label,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        when (label) {
                            "⌫" -> onChange(value.dropLast(1))
                            "." -> if ('.' !in value) onChange(value + ".")
                            else -> onChange((value + label).take(14))
                        }
                    }
                }
            }
        }
    }
}

// ---- unit converter tool ----

/**
 * Unit converter: pick a category, a from/to unit pair, type a value on
 * the keypad and insert the result. Everything converts locally. The
 * selection persists (via [onSelectionChange]) so the panel reopens on
 * whatever was converted last.
 */
@Composable
internal fun UnitConverterPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    callbacks: ConverterCallbacks = ConverterCallbacks(),
    onPrefillConsumed: () -> Unit = {},
) {
    val onSelectionChange = callbacks.onUnitSelection
    val kb = LocalKbTheme.current
    // "Cat|from|to;Cat|from|to;…" — every category keeps its own unit pair,
    // and the last-used category leads the list (it's what the panel reopens
    // on). Matching is by name/symbol, not index, so a units-list change in
    // an update degrades to the defaults instead of picking a wrong unit.
    val savedEntries = state.settings.unitConvertLast.split(';').mapNotNull { entry ->
        val parts = entry.split('|')
        if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
    }
    // Opened from a "1 ft" chip: the category, pair and amount all arrive
    // together and outrank the remembered selection. Snapshotted at first
    // composition because the service clears the prefill immediately after.
    val prefill = remember { state.toolPrefill as? ToolPrefill.Units }
    LaunchedEffect(Unit) { if (prefill != null) onPrefillConsumed() }
    var categoryIndex by rememberSaveable {
        mutableIntStateOf(
            UnitConvert.categories
                .indexOfFirst { it.id == (prefill?.category ?: savedEntries.firstOrNull()?.first) }
                .takeIf { it >= 0 } ?: 0
        )
    }
    val category = UnitConvert.categories[categoryIndex.coerceIn(UnitConvert.categories.indices)]
    fun savedUnit(pick: (Triple<String, String, String>) -> String, fallback: Int): Int =
        savedEntries.firstOrNull { it.first == category.id }?.let { entry ->
            category.units.indexOfFirst { it.symbol == pick(entry) }.takeIf { it >= 0 }
        } ?: fallback
    /** The prefilled unit, but only while the panel is on its category. */
    fun prefilledUnit(pick: (ToolPrefill.Units) -> String): Int? =
        prefill?.takeIf { it.category == category.id }
            ?.let { category.units.indexOfFirst { unit -> unit.symbol == pick(it) } }
            ?.takeIf { it >= 0 }
    var fromIndex by rememberSaveable(categoryIndex) {
        mutableIntStateOf(prefilledUnit { it.from } ?: savedUnit({ it.second }, 0))
    }
    var toIndex by rememberSaveable(categoryIndex) {
        mutableIntStateOf(prefilledUnit { it.to } ?: savedUnit({ it.third }, 1))
    }
    // The amount lives on the service (physical keys type into it); the
    // prefill's value is seeded there by onPanelChange, not here.
    val value = state.converterValue

    val from = category.units.getOrElse(fromIndex) { category.units.first() }
    val to = category.units.getOrElse(toIndex) { category.units.last() }
    LaunchedEffect(category.id, from.symbol, to.symbol) {
        val others = savedEntries.filter { it.first != category.id }
        val encoded = (listOf(Triple(category.id, from.symbol, to.symbol)) + others)
            .joinToString(";") { "${it.first}|${it.second}|${it.third}" }
        onSelectionChange(encoded)
    }
    val amount = value.toDoubleOrNull()
    val converted = amount?.let { UnitConvert.convert(it, from, to) }
    val finite = converted?.takeIf { !it.isNaN() && !it.isInfinite() }
    // A length in feet reads as feet and inches unless the setting says
    // otherwise. A compound reading is inserted whole — "3 ft 3.37 in" means
    // nothing without its two units — where a plain result still inserts the
    // bare number, which is what a field being converted into wants.
    val compound = finite?.takeIf { state.settings.compoundUnits }?.let {
        CompoundUnits.format(
            it,
            to.symbol,
            CompoundUnits.Style.SYMBOL,
            state.settings.calcPrecision.coerceAtMost(2),
        )
    }
    val plainText = finite?.let { CalcEngine.format(it, state.settings.calcPrecision) }
    val resultText = compound ?: plainText?.let { "$it ${to.symbol}" }
    val insertText = compound ?: plainText

    // Ring regions. From and to publish as one 2×n grid so Down/Up crosses
    // between the rows; the keypad stays ring-free (physical digits type).
    val unitCount = category.units.size
    PanelFocusTarget(
        panel = PanelMode.UNIT_CONVERT,
        region = FocusRegion.CATEGORIES,
        count = UnitConvert.categories.size,
        columns = UnitConvert.categories.size,
    ) { index -> if (index in UnitConvert.categories.indices) categoryIndex = index }
    PanelFocusTarget(
        panel = PanelMode.UNIT_CONVERT,
        region = FocusRegion.CHIPS,
        count = unitCount * 2,
        columns = unitCount.coerceAtLeast(1),
    ) { index ->
        when {
            index < unitCount -> fromIndex = index
            index < unitCount * 2 -> toIndex = index - unitCount
        }
    }
    PanelFocusTarget(
        panel = PanelMode.UNIT_CONVERT,
        region = FocusRegion.ACTIONS,
        count = if (resultText != null) 2 else 1,
        columns = 2,
    ) { index ->
        when (index) {
            0 -> { val tmp = fromIndex; fromIndex = toIndex; toIndex = tmp }
            1 -> insertText?.let(onInsert)
        }
    }
    val focusedCategory = state.focusedIndex(FocusRegion.CATEGORIES)
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            UnitConvert.categories.forEachIndexed { index, cat ->
                ToolPanelChip(
                    stringResource(cat.nameRes),
                    selected = index == categoryIndex,
                    modifier = Modifier.focusRing(index == focusedCategory),
                ) {
                    categoryIndex = index
                }
            }
        }
        UnitChipRow(
            label = stringResource(R.string.ime_converter_from_label),
            units = category.units,
            selected = fromIndex,
            focused = focusedChip?.takeIf { it < unitCount },
        ) { fromIndex = it }
        UnitChipRow(
            label = stringResource(R.string.ime_converter_to_label),
            units = category.units,
            selected = toIndex,
            focused = focusedChip?.takeIf { it >= unitCount }?.minus(unitCount),
        ) { toIndex = it }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldFocusSwitch(LocalCaptureCaret.current)
            Text(
                "$value ${from.symbol}",
                color = kb.modifierKeyText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
            IconHint(stringResource(R.string.ime_units_swap_desc)) {
                IconButton(
                    onClick = { val tmp = fromIndex; fromIndex = toIndex; toIndex = tmp },
                    modifier = Modifier
                        .size(30.dp)
                        .focusRing(focusedAction == 0, CircleShape),
                ) {
                    Icon(
                        Icons.Outlined.SwapHoriz,
                        contentDescription = stringResource(R.string.ime_units_swap_desc),
                        tint = kb.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                resultText.orEmpty(),
                color = kb.accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (resultText != null) {
                ToolPanelChip(
                    stringResource(R.string.ime_units_insert_action),
                    modifier = Modifier.focusRing(focusedAction == 1),
                ) {
                    insertText?.let(onInsert)
                }
            }
        }
        ConverterKeypad(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onChange = callbacks.onConverterEdit,
            value = value,
        )
    }
}

@Composable
private fun UnitChipRow(
    label: String,
    units: List<UnitConvert.ConvUnit>,
    selected: Int,
    focused: Int? = null,
    onSelect: (Int) -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = kb.secondaryText, fontSize = 10.sp, modifier = Modifier.width(34.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            units.forEachIndexed { index, unit ->
                ToolPanelChip(
                    unit.symbol,
                    selected = index == selected,
                    modifier = Modifier.focusRing(index == focused),
                ) { onSelect(index) }
            }
        }
    }
}

// ---- currency converter tool ----

/**
 * Currency converter on free keyless rate APIs. Rates come as one
 * USD-based table and cross-convert locally; the from/to pair persists
 * via [onPairChange].
 */
@Composable
internal fun CurrencyPanel(
    state: KeyboardUiState,
    callbacks: ConverterCallbacks = ConverterCallbacks(),
    onInsert: (String) -> Unit,
    onPrefillConsumed: () -> Unit = {},
) {
    val kb = LocalKbTheme.current
    val onPairChange = callbacks.onCurrencyPairChange
    val onRefresh = callbacks.onCurrencyRefresh
    // Opened from a "150 usd" chip: push its pair into settings (which is
    // where the panel reads from/to) before the first conversion is drawn.
    // The amount half of the prefill is seeded onto the service's buffer by
    // onPanelChange — physical keys type into it there.
    val prefill = remember { state.toolPrefill as? ToolPrefill.Currency }
    val amountText = state.converterValue
    LaunchedEffect(Unit) {
        prefill?.let {
            if (it.from != state.settings.currencyFrom || it.to != state.settings.currencyTo) {
                onPairChange(it.from, it.to)
            }
            onPrefillConsumed()
        }
    }
    val from = state.settings.currencyFrom
    val to = state.settings.currencyTo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        when (val currency = state.currency) {
            CurrencyUi.Loading -> ConverterMessage(stringResource(R.string.ime_currency_progress))
            is CurrencyUi.Error -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The only control on the error screen is Retry.
                PanelFocusTarget(
                    panel = PanelMode.CURRENCY,
                    region = FocusRegion.ACTIONS,
                    count = 1,
                    columns = 1,
                    onActivate = { onRefresh() },
                )
                Text(
                    currency.message,
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                ToolPanelChip(
                    stringResource(CommonR.string.common_retry),
                    modifier = Modifier.focusRing(
                        state.focusedIndex(FocusRegion.ACTIONS) == 0,
                    ),
                ) { onRefresh() }
            }
            is CurrencyUi.Ready -> {
                val sections = remember(currency.rates) { codeSections(currency.rates) }
                val coinsShown = currency.rates.crypto.isNotEmpty()
                // A saved pair can name a coin that has since been switched
                // off, and a code the table no longer holds converts to
                // nothing at all; fall back rather than show a blank panel.
                val fromCode = inTableOr(from, currency.rates)
                val toCode = inTableOr(to, currency.rates, avoid = fromCode)
                // From and to publish as one 2×n grid over the flattened code
                // list, so Down crosses from the from-row into the to-row.
                val flatCodes = remember(sections) { sections.flatMap { it.codes } }
                val codeCount = flatCodes.size
                PanelFocusTarget(
                    panel = PanelMode.CURRENCY,
                    region = FocusRegion.CHIPS,
                    count = codeCount * 2,
                    columns = codeCount.coerceAtLeast(1),
                ) { index ->
                    when {
                        index < codeCount ->
                            flatCodes.getOrNull(index)?.let { onPairChange(it, toCode) }
                        else ->
                            flatCodes.getOrNull(index - codeCount)
                                ?.let { onPairChange(fromCode, it) }
                    }
                }
                val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
                CurrencyChipRow(
                    stringResource(R.string.ime_converter_from_label),
                    sections,
                    fromCode,
                    coinsShown,
                    focusedCode = focusedChip?.takeIf { it < codeCount }
                        ?.let { flatCodes.getOrNull(it) },
                ) { onPairChange(it, toCode) }
                CurrencyChipRow(
                    stringResource(R.string.ime_converter_to_label),
                    sections,
                    toCode,
                    coinsShown,
                    focusedCode = focusedChip?.takeIf { it >= codeCount }
                        ?.let { flatCodes.getOrNull(it - codeCount) },
                ) { onPairChange(fromCode, it) }
                val amount = amountText.toDoubleOrNull()
                val converted = amount?.let {
                    CurrencyClient.convert(it, fromCode, toCode, currency.rates)
                }
                // Two decimals turn a fraction of a coin into "0", so a coin
                // result keeps its digits unless the user asked for a count.
                val resultText = converted?.let {
                    val coinDecimals = state.settings.rateSources.cryptoDecimals
                    when {
                        !currency.rates.isCrypto(toCode) ->
                            CalcEngine.format(it, state.settings.currencyDecimals)
                        coinDecimals > 0 -> CalcEngine.format(it, coinDecimals)
                        else -> CalcEngine.format(it, COIN_DECIMALS)
                    }
                }
                // Fixed three slots — swap, Insert, refresh — so the ring's
                // index cannot shift when the result's validity flips; the
                // Insert slot just no-ops while there is nothing to insert.
                PanelFocusTarget(
                    panel = PanelMode.CURRENCY,
                    region = FocusRegion.ACTIONS,
                    count = 3,
                    columns = 3,
                ) { index ->
                    when (index) {
                        0 -> onPairChange(toCode, fromCode)
                        1 -> resultText?.let(onInsert)
                        2 -> onRefresh()
                    }
                }
                val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FieldFocusSwitch(LocalCaptureCaret.current)
                    Text(
                        "$amountText $fromCode",
                        color = kb.modifierKeyText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                    IconHint(stringResource(R.string.ime_currency_swap_desc)) {
                        IconButton(
                            onClick = { onPairChange(toCode, fromCode) },
                            modifier = Modifier
                                .size(30.dp)
                                .focusRing(focusedAction == 0, CircleShape),
                        ) {
                            Icon(
                                Icons.Outlined.SwapHoriz,
                                contentDescription = stringResource(R.string.ime_currency_swap_desc),
                                tint = kb.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        if (resultText != null) "$resultText $toCode" else "",
                        color = kb.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (resultText != null) {
                        ToolPanelChip(
                            stringResource(R.string.ime_currency_insert_action),
                            modifier = Modifier.focusRing(focusedAction == 1),
                        ) {
                            onInsert(resultText)
                        }
                    }
                    IconHint(
                        stringResource(
                            R.string.ime_currency_refresh_desc,
                        ),
                    ) {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier
                                .size(28.dp)
                                .focusRing(focusedAction == 2, CircleShape),
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(
                                    R.string.ime_currency_refresh_desc,
                                ),
                                tint = kb.toolbarIcon,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                ConverterKeypad(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onChange = callbacks.onConverterEdit,
                    value = amountText,
                )
            }
        }
    }
}

/** Coin amounts keep enough places that a fraction of one never reads as zero. */
private const val COIN_DECIMALS = 12

/** One run of codes in the picker, under its own heading. */
private data class CodeSection(@StringRes val label: Int, val codes: List<String>)

/**
 * The picker list: the pinned favourites, then the coins in catalogue
 * order, then every other code alphabetically. With no coins in the table
 * the two remaining sections read as one flat list, exactly as before.
 */
private fun codeSections(rates: CurrencyClient.Rates): List<CodeSection> {
    val coins = rates.crypto
    val popular = CurrencyClient.popular.filter { it in rates.rates }
    val rest = rates.rates.keys
        .filterNot { it in CurrencyClient.popular || it in coins }
        .sorted()
    return buildList {
        add(CodeSection(R.string.ime_currency_section_popular, popular))
        if (coins.isNotEmpty()) {
            val ordered = CryptoCatalog.coins.map { it.code }.filter { it in coins } +
                coins.filterNot { CryptoCatalog.isKnown(it) }.sorted()
            add(CodeSection(R.string.ime_currency_section_crypto, ordered))
        }
        add(CodeSection(R.string.ime_currency_section_all, rest))
    }
}

/** [code] if the table still carries it, else the first pinned code that it does. */
private fun inTableOr(
    code: String,
    rates: CurrencyClient.Rates,
    avoid: String? = null,
): String {
    if (rates.rates.containsKey(code) && code != avoid) return code
    return CurrencyClient.popular.firstOrNull { it in rates.rates && it != avoid }
        ?: rates.rates.keys.firstOrNull { it != avoid }
        ?: code
}

@Composable
private fun CurrencyChipRow(
    label: String,
    sections: List<CodeSection>,
    selected: String,
    showHeadings: Boolean,
    focusedCode: String? = null,
    onSelect: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val listState = rememberLazyListState()
    // The coin section sits between the favourites and the long alphabetical
    // tail, so without this the chip that is actually selected is usually
    // scrolled off the row.
    LaunchedEffect(selected, sections, showHeadings) {
        val index = flatIndexOf(selected, sections, showHeadings)
        if (index >= 0) listState.scrollToItem(index)
    }
    // Same again for the hardware ring, which walks the row chip by chip.
    LaunchedEffect(focusedCode) {
        val index = focusedCode?.let { flatIndexOf(it, sections, showHeadings) } ?: -1
        if (index >= 0) listState.scrollToItem(index)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = kb.secondaryText, fontSize = 10.sp, modifier = Modifier.width(34.dp))
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.weight(1f),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (section in sections) {
                if (showHeadings) {
                    item {
                        Text(
                            stringResource(section.label),
                            color = kb.secondaryText,
                            fontSize = 9.sp,
                        )
                    }
                }
                items(section.codes.size) { index ->
                    val code = section.codes[index]
                    ToolPanelChip(
                        code,
                        selected = code == selected,
                        modifier = Modifier.focusRing(code == focusedCode),
                    ) { onSelect(code) }
                }
            }
        }
    }
}

/** Where [selected] sits once the sections and their headings are laid end to end. */
private fun flatIndexOf(
    selected: String,
    sections: List<CodeSection>,
    showHeadings: Boolean,
): Int {
    var index = 0
    for (section in sections) {
        if (showHeadings) index++
        val at = section.codes.indexOf(selected)
        if (at >= 0) return index + at
        index += section.codes.size
    }
    return -1
}

@Composable
private fun ConverterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = LocalKbTheme.current.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
