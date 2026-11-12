package com.wasimaster.wmkeyboard.core.plugins.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.plugins.PluginColorRole
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginWidget
import com.wasimaster.wmkeyboard.plugins.R

/** The theme colour for a [PluginColorRole]; null is the panel's text colour. */
internal fun PluginPanelStyle.colorOf(role: PluginColorRole?): Color = when (role) {
    null -> text
    PluginColorRole.PRIMARY, PluginColorRole.ACCENT -> accent
    PluginColorRole.SECONDARY, PluginColorRole.MUTED -> secondaryText
    PluginColorRole.DANGER -> danger
    PluginColorRole.SUCCESS -> success
    PluginColorRole.WARNING -> warning
}

/**
 * Draws the API 5 widgets. Every one is made of the host's own pieces in the
 * keyboard's theme; nothing a plugin sends can set a colour, a font or a
 * position outside the box it was given.
 */
@Composable
internal fun PackageWidgetView(
    widget: PluginWidget,
    host: PluginInputHost,
    onEvent: (PluginEvent) -> Unit,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    ringed: PluginWidget?,
) {
    val style = LocalPluginPanelStyle.current
    when (widget) {
        is PluginWidget.ProgressValue -> LinearProgressIndicator(
            progress = { widget.value },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = style.accent,
            trackColor = style.surface,
        )

        is PluginWidget.Grid -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (row in widget.children.chunked(widget.columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    for (child in row) {
                        Box(Modifier.weight(1f)) { PluginWidgetList(listOf(child), host, onEvent, onInsert, onCopy, ringed) }
                    }
                    repeat(widget.columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }

        is PluginWidget.Card -> CardBox(clickable = widget.clickable, onClick = { onEvent(PluginEvent.Click(widget.id)) }) {
            if (widget.title.isNotEmpty()) Text(widget.title, color = style.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            PluginWidgetList(widget.children, host, onEvent, onInsert, onCopy, ringed)
        }

        is PluginWidget.Section -> {
            var open by rememberSaveable(widget.id, widget.title) { androidx.compose.runtime.mutableStateOf(widget.open) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (widget.collapsible) Modifier.clickable { open = !open } else Modifier),
                ) {
                    Text(widget.title, color = style.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (widget.collapsible) Text(if (open) "▾" else "▸", color = style.secondaryText, fontSize = 12.sp)
                }
                if (open || !widget.collapsible) PluginWidgetList(widget.children, host, onEvent, onInsert, onCopy, ringed)
            }
        }

        is PluginWidget.Scroll -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (widget.height ?: DEFAULT_SCROLL).dp)
                .verticalScroll(rememberScrollState()),
        ) { PluginWidgetList(widget.children, host, onEvent, onInsert, onCopy, ringed) }

        is PluginWidget.Markdown -> MarkdownText(widget.text)

        is PluginWidget.Empty -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
            widget.icon?.let { style.icon(it, style.secondaryText, 32.dp) }
            Text(widget.text, color = style.secondaryText, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (widget.action.isNotEmpty()) style.chip(widget.action, true, true, Modifier) { onEvent(PluginEvent.Click(widget.id)) }
        }

        is PluginWidget.Checkbox -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = style.ring(Modifier.fillMaxWidth(), widget === ringed)
                .clickable { onEvent(PluginEvent.ValueChanged(widget.id, (!widget.checked).toString())) },
        ) {
            Checkbox(
                checked = widget.checked,
                onCheckedChange = { onEvent(PluginEvent.ValueChanged(widget.id, it.toString())) },
                colors = CheckboxDefaults.colors(checkedColor = style.accent),
            )
            Text(widget.label, color = style.text, fontSize = 13.sp)
        }

        is PluginWidget.Search -> InputWidget(PluginWidget.Input(widget.id, "", widget.placeholder, kind = "search", live = widget.live, submit = true), host, onEvent)

        is PluginWidget.Slider -> if (widget.stepper) StepperWidget(widget, onEvent) else SliderWidget(widget, onEvent)

        is PluginWidget.Select -> SelectWidget(widget, onEvent)

        is PluginWidget.Chips -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            for (option in widget.options) {
                val chosen = option.value in widget.value
                style.chip(option.label, chosen, true, Modifier) {
                    val next = when {
                        !widget.multi -> listOf(option.value)
                        chosen -> widget.value - option.value
                        else -> widget.value + option.value
                    }
                    onEvent(PluginEvent.ChipsChanged(widget.id, next, widget.multi))
                }
            }
        }

        is PluginWidget.Keys -> Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            for (row in widget.rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    for (key in row) {
                        Box(Modifier.weight(1f)) {
                            style.chip(key, false, true, Modifier.fillMaxWidth()) { onEvent(PluginEvent.KeyPressed(widget.id, key)) }
                        }
                    }
                }
            }
        }

        is PluginWidget.ListView -> Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
            if (widget.items.isEmpty()) {
                Text(
                    widget.empty.ifEmpty { stringResource(R.string.core_plugins_ui_list_empty) },
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            widget.items.forEachIndexed { index, item ->
                ItemRow(item) {
                    if (item.clickable) onEvent(PluginEvent.Click(item.id)) else if (widget.selectable) onEvent(PluginEvent.Selected(widget.id, index))
                }
                // An item's swipe actions go to the item's own id.
                ItemActions(item, onEvent)
            }
        }

        is PluginWidget.Item -> {
            ItemRow(widget) { if (widget.clickable) onEvent(PluginEvent.Click(widget.id)) }
            ItemActions(widget, onEvent)
        }

        is PluginWidget.Image -> ImageWidget(widget, onEvent, onInsert)

        is PluginWidget.Icon -> style.icon(widget.name, style.colorOf(widget.color), (widget.size ?: DEFAULT_ICON).dp)

        is PluginWidget.Qr -> style.qr(widget.text, (widget.size ?: DEFAULT_QR).dp)

        is PluginWidget.Stat -> CardBox(clickable = false, onClick = {}) {
            Text(widget.label, color = style.secondaryText, fontSize = 11.sp)
            Text(widget.value, color = style.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (widget.caption.isNotEmpty()) Text(widget.caption, color = style.secondaryText, fontSize = 11.sp)
        }

        is PluginWidget.Ring -> RingWidget(widget)

        is PluginWidget.Chart -> ChartWidget(widget)

        is PluginWidget.Canvas -> CanvasWidget(widget, onEvent)

        else -> Unit
    }
}

@Composable
private fun CardBox(clickable: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val style = LocalPluginPanelStyle.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.cardShape)
            .background(style.surface)
            .then(style.cardBorder(Modifier, style.cardShape))
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) { content() }
}

@Composable
private fun ItemRow(item: PluginWidget.Item, onClick: () -> Unit) {
    val style = LocalPluginPanelStyle.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.cardShape)
            .background(if (item.selected) style.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        item.icon?.let { style.icon(it, style.secondaryText, 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(item.title, color = style.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotEmpty()) {
                Text(item.subtitle, color = style.secondaryText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.trailing.isNotEmpty()) Text(item.trailing, color = style.secondaryText, fontSize = 12.sp)
    }
}

/**
 * A row's actions, drawn inline under it rather than in a popup: a keyboard's
 * popup window is the one thing apps may refuse touches through.
 */
@Composable
private fun ItemActions(item: PluginWidget.Item, onEvent: (PluginEvent) -> Unit) {
    if (item.actions.isEmpty()) return
    val style = LocalPluginPanelStyle.current
    var shown by rememberSaveable(item.id) { androidx.compose.runtime.mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp)) {
        style.chip(if (shown) "×" else "⋯", false, true, Modifier) { shown = !shown }
        if (shown) {
            for (label in item.actions) {
                style.chip(label, false, true, Modifier) {
                    shown = false
                    onEvent(PluginEvent.ItemAction(item.id, label))
                }
            }
        }
    }
}

@Composable
private fun SliderWidget(widget: PluginWidget.Slider, onEvent: (PluginEvent) -> Unit) {
    val style = LocalPluginPanelStyle.current
    var value by remember(widget.id, widget.value) { mutableFloatStateOf(widget.value.toFloat()) }
    val steps = ((widget.max - widget.min) / widget.step).toInt().let { if (it in 2..MAX_SLIDER_STEPS) it - 1 else 0 }
    Column(Modifier.fillMaxWidth()) {
        if (widget.label.isNotEmpty()) {
            Row {
                Text(widget.label, color = style.secondaryText, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(formatNumber(value.toDouble(), widget.step), color = style.secondaryText, fontSize = 11.sp)
            }
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            // One event when the finger lifts, not one per pixel.
            onValueChangeFinished = { onEvent(PluginEvent.NumberChanged(widget.id, snap(value.toDouble(), widget))) },
            valueRange = widget.min.toFloat()..widget.max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = style.accent, activeTrackColor = style.accent),
        )
    }
}

@Composable
private fun StepperWidget(widget: PluginWidget.Slider, onEvent: (PluginEvent) -> Unit) {
    val style = LocalPluginPanelStyle.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(widget.label, color = style.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        style.chip("−", false, widget.value > widget.min, Modifier) {
            onEvent(PluginEvent.NumberChanged(widget.id, snap(widget.value - widget.step, widget)))
        }
        Text(formatNumber(widget.value, widget.step), color = style.text, fontSize = 14.sp)
        style.chip("+", false, widget.value < widget.max, Modifier) {
            onEvent(PluginEvent.NumberChanged(widget.id, snap(widget.value + widget.step, widget)))
        }
    }
}

private fun snap(value: Double, widget: PluginWidget.Slider): Double {
    val steps = Math.round((value - widget.min) / widget.step)
    return (widget.min + steps * widget.step).coerceIn(widget.min, widget.max)
}

private fun formatNumber(value: Double, step: Double): String =
    if (step >= 1 && value == Math.floor(value)) value.toLong().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

/** One of several options. A dropdown opens inline, not in a popup, for the same reason as [ItemActions]. */
@Composable
private fun SelectWidget(widget: PluginWidget.Select, onEvent: (PluginEvent) -> Unit) {
    val style = LocalPluginPanelStyle.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (widget.label.isNotEmpty()) Text(widget.label, color = style.secondaryText, fontSize = 11.sp)
        if (widget.segmented) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (option in widget.options) {
                    style.chip(option.label, option.value == widget.value, true, Modifier) { onEvent(PluginEvent.ValueChanged(widget.id, option.value)) }
                }
            }
        } else {
            var open by remember(widget.id) { androidx.compose.runtime.mutableStateOf(false) }
            val current = widget.options.firstOrNull { it.value == widget.value }?.label ?: "—"
            style.chip("$current ▾", false, true, Modifier) { open = !open }
            if (open) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (option in widget.options) {
                        style.chip(option.label, option.value == widget.value, true, Modifier.fillMaxWidth()) {
                            open = false
                            onEvent(PluginEvent.ValueChanged(widget.id, option.value))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageWidget(widget: PluginWidget.Image, onEvent: (PluginEvent) -> Unit, onInsert: (String) -> Unit) {
    val style = LocalPluginPanelStyle.current
    val modifier = Modifier
        .fillMaxWidth()
        .then(widget.height?.let { Modifier.height(it.dp) } ?: Modifier.heightIn(max = DEFAULT_IMAGE.dp))
        .clip(style.cardShape)
        .then(if (widget.clickable) Modifier.clickable { onEvent(PluginEvent.Click(widget.id)) } else Modifier)
        .semantics { contentDescription = widget.alt }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (widget.src.startsWith("data:")) {
            val bitmap = remember(widget.src) { decodeData(widget.src) }
            if (bitmap != null) {
                Image(bitmap, widget.alt, modifier = modifier, contentScale = ContentScale.Fit)
            } else {
                Text(widget.alt, color = style.secondaryText, fontSize = 12.sp)
            }
        } else {
            style.image(widget.src, widget.alt, modifier)
        }
        if (widget.insertable) {
            style.chip(stringResource(R.string.core_plugins_ui_insert_action), false, true, Modifier) { onInsert(widget.src) }
        }
    }
}

private fun decodeData(src: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(src.removePrefix("data:"), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun RingWidget(widget: PluginWidget.Ring) {
    val style = LocalPluginPanelStyle.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
            Canvas(Modifier.size(64.dp)) {
                val stroke = Stroke(width = 6.dp.toPx())
                drawArc(style.surface, 0f, 360f, false, style = stroke)
                drawArc(style.accent, -90f, 360f * widget.value, false, style = stroke)
            }
            Text("${Math.round(widget.value * 100)}%", color = style.text, fontSize = 12.sp)
        }
        Column {
            if (widget.label.isNotEmpty()) Text(widget.label, color = style.text, fontSize = 14.sp)
            if (widget.caption.isNotEmpty()) Text(widget.caption, color = style.secondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChartWidget(widget: PluginWidget.Chart) {
    val style = LocalPluginPanelStyle.current
    val palette = listOf(style.accent, style.success, style.warning, style.danger, style.secondaryText)
    val height = (widget.height ?: if (widget.kind == "spark") SPARK_HEIGHT else CHART_HEIGHT).dp
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val all = widget.series.flatMap { it.values }
            if (all.isEmpty()) return@Canvas
            val low = minOf(0f, all.min())
            val high = maxOf(all.max(), low + 1e-6f)
            fun y(v: Float) = size.height - (v - low) / (high - low) * size.height
            val count = widget.series.maxOf { it.values.size }.coerceAtLeast(1)
            widget.series.forEachIndexed { s, series ->
                val color = palette[s % palette.size]
                if (widget.kind == "bar") {
                    val slot = size.width / count
                    val bar = slot / (widget.series.size + 1)
                    series.values.forEachIndexed { i, v ->
                        val top = y(v)
                        drawRect(color, Offset(i * slot + s * bar + bar / 2, minOf(top, y(0f))), Size(bar, kotlin.math.abs(y(0f) - top)))
                    }
                } else {
                    val step = if (count > 1) size.width / (count - 1) else 0f
                    for (i in 1 until series.values.size) {
                        drawLine(color, Offset((i - 1) * step, y(series.values[i - 1])), Offset(i * step, y(series.values[i])), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
        if (widget.labels.isNotEmpty() && widget.kind != "spark") {
            Row(Modifier.fillMaxWidth()) {
                for (label in widget.labels.take(MAX_AXIS_LABELS)) {
                    Text(label, color = style.secondaryText, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * A plugin's drawing, scaled to the panel's width. Taps and drags come back in
 * the drawing's own units. A drag sends its start and end, and at most one
 * move every [DRAG_EVENT_MILLIS].
 */
@Composable
private fun CanvasWidget(widget: PluginWidget.Canvas, onEvent: (PluginEvent) -> Unit) {
    val style = LocalPluginPanelStyle.current
    val measurer = rememberTextMeasurer()
    var scale by remember { mutableFloatStateOf(1f) }
    var last by remember { mutableLongStateOf(0L) }
    var modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(widget.width / widget.height)
        .clip(style.cardShape)
        .background(style.surface)
    if (widget.tappable) {
        modifier = modifier.pointerInput(widget.id) {
            detectTapGestures { at -> onEvent(PluginEvent.Tapped(widget.id, at.x / scale, at.y / scale)) }
        }
    }
    if (widget.draggable) {
        modifier = modifier.pointerInput(widget.id) {
            var x = 0f
            var y = 0f
            detectDragGestures(
                onDragStart = { at ->
                    x = at.x / scale
                    y = at.y / scale
                    onEvent(PluginEvent.Dragged(widget.id, x, y, 0f, 0f, "start"))
                },
                onDragEnd = { onEvent(PluginEvent.Dragged(widget.id, x, y, 0f, 0f, "end")) },
            ) { change, amount ->
                change.consume()
                x += amount.x / scale
                y += amount.y / scale
                val now = System.currentTimeMillis()
                if (now - last >= DRAG_EVENT_MILLIS) {
                    last = now
                    onEvent(PluginEvent.Dragged(widget.id, x, y, amount.x / scale, amount.y / scale, "move"))
                }
            }
        }
    }
    Canvas(modifier) {
        scale = size.width / widget.width
        val k = scale
        for (shape in widget.shapes) {
            val color = style.colorOf(shape.color)
            when (shape) {
                is PluginWidget.Canvas.Shape.Rect -> if (shape.fill) {
                    drawRoundRect(color, Offset(shape.x * k, shape.y * k), Size(shape.w * k, shape.h * k), CornerRadius(shape.radius * k))
                } else {
                    drawRoundRect(color, Offset(shape.x * k, shape.y * k), Size(shape.w * k, shape.h * k), CornerRadius(shape.radius * k), style = Stroke(1.dp.toPx()))
                }
                is PluginWidget.Canvas.Shape.Circle -> drawCircle(
                    color,
                    shape.r * k,
                    Offset(shape.x * k, shape.y * k),
                    style = if (shape.fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.dp.toPx()),
                )
                is PluginWidget.Canvas.Shape.Line -> drawLine(color, Offset(shape.x * k, shape.y * k), Offset(shape.x2 * k, shape.y2 * k), strokeWidth = shape.width * k)
                is PluginWidget.Canvas.Shape.Text -> drawText(
                    measurer,
                    shape.text,
                    Offset(shape.x * k, shape.y * k),
                    TextStyle(color = color, fontSize = (shape.size * k / density).sp),
                )
            }
        }
    }
}

/** Headings (`#`), bullets (`-`), and inline `**bold**`, `*italic*` and `` `code` ``. Links show as text. */
@Composable
private fun MarkdownText(source: String) {
    val style = LocalPluginPanelStyle.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        for (raw in source.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Box(Modifier.height(4.dp))
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(inline(line.drop(level).trim()), color = style.text, fontSize = (18 - level * 2).coerceAtLeast(13).sp, fontWeight = FontWeight.Medium)
                }
                line.startsWith("- ") || line.startsWith("* ") ->
                    Text(AnnotatedString("•  ") + inline(line.drop(2)), color = style.text, fontSize = 13.sp)
                else -> Text(inline(line), color = style.text, fontSize = 13.sp)
            }
        }
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val bold = text.startsWith("**", i)
        val code = text[i] == '`'
        val italic = !bold && (text[i] == '*' || text[i] == '_')
        val marker = when {
            bold -> "**"
            code -> "`"
            italic -> text[i].toString()
            else -> null
        }
        val end = marker?.let { text.indexOf(it, i + it.length) } ?: -1
        if (marker != null && end > i + marker.length) {
            val inner = text.substring(i + marker.length, end)
            val span = when {
                bold -> SpanStyle(fontWeight = FontWeight.Bold)
                code -> SpanStyle(fontFamily = FontFamily.Monospace)
                else -> SpanStyle(fontStyle = FontStyle.Italic)
            }
            withStyle(span) { append(inner) }
            i = end + marker.length
        } else {
            append(text[i])
            i++
        }
    }
}

private const val DEFAULT_SCROLL = 240
private const val DEFAULT_ICON = 24
private const val DEFAULT_QR = 160
private const val DEFAULT_IMAGE = 240
private const val CHART_HEIGHT = 120
private const val SPARK_HEIGHT = 32
private const val MAX_AXIS_LABELS = 12
private const val MAX_SLIDER_STEPS = 100
private const val DRAG_EVENT_MILLIS = 50L
