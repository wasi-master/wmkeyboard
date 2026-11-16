package com.wasimaster.wmkeyboard.core.plugins.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.plugins.PluginDates
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginLabelStyle
import com.wasimaster.wmkeyboard.core.plugins.PluginWidget
import com.wasimaster.wmkeyboard.core.ui.FitText
import com.wasimaster.wmkeyboard.core.ui.TextFitting
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
 * Only the [PluginWidget.Chips] filter row wears it now; buttons, tabs, fields
 * and lists are built here from the colours below, so they look the same in
 * both hosts and like the rest of the keyboard's own panels.
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
    /**
     * The fill of a filled button, a selected segment and a switch that is on,
     * and what is drawn on it. The keyboard passes its selected-chip pair, so a
     * plugin's main button is the colour the keyboard's own selected pills are.
     */
    val accentContainer: Color = accent,
    val onAccentContainer: Color = Color.White,
    /** The outline of buttons and segments. The keyboard passes its chip shape. */
    val buttonShape: Shape = cardShape,
    /**
     * What a raised surface (a button, a card, a field) wears under its fill.
     * The keyboard's glass themes blur the board through it; elsewhere nothing.
     */
    val glass: @Composable (Modifier, Shape) -> Modifier = { modifier, _ -> modifier },
)

/** The style [PluginWidgetList] draws with. A host must provide one. */
val LocalPluginPanelStyle = staticCompositionLocalOf<PluginPanelStyle> {
    error("No PluginPanelStyle: wrap PluginWidgetList in CompositionLocalProvider(LocalPluginPanelStyle provides ...)")
}

/**
 * Whether the widget being drawn sits in a share of a row or a grid cell. A
 * button there fills its share, so "a row splits the width evenly" is what the
 * user sees, rather than pills stranded at the left of invisible boxes.
 * Containers that run the full width set it back to false for their children.
 */
internal val LocalFillCell = compositionLocalOf { false }

/** True for a row's child that keeps its own width: a button there must not stretch over its neighbours. */
internal val LocalOwnWidth = compositionLocalOf { false }

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
    // The keyboard's Material typography gives every Text a 24 sp line, which
    // double-spaces anything smaller. A line in proportion to the font instead.
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(PanelTextStyle)) {
        for (widget in widgets) {
            PluginWidgetView(widget, host, onEvent, onInsert, onCopy, ringed)
        }
    }
}

private val PanelTextStyle = TextStyle(lineHeight = 1.35.em)

/** Draws [widgets] as the full-width children of a container: nothing in them fills a cell. */
@Composable
internal fun PluginWidgetColumnContent(
    widgets: List<PluginWidget>,
    host: PluginInputHost,
    onEvent: (PluginEvent) -> Unit,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    ringed: PluginWidget?,
) {
    CompositionLocalProvider(LocalFillCell provides false, LocalOwnWidth provides false) {
        PluginWidgetList(widgets, host, onEvent, onInsert, onCopy, ringed)
    }
}

/** What a button looks like: the one that matters, the ordinary one, or words alone. */
internal enum class ButtonKind { FILLED, TONAL, TEXT }

/**
 * A button drawn from the panel style. Forty dp tall so a thumb finds it,
 * label centred so it still reads when the button fills a share of a row.
 */
@Composable
internal fun PluginButton(
    label: String,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: String? = null,
    tint: Color? = null,
    onHold: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val style = LocalPluginPanelStyle.current
    val shape = style.buttonShape
    val fill = when (kind) {
        ButtonKind.FILLED -> tint ?: style.accentContainer
        ButtonKind.TONAL -> style.surface
        ButtonKind.TEXT -> Color.Transparent
    }
    val content = when (kind) {
        ButtonKind.FILLED -> if (tint != null) Color.White else style.onAccentContainer
        ButtonKind.TONAL -> tint ?: style.onSurface
        ButtonKind.TEXT -> tint ?: style.accent
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = BUTTON_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .then(if (kind == ButtonKind.TEXT) Modifier else style.glass(Modifier, shape))
            .background(fill)
            .then(if (kind == ButtonKind.TONAL) style.cardBorder(Modifier, shape) else Modifier)
            .combinedClickable(enabled = enabled, role = Role.Button, onLongClick = onHold, onClick = onClick)
            // Padding is room the label can't use: kept small, so a label in
            // a narrow share squeezes only when it has to.
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) style.icon(icon, content, 18.dp)
        if (label.isNotEmpty()) {
            // Squeezed, then shrunk, before an ellipsis: a short label beside
            // an icon in a narrow cell reads whole rather than as "Ope…".
            // A one-character label is a symbol key (a palette of α, ∫, ≤):
            // drawn at a size a symbol can be read at.
            val symbol = icon == null && label.codePointCount(0, label.length) == 1
            FitText(label, if (symbol) SYMBOL_LABEL else BUTTON_LABEL, color = content)
        }
    }
}

/** A round icon-only button. [label] is what TalkBack reads. */
@Composable
internal fun PluginIconButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    tonal: Boolean = false,
    onHold: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val style = LocalPluginPanelStyle.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(BUTTON_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .then(if (tonal) style.glass(Modifier, CircleShape).background(style.surface) else Modifier)
            .combinedClickable(enabled = enabled, role = Role.Button, onLongClick = onHold, onClick = onClick)
            .semantics { contentDescription = label },
    ) { style.icon(icon, tint ?: if (tonal) style.onSurface else style.secondaryText, 22.dp) }
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
            verticalArrangement = Arrangement.spacedBy((widget.gap ?: DEFAULT_GAP).dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PluginWidgetColumnContent(widget.children, host, onEvent, onInsert, onCopy, ringed)
        }

        is PluginWidget.Row -> RowWidget(widget, host, onEvent, onInsert, onCopy, ringed)

        is PluginWidget.Label -> Text(
            widget.text,
            color = when {
                widget.color != null -> style.colorOf(widget.color)
                widget.style == PluginLabelStyle.CAPTION -> style.secondaryText
                else -> style.text
            },
            fontSize = when (widget.style) {
                PluginLabelStyle.HEADLINE -> 22.sp
                PluginLabelStyle.TITLE -> 16.sp
                PluginLabelStyle.BODY -> 14.sp
                PluginLabelStyle.CAPTION -> 12.sp
            },
            fontWeight = if (widget.style == PluginLabelStyle.TITLE || widget.style == PluginLabelStyle.HEADLINE) FontWeight.Medium else FontWeight.Normal,
        )

        is PluginWidget.Output -> OutputWidget(widget, onInsert, onCopy)

        is PluginWidget.Button -> if (widget.icon != null && (widget.compact || widget.text.isEmpty())) {
            // An icon button: the label, when there is one, is what TalkBack reads.
            PluginIconButton(
                icon = widget.icon,
                label = widget.text,
                enabled = widget.enabled,
                tint = widget.color?.let { style.colorOf(it) } ?: style.text,
                modifier = style.ring(Modifier, widget === ringed),
                onHold = if (widget.holdable) {
                    { onEvent(PluginEvent.Held(widget.id, 0f, 0f)) }
                } else {
                    null
                },
            ) { onEvent(PluginEvent.Click(widget.id)) }
        } else {
            // In a row's share it fills the share; on a line of its own it
            // runs the width too, centred, rather than a pill stranded at the
            // left with the rest of the line empty (LocalFillCell false there
            // means "drawn alone", since a row's own-width child is wrapped).
            PluginButton(
                label = widget.text,
                kind = if (widget.primary) ButtonKind.FILLED else ButtonKind.TONAL,
                enabled = widget.enabled,
                icon = widget.icon,
                tint = widget.color?.let { style.colorOf(it) },
                modifier = style.ring(if (LocalOwnWidth.current) Modifier else Modifier.fillMaxWidth(), widget === ringed),
                onHold = if (widget.holdable) {
                    { onEvent(PluginEvent.Held(widget.id, 0f, 0f)) }
                } else {
                    null
                },
            ) { onEvent(PluginEvent.Click(widget.id)) }
        }

        is PluginWidget.Toggle -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = style.ring(Modifier.fillMaxWidth(), widget === ringed)
                .heightIn(min = ROW_HEIGHT)
                .clickable(role = Role.Switch) { onEvent(PluginEvent.ToggleChanged(widget.id, !widget.checked)) },
        ) {
            Text(
                widget.label,
                color = style.text,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = widget.checked,
                onCheckedChange = { onEvent(PluginEvent.ToggleChanged(widget.id, it)) },
                colors = style.switchColors(),
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
                .padding(vertical = 4.dp)
                .height(1.dp)
                .background(style.divider),
        )

        PluginWidget.Progress -> LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = style.accent,
            trackColor = style.surface,
        )

        is PluginWidget.Tabs -> TabsWidget(widget, host, onEvent, onInsert, onCopy)

        else -> PackageWidgetView(widget, host, onEvent, onInsert, onCopy, ringed)
    }
}

/** A hairline between parts, faint enough not to compete with the content. */
internal val PluginPanelStyle.divider: Color get() = secondaryText.copy(alpha = DIVIDER_ALPHA)

/** A switch in the panel's colours, Material's shape. */
@Composable
internal fun PluginPanelStyle.switchColors() = SwitchDefaults.colors(
    checkedThumbColor = onAccentContainer,
    checkedTrackColor = accentContainer,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = secondaryText,
    uncheckedTrackColor = surface,
    uncheckedBorderColor = secondaryText,
)

/**
 * A row. Given weights, each child gets its share. Otherwise a row of buttons
 * splits the width evenly, like a button bar, and in a mixed row the buttons
 * keep their own width while the text, fields and everything else share the
 * rest — so a box with an Add button beside it is a wide box and a small
 * button, not two halves.
 */
@Composable
private fun RowWidget(
    widget: PluginWidget.Row,
    host: PluginInputHost,
    onEvent: (PluginEvent) -> Unit,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    ringed: PluginWidget?,
) {
    val weights = widget.weights
    val allButtons = widget.children.all { it is PluginWidget.Button }
    Row(
        horizontalArrangement = Arrangement.spacedBy((widget.gap ?: DEFAULT_GAP).dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        widget.children.forEachIndexed { i, child ->
            val share = when {
                weights != null -> weights.getOrNull(i) ?: 1f
                allButtons || child.flexible -> 1f
                else -> null
            }
            if (share != null && share > 0f) {
                Box(Modifier.weight(share), contentAlignment = Alignment.CenterStart) {
                    CompositionLocalProvider(LocalFillCell provides true, LocalOwnWidth provides false) {
                        PluginWidgetList(listOf(child), host, onEvent, onInsert, onCopy, ringed)
                    }
                }
            } else {
                CompositionLocalProvider(LocalFillCell provides false, LocalOwnWidth provides true) {
                    PluginWidgetList(listOf(child), host, onEvent, onInsert, onCopy, ringed)
                }
            }
        }
    }
}

/** Whether a widget takes the room a row has left over, rather than its own width. */
private val PluginWidget.flexible: Boolean
    get() = when (this) {
        is PluginWidget.Button,
        is PluginWidget.Icon,
        is PluginWidget.Qr,
        is PluginWidget.Spacer,
        is PluginWidget.Ring,
        -> false
        else -> true
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
    val actions = widget.text.isNotEmpty() && (widget.insertable || widget.copyable)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(style.glass(Modifier, shape))
            .background(style.surface)
            .then(style.cardBorder(Modifier, shape))
            .padding(start = 16.dp, end = 8.dp, top = if (actions) 6.dp else 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Copy and Insert above the text, so a long result can be taken
        // without scrolling past it first.
        if (actions) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (widget.copyable) {
                    PluginButton(stringResource(CommonR.string.common_copy), ButtonKind.TEXT, icon = ICON_COPY) {
                        onCopy(widget.text)
                    }
                }
                if (widget.insertable) {
                    PluginButton(stringResource(R.string.core_plugins_ui_insert_action), ButtonKind.FILLED) {
                        onInsert(widget.text)
                    }
                }
            }
        }
        Text(
            widget.text,
            color = style.onSurface,
            fontSize = 15.sp,
            fontFamily = if (widget.mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

/**
 * A plugin's text box.
 *
 * In the keyboard, not a text field: tapping it tells the service to route the
 * keys here, and the text drawn is whatever the service has collected. In the
 * editor's preview, a real field. The paste button sits inside the box in
 * both, and is how the user hands a plugin something they already had, in
 * place of the clipboard and text-reading APIs the sandbox deliberately does
 * not have.
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
    val pasteLabel = stringResource(CommonR.string.common_paste)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (widget.label.isNotEmpty()) {
            Text(widget.label, color = style.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (edit != null) {
            OutlinedTextField(
                value = value,
                onValueChange = { edit(widget.id, it) },
                placeholder = { Text(widget.placeholder, fontSize = 15.sp) },
                maxLines = if (widget.multiline) 6 else 3,
                singleLine = widget.submit && !widget.multiline,
                visualTransformation = if (widget.kind == "password") PasswordVisualTransformation() else VisualTransformation.None,
                keyboardActions = KeyboardActions(
                    onDone = { if (widget.submit) onEvent(PluginEvent.Submitted(widget.id, value)) },
                    onSearch = { if (widget.submit) onEvent(PluginEvent.Submitted(widget.id, value)) },
                ),
                keyboardOptions = KeyboardOptions(imeAction = if (widget.kind == "search") ImeAction.Search else ImeAction.Done),
                leadingIcon = if (widget.kind == "search") ({ style.icon(ICON_SEARCH, style.secondaryText, 20.dp) }) else null,
                trailingIcon = { PluginIconButton(ICON_PASTE, pasteLabel) { host.onPaste(widget.id) } },
                shape = style.cardShape,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val fieldShape = style.cardShape
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FIELD_HEIGHT)
                    .clip(fieldShape)
                    .then(style.glass(Modifier, fieldShape))
                    .background(style.surface)
                    .then(
                        if (focused) {
                            // Focus wins over the theme's outline: the accent
                            // ring is what says "keys go here now".
                            Modifier.border(2.dp, style.accent, fieldShape)
                        } else {
                            style.cardBorder(Modifier, fieldShape)
                        },
                    )
                    .clickable { host.onFocus(widget.id) }
                    .padding(start = if (widget.kind == "search") 12.dp else 16.dp, end = 4.dp),
            ) {
                if (widget.kind == "search") {
                    style.icon(ICON_SEARCH, style.secondaryText, 20.dp)
                    Spacer(Modifier.width(10.dp))
                }
                // One line scrolls sideways to keep the end in view, as a
                // field does, instead of cutting the text with "…".
                val sideways = rememberScrollState()
                if (!widget.multiline) {
                    // The hint reads from its start; only typed text keeps its end in view.
                    LaunchedEffect(value, focused) { sideways.scrollTo(if (value.isEmpty()) 0 else sideways.maxValue) }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (widget.multiline) Modifier else Modifier.horizontalScroll(sideways, enabled = false))
                        .padding(vertical = 12.dp),
                ) {
                    val caret = @Composable {
                        // The caret, so the box that is taking the keys says so
                        // even before the first one lands.
                        Box(
                            Modifier
                                .padding(start = if (value.isEmpty()) 0.dp else 1.dp)
                                .width(2.dp)
                                .height(20.dp)
                                .background(style.accent),
                        )
                    }
                    if (value.isEmpty()) {
                        // The caret goes where typing starts, before the hint,
                        // and the hint is faint so it never reads as text.
                        if (focused) caret()
                        Text(
                            widget.placeholder,
                            color = style.secondaryText.copy(alpha = style.secondaryText.alpha * HINT_ALPHA),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = if (focused) 2.dp else 0.dp),
                        )
                    } else {
                        Text(
                            // A password box in the keyboard shows dots, like any other.
                            if (widget.kind == "password") "•".repeat(value.length) else value,
                            color = style.onSurface,
                            fontSize = 15.sp,
                            maxLines = if (widget.multiline) MULTILINE_LINES else 1,
                            softWrap = widget.multiline,
                            overflow = if (widget.multiline) TextOverflow.Ellipsis else TextOverflow.Clip,
                            modifier = if (widget.multiline) Modifier.weight(1f, fill = false) else Modifier,
                        )
                        if (focused) caret()
                    }
                }
                PluginIconButton(ICON_PASTE, pasteLabel) { host.onPaste(widget.id) }
            }
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (widget.label.isNotEmpty()) {
            Text(widget.label, color = style.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        val value = widget.value
        if (value == null) {
            FieldBox {
                Text(
                    stringResource(R.string.core_plugins_ui_date_none),
                    color = style.secondaryText,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                PluginButton(stringResource(R.string.core_plugins_ui_date_set), ButtonKind.TEXT, icon = ICON_ADD) {
                    send(PluginDates.initial(System.currentTimeMillis(), widget.time, zone))
                }
            }
        } else {
            DateSteps(widget, value, dateFormat, timeFormat, zone, ::send)
        }
    }
}

/** The field-shaped strip a date or a dropdown sits in. */
@Composable
private fun FieldBox(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val style = LocalPluginPanelStyle.current
    val shape = style.cardShape
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FIELD_HEIGHT)
            .clip(shape)
            .then(style.glass(Modifier, shape))
            .background(style.surface)
            .then(style.cardBorder(Modifier, shape))
            .padding(horizontal = 4.dp),
        content = content,
    )
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
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        PluginButton(stringResource(R.string.core_plugins_ui_date_clear), ButtonKind.TEXT) { send(null) }
        PluginButton(stringResource(R.string.core_plugins_ui_date_today), ButtonKind.TONAL) {
            send(PluginDates.today(value, System.currentTimeMillis(), zone))
        }
    }
}

/**
 * A value between buttons that move it, in a field-shaped strip. [back] and
 * [forward] are drawn as arrows and named for TalkBack by their first part;
 * [farBack] and [farForward], when given, are drawn outside them with their
 * words showing.
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
    FieldBox {
        farBack?.let { PluginButton(it.first, ButtonKind.TEXT, onClick = it.second) }
        PluginIconButton(ICON_PREVIOUS, back.first, tint = style.onSurface, onClick = back.second)
        Text(
            text,
            color = style.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PluginIconButton(ICON_NEXT, forward.first, tint = style.onSurface, onClick = forward.second)
        farForward?.let { PluginButton(it.first, ButtonKind.TEXT, onClick = it.second) }
    }
}

/** Weekday, day, month and year, in whatever order and words the user's language uses. */
private const val DATE_SKELETON = "EEEdMMMyyyy"
private const val MILLIS = 1000L
private const val MINUTES_PER_HOUR = 60

/**
 * Tabs the Material way: words, with a bar under the one showing. Not pills,
 * so a tab never looks like the plugin's main button. Up to four share the
 * width; more scroll sideways rather than being cut off.
 */
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
    // track it, and remembered per plugin so it opens on the page it was
    // left on. A script that keeps its own idea of the page hears the
    // restored one as if it had been tapped.
    val pluginId = LocalPluginId.current
    val context = LocalContext.current
    val restored = remember(pluginId, widget.id) {
        pluginId?.let { PluginTabMemory.get(context, it, widget.id) }?.takeIf { it in widget.pages.indices } ?: 0
    }
    var selected by rememberSaveable(widget.id) { mutableIntStateOf(restored) }
    LaunchedEffect(pluginId, widget.id) {
        if (restored != 0) onEvent(PluginEvent.TabSelected(widget.id, restored))
    }
    val index = selected.coerceIn(0, widget.pages.lastIndex)
    val fixed = widget.pages.size <= FIXED_TABS
    val tabFitting = TextFitting.DEFAULT
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = if (fixed) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                widget.pages.forEachIndexed { i, page ->
                    val title = page.title.ifEmpty { stringResource(R.string.core_plugins_ui_tab_default_title, i + 1) }
                    val on = i == index
                    val ink by animateColorAsState(if (on) style.accent else style.secondaryText, label = "tab ink")
                    val bar by animateColorAsState(if (on) style.accent else Color.Transparent, label = "tab bar")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = (if (fixed) Modifier.weight(1f) else Modifier)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Tab) {
                                selected = i
                                pluginId?.let { PluginTabMemory.set(context, it, widget.id, i) }
                                onEvent(PluginEvent.TabSelected(widget.id, i))
                            }
                            // Shared tabs keep a little room each and narrow
                            // their words to fit, as the suggestion strip does.
                            .padding(horizontal = if (fixed) 4.dp else 16.dp)
                            .padding(top = 10.dp),
                    ) {
                        Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                            FitText(title, TAB_LABEL, color = ink, fitting = tabFitting)
                            Spacer(Modifier.height(10.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(bar),
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(style.divider))
        }
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val forward = targetState > initialState
                (fadeIn() + slideInHorizontally { w -> if (forward) w / TAB_SLIDE else -w / TAB_SLIDE }) togetherWith
                    (fadeOut() + slideOutHorizontally { w -> if (forward) -w / TAB_SLIDE else w / TAB_SLIDE })
            },
            label = "tab page",
        ) { page ->
            Column(verticalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp), modifier = Modifier.fillMaxWidth()) {
                PluginWidgetColumnContent(widget.pages[page.coerceIn(0, widget.pages.lastIndex)].children, host, onEvent, onInsert, onCopy, null)
            }
        }
    }
}

private val BUTTON_HEIGHT = 40.dp

/** A button's label before [FitText] squeezes or shrinks it. */
internal val BUTTON_LABEL = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)

/** A one-character button's label: a symbol, larger than a word. */
private val SYMBOL_LABEL = TextStyle(fontSize = 20.sp)
private val FIELD_HEIGHT = 52.dp
internal val ROW_HEIGHT = 48.dp
private const val DISABLED_ALPHA = 0.38f
private const val DIVIDER_ALPHA = 0.2f
private const val MULTILINE_LINES = 4

/** How faint a box's hint is, against the panel's secondary text. */
private const val HINT_ALPHA = 0.6f
/** Up to this many tabs share the width, their words narrowed to fit; more scroll. */
private const val FIXED_TABS = 6
private const val TAB_SLIDE = 8
private val TAB_LABEL = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
internal const val DEFAULT_GAP = 8

// Icon names the renderer asks its host for. Every host's icon set has these.
internal const val ICON_PASTE = "paste"
internal const val ICON_COPY = "copy"
internal const val ICON_SEARCH = "search"
internal const val ICON_ADD = "add"
internal const val ICON_REMOVE = "remove"
internal const val ICON_PREVIOUS = "chevron_left"
internal const val ICON_NEXT = "chevron_right"
internal const val ICON_EXPAND = "chevron_down"
internal const val ICON_COLLAPSE = "chevron_up"
internal const val ICON_CHECK = "check"
internal const val ICON_MORE = "more"
