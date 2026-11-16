package com.wasimaster.wmkeyboard.core.plugins.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.plugins.PluginColorRole
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginWidget
import com.wasimaster.wmkeyboard.core.ui.FitText
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
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = style.accent,
            trackColor = style.surface,
            drawStopIndicator = {},
        )

        is PluginWidget.Grid -> Column(verticalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp), modifier = Modifier.fillMaxWidth()) {
            for (row in widget.children.chunked(widget.columns)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (child in row) {
                        Box(Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalFillCell provides true) {
                                PluginWidgetList(listOf(child), host, onEvent, onInsert, onCopy, ringed)
                            }
                        }
                    }
                    repeat(widget.columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }

        is PluginWidget.Card -> CardBox(
            clickable = widget.clickable,
            onClick = { onEvent(PluginEvent.Click(widget.id)) },
            onHold = if (widget.holdable) {
                { onEvent(PluginEvent.Held(widget.id, 0f, 0f)) }
            } else {
                null
            },
        ) {
            if (widget.title.isNotEmpty()) Text(widget.title, color = style.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            PluginWidgetColumnContent(widget.children, host, onEvent, onInsert, onCopy, ringed)
        }

        is PluginWidget.Section -> {
            var open by rememberSaveable(widget.id, widget.title) { mutableStateOf(widget.open) }
            Column(verticalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (widget.collapsible) ROW_HEIGHT else 32.dp)
                        .then(if (widget.collapsible) Modifier.clip(style.cardShape).clickable(role = Role.Button) { open = !open } else Modifier)
                        .padding(top = 8.dp),
                ) {
                    // The way a settings screen titles a group: small, in the accent.
                    Text(widget.title, color = style.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (widget.collapsible) style.icon(if (open) ICON_COLLAPSE else ICON_EXPAND, style.secondaryText, 22.dp)
                }
                if (open || !widget.collapsible) PluginWidgetColumnContent(widget.children, host, onEvent, onInsert, onCopy, ringed)
            }
        }

        is PluginWidget.Scroll -> Column(
            verticalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (widget.height ?: DEFAULT_SCROLL).dp)
                .verticalScroll(rememberScrollState()),
        ) { PluginWidgetColumnContent(widget.children, host, onEvent, onInsert, onCopy, ringed) }

        is PluginWidget.Markdown -> MarkdownText(widget.text)

        is PluginWidget.Empty -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
        ) {
            widget.icon?.let {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(style.surface),
                ) { style.icon(it, style.secondaryText, 32.dp) }
            }
            Text(widget.text, color = style.secondaryText, fontSize = 14.sp, textAlign = TextAlign.Center)
            if (widget.action.isNotEmpty()) PluginButton(widget.action, ButtonKind.FILLED) { onEvent(PluginEvent.Click(widget.id)) }
        }

        is PluginWidget.Checkbox -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = style.ring(Modifier.fillMaxWidth(), widget === ringed)
                .heightIn(min = ROW_HEIGHT)
                .clip(style.cardShape)
                .clickable(role = Role.Checkbox) { onEvent(PluginEvent.ValueChanged(widget.id, (!widget.checked).toString())) },
        ) {
            Checkbox(
                checked = widget.checked,
                onCheckedChange = { onEvent(PluginEvent.ValueChanged(widget.id, it.toString())) },
                colors = CheckboxDefaults.colors(
                    checkedColor = style.accentContainer,
                    checkmarkColor = style.onAccentContainer,
                    uncheckedColor = style.secondaryText,
                ),
            )
            Text(widget.label, color = style.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        }

        is PluginWidget.Search -> InputWidget(PluginWidget.Input(widget.id, "", widget.placeholder, kind = "search", live = widget.live, submit = true), host, onEvent)

        is PluginWidget.Slider -> if (widget.stepper) StepperWidget(widget, onEvent) else SliderWidget(widget, onEvent)

        is PluginWidget.Select -> SelectWidget(widget, onEvent)

        is PluginWidget.Chips -> Row(
            horizontalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp),
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

        // Keys a plugin draws are keys: the same height and spacing as a keypad.
        is PluginWidget.Keys -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (row in widget.rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    for (key in row) {
                        PluginButton(key, ButtonKind.TONAL, modifier = Modifier.weight(1f).heightIn(min = KEY_HEIGHT)) {
                            onEvent(PluginEvent.KeyPressed(widget.id, key))
                        }
                    }
                }
            }
        }

        is PluginWidget.ListView -> Column(modifier = Modifier.fillMaxWidth()) {
            if (widget.items.isEmpty()) {
                Text(
                    widget.empty.ifEmpty { stringResource(R.string.core_plugins_ui_list_empty) },
                    color = style.secondaryText,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
            widget.items.forEachIndexed { index, item ->
                ItemRow(item, onEvent) {
                    if (item.clickable) onEvent(PluginEvent.Click(item.id)) else if (widget.selectable) onEvent(PluginEvent.Selected(widget.id, index))
                }
            }
        }

        is PluginWidget.Item -> ItemRow(widget, onEvent) { if (widget.clickable) onEvent(PluginEvent.Click(widget.id)) }

        is PluginWidget.Image -> ImageWidget(widget, onEvent, onInsert)

        is PluginWidget.Icon -> style.icon(widget.name, style.colorOf(widget.color), (widget.size ?: DEFAULT_ICON).dp)

        is PluginWidget.Qr -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.clip(style.cardShape).background(Color.White).padding(10.dp),
        ) { style.qr(widget.text, (widget.size ?: DEFAULT_QR).dp) }

        is PluginWidget.Stat -> CardBox(clickable = false, onClick = {}) {
            // One line, narrowed to fit: "Green-Score" in a third of the panel
            // broke as "Green-Scor / e".
            FitText(widget.label, STAT_LABEL, color = style.secondaryText)
            Text(widget.value, color = style.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (widget.caption.isNotEmpty()) Text(widget.caption, color = style.secondaryText, fontSize = 12.sp)
        }

        is PluginWidget.Ring -> RingWidget(widget)

        is PluginWidget.Chart -> ChartWidget(widget)

        is PluginWidget.Canvas -> CanvasWidget(widget, onEvent)

        else -> Unit
    }
}

@Composable
private fun CardBox(clickable: Boolean, onClick: () -> Unit, onHold: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val style = LocalPluginPanelStyle.current
    Column(
        verticalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.cardShape)
            .then(style.glass(Modifier, style.cardShape))
            .background(style.surface)
            .then(style.cardBorder(Modifier, style.cardShape))
            .then(
                when {
                    clickable || onHold != null -> Modifier.combinedClickable(onLongClick = onHold, onClick = { if (clickable) onClick() })
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { content() }
}

/**
 * A list row the way Android draws one: a round icon, a title with a line
 * under it, something short at the end. A row with actions gets a ⋯ button at
 * its end, and the actions open inline under it rather than in a popup: a
 * keyboard's popup window is the one thing apps may refuse touches through.
 */
@Composable
private fun ItemRow(item: PluginWidget.Item, onEvent: (PluginEvent) -> Unit, onClick: () -> Unit) {
    val style = LocalPluginPanelStyle.current
    var shown by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(style.cardShape)
                .background(if (item.selected) style.accentContainer.copy(alpha = SELECTED_ALPHA) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(start = 8.dp, end = if (item.actions.isEmpty()) 12.dp else 0.dp, top = 8.dp, bottom = 8.dp),
        ) {
            item.icon?.let {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(style.surface),
                ) { style.icon(it, style.accent, 20.dp) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    color = if (item.selected) style.accent else style.text,
                    fontSize = 15.sp,
                    fontWeight = if (item.selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle.isNotEmpty()) {
                    Text(item.subtitle, color = style.secondaryText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (item.trailing.isNotEmpty()) Text(item.trailing, color = style.secondaryText, fontSize = 13.sp)
            if (item.actions.isNotEmpty()) {
                PluginIconButton(
                    icon = if (shown) ICON_COLLAPSE else ICON_MORE,
                    label = stringResource(if (shown) CommonR.string.common_close else CommonR.string.common_more),
                ) { shown = !shown }
            }
        }
        if (shown) {
            // An item's actions go to the item's own id.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 48.dp, bottom = 4.dp),
            ) {
                for (label in item.actions) {
                    PluginButton(label, ButtonKind.TONAL) {
                        shown = false
                        onEvent(PluginEvent.ItemAction(item.id, label))
                    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(widget.label, color = style.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(formatNumber(value.toDouble(), widget.step), color = style.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            // One event when the finger lifts, not one per pixel.
            onValueChangeFinished = { onEvent(PluginEvent.NumberChanged(widget.id, snap(value.toDouble(), widget))) },
            valueRange = widget.min.toFloat()..widget.max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = style.accent,
                activeTrackColor = style.accent,
                inactiveTrackColor = style.surface,
                activeTickColor = style.onAccentContainer.copy(alpha = TICK_ALPHA),
                inactiveTickColor = style.secondaryText.copy(alpha = TICK_ALPHA),
            ),
        )
    }
}

@Composable
private fun StepperWidget(widget: PluginWidget.Slider, onEvent: (PluginEvent) -> Unit) {
    val style = LocalPluginPanelStyle.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT),
    ) {
        Text(widget.label, color = style.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        PluginIconButton(ICON_REMOVE, stringResource(R.string.core_plugins_ui_decrease), enabled = widget.value > widget.min, tonal = true) {
            onEvent(PluginEvent.NumberChanged(widget.id, snap(widget.value - widget.step, widget)))
        }
        Text(
            formatNumber(widget.value, widget.step),
            color = style.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        PluginIconButton(ICON_ADD, stringResource(R.string.core_plugins_ui_increase), enabled = widget.value < widget.max, tonal = true) {
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

/**
 * One of several options: connected segments, or a field that opens its list
 * inline, not in a popup, for the same reason as a row's actions.
 */
@Composable
private fun SelectWidget(widget: PluginWidget.Select, onEvent: (PluginEvent) -> Unit) {
    val style = LocalPluginPanelStyle.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        if (widget.label.isNotEmpty()) Text(widget.label, color = style.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (widget.segmented) {
            Segmented(widget.options, widget.value) { onEvent(PluginEvent.ValueChanged(widget.id, it)) }
        } else {
            // The options open over the panel when the host has a layer for
            // them (the keyboard, the editor's preview); inline otherwise.
            val overlay = LocalPluginOverlay.current
            var inlineOpen by remember(widget.id) { mutableStateOf(false) }
            var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
            val menuOpen = overlay?.menu?.let { it.anchor == anchor && anchor != androidx.compose.ui.geometry.Rect.Zero } == true
            val open = inlineOpen || menuOpen
            val current = widget.options.firstOrNull { it.value == widget.value }?.label ?: "—"
            val shape = style.cardShape
            val pick = { value: String ->
                inlineOpen = false
                overlay?.close()
                onEvent(PluginEvent.ValueChanged(widget.id, value))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .then(style.glass(Modifier, shape))
                    .background(style.surface)
                    .then(if (open) Modifier.border(2.dp, style.accent, shape) else style.cardBorder(Modifier, shape)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .onGloballyPositioned { anchor = it.boundsInRoot() }
                        .clickable(role = Role.DropdownList) {
                            when {
                                overlay == null -> inlineOpen = !inlineOpen
                                menuOpen -> overlay.close()
                                else -> overlay.menu = OverlayMenu(anchor) {
                                    for (option in widget.options) {
                                        MenuOption(option.label, option.value == widget.value) { pick(option.value) }
                                    }
                                }
                            }
                        }
                        .padding(start = 16.dp, end = 12.dp),
                ) {
                    FitText(current, FIELD_VALUE, color = style.onSurface, modifier = Modifier.weight(1f))
                    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "dropdown arrow")
                    Box(Modifier.graphicsLayer { rotationZ = turn }) { style.icon(ICON_EXPAND, style.secondaryText, 22.dp) }
                }
                if (inlineOpen) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(style.divider))
                    for (option in widget.options) {
                        MenuOption(option.label, option.value == widget.value) { pick(option.value) }
                    }
                }
            }
        }
    }
}

/**
 * Material's connected segmented buttons: one track, the chosen segment
 * filled with a check. Up to four share the width; more scroll.
 */
@Composable
private fun Segmented(options: List<PluginWidget.Option>, value: String?, onPick: (String) -> Unit) {
    val style = LocalPluginPanelStyle.current
    val shape = style.buttonShape
    val fixed = options.size <= MAX_FIXED_SEGMENTS
    // Shares in proportion to each label's own width (plus room for the
    // check), not equal thirds: "English → Bengali" beside "Progress" gets
    // the room the short one doesn't use, rather than squeezing next to it.
    val measurer = rememberTextMeasurer()
    val shares = remember(options, measurer) {
        options.map { measurer.measure(it.label, BUTTON_LABEL, maxLines = 1, softWrap = false).size.width.toFloat() + SEGMENT_ROOM }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (fixed) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            .clip(shape)
            .then(style.glass(Modifier, shape))
            .background(style.surface)
            .then(style.cardBorder(Modifier, shape))
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val chosen = option.value == value
            // The fill, the words' colour and the check move together, so a
            // choice slides into place rather than snapping.
            val fill by animateColorAsState(if (chosen) style.accentContainer else Color.Transparent, label = "segment fill")
            val ink by animateColorAsState(if (chosen) style.onAccentContainer else style.onSurface, label = "segment ink")
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = (if (fixed) Modifier.weight(shares[index].coerceAtLeast(1f)) else Modifier)
                    .heightIn(min = 36.dp)
                    .clip(shape)
                    .background(fill)
                    .clickable(role = Role.RadioButton) { onPick(option.value) }
                    .padding(horizontal = if (fixed) 4.dp else 12.dp),
            ) {
                AnimatedVisibility(
                    visible = chosen,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn() + scaleIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut() + scaleOut(),
                ) {
                    Box(Modifier.padding(end = 6.dp)) { style.icon(ICON_CHECK, style.onAccentContainer, 16.dp) }
                }
                FitText(option.label, BUTTON_LABEL, color = ink)
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (widget.src.startsWith("data:")) {
            val bitmap = remember(widget.src) { decodeData(widget.src) }
            if (bitmap != null) {
                Image(bitmap, widget.alt, modifier = modifier, contentScale = ContentScale.Fit)
            } else {
                Text(widget.alt, color = style.secondaryText, fontSize = 13.sp)
            }
        } else {
            style.image(widget.src, widget.alt, modifier)
        }
        if (widget.insertable) {
            PluginButton(stringResource(R.string.core_plugins_ui_insert_action), ButtonKind.TONAL) { onInsert(widget.src) }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
            Canvas(Modifier.size(72.dp)) {
                val width = 8.dp.toPx()
                val inset = width / 2
                val arc = Size(size.width - width, size.height - width)
                drawArc(style.surface, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(width))
                if (widget.value > 0f) {
                    drawArc(style.accent, -90f, 360f * widget.value, false, Offset(inset, inset), arc, style = Stroke(width, cap = StrokeCap.Round))
                }
            }
            Text("${Math.round(widget.value * 100)}%", color = style.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (widget.label.isNotEmpty()) Text(widget.label, color = style.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (widget.caption.isNotEmpty()) Text(widget.caption, color = style.secondaryText, fontSize = 13.sp)
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
                        drawRoundRect(color, Offset(i * slot + s * bar + bar / 2, minOf(top, y(0f))), Size(bar, kotlin.math.abs(y(0f) - top)), CornerRadius(minOf(bar / 4, 3.dp.toPx())))
                    }
                } else {
                    val step = if (count > 1) size.width / (count - 1) else 0f
                    for (i in 1 until series.values.size) {
                        drawLine(color, Offset((i - 1) * step, y(series.values[i - 1])), Offset(i * step, y(series.values[i])), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
        }
        if (widget.labels.isNotEmpty() && widget.kind != "spark") {
            Row(Modifier.fillMaxWidth()) {
                for (label in widget.labels.take(MAX_AXIS_LABELS)) {
                    Text(label, color = style.secondaryText, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * A plugin's drawing, scaled to the panel's width. Taps, holds and drags come
 * back in the drawing's own units. A drag sends its start and end, and at most
 * one move every [DRAG_EVENT_MILLIS]; with [PluginWidget.Canvas.dragAfterHold]
 * it starts only after a long press, so a plain swipe scrolls the panel.
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
    val afterHold = widget.draggable && widget.dragAfterHold
    // A hold that starts a drag is the drag's own start, never a Held as well.
    val holds = widget.holdable && !afterHold
    if (widget.tappable || holds) {
        modifier = modifier.pointerInput(widget.id, widget.tappable, holds, afterHold) {
            detectTapGestures(
                onLongPress = when {
                    holds -> { at: Offset -> onEvent(PluginEvent.Held(widget.id, at.x / scale, at.y / scale)) }
                    // The hold belongs to the drag: lifting after it is not a tap.
                    afterHold -> { _: Offset -> }
                    else -> null
                },
                onTap = if (widget.tappable) {
                    { at: Offset -> onEvent(PluginEvent.Tapped(widget.id, at.x / scale, at.y / scale)) }
                } else {
                    null
                },
            )
        }
    }
    if (widget.draggable) {
        modifier = modifier.pointerInput(widget.id, afterHold) {
            var x = 0f
            var y = 0f
            val onStart = { at: Offset ->
                x = at.x / scale
                y = at.y / scale
                onEvent(PluginEvent.Dragged(widget.id, x, y, 0f, 0f, "start"))
            }
            val onEnd = { onEvent(PluginEvent.Dragged(widget.id, x, y, 0f, 0f, "end")) }
            val onMove = { change: PointerInputChange, amount: Offset ->
                change.consume()
                x += amount.x / scale
                y += amount.y / scale
                val now = System.currentTimeMillis()
                if (now - last >= DRAG_EVENT_MILLIS) {
                    last = now
                    onEvent(PluginEvent.Dragged(widget.id, x, y, amount.x / scale, amount.y / scale, "move"))
                }
            }
            if (afterHold) {
                detectDragGesturesAfterLongPress(onDragStart = onStart, onDragEnd = onEnd, onDragCancel = onEnd, onDrag = onMove)
            } else {
                detectDragGestures(onDragStart = onStart, onDragEnd = onEnd, onDrag = onMove)
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for (raw in source.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Box(Modifier.height(4.dp))
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(inline(line.drop(level).trim()), color = style.text, fontSize = (20 - level * 2).coerceAtLeast(15).sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                }
                line.startsWith("- ") || line.startsWith("* ") ->
                    Text(AnnotatedString("•  ") + inline(line.drop(2)), color = style.text, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                else -> Text(inline(line), color = style.text, fontSize = 14.sp)
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
private const val MAX_FIXED_SEGMENTS = 4
private val STAT_LABEL = TextStyle(fontSize = 13.sp)
private val FIELD_VALUE = TextStyle(fontSize = 15.sp)

/** What a segment needs besides its label, in px-ish units of the share: the check and the padding. */
private const val SEGMENT_ROOM = 80f
private const val SELECTED_ALPHA = 0.35f
private const val TICK_ALPHA = 0.6f
private val KEY_HEIGHT = 44.dp
