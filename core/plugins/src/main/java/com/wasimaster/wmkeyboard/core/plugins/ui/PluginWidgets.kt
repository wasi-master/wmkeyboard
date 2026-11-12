package com.wasimaster.wmkeyboard.core.plugins.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.plugins.PluginDates
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginLabelStyle
import com.wasimaster.wmkeyboard.core.plugins.PluginWidget
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * How a plugin's widgets look, supplied by whichever screen draws them.
 *
 * The keyboard panel and the plugin editor's preview draw one plugin with one
 * renderer, so what an author sees in the preview is what a user will see in
 * the keyboard. What differs between the two is only this: the keyboard takes
 * its colours, shapes, chips and focus ring from the keyboard theme, and the
 * editor from Material.
 *
 * The chip is a slot rather than a pair of colours on purpose. The keyboard's
 * chip carries its own shape, outline and disabled dimming rule, and rebuilding
 * that from colours here is exactly how the two would start to look different.
 */
@Stable
class PluginPanelStyle(
    val text: Color,
    val secondaryText: Color,
    /** The fill behind cards, inputs and dividers. */
    val surface: Color,
    /** Text drawn on [surface]. */
    val onSurface: Color,
    val accent: Color,
    val cardShape: Shape,
    /** The outline an output card or an unfocused input wears. Returns the modifier unchanged for none. */
    val cardBorder: (Modifier, Shape) -> Modifier,
    /** The hardware focus ring. Returns the modifier unchanged where the host has no ring. */
    val ring: @Composable (Modifier, Boolean) -> Modifier,
    /** A pill button: its label, whether it is selected, whether it is enabled, its modifier, and its click. */
    val chip: @Composable (String, Boolean, Boolean, Modifier, () -> Unit) -> Unit,
    // ---- API 5 ----
    val danger: Color = Color(0xFFD93025),
    val success: Color = Color(0xFF1E8E3E),
    val warning: Color = Color(0xFFE37400),
    /** A Material Symbols glyph by name, in a colour and at a size. Draws nothing where the host has no icon set. */
    val icon: @Composable (String, Color, Dp) -> Unit = { _, _, _ -> },
    /**
     * A picture from `assets/<name>` or an `https://` address. The host loads
     * it: assets from the plugin's folder, addresses under the plugin's host
     * rules. The default shows the alt text.
     */
    val image: @Composable (String, String, Modifier) -> Unit = { _, alt, modifier -> Text(alt, color = secondaryText, fontSize = 12.sp, modifier = modifier) },
    /** A QR code for the text. The default shows the text. */
    val qr: @Composable (String, Dp) -> Unit = { value, _ -> Text(value, color = text, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
)

/** The style [PluginWidgetList] draws with. A host must provide one. */
val LocalPluginPanelStyle = staticCompositionLocalOf<PluginPanelStyle> {
    error("No PluginPanelStyle: wrap PluginWidgetList in CompositionLocalProvider(LocalPluginPanelStyle provides ...)")
}

/**
 * Where a plugin's input widgets get their text, and where their taps go.
 *
 * [onValueChange] is the one real difference between the hosts, and it is in the
 * type rather than behind a flag. The keyboard's is null: there is no text field
 * anywhere in the IME, because one fights the input connection, so a plugin's
 * box is a tap target and the keyboard routes keystrokes into it. The editor's
 * preview has no keyboard to route, so it passes one and gets a real field.
 */
@Stable
interface PluginInputHost {
    fun value(id: String): String

    fun isFocused(id: String): Boolean

    fun onFocus(id: String)

    fun onPaste(id: String)

    val onValueChange: ((id: String, text: String) -> Unit)?
}

/**
 * Draws [widgets] in order. [ringed] is the widget the hardware focus ring is on,
 * compared by identity. [onInsert] is the only route from a plugin's output into
 * the text the user is writing, and it is a button the host draws.
 */
@Composable
fun PluginWidgetList(
    widgets: List<PluginWidget>,
    host: PluginInputHost,
    onEvent: (PluginEvent) -> Unit,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    ringed: PluginWidget? = null,
) {
    for (widget in widgets) {
        PluginWidgetView(widget, host, onEvent, onInsert, onCopy, ringed)
    }
}

@Composable
private fun PluginWidgetView(
    widget: PluginWidget,
    host: PluginInputHost,
    onEvent: (PluginEvent) -> Unit,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    ringed: PluginWidget?,
) {
    val style = LocalPluginPanelStyle.current
    when (widget) {
        is PluginWidget.Column -> Column(
            verticalArrangement = Arrangement.spacedBy((widget.gap ?: 6).dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PluginWidgetList(widget.children, host, onEvent, onInsert, onCopy, ringed)
        }

        is PluginWidget.Row -> Row(
            horizontalArrangement = Arrangement.spacedBy((widget.gap ?: 6).dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            widget.children.forEachIndexed { i, child ->
                Box(modifier = Modifier.weight(widget.weights?.getOrNull(i) ?: 1f)) {
                    PluginWidgetView(child, host, onEvent, onInsert, onCopy, ringed)
                }
            }
        }

        is PluginWidget.Label -> Text(
            widget.text,
            color = when {
                widget.color != null -> style.colorOf(widget.color)
                widget.style == PluginLabelStyle.CAPTION -> style.secondaryText
                else -> style.text
            },
            fontSize = when (widget.style) {
                PluginLabelStyle.HEADLINE -> 20.sp
                PluginLabelStyle.TITLE -> 15.sp
                PluginLabelStyle.BODY -> 13.sp
                PluginLabelStyle.CAPTION -> 11.sp
            },
            fontWeight = if (widget.style == PluginLabelStyle.TITLE || widget.style == PluginLabelStyle.HEADLINE) FontWeight.Medium else FontWeight.Normal,
        )

        is PluginWidget.Output -> OutputWidget(widget, onInsert, onCopy)

        is PluginWidget.Button -> if (widget.icon != null && (widget.compact || widget.text.isEmpty())) {
            // An icon button: the label, when there is one, is what TalkBack reads.
            Box(
                contentAlignment = Alignment.Center,
                modifier = style.ring(Modifier, widget === ringed)
                    .clip(style.cardShape)
                    .clickable(enabled = widget.enabled) { onEvent(PluginEvent.Click(widget.id)) }
                    .semantics { contentDescription = widget.text }
                    .padding(6.dp),
            ) { style.icon(widget.icon, if (widget.color != null) style.colorOf(widget.color) else style.text, 22.dp) }
        } else {
            style.chip(
                widget.text,
                widget.primary,
                widget.enabled,
                style.ring(Modifier, widget === ringed),
            ) { onEvent(PluginEvent.Click(widget.id)) }
        }

        is PluginWidget.Toggle -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = style.ring(Modifier.fillMaxWidth(), widget === ringed),
        ) {
            Text(
                widget.label,
                color = style.text,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = widget.checked,
                onCheckedChange = { onEvent(PluginEvent.ToggleChanged(widget.id, it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = style.accent),
            )
        }

        is PluginWidget.Input -> Box(style.ring(Modifier, widget === ringed)) {
            InputWidget(widget, host, onEvent)
        }

        is PluginWidget.DatePicker -> DateWidget(widget, onEvent)

        is PluginWidget.Spacer -> Spacer(modifier = Modifier.height(widget.height.dp))

        PluginWidget.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(style.surface),
        )

        PluginWidget.Progress -> LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = style.accent,
            trackColor = style.surface,
        )

        is PluginWidget.Tabs -> TabsWidget(widget, host, onEvent, onInsert, onCopy)

        else -> PackageWidgetView(widget, host, onEvent, onInsert, onCopy, ringed)
    }
}

/**
 * A block of result text, with the host's own Insert and Copy buttons.
 *
 * That Insert button is the *only* way a plugin's output reaches the text the
 * user is writing. There is no API a script can call to type for them, so every
 * character a plugin contributes is one the user pressed a button to accept.
 */
@Composable
private fun OutputWidget(
    widget: PluginWidget.Output,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val style = LocalPluginPanelStyle.current
    val shape = style.cardShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(style.surface)
            .then(style.cardBorder(Modifier, shape))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            widget.text,
            color = style.onSurface,
            fontSize = 13.sp,
            fontFamily = if (widget.mono) FontFamily.Monospace else FontFamily.Default,
        )
        if (widget.text.isNotEmpty() && (widget.insertable || widget.copyable)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (widget.insertable) {
                    style.chip(stringResource(R.string.core_plugins_ui_insert_action), false, true, Modifier) {
                        onInsert(widget.text)
                    }
                }
                if (widget.copyable) {
                    style.chip(stringResource(CommonR.string.common_copy), false, true, Modifier) {
                        onCopy(widget.text)
                    }
                }
            }
        }
    }
}

/**
 * A plugin's text box.
 *
 * In the keyboard, not a text field: tapping it tells the service to route the
 * keys here, and the text drawn is whatever the service has collected. In the
 * editor's preview, a real field. The Paste button is in both, and is how the
 * user hands a plugin something they already had, in place of the clipboard and
 * text-reading APIs the sandbox deliberately does not have.
 */
@Composable
internal fun InputWidget(widget: PluginWidget.Input, host: PluginInputHost, onEvent: (PluginEvent) -> Unit) {
    val style = LocalPluginPanelStyle.current
    val value = host.value(widget.id)
    val focused = host.isFocused(widget.id)
    val edit = host.onValueChange
    // Focusing a box collapses the keyboard panel to make room for the key rows,
    // so the box the user just pressed can end up under them. Scroll it back
    // into view rather than leaving them typing into something they cannot see.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // A frame's grace: the panel is still resizing when the focus lands.
        delay(80)
        // Not runCatching: the panel closing cancels this effect, and swallowing
        // that would leave the coroutine running. See runCancellable's KDoc.
        runCancellable { requester.bringIntoView() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (widget.label.isNotEmpty()) {
            Text(widget.label, color = style.secondaryText, fontSize = 11.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (edit != null) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { edit(widget.id, it) },
                    placeholder = { Text(widget.placeholder, fontSize = 13.sp) },
                    maxLines = if (widget.multiline) 6 else 3,
                    singleLine = widget.submit && !widget.multiline,
                    visualTransformation = if (widget.kind == "password") PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardActions = KeyboardActions(
                        onDone = { if (widget.submit) onEvent(PluginEvent.Submitted(widget.id, value)) },
                        onSearch = { if (widget.submit) onEvent(PluginEvent.Submitted(widget.id, value)) },
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = if (widget.kind == "search") ImeAction.Search else ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
            } else {
                val fieldShape = style.cardShape
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(fieldShape)
                        .background(style.surface)
                        .then(
                            if (focused) {
                                // Focus wins over the theme's outline: the accent
                                // ring is what says "keys go here now".
                                Modifier.border(1.dp, style.accent, fieldShape)
                            } else {
                                style.cardBorder(Modifier, fieldShape)
                            },
                        )
                        .clickable { host.onFocus(widget.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        // A password box in the keyboard shows dots, like any other.
                        if (widget.kind == "password" && value.isNotEmpty()) "•".repeat(value.length) else value.ifEmpty { widget.placeholder },
                        color = if (value.isEmpty()) style.secondaryText else style.onSurface,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            style.chip(stringResource(CommonR.string.common_paste), false, true, Modifier) { host.onPaste(widget.id) }
        }
    }
}

/**
 * A plugin's date: the day with a button either side, the time with buttons
 * that move it, and Today and Clear.
 *
 * Buttons rather than a date picker dialog because the keyboard cannot open a
 * dialog: an input method has no activity to show one from, and a window of
 * its own over the app would fight the field the user is typing in. The same
 * buttons draw in the editor's preview, so an author sees what a user gets.
 *
 * Holds nothing. Each press sends the new value, and the date drawn is the one
 * the script handed back on its next render.
 */
@Composable
private fun DateWidget(widget: PluginWidget.DatePicker, onEvent: (PluginEvent) -> Unit) {
    val style = LocalPluginPanelStyle.current
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val zone = TimeZone.getDefault()
    val dateFormat = remember(locale, zone) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, DATE_SKELETON), locale).apply { timeZone = zone }
    }
    val timeFormat = remember(locale, zone) {
        DateFormat.getTimeFormat(context).apply { timeZone = zone }
    }
    fun send(value: Long?) = onEvent(PluginEvent.DateChanged(widget.id, value))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (widget.label.isNotEmpty()) {
            Text(widget.label, color = style.secondaryText, fontSize = 11.sp)
        }
        val value = widget.value
        if (value == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.core_plugins_ui_date_none),
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                style.chip(stringResource(R.string.core_plugins_ui_date_set), false, true, Modifier) {
                    send(PluginDates.initial(System.currentTimeMillis(), widget.time, zone))
                }
            }
        } else {
            DateSteps(widget, value, dateFormat, timeFormat, zone, ::send)
        }
    }
}

@Composable
private fun DateSteps(
    widget: PluginWidget.DatePicker,
    value: Long,
    dateFormat: java.text.DateFormat,
    timeFormat: java.text.DateFormat,
    zone: TimeZone,
    send: (Long?) -> Unit,
) {
    val style = LocalPluginPanelStyle.current
    val date = Date(value * MILLIS)
    StepRow(
        text = dateFormat.format(date),
        back = stringResource(R.string.core_plugins_ui_date_previous_day) to {
            send(PluginDates.addDays(value, -1, zone))
        },
        forward = stringResource(R.string.core_plugins_ui_date_next_day) to {
            send(PluginDates.addDays(value, 1, zone))
        },
    )
    if (widget.time) {
        StepRow(
            text = timeFormat.format(date),
            back = stringResource(R.string.core_plugins_ui_date_earlier, widget.step) to {
                send(PluginDates.addMinutes(value, -widget.step))
            },
            forward = stringResource(R.string.core_plugins_ui_date_later, widget.step) to {
                send(PluginDates.addMinutes(value, widget.step))
            },
            farBack = stringResource(R.string.core_plugins_ui_date_hour_earlier) to {
                send(PluginDates.addMinutes(value, -MINUTES_PER_HOUR))
            },
            farForward = stringResource(R.string.core_plugins_ui_date_hour_later) to {
                send(PluginDates.addMinutes(value, MINUTES_PER_HOUR))
            },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        style.chip(stringResource(R.string.core_plugins_ui_date_today), false, true, Modifier) {
            send(PluginDates.today(value, System.currentTimeMillis(), zone))
        }
        style.chip(stringResource(R.string.core_plugins_ui_date_clear), false, true, Modifier) { send(null) }
    }
}

/**
 * A value between buttons that move it. [back] and [forward] are drawn as
 * arrows and named for TalkBack by their first part; [farBack] and
 * [farForward], when given, are drawn outside them with their words showing.
 */
@Composable
private fun StepRow(
    text: String,
    back: Pair<String, () -> Unit>,
    forward: Pair<String, () -> Unit>,
    farBack: Pair<String, () -> Unit>? = null,
    farForward: Pair<String, () -> Unit>? = null,
) {
    val style = LocalPluginPanelStyle.current
    @Composable
    fun arrow(glyph: String, action: Pair<String, () -> Unit>) {
        val name = action.first
        style.chip(glyph, false, true, Modifier.semantics(mergeDescendants = true) { contentDescription = name }, action.second)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        farBack?.let { style.chip(it.first, false, true, Modifier, it.second) }
        arrow("‹", back)
        Text(
            text,
            color = style.text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        arrow("›", forward)
        farForward?.let { style.chip(it.first, false, true, Modifier, it.second) }
    }
}

/** Weekday, day, month and year, in whatever order and words the user's language uses. */
private const val DATE_SKELETON = "EEEdMMMyyyy"
private const val MILLIS = 1000L
private const val MINUTES_PER_HOUR = 60

@Composable
private fun TabsWidget(
    widget: PluginWidget.Tabs,
    host: PluginInputHost,
    onEvent: (PluginEvent) -> Unit,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val style = LocalPluginPanelStyle.current
    // Which tab is showing is the host's business, kept in the panel's own
    // saved state so it survives a recomposition without the script having to
    // track it or the service having to store it.
    var selected by rememberSaveable(widget.id) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, widget.pages.lastIndex)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            widget.pages.forEachIndexed { i, page ->
                val title = page.title.ifEmpty { stringResource(R.string.core_plugins_ui_tab_default_title, i + 1) }
                style.chip(title, i == index, true, Modifier) {
                    selected = i
                    onEvent(PluginEvent.TabSelected(widget.id, i))
                }
            }
        }
        PluginWidgetList(widget.pages[index].children, host, onEvent, onInsert, onCopy)
    }
}
