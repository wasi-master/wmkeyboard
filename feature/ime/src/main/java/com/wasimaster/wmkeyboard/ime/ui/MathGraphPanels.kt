package com.wasimaster.wmkeyboard.ime.ui

import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CenterFocusStrong
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import com.wasimaster.wmkeyboard.core.tools.CalcEngine
import com.wasimaster.wmkeyboard.core.tools.ChartData
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ---- graph page ----

/** The most functions one graph draws. */
private const val MAX_FUNCTIONS = 4

/** Points a curve is drawn through across the graph's width. */
private const val CURVE_SAMPLES = 240

/** One colour per function, the same on the chips and the curves; picked to read on light and dark boards. */
private val CurveColors = listOf(Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00))

/** A typed function as the engine reads it: `y = …` and `f(x) = …` lose their left side. */
private fun functionBody(typed: String): String =
    typed.substringAfter('=', typed).trim()

/** Whether [typed] reads as a function of x at all. Its values may still be missing in places. */
private fun functionError(typed: String, degrees: Boolean): CalcEngine.CalcException? {
    val body = functionBody(typed)
    if (body.isBlank()) return null
    return try {
        CalcEngine.valueAt(body, degrees, mapOf("x" to 0.5))
        null
    } catch (e: CalcEngine.CalcException) {
        e
    }
}

/** Steps of 1, 2 or 5 times a power of ten, about [target] apart. */
private fun niceStep(target: Double): Double {
    if (target <= 0 || !target.isFinite()) return 1.0
    val power = 10.0.pow(floor(log10(target)))
    val unit = target / power
    return power * when {
        unit < 1.5 -> 1.0
        unit < 3.5 -> 2.0
        unit < 7.5 -> 5.0
        else -> 10.0
    }
}

/**
 * The view a graph opens on: a turn either way in degrees, about three in
 * radians, and tall enough for the middle 90% of the drawn values.
 */
private fun fittedViewport(functions: List<String>, degrees: Boolean): GraphViewport {
    val half = if (degrees) 360.0 else 10.0
    val values = ArrayList<Double>()
    val vars = HashMap<String, Double>()
    for (typed in functions) {
        if (functionError(typed, degrees) != null) continue
        val body = functionBody(typed)
        if (body.isBlank()) continue
        for (i in 0..100) {
            vars["x"] = -half + 2 * half * i / 100
            val y = try { CalcEngine.valueAt(body, degrees, vars) } catch (_: CalcEngine.CalcException) { Double.NaN }
            if (y.isFinite()) values.add(y)
        }
    }
    if (values.isEmpty()) return GraphViewport(-half, half, -5.0, 5.0)
    values.sort()
    var lo = values[(values.size * 0.05).toInt()]
    var hi = values[((values.size - 1) * 0.95).toInt()]
    if (hi - lo < 1e-9) {
        lo -= 1
        hi += 1
    }
    val pad = (hi - lo) * 0.15
    return GraphViewport(-half, half, lo - pad, hi + pad)
}

private fun formatAxis(value: Double, step: Double): String {
    if (abs(value) < step / 1e6) return "0"
    return CalcEngine.format(value, max(0, -floor(log10(step)).toInt() + 1).coerceAtMost(10))
}

/**
 * Plots y = f(x) for up to four functions. Drag to pan, pinch to zoom, tap
 * to read every function's value at that x; the table view lists them in
 * steps across the view. Typing a function opens its own keypad, with x and
 * the trig functions on it.
 */
@Composable
internal fun GraphPage(state: KeyboardUiState, onInsert: (String) -> Unit, callbacks: ConverterCallbacks) {
    val mem = MathToolMemory
    val degrees = state.settings.calcDegrees
    val precision = state.settings.calcPrecision
    if (mem.editing in mem.functions.indices) {
        FunctionEditor(mem.editing, degrees)
        return
    }
    // Fitted to the functions on first sight and after Fit; a new trig unit
    // is a new x scale, so it fits again then too. Written back after
    // composition, never during it.
    val stale = mem.viewport == null || mem.viewportDegrees != degrees
    val fitted = if (stale) fittedViewport(mem.functions.toList(), degrees) else null
    SideEffect {
        if (fitted != null) {
            mem.viewport = fitted
            mem.viewportDegrees = degrees
            mem.traceX = null
        }
    }
    val kb = LocalKbTheme.current
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mem.functions.forEachIndexed { index, typed ->
                FunctionChip(index, typed, degrees) { mem.editing = index }
            }
            if (mem.functions.size < MAX_FUNCTIONS) {
                ToolPanelChip(stringResource(R.string.ime_math_graph_add_desc), icon = Icons.Outlined.Add) {
                    mem.functions.add("")
                    mem.editing = mem.functions.lastIndex
                }
            }
            ToolPanelChip(
                stringResource(if (mem.graphTable) R.string.ime_math_graph_plot else R.string.ime_math_graph_table),
            ) { mem.graphTable = !mem.graphTable }
            ToolPanelChip(
                stringResource(if (degrees) R.string.ime_calc_degrees_label else R.string.ime_calc_radians_label),
                selected = true,
            ) { callbacks.onCalcToggleDegrees() }
        }
        val viewport = fitted ?: mem.viewport ?: return@Column
        if (mem.graphTable) {
            GraphTable(viewport, degrees, precision, onInsert, Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 3.dp)) {
                GraphCanvas(degrees)
                GraphReadout(degrees, precision, onInsert, Modifier.align(Alignment.TopStart))
                Column(Modifier.align(Alignment.TopEnd)) {
                    GraphButton(Icons.Outlined.Add, stringResource(R.string.ime_math_graph_zoom_in_desc)) { zoom(0.5) }
                    GraphButton(Icons.Outlined.Remove, stringResource(R.string.ime_math_graph_zoom_out_desc)) { zoom(2.0) }
                    GraphButton(Icons.Outlined.CenterFocusStrong, stringResource(R.string.ime_math_graph_reset_desc)) {
                        mem.viewport = null
                    }
                }
                if (mem.traceX == null) {
                    Text(
                        stringResource(R.string.ime_math_graph_trace_hint),
                        color = kb.secondaryText,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/** Shrinks (below 1) or grows the view about its middle. */
private fun zoom(factor: Double) {
    val v = MathToolMemory.viewport ?: return
    val cx = (v.xMin + v.xMax) / 2
    val cy = (v.yMin + v.yMax) / 2
    val hx = (v.xMax - v.xMin) / 2 * factor
    val hy = (v.yMax - v.yMin) / 2 * factor
    MathToolMemory.viewport = GraphViewport(cx - hx, cx + hx, cy - hy, cy + hy)
}

@Composable
private fun GraphButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(kb.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = kb.chipText, modifier = Modifier.size(16.dp))
        }
    }
}

/** A function's chip: its colour, and what it says. */
@Composable
private fun FunctionChip(index: Int, typed: String, degrees: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val broken = remember(typed, degrees) { functionError(typed, degrees) != null }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(CurveColors[index % CurveColors.size]),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "y = ${functionBody(typed).ifBlank { "…" }}",
            color = if (broken) kb.secondaryText else kb.chipText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** The curves, the grid and its numbers, and the traced x. */
@Composable
private fun GraphCanvas(degrees: Boolean) {
    val kb = LocalKbTheme.current
    val mem = MathToolMemory
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = kb.secondaryText, fontSize = 9.sp)
    val gridColor = kb.secondaryText.copy(alpha = 0.18f)
    val axisColor = kb.keyText.copy(alpha = 0.6f)
    val bodies = mem.functions.map { functionBody(it) }
    val valid = remember(bodies, degrees) { bodies.map { it.isNotBlank() && functionError(it, degrees) == null } }
    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val v = mem.viewport ?: return@detectTransformGestures
                    val w = size.width.toDouble().coerceAtLeast(1.0)
                    val h = size.height.toDouble().coerceAtLeast(1.0)
                    val xRange = v.xMax - v.xMin
                    val yRange = v.yMax - v.yMin
                    // The point under the fingers stays under them while they pinch.
                    val cx = v.xMin + centroid.x / w * xRange
                    val cy = v.yMax - centroid.y / h * yRange
                    val z = zoom.toDouble().coerceIn(0.2, 5.0)
                    val dx = -pan.x / w * xRange
                    val dy = pan.y / h * yRange
                    mem.viewport = GraphViewport(
                        cx - (cx - v.xMin) / z + dx,
                        cx + (v.xMax - cx) / z + dx,
                        cy - (cy - v.yMin) / z + dy,
                        cy + (v.yMax - cy) / z + dy,
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    val v = mem.viewport ?: return@detectTapGestures
                    mem.traceX = v.xMin + at.x / size.width.coerceAtLeast(1) * (v.xMax - v.xMin)
                }
            },
    ) {
        val v = mem.viewport ?: return@Canvas
        fun px(x: Double) = ((x - v.xMin) / (v.xMax - v.xMin) * size.width).toFloat()
        fun py(y: Double) = ((v.yMax - y) / (v.yMax - v.yMin) * size.height).toFloat()
        val xStep = niceStep((v.xMax - v.xMin) / 6)
        val yStep = niceStep((v.yMax - v.yMin) / 5)
        // The axes' numbers sit along the axes, or along the edge the axis is past.
        val axisY = py(0.0).coerceIn(0f, size.height - 12f)
        val axisX = px(0.0).coerceIn(0f, size.width - 24f)
        var gx = ceil(v.xMin / xStep) * xStep
        while (gx <= v.xMax) {
            val x = px(gx)
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            if (abs(gx) > xStep / 1e6) drawLabel(measurer, formatAxis(gx, xStep), Offset(x + 2f, axisY), labelStyle)
            gx += xStep
        }
        var gy = ceil(v.yMin / yStep) * yStep
        while (gy <= v.yMax) {
            val y = py(gy)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            if (abs(gy) > yStep / 1e6) drawLabel(measurer, formatAxis(gy, yStep), Offset(axisX + 2f, y), labelStyle)
            gy += yStep
        }
        if (v.xMin <= 0 && v.xMax >= 0) drawLine(axisColor, Offset(px(0.0), 0f), Offset(px(0.0), size.height), 1.5f)
        if (v.yMin <= 0 && v.yMax >= 0) drawLine(axisColor, Offset(0f, py(0.0)), Offset(size.width, py(0.0)), 1.5f)

        val vars = HashMap<String, Double>()
        val yRange = v.yMax - v.yMin
        bodies.forEachIndexed { index, body ->
            if (!valid.getOrElse(index) { false }) return@forEachIndexed
            val path = Path()
            var drawing = false
            var lastY = Double.NaN
            for (i in 0..CURVE_SAMPLES) {
                val x = v.xMin + (v.xMax - v.xMin) * i / CURVE_SAMPLES
                vars["x"] = x
                val y = try { CalcEngine.valueAt(body, degrees, vars) } catch (_: CalcEngine.CalcException) { Double.NaN }
                // A gap where there is no value, and a break across an asymptote
                // (tan at 90°), rather than a line straight down the graph.
                val jump = drawing && lastY.isFinite() && abs(y - lastY) > yRange * 2
                if (!y.isFinite() || jump) {
                    drawing = false
                    lastY = y
                    if (!y.isFinite()) continue
                }
                val point = Offset(px(x), py(y.coerceIn(v.yMin - yRange * 4, v.yMax + yRange * 4)))
                if (drawing) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                drawing = true
                lastY = y
            }
            drawPath(path, CurveColors[index % CurveColors.size], style = Stroke(width = 2.dp.toPx()))
        }
        mem.traceX?.let { tx ->
            if (tx < v.xMin || tx > v.xMax) return@let
            val x = px(tx)
            drawLine(axisColor, Offset(x, 0f), Offset(x, size.height), 1f)
            vars["x"] = tx
            bodies.forEachIndexed { index, body ->
                if (!valid.getOrElse(index) { false }) return@forEachIndexed
                val y = try { CalcEngine.valueAt(body, degrees, vars) } catch (_: CalcEngine.CalcException) { Double.NaN }
                if (y.isFinite() && y in v.yMin..v.yMax) {
                    drawCircle(CurveColors[index % CurveColors.size], radius = 4.dp.toPx(), center = Offset(x, py(y)))
                }
            }
        }
    }
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    val layout = measurer.measure(text, style)
    val x = at.x.coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    val y = at.y.coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(x, y))
}

/** What each function is at the traced x, with Insert. */
@Composable
private fun GraphReadout(degrees: Boolean, precision: Int, onInsert: (String) -> Unit, modifier: Modifier) {
    val kb = LocalKbTheme.current
    val mem = MathToolMemory
    val tx = mem.traceX ?: return
    val lines = ArrayList<Pair<Int, String>>()
    val vars = mapOf("x" to tx)
    mem.functions.forEachIndexed { index, typed ->
        val body = functionBody(typed)
        if (body.isBlank() || functionError(body, degrees) != null) return@forEachIndexed
        val y = try { CalcEngine.valueAt(body, degrees, vars) } catch (_: CalcEngine.CalcException) { Double.NaN }
        lines.add(index to "$body = ${if (y.isFinite()) CalcEngine.format(y, precision) else "—"}")
    }
    val xText = "x = ${CalcEngine.format(tx, precision.coerceAtMost(6))}"
    Column(
        modifier = modifier
            .padding(4.dp)
            .clip(kb.cardShape())
            .background(kb.popup)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(xText, color = kb.popupText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.ime_math_graph_insert_value),
                color = kb.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    onInsert((listOf(xText) + lines.map { it.second }).joinToString(", "))
                },
            )
        }
        for ((index, line) in lines) {
            Text(
                line,
                color = CurveColors[index % CurveColors.size],
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The functions' values in steps across the view, with Insert table. */
@Composable
private fun GraphTable(
    viewport: GraphViewport,
    degrees: Boolean,
    precision: Int,
    onInsert: (String) -> Unit,
    modifier: Modifier,
) {
    val mem = MathToolMemory
    val bodies = mem.functions.map { functionBody(it) }.filter { it.isNotBlank() && functionError(it, degrees) == null }
    val rows = remember(viewport, bodies, degrees, precision) {
        val step = niceStep((viewport.xMax - viewport.xMin) / 12)
        val vars = HashMap<String, Double>()
        val out = ArrayList<List<String>>()
        var x = ceil(viewport.xMin / step) * step
        while (x <= viewport.xMax && out.size < 100) {
            vars["x"] = x
            val cells = ArrayList<String>()
            cells.add(formatAxis(x, step))
            for (body in bodies) {
                val y = try { CalcEngine.valueAt(body, degrees, vars) } catch (_: CalcEngine.CalcException) { Double.NaN }
                cells.add(if (y.isFinite()) CalcEngine.format(y, precision.coerceAtMost(6)) else "—")
            }
            out.add(cells)
            x += step
        }
        out
    }
    Column(modifier.padding(top = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TableRow(listOf("x") + bodies, header = true, modifier = Modifier.weight(1f))
            ToolPanelChip(stringResource(R.string.ime_math_graph_insert_table)) {
                val header = (listOf("x") + bodies).joinToString("\t")
                onInsert((listOf(header) + rows.map { it.joinToString("\t") }).joinToString("\n"))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows) { cells -> TableRow(cells, header = false, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean, modifier: Modifier) {
    val kb = LocalKbTheme.current
    Row(modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        for (cell in cells) {
            Text(
                cell,
                color = if (header) kb.secondaryText else kb.keyText,
                fontSize = 12.sp,
                fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
        }
    }
}

private const val KEY_DONE = "\u0000done"
private const val KEY_REMOVE = "\u0000remove"
private const val KEY_CLEAR_FN = "C"

/** The function keypad: x and the functions on the left, the digits on the right. */
private val FunctionKeys = listOf(
    listOf("x", "sin", "cos", "tan", "(", ")", "^", MATH_BACKSPACE),
    listOf("π", "asin", "acos", "atan", "7", "8", "9", "÷"),
    listOf("e", "ln", "log", "√", "4", "5", "6", "×"),
    listOf("abs", "x²", "x³", "!", "1", "2", "3", "−"),
    listOf(KEY_REMOVE, KEY_CLEAR_FN, "sinh", "cosh", "0", ".", KEY_DONE, "+"),
)

/** What a function key types; the function names open their bracket. */
private fun functionToken(label: String): String = when (label) {
    "sin", "cos", "tan", "asin", "acos", "atan", "ln", "log", "abs", "sinh", "cosh" -> "$label("
    "x²" -> "^2"
    "x³" -> "^3"
    else -> label
}

/**
 * [token] added to the end of [text]. A name straight after a letter gets a
 * multiplication dot, so x then sin reads x·sin( and not the unknown name
 * xsin; elsewhere the engine multiplies what stands side by side already.
 */
private fun typeFunction(text: String, token: String): String {
    val last = text.lastOrNull()
    val first = token.firstOrNull() ?: return text
    return if (last != null && last.isLetter() && first.isLetter()) "$text·$token" else text + token
}

/** One key back: a whole function name with its bracket, or one character. */
private fun backspaceFunction(text: String): String {
    val name = Regex("[a-z]+\\($").find(text)
    return if (name != null) text.substring(0, name.range.first) else text.dropLast(1)
}

/** Types one function, with its colour, what it reads as so far, and the keypad. */
@Composable
private fun FunctionEditor(index: Int, degrees: Boolean) {
    val kb = LocalKbTheme.current
    val mem = MathToolMemory
    val typed = mem.functions[index]
    val error = remember(typed, degrees) { functionError(typed, degrees) }
    fun finish() {
        if (functionBody(mem.functions[index]).isBlank()) mem.functions.removeAt(index)
        mem.editing = -1
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        val shape = kb.cardShape()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(CurveColors[index % CurveColors.size]),
            )
            Spacer(Modifier.width(8.dp))
            Text("y = ", color = kb.chipText, fontSize = 16.sp)
            Text(
                typed.ifEmpty { stringResource(R.string.ime_math_graph_placeholder) },
                color = if (typed.isEmpty()) kb.secondaryText else kb.chipText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            error?.let {
                Text(
                    stringResource(it.messageRes, it.detail),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                )
            }
        }
        MathKeyGrid(FunctionKeys, Modifier.weight(1f).fillMaxWidth().padding(top = 3.dp)) { label, modifier ->
            MathKey(
                label = when (label) {
                    KEY_DONE -> stringResource(R.string.ime_math_graph_done)
                    KEY_REMOVE -> ""
                    else -> label
                },
                modifier = modifier,
                accent = label == KEY_DONE,
                icon = when (label) {
                    MATH_BACKSPACE -> Icons.AutoMirrored.Outlined.Backspace
                    KEY_REMOVE -> Icons.Outlined.Delete
                    else -> null
                },
                description = when (label) {
                    MATH_BACKSPACE -> stringResource(CommonR.string.common_delete)
                    KEY_REMOVE -> stringResource(R.string.ime_math_graph_remove_desc)
                    else -> null
                },
            ) {
                val current = mem.functions.getOrNull(index) ?: return@MathKey
                when (label) {
                    KEY_DONE -> finish()
                    KEY_REMOVE -> {
                        mem.functions.removeAt(index)
                        mem.editing = -1
                    }
                    KEY_CLEAR_FN -> mem.functions[index] = ""
                    MATH_BACKSPACE -> mem.functions[index] = backspaceFunction(current)
                    else -> mem.functions[index] = typeFunction(current, functionToken(label))
                }
            }
        }
    }
}

// ---- chart page ----

/**
 * A bar, line or pie chart of a list of numbers or a pasted table, with the
 * list's count, sum, mean, median, minimum and maximum under it.
 */
@Composable
internal fun ChartPage(state: KeyboardUiState, onInsert: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val mem = MathToolMemory
    val context = LocalContext.current
    val precision = state.settings.calcPrecision.coerceAtMost(6)
    val points = remember(mem.chartText) { ChartData.parse(mem.chartText) }
    val stats = remember(points) { ChartData.stats(points.map { it.value }) }
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((kind, label) in listOf(
                ChartKind.BAR to R.string.ime_math_chart_bar,
                ChartKind.LINE to R.string.ime_math_chart_line,
                ChartKind.PIE to R.string.ime_math_chart_pie,
            )) {
                ToolPanelChip(stringResource(label), selected = mem.chartKind == kind) { mem.chartKind = kind }
            }
            ToolPanelChip(stringResource(R.string.ime_math_chart_paste), icon = Icons.Outlined.ContentPaste) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrBlank()) {
                    mem.chartText = text
                    mem.chartTyping = false
                }
            }
            ToolPanelChip(stringResource(R.string.ime_math_chart_keypad), selected = mem.chartTyping) {
                mem.chartTyping = !mem.chartTyping
            }
            if (stats != null) {
                ToolPanelChip(stringResource(R.string.ime_math_chart_insert_stats)) {
                    fun f(value: Double) = CalcEngine.format(value, precision)
                    onInsert(
                        "n = ${stats.count}, sum = ${f(stats.sum)}, mean = ${f(stats.mean)}, " +
                            "median = ${f(stats.median)}, min = ${f(stats.min)}, max = ${f(stats.max)}",
                    )
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 3.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (points.isEmpty()) {
                    Text(
                        stringResource(R.string.ime_math_chart_empty),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                } else {
                    ChartCanvas(points, mem.chartKind, precision)
                }
            }
            if (mem.chartTyping) {
                Column(Modifier.weight(0.8f).fillMaxHeight()) {
                    Text(
                        mem.chartText.replace('\n', ' ').ifEmpty { " " },
                        color = kb.keyText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                    MathKeyGrid(ChartKeys, Modifier.weight(1f).fillMaxWidth()) { label, modifier ->
                        MathKey(
                            label,
                            modifier = modifier,
                            icon = if (label == MATH_BACKSPACE) Icons.AutoMirrored.Outlined.Backspace else null,
                            description = if (label == MATH_BACKSPACE) stringResource(CommonR.string.common_delete) else null,
                        ) {
                            val text = mem.chartText
                            mem.chartText = when (label) {
                                // A separator goes as one key, the way it came.
                                MATH_BACKSPACE -> if (text.endsWith(", ")) text.dropLast(2) else text.dropLast(1)
                                "C" -> ""
                                "," -> if (text.isBlank() || text.trimEnd().endsWith(',')) text else "${text.trimEnd()}, "
                                else -> text + label
                            }
                        }
                    }
                }
            }
        }
        stats?.let {
            fun f(value: Double) = CalcEngine.format(value, precision)
            Text(
                stringResource(R.string.ime_math_chart_stats, it.count, f(it.sum), f(it.mean), f(it.median), f(it.min), f(it.max)),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

private val ChartKeys = listOf(
    listOf("7", "8", "9", MATH_BACKSPACE),
    listOf("4", "5", "6", ","),
    listOf("1", "2", "3", "−"),
    listOf("0", ".", "%", "C"),
)

/** Labels under bars and points are drawn up to this many; past it they would overlap. */
private const val MAX_LABELLED = 12

@Composable
private fun ChartCanvas(points: List<ChartData.Point>, kind: ChartKind, precision: Int) {
    val kb = LocalKbTheme.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = kb.secondaryText, fontSize = 9.sp)
    val valueStyle = TextStyle(color = kb.keyText, fontSize = 9.sp)
    val axisColor = kb.secondaryText.copy(alpha = 0.5f)
    val accent = kb.accent
    if (kind == ChartKind.PIE) {
        PieChart(points, precision)
        return
    }
    Canvas(Modifier.fillMaxSize().clipToBounds().padding(4.dp)) {
        val labelled = points.size <= MAX_LABELLED
        val bottomBand = if (labelled) 14f * density else 0f
        val topBand = if (labelled) 12f * density else 0f
        val lo = min(0.0, points.minOf { it.value })
        val hi = max(0.0, points.maxOf { it.value }).let { if (it - lo < 1e-12) lo + 1 else it }
        val plotHeight = size.height - bottomBand - topBand
        fun py(v: Double) = (topBand + (hi - v) / (hi - lo) * plotHeight).toFloat()
        val zero = py(0.0)
        drawLine(axisColor, Offset(0f, zero), Offset(size.width, zero), 1f)
        val slot = size.width / points.size
        val path = Path()
        points.forEachIndexed { i, point ->
            val cx = slot * (i + 0.5f)
            val y = py(point.value)
            if (kind == ChartKind.BAR) {
                val w = slot * 0.7f
                drawRect(accent, topLeft = Offset(cx - w / 2, min(y, zero)), size = Size(w, abs(zero - y)))
            } else {
                if (i == 0) path.moveTo(cx, y) else path.lineTo(cx, y)
                drawCircle(accent, radius = 3.dp.toPx(), center = Offset(cx, y))
            }
            if (labelled) {
                val value = measurer.measure(CalcEngine.format(point.value, precision), valueStyle)
                drawText(
                    value,
                    topLeft = Offset(
                        (cx - value.size.width / 2).coerceIn(0f, (size.width - value.size.width).coerceAtLeast(0f)),
                        (min(y, zero) - value.size.height).coerceAtLeast(0f),
                    ),
                )
                val label = measurer.measure(
                    point.label,
                    labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = slot.toInt().coerceAtLeast(1)),
                )
                drawText(label, topLeft = Offset(cx - label.size.width / 2, size.height - label.size.height))
            }
        }
        if (kind == ChartKind.LINE) drawPath(path, accent, style = Stroke(width = 2.dp.toPx()))
    }
}

/** A pie of the positive values, with a legend of labels and shares beside it. */
@Composable
private fun PieChart(points: List<ChartData.Point>, precision: Int) {
    val kb = LocalKbTheme.current
    val slices = points.filter { it.value > 0 }
    val total = slices.sumOf { it.value }
    val palette = listOf(kb.accent) + CurveColors + listOf(Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFF6D4C41))
    Row(Modifier.fillMaxSize()) {
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(4.dp)) {
            if (total <= 0) return@Canvas
            val d = min(size.width, size.height)
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            var start = -90f
            slices.forEachIndexed { i, point ->
                val sweep = (point.value / total * 360).toFloat()
                drawArc(palette[i % palette.size], start, sweep, useCenter = true, topLeft = topLeft, size = Size(d, d))
                start += sweep
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
            items(slices.size) { i ->
                val point = slices[i]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(palette[i % palette.size]),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${point.label}: ${CalcEngine.format(point.value, precision)} " +
                            "(${CalcEngine.format(point.value / total * 100, 1)}%)",
                        color = kb.keyText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
