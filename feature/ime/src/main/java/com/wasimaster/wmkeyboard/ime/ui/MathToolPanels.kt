package com.wasimaster.wmkeyboard.ime.ui

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Calculate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.CalcEngine
import com.wasimaster.wmkeyboard.core.tools.MathConstants
import com.wasimaster.wmkeyboard.core.tools.MathFormulas
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.CaptureTarget
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * The calculator tool's pages (#1574). The plain calculator is the first and
 * the one the tool always opens on; the rest are tabs in its header.
 */
internal enum class MathPage(@StringRes val labelRes: Int) {
    CALCULATOR(R.string.ime_math_tab_calculator),
    SCIENTIFIC(R.string.ime_math_tab_scientific),
    CONSTANTS(R.string.ime_math_tab_constants),
    FORMULAS(R.string.ime_math_tab_formulas),
    GRAPH(R.string.ime_math_tab_graph),
    CHART(R.string.ime_math_tab_chart),
    ;

    /** The pages that draw the expression and its focus switch (#665). */
    val showsExpression: Boolean get() = this == CALCULATOR || this == SCIENTIFIC
}

/** How a chart page draws its numbers. */
internal enum class ChartKind { BAR, LINE, PIE }

/** The part of the plane a graph shows. */
internal data class GraphViewport(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double)

/**
 * What the math pages hold between openings of the calculator, for as long as
 * the keyboard runs: the memory, the formula being filled in, the functions on
 * the graph and the chart's numbers. Shut and opened again, the tool comes
 * back on the plain calculator with all of it still there a tab away.
 */
@Stable
internal object MathToolMemory {
    var memory by mutableStateOf<Double?>(null)
    var inverse by mutableStateOf(false)
    var hyperbolic by mutableStateOf(false)

    var constantGroup by mutableStateOf(MathConstants.Group.MATHS)

    var topic by mutableStateOf(MathFormulas.Topic.ALGEBRA)
    var formulaId by mutableStateOf<String?>(null)

    /** What is typed in each of the open formula's fields, by quantity name. */
    val formulaValues = mutableStateMapOf<String, String>()
    var unknown by mutableStateOf<String?>(null)
    var focusedField by mutableStateOf<String?>(null)

    /** The graph's functions of x, as typed. Starts with one to show what the page is for. */
    val functions = mutableStateListOf("sin(x)")

    /** The function being typed, or -1 while the graph shows. */
    var editing by mutableIntStateOf(-1)
    var viewport by mutableStateOf<GraphViewport?>(null)

    /** The viewport's trig unit: it is fitted again when that changes. */
    var viewportDegrees by mutableStateOf<Boolean?>(null)
    var traceX by mutableStateOf<Double?>(null)
    var graphTable by mutableStateOf(false)

    var chartText by mutableStateOf("")
    var chartKind by mutableStateOf(ChartKind.BAR)
    var chartTyping by mutableStateOf(false)

    fun openFormula(formula: MathFormulas.Formula) {
        formulaValues.clear()
        formula.quantities.forEach { q -> q.default?.let { formulaValues[q.name] = it } }
        unknown = formula.target.name
        focusedField = formula.quantities.drop(1).firstOrNull { it.default == null }?.name
            ?: formula.quantities.getOrNull(1)?.name
        formulaId = formula.id
    }
}

/**
 * The calculator tool: its header's page tabs over the page that is open. Lives
 * here rather than in [KeyboardScreen]'s panel switch, whose method sits
 * against the JVM's size ceiling.
 *
 * The constants page searches with the key rows, through the same buffer the
 * symbols tool's Unicode search uses ([KeyboardUiState.unicodeQuery]): while it
 * is open the panel collapses over the keys, as that search's does.
 */
@Composable
internal fun CalculatorTool(
    state: KeyboardUiState,
    onClose: () -> Unit,
    onInsert: (String) -> Unit,
    converter: ConverterCallbacks,
    onSearchToggle: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(MathPage.CALCULATOR) }
    val searching = page == MathPage.CONSTANTS && state.unicodeQuery != null
    // Leaving the constants page shuts its search, so the keys type into the
    // expression again.
    val select: (MathPage) -> Unit = { next ->
        if (state.unicodeQuery != null) onSearchToggle()
        page = next
    }
    // Collapsed over the key rows while the focus switch sends the keys to
    // the app's field (#665), like any tool whose box lets go, and while the
    // constants are searched.
    FullBleedTool(
        state,
        title = "",
        onClose = onClose,
        compact = searching || page.showsExpression && state.captureHandedToApp,
        compactHeight = if (searching) 188.dp else 132.dp,
        headerActions = {
            if (searching) MathSearchBar(state, onSearchToggle) else MathPageTabs(page, select)
        },
    ) {
        when (page) {
            MathPage.CALCULATOR -> CalculatorPanel(state, onInsert, converter)
            MathPage.SCIENTIFIC -> ScientificPage(state, onInsert, converter)
            MathPage.CONSTANTS -> ConstantsPage(
                state,
                onInsert,
                onUse = { text ->
                    select(MathPage.CALCULATOR)
                    converter.onCalcType(text)
                },
                onSearch = onSearchToggle,
            )
            MathPage.FORMULAS -> FormulasPage(state, onInsert)
            MathPage.GRAPH -> GraphPage(state, onInsert, converter)
            MathPage.CHART -> ChartPage(state, onInsert)
        }
    }
}

/** The page tabs, in the header where the tool's title was. */
@Composable
private fun RowScope.MathPageTabs(page: MathPage, onSelect: (MathPage) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tab in MathPage.entries) {
                val selected = tab == page
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(shape)
                        .background(if (selected) kb.chipActive else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab) {
                            if (!selected) onSelect(tab)
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(tab.labelRes),
                        color = if (selected) kb.chipActiveText else kb.chipText,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The constants' search box, in the header while the key rows type into it. */
@Composable
private fun RowScope.MathSearchBar(state: KeyboardUiState, onClose: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 6.dp)
            .height(32.dp)
            .clip(kb.cardShape())
            .background(kb.modifierKey)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = kb.toolbarIcon)
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = state.unicodeQuery.orEmpty(),
            placeholder = stringResource(R.string.ime_math_constants_search_hint),
            active = true,
            textColor = kb.modifierKeyText,
            placeholderColor = kb.toolbarIcon,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_math_constants_search_close),
                modifier = Modifier.size(16.dp),
                tint = kb.toolbarIcon,
            )
        }
    }
}

/**
 * A key of the math pages' keypads, dressed like the calculator's own: the
 * theme's key shape, face and border. [lit] is a toggle that is on, drawn
 * the way the calculator draws its = key.
 */
@Composable
internal fun MathKey(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    lit: Boolean = false,
    icon: ImageVector? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.keyShape()
    val filled = (accent || lit) && kb.keysHaveFaces
    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(shape)
            .then(if (kb.keysHaveFaces) Modifier.frostGlass(kb, shape, FrostSurface.KEYS) else Modifier)
            .background(if (filled) kb.toolCircleActive else kb.keyFace(kb.key))
            .panelKeyBorder(kb, shape)
            .clickable { feedback(); onClick() }
            .then(
                if (icon == null && description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // With no key faces a lit toggle has nothing to fill, so its label
        // takes the accent instead.
        val content = when {
            filled -> kb.toolCircleActiveIcon
            lit -> kb.accent
            else -> kb.keyText
        }
        if (icon != null) {
            Icon(icon, contentDescription = description ?: label, tint = content, modifier = Modifier.size(20.dp))
        } else {
            Text(
                label,
                color = content,
                fontSize = if (label.length > 3) 13.sp else 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** A keypad's backspace, drawn as the key grid's backspace icon. */
internal const val MATH_BACKSPACE = "⌫"

/** Rows of [MathKey]s that share the height they are given. */
@Composable
internal fun MathKeyGrid(
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    keyFor: @Composable (label: String, modifier: Modifier) -> Unit,
) {
    Column(modifier) {
        for (row in rows) {
            Row(Modifier.weight(1f)) {
                for (label in row) keyFor(label, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

// ---- scientific page ----

private const val KEY_CLEAR = "C"
private const val KEY_EQUALS = "="

/**
 * The scientific page: the calculator's expression and result over a full
 * function keypad. The same expression as the calculator page, so a sum can
 * start on one and finish on the other; the same caret too (#1536).
 */
@Composable
private fun ScientificPage(state: KeyboardUiState, onInsert: (String) -> Unit, callbacks: ConverterCallbacks) {
    val kb = LocalKbTheme.current
    val mem = MathToolMemory
    val expression = state.calcExpression
    val degrees = state.settings.calcDegrees
    val precision = state.settings.calcPrecision
    val result = remember(expression, degrees, precision) {
        if (expression.isBlank()) null
        else runCatching { CalcEngine.evaluate(expression, degrees) }
    }
    val resultText = result?.getOrNull()?.let { CalcEngine.format(it, precision) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        ExpressionDisplay(state, expression, result, resultText, onInsert)
        // The toggles, the trig unit and the memory, as chips over the keys.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolPanelChip("2nd", selected = mem.inverse) { mem.inverse = !mem.inverse }
            ToolPanelChip("hyp", selected = mem.hyperbolic) { mem.hyperbolic = !mem.hyperbolic }
            ToolPanelChip(
                stringResource(if (degrees) R.string.ime_calc_degrees_label else R.string.ime_calc_radians_label),
                selected = true,
            ) { callbacks.onCalcToggleDegrees() }
            ToolPanelChip("MC", enabled = mem.memory != null) { mem.memory = null }
            ToolPanelChip("MR", enabled = mem.memory != null) {
                mem.memory?.let { callbacks.onCalcType(calcNumber(it, precision)) }
            }
            ToolPanelChip("M+", enabled = result?.isSuccess == true) {
                result?.getOrNull()?.let { mem.memory = (mem.memory ?: 0.0) + it }
            }
            ToolPanelChip("M−", enabled = result?.isSuccess == true) {
                result?.getOrNull()?.let { mem.memory = (mem.memory ?: 0.0) - it }
            }
            mem.memory?.let { value ->
                Text(
                    stringResource(R.string.ime_math_memory_value, CalcEngine.format(value, precision)),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
        val inv = mem.inverse
        val hyp = mem.hyperbolic
        val keys = remember(inv, hyp) { scientificKeys(inv, hyp) }
        val tokenOf = remember(keys) { keys.flatten().toMap() }
        MathKeyGrid(
            rows = keys.map { row -> row.map { it.first } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { label, modifier ->
            val token = tokenOf[label] ?: label
            MathKey(
                label,
                modifier = modifier,
                accent = label == KEY_EQUALS,
                icon = if (label == MATH_BACKSPACE) Icons.AutoMirrored.Outlined.Backspace else null,
                description = if (label == MATH_BACKSPACE) stringResource(CommonR.string.common_delete) else null,
            ) {
                when (token) {
                    MATH_BACKSPACE -> callbacks.onCalcBackspace()
                    KEY_EQUALS -> resultText?.let(callbacks.onCalcEdit)
                    CLEAR_TOKEN -> callbacks.onCalcEdit("")
                    else -> callbacks.onCalcType(token)
                }
            }
        }
    }
}

/** What the scientific page's C key sends; its label is plain C, which nCr's token also is. */
private const val CLEAR_TOKEN = "\u0000clear"

/**
 * The scientific keypad, label to what it types, in rows of eight: the
 * functions on the left, the calculator's own digits and operators on the
 * right. 2nd turns the trig keys into their inverses (and x² into x³, √ into
 * ∛), hyp into the hyperbolic ones; both at once are the inverse hyperbolics.
 */
private fun scientificKeys(inverse: Boolean, hyperbolic: Boolean): List<List<Pair<String, String>>> {
    fun trig(name: String): Pair<String, String> {
        val fn = name + if (hyperbolic) "h" else ""
        return if (inverse) "$fn⁻¹" to "a$fn(" else fn to "$fn("
    }
    return listOf(
        listOf(trig("sin"), trig("cos"), trig("tan"), "ln" to "ln(", "log" to "log(", "(" to "(", ")" to ")", MATH_BACKSPACE to MATH_BACKSPACE),
        listOf(
            if (inverse) "x³" to "^3" else "x²" to "^2",
            "xʸ" to "^",
            if (inverse) "∛" to "cbrt(" else "√" to "√",
            "x!" to "!",
            "7" to "7", "8" to "8", "9" to "9", "÷" to "÷",
        ),
        listOf("π" to "π", "e" to "e", "x⁻¹" to "^(−1)", "|x|" to "abs(", "4" to "4", "5" to "5", "6" to "6", "×" to "×"),
        listOf("nPr" to "P", "nCr" to "C", "mod" to "mod", "%" to "%", "1" to "1", "2" to "2", "3" to "3", "−" to "−"),
        listOf("eˣ" to "exp(", "10ˣ" to "10^", "×10ˣ" to "×10^", KEY_CLEAR to CLEAR_TOKEN, "0" to "0", "." to ".", KEY_EQUALS to KEY_EQUALS, "+" to "+"),
    )
}

/** A number as the calculator types it: bracketed when negative, so it never reads as a subtraction. */
internal fun calcNumber(value: Double, precision: Int): String {
    val text = CalcEngine.format(value, precision)
    return if (text.startsWith('-')) "(−${text.drop(1)})" else text
}

/** The expression, its caret and its live result, with Insert: the calculator's own display. */
@Composable
private fun ExpressionDisplay(
    state: KeyboardUiState,
    expression: String,
    result: Result<Double>?,
    resultText: String?,
    onInsert: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
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
        val caretHandle = LocalCaptureCaret.current
        FieldFocusSwitch(caretHandle, Modifier.padding(end = 6.dp))
        Column(Modifier.weight(1f)) {
            SearchQueryText(
                query = expression,
                placeholder = "0",
                active = state.panelFieldTarget() == CaptureTarget.CALC,
                textColor = kb.chipText,
                placeholderColor = kb.chipText,
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth(),
                alignEnd = true,
                focusSwitch = false,
            )
            val failure = result?.exceptionOrNull() as? CalcEngine.CalcException
            val line = when {
                resultText != null -> "= $resultText"
                failure != null -> stringResource(failure.messageRes, failure.detail)
                result != null -> "…"
                else -> " "
            }
            Text(
                line,
                color = if (resultText != null) kb.accent else kb.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (caretHandle.toApp) Modifier.alpha(FieldToAppAlpha) else Modifier),
            )
        }
        if (resultText != null) {
            Spacer(Modifier.width(8.dp))
            ToolPanelChip(stringResource(R.string.ime_calc_insert_action)) { onInsert(resultText) }
        }
    }
}

// ---- constants page ----

/**
 * Maths, physics and chemistry constants by group, or what the search finds
 * across all of them. A tap types the value and its unit into the app; the
 * calculator button puts the value into the expression instead.
 */
@Composable
private fun ConstantsPage(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onUse: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val mem = MathToolMemory
    val query = state.unicodeQuery
    val context = LocalContext.current
    val group = mem.constantGroup
    val shown = remember(query, group) {
        val needle = query?.trim()?.lowercase()
        if (needle == null) {
            MathConstants.inGroup(group)
        } else if (needle.isEmpty()) {
            MathConstants.all
        } else {
            MathConstants.all.filter { c ->
                context.getString(c.nameRes).lowercase().contains(needle) ||
                    c.symbol.lowercase().contains(needle) ||
                    c.unit.lowercase().contains(needle)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (query == null) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    ToolPanelChip(
                        stringResource(R.string.ime_math_constants_search),
                        icon = Icons.Outlined.Search,
                        onClick = onSearch,
                    )
                }
                items(MathConstants.Group.entries) { g ->
                    ToolPanelChip(stringResource(g.nameRes), selected = g == group) { mem.constantGroup = g }
                }
            }
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.ime_math_constants_none, query.orEmpty()),
                color = kb.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { it.id }) { constant ->
                ConstantRow(constant, onInsert, onUse)
            }
        }
    }
}

@Composable
private fun ConstantRow(constant: MathConstants.Constant, onInsert: (String) -> Unit, onUse: (String) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onInsert(MathConstants.asText(constant)) }
            .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(constant.nameRes),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${constant.symbol} = ${MathConstants.pretty(constant.value)} ${constant.unit}".trimEnd(),
                    color = kb.keyText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (constant.exact) {
                    Text(
                        stringResource(R.string.ime_math_constants_exact),
                        color = kb.accent,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        IconButton(onClick = { onUse(MathConstants.forCalculator(constant)) }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Calculate,
                contentDescription = stringResource(R.string.ime_math_constants_use_desc),
                tint = kb.toolbarIcon,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ---- formulas page ----

/** The formulas by topic, or the one being filled in. */
@Composable
private fun FormulasPage(state: KeyboardUiState, onInsert: (String) -> Unit) {
    val formula = MathToolMemory.formulaId?.let(MathFormulas::byId)
    if (formula == null) {
        FormulaList()
    } else {
        FormulaEditor(formula, state.settings.calcPrecision, onInsert)
    }
}

@Composable
private fun FormulaList() {
    val kb = LocalKbTheme.current
    val mem = MathToolMemory
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(MathFormulas.Topic.entries) { topic ->
                ToolPanelChip(stringResource(topic.nameRes), selected = topic == mem.topic) { mem.topic = topic }
            }
        }
        val formulas = remember(mem.topic) { MathFormulas.inTopic(mem.topic) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(formulas, key = { it.id }) { formula ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mem.openFormula(formula) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(formula.nameRes),
                        color = kb.keyText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        MathFormulas.shown(formula),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A field's text as a number, or null while it is empty or half typed ("6.02e"). */
private fun fieldNumber(text: String?): Double? =
    text?.replace('−', '-')?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** A value with its unit, the way it is printed: `4.75 s`, `30°`. */
private fun withUnit(value: String, unit: String): String = when (unit) {
    "" -> value
    "°" -> "$value°"
    else -> "$value $unit"
}

/**
 * One formula, filled in: a field per quantity, the one being worked out
 * picked by tapping its symbol, and a numeric keypad that types into the
 * focused field. Insert types the answer with its unit; Insert with formula
 * types the formula with the values in it too.
 */
@Composable
private fun FormulaEditor(formula: MathFormulas.Formula, precision: Int, onInsert: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val mem = MathToolMemory
    val unknown = mem.unknown?.takeIf { name -> formula.quantities.any { it.name == name } } ?: formula.target.name
    val typed = formula.quantities.map { mem.formulaValues[it.name] }
    val answer = remember(formula.id, unknown, typed) {
        val known = HashMap<String, Double>()
        for (q in formula.quantities) {
            if (q.name == unknown) continue
            known[q.name] = fieldNumber(mem.formulaValues[q.name]) ?: return@remember null
        }
        Result.success(MathFormulas.solve(formula, known, unknown))
    }
    val unknownQuantity = formula.quantities.first { it.name == unknown }
    val answerText = answer?.getOrNull()?.let { CalcEngine.format(it, precision) }
    val answerWithUnit = answerText?.let { withUnit(it, unknownQuantity.unit) }
    val knownNames = formula.quantities.map { it.name }.filter { it != unknown }
    val focused = mem.focusedField?.takeIf { it in knownNames } ?: knownNames.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { mem.formulaId = null }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.ime_math_formulas_back_desc),
                    tint = kb.toolbarIcon,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(formula.nameRes),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    MathFormulas.shown(formula),
                    color = kb.keyText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val line = when {
                answerWithUnit != null -> "${unknownQuantity.shown} = $answerWithUnit"
                answer == null -> stringResource(R.string.ime_math_formulas_need_values)
                else -> stringResource(R.string.ime_math_formulas_no_solution)
            }
            Text(
                line,
                color = if (answerWithUnit != null) kb.accent else kb.secondaryText,
                fontSize = 13.sp,
                fontWeight = if (answerWithUnit != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (answerWithUnit != null) {
                ToolPanelChip(stringResource(R.string.ime_math_formulas_insert)) { onInsert(answerWithUnit) }
                ToolPanelChip(stringResource(R.string.ime_math_formulas_insert_working)) {
                    val values = formula.quantities.associate { it.name to mem.formulaValues[it.name].orEmpty() }
                    val filled = MathFormulas.filled(formula, values, unknown)
                    val direct = unknown == formula.target.name &&
                        formula.template.startsWith("{${formula.target.name}} = ")
                    onInsert(
                        if (direct) "$filled = $answerWithUnit" else "$filled, ${unknownQuantity.shown} = $answerWithUnit",
                    )
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                for (quantity in formula.quantities) {
                    QuantityRow(
                        quantity,
                        isUnknown = quantity.name == unknown,
                        isFocused = quantity.name == focused,
                        text = if (quantity.name == unknown) answerText ?: "?" else mem.formulaValues[quantity.name].orEmpty(),
                        onSolveFor = {
                            mem.unknown = quantity.name
                            if (mem.focusedField == quantity.name) mem.focusedField = null
                        },
                        onFocus = { if (quantity.name != unknown) mem.focusedField = quantity.name },
                    )
                }
                Text(
                    stringResource(R.string.ime_math_formulas_hint),
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(4.dp),
                )
            }
            FormulaKeypad(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onKey = { key ->
                    val field = focused ?: return@FormulaKeypad
                    if (key == KEY_NEXT) {
                        mem.focusedField = knownNames[(knownNames.indexOf(field) + 1) % knownNames.size]
                    } else {
                        mem.formulaValues[field] = editNumber(mem.formulaValues[field].orEmpty(), key)
                    }
                },
            )
        }
    }
}

@Composable
private fun QuantityRow(
    quantity: MathFormulas.Quantity,
    isUnknown: Boolean,
    isFocused: Boolean,
    text: String,
    onSolveFor: () -> Unit,
    onFocus: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolPanelChip(
            quantity.shown,
            selected = isUnknown,
            modifier = Modifier.widthIn(min = 40.dp),
            onClick = onSolveFor,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .clickable(onClick = onFocus),
        ) {
            Text(
                stringResource(quantity.labelRes),
                color = kb.secondaryText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val shape = kb.cardShape()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(shape)
                    .background(if (isFocused) kb.chipActive else kb.modifierKey)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text,
                    color = when {
                        isUnknown -> kb.accent
                        isFocused -> kb.chipActiveText
                        else -> kb.modifierKeyText
                    },
                    fontSize = 13.sp,
                    fontWeight = if (isUnknown) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            quantity.unit,
            color = kb.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 24.dp),
        )
    }
}

private const val KEY_SIGN = "±"
private const val KEY_EXPONENT = "E"
private const val KEY_NEXT = "→"

/** One key typed into a number field: digits, one point, one exponent, the sign, backspace, clear. */
private fun editNumber(text: String, key: String): String {
    val e = text.indexOf('e')
    return when (key) {
        MATH_BACKSPACE -> text.dropLast(1)
        KEY_CLEAR -> ""
        "." -> if (e < 0 && '.' !in text) (if (text.isEmpty() || text == "-") "${text}0." else "$text.") else text
        KEY_EXPONENT -> if (e < 0 && text.any(Char::isDigit)) "${text}e" else text
        // On the exponent once there is one: 6.02e23 to 6.02e-23.
        KEY_SIGN -> if (e >= 0) {
            val exp = text.substring(e + 1)
            text.substring(0, e + 1) + if (exp.startsWith('-')) exp.drop(1) else "-$exp"
        } else if (text.startsWith('-')) {
            text.drop(1)
        } else {
            "-$text"
        }
        else -> if (key.length == 1 && key[0].isDigit()) text + key else text
    }
}

@Composable
private fun FormulaKeypad(modifier: Modifier, onKey: (String) -> Unit) {
    val rows = listOf(
        listOf("7", "8", "9", MATH_BACKSPACE),
        listOf("4", "5", "6", KEY_SIGN),
        listOf("1", "2", "3", KEY_EXPONENT),
        listOf("0", ".", KEY_CLEAR, KEY_NEXT),
    )
    MathKeyGrid(rows, modifier) { label, keyModifier ->
        MathKey(
            label = if (label == KEY_EXPONENT) "×10ˣ" else label,
            modifier = keyModifier,
            accent = label == KEY_NEXT,
            icon = if (label == MATH_BACKSPACE) Icons.AutoMirrored.Outlined.Backspace else null,
            description = when (label) {
                MATH_BACKSPACE -> stringResource(CommonR.string.common_delete)
                KEY_SIGN -> stringResource(R.string.ime_math_formulas_sign_desc)
                KEY_EXPONENT -> stringResource(R.string.ime_math_formulas_exponent_desc)
                KEY_NEXT -> stringResource(R.string.ime_math_formulas_next_desc)
                else -> null
            },
        ) { onKey(label) }
    }
}
