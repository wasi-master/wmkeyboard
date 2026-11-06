package com.wasimaster.wmkeyboard.ime.floating

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragHandle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInFull
import com.wasimaster.wmkeyboard.ime.CaptureSelectionAction
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockCallbacks
import com.wasimaster.wmkeyboard.ime.todo.TodoCallbacks
import com.wasimaster.wmkeyboard.ime.ui.CalculatorPanel
import com.wasimaster.wmkeyboard.ime.ui.CaptureCaretHandle
import com.wasimaster.wmkeyboard.ime.ui.ConverterCallbacks
import com.wasimaster.wmkeyboard.ime.ui.CurrencyPanel
import com.wasimaster.wmkeyboard.ime.ui.KeyboardThemeProvider
import com.wasimaster.wmkeyboard.ime.ui.LocalCaptureCaret
import com.wasimaster.wmkeyboard.ime.ui.NotesCallbacks
import com.wasimaster.wmkeyboard.ime.ui.NotesPanelHost
import com.wasimaster.wmkeyboard.ime.ui.UnitConverterPanel
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockPanel
import com.wasimaster.wmkeyboard.ime.ui.todo.TodoPanel

/**
 * What the floating box (#1155) needs of the keyboard to run a tool's panel
 * outside it: the same callback bundles the keyboard's own panels are handed,
 * so a tool behaves the same in the box as in the keyboard.
 */
@Immutable
internal class AppletCallbacks(
    val converter: ConverterCallbacks,
    val clock: ClockCallbacks,
    val todo: TodoCallbacks,
    val notes: NotesCallbacks,
    /** A result's Insert: into the field when the keyboard has one, else to the clipboard. */
    val onInsert: (String) -> Unit,
    val onPrefillConsumed: () -> Unit,
    /** A panel asking for another panel: opened in the keyboard. */
    val onPanelChange: (PanelMode) -> Unit,
    val onOpenRoute: (String) -> Unit,
    /** The tool's text box, as the keyboard's own caret handle reaches it. */
    val onCaretTap: (Int) -> Unit,
    val onSelect: (Int, Int) -> Unit,
    val onSelectionAction: (CaptureSelectionAction) -> Unit,
)

/** What the box shows; written by [FloatingApplet]. */
internal class FloatingAppletState {
    var panel by mutableStateOf<PanelMode?>(null)
    var title by mutableStateOf("")
    var icon by mutableStateOf<ImageVector?>(null)
}

/**
 * The floating box (#1155): a rounded card in the keyboard's theme with a
 * title bar to drag it by, a close button, the tool's own panel under it, and
 * a grip in the corner to resize it. Drags are read as raw screen points
 * ([onMove], [onResize]), because the window moves under the finger and
 * points measured inside it would chase themselves.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FloatingAppletFrame(
    box: FloatingAppletState,
    state: KeyboardUiState,
    callbacks: AppletCallbacks,
    onMove: (MotionEvent) -> Boolean,
    onResize: (MotionEvent) -> Boolean,
    onClose: () -> Unit,
    onOpenInKeyboard: () -> Unit,
) {
    val panel = box.panel ?: return
    KeyboardThemeProvider(state.settings) {
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.fillMaxSize().padding(FLOATING_APPLET_SHADOW_DP.dp)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(colors.surface)
                    .border(1.dp, colors.outlineVariant, shape),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The handle: the icon and the name, and the room around
                    // them, but not the buttons, which keep their own taps.
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInteropFilter(onTouchEvent = onMove)
                            .padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        box.icon?.let {
                            Icon(it, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            box.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Back into the keyboard, as its panel, for a tool that wants the keys.
                    IconButton(onClick = onOpenInKeyboard) {
                        Icon(
                            Icons.Outlined.OpenInFull,
                            contentDescription = stringResource(R.string.ime_applet_open_in_keyboard),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ime_applet_close),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The tool's own text box takes the keyboard's caret handle,
                    // as it does inside the keyboard, while the keys are the box's.
                    val caret = if (state.panel == PanelMode.NONE && state.toolUp(panel)) {
                        CaptureCaretHandle(
                            state.captureCaretIndex(),
                            callbacks.onCaretTap,
                            anchor = state.captureAnchorIndex(),
                            onSelect = callbacks.onSelect,
                            onSelectionAction = callbacks.onSelectionAction,
                        )
                    } else {
                        CaptureCaretHandle(0, {})
                    }
                    CompositionLocalProvider(LocalCaptureCaret provides caret) {
                        AppletPanel(panel, state, callbacks, onClose)
                    }
                }
                // The grip: drag it to resize, from the bottom right corner.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    val resize = stringResource(R.string.ime_applet_resize)
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(28.dp, 18.dp)
                            .semantics { contentDescription = resize }
                            .pointerInteropFilter(onTouchEvent = onResize),
                    )
                }
            }
        }
    }
}

/**
 * The tool's own panel, the one the keyboard draws for it, run in the box.
 * Only the [com.wasimaster.wmkeyboard.core.settings.AppletTools] have one here.
 */
@Composable
private fun AppletPanel(panel: PanelMode, state: KeyboardUiState, callbacks: AppletCallbacks, onClose: () -> Unit) {
    when (panel) {
        PanelMode.CALCULATOR -> CalculatorPanel(state, callbacks.onInsert, callbacks.converter)
        PanelMode.UNIT_CONVERT -> UnitConverterPanel(state, callbacks.onInsert, callbacks.converter, callbacks.onPrefillConsumed)
        PanelMode.CURRENCY -> CurrencyPanel(
            state = state,
            callbacks = callbacks.converter,
            onInsert = callbacks.onInsert,
            onPrefillConsumed = callbacks.onPrefillConsumed,
        )
        PanelMode.CLOCK -> ClockPanel(state = state, callbacks = callbacks.clock, onClose = onClose)
        PanelMode.TODO -> TodoPanel(state = state, callbacks = callbacks.todo, onClose = onClose)
        PanelMode.NOTES -> NotesPanelHost(
            state,
            callbacks.notes,
            // Notes closes itself by asking for its own panel: here that is the box.
            onPanelChange = { if (it == PanelMode.NOTES) onClose() else callbacks.onPanelChange(it) },
            onOpenRoute = callbacks.onOpenRoute,
        )
        else -> Unit
    }
}

/** Room round the card for its shadow, in dp. */
internal const val FLOATING_APPLET_SHADOW_DP = 10
