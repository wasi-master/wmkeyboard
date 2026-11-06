package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The keyboard test on every settings screen (#412): a small button that opens
 * a text field docked above the real keyboard, so a change can be tried on the
 * screen it was made on instead of by leaving for another app.
 *
 * One instance for the whole graph, published by the nav host, so the field and
 * what was typed into it survive moving between screens: every [WmScreen] frame
 * draws its own copy of the button and the field, and they all show this state.
 * Null when the user has switched the button off (Accessibility › Settings
 * app), and in the list pane of the two-pane layout while the pane beside it
 * has a screen of its own — one button on the window, not two.
 */
@Stable
internal class KeyboardPreviewState {
    /** Whether the field is up. */
    var open by mutableStateOf(false)

    /**
     * What the user has typed into it, with the caret and the keyboard's
     * composing region: the whole value, not only the text. Every screen's
     * frame draws its own field, and the String overload keeps the caret
     * inside each one, starting at 0, so after a screen change the next
     * letters went in front of what was there (#684). Held here, the field the
     * user arrives at carries on from where the one they left stopped.
     */
    var value by mutableStateOf(TextFieldValue())

    /**
     * How many screens are drawing the field right now. Two while a screen
     * pushes over another, none once the user has left for a screen that has
     * no frame of its own (a code editor, search) or that hides the button
     * (the theme editor) — and then the field is closed rather than left to
     * jump back up, keyboard and all, on the way back.
     */
    var hosts by mutableIntStateOf(0)

    /**
     * The keyboard the field brings up should open in resize mode: set by the
     * Resize by dragging row on Layout & size (#912), spent once it is asked.
     */
    var resizeOnShow by mutableStateOf(false)
}

internal val LocalKeyboardPreview = compositionLocalOf<KeyboardPreviewState?> { null }

/**
 * Closes [state]'s field once no screen has drawn it for a moment. Run once by
 * the nav host. The wait covers a screen change, where the frame being left can
 * let go of the field a frame before the arriving one picks it up — with
 * transitions off, in the very same frame.
 */
@Composable
internal fun CloseOrphanedKeyboardPreview(state: KeyboardPreviewState) {
    LaunchedEffect(state) {
        snapshotFlow { state.open && state.hosts == 0 }.collectLatest { orphaned ->
            if (!orphaned) return@collectLatest
            delay(OrphanGraceMs)
            state.open = false
        }
    }
}

private const val OrphanGraceMs = 400L

/** How long the field waits for its keyboard before asking for resize mode anyway. */
private const val ResizeWaitMs = 1_500L

/**
 * The button: a keyboard while the field is closed, the keyboard put away while
 * it is open. Small and in the secondary colour, so on a screen that has an
 * "add" button of its own the two read as one primary action and one aside.
 */
@Composable
internal fun KeyboardPreviewFab(state: KeyboardPreviewState) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Every screen draws its own, so without a shared key the button of the
    // screen being left blinks out as the next one's appears. The key sits on
    // a wrapper, not on the button: mid-flight sharedElement measures its
    // child at exactly the bounds it recorded, and the button's recorded
    // bounds are its 48dp touch target, so a fixed 48dp would stretch the
    // 40dp surface to fill it until the flight lands. The Box hands the button
    // loose constraints, so it keeps its own size the whole way.
    Box(Modifier.wmSharedElement(PreviewFabKey)) {
        SmallFloatingActionButton(
            onClick = {
                if (state.open) {
                    // Put away first: taking the field out of the tree drops its
                    // input session too, but not always the keyboard with it.
                    keyboard?.hide()
                    focus.clearFocus()
                }
                state.open = !state.open
            },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            if (state.open) {
                Icon(Icons.Outlined.KeyboardHide, stringResource(R.string.keyboard_preview_close))
            } else {
                Icon(Icons.Outlined.Keyboard, stringResource(R.string.keyboard_preview_open))
            }
        }
    }
}

private const val PreviewFabKey = "wm.fab.keyboard_preview"

/**
 * The field, in the frame's bottom bar: the frame then pads the body by its
 * height and floats the buttons above it, so nothing on the screen ends up
 * behind it or behind the keyboard under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyboardPreviewBar(state: KeyboardPreviewState) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    DisposableEffect(state) {
        state.hosts++
        onDispose { state.hosts-- }
    }
    // Pads itself by the keyboard below, so the window must not pan as well:
    // the two together threw the field up under the status bar.
    ResizeForKeyboard()
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // The window's own word on the keyboard, beside Compose's: on some phones
    // Compose's keyboard inset stays at nothing the first time the field
    // opens, until the app has been left and come back to, which left the
    // field under the keys and the keyboard's Back unable to close it (#1127).
    val view = LocalView.current
    val windowIme = rememberWindowImeBottom(view)
    // What the Row's own padding already covers: the larger of the two, as
    // their union pads.
    val covered = maxOf(
        WindowInsets.ime.getBottom(LocalDensity.current),
        WindowInsets.navigationBars.getBottom(LocalDensity.current),
    )
    // Closes with the keyboard: put away with back, or with the keyboard's own
    // hide key, the field has nothing left to show. Only once the keyboard has
    // been seen up, or the field would close on the frame it opens. Where the
    // window does not hand its insets down (Android 14 and older, which pan the
    // window instead) this never reads true, and the button closes the field.
    val imeVisible = WindowInsets.isImeVisible || windowIme > 0
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) seen = true else if (seen) state.open = false
    }
    // Typing into somebody else's keyboard tests nothing. Read again whenever
    // the keyboard comes or goes: the picker is a system dialog, so nothing
    // else here hears that the user switched.
    val ours = remember(imeVisible) { imeSelected(context) }
    val imeUp by rememberUpdatedState(imeVisible)
    LaunchedEffect(state.resizeOnShow) {
        if (!state.resizeOnShow) return@LaunchedEffect
        // Until the keyboard is up, or a moment for the windows that never
        // say so (see above). Resize mode on a keyboard that is not ours, or
        // not running, is nothing to ask for.
        withTimeoutOrNull(ResizeWaitMs) { snapshotFlow { imeUp }.first { it } }
        state.resizeOnShow = false
        if (imeSelected(context)) KeyboardAutomation.host?.startResize()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Whatever of the keyboard Compose has not been told about yet.
        val missing = with(LocalDensity.current) { (windowIme - covered).coerceAtLeast(0).toDp() }
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(bottom = missing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.value,
                onValueChange = { state.value = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                placeholder = { Text(stringResource(R.string.keyboard_preview_hint)) },
                maxLines = 4,
                trailingIcon = if (state.value.text.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { state.value = TextFieldValue() }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.keyboard_preview_clear))
                        }
                    }
                },
            )
            if (!ours) {
                IconButton(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                ) {
                    Icon(Icons.Outlined.KeyboardAlt, stringResource(R.string.keyboard_preview_switch))
                }
            }
        }
    }
}

/**
 * The keyboard's height over [view]'s window in pixels, as the window itself
 * reports it, or 0 while it is down. Read again on every layout of the
 * window, which a keyboard coming, going or changing height brings.
 */
@Composable
private fun rememberWindowImeBottom(view: View): Int {
    var bottom by remember(view) { mutableIntStateOf(windowImeBottom(view)) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { bottom = windowImeBottom(view) }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return bottom
}

private fun windowImeBottom(view: View): Int {
    // Before 11 the window is resized or panned for the keyboard rather than
    // told about it, and the compat reading is only an estimate.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return 0
    val raw = view.rootWindowInsets ?: return 0
    val insets = WindowInsetsCompat.toWindowInsetsCompat(raw, view)
    if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return 0
    return insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
}
