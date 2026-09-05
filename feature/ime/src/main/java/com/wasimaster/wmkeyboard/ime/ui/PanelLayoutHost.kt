package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The panels that are layouts now (issue #63), each hosted here: the grid the
 * user laid out, filled to the key area's height, with the panel's own
 * components in its field cells and its search mode left exactly as it was.
 *
 * The callbacks ride in one object so `KeyboardBody` builds it once from the
 * lambdas it already has and nothing new travels through `KeyboardScreen`'s
 * argument list, which sits under the JVM's method-size ceiling.
 */
@Immutable
internal class PanelLayoutCallbacks(
    val onKey: (Key) -> Unit,
    val onText: (String) -> Unit,
    val onCursorMove: (Int) -> Unit,
    val onLayoutSelect: (String) -> Unit,
    val onPanelChange: (PanelMode) -> Unit,
    val emoji: EmojiFieldCallbacks,
    val clipboard: ClipboardFieldCallbacks,
    val trackpad: TrackpadFieldCallbacks,
)

/**
 * What the trackpad surface reaches (issue #39). Every caret move is an edit
 * key through [onKey], the path the text-editing panel's own keys take, so it
 * arrives at the service's `onTextEdit` with the selection mode it already
 * honours; the two-finger tap is a real space key for the same reason. The
 * hold rides the Selection mode tool's hold callback: on at the long press,
 * off at the release, the selection left standing.
 */
@Immutable
internal class TrackpadFieldCallbacks(
    private val onKey: (Key) -> Unit,
    val onSelectionHold: (Boolean) -> Unit,
) {
    fun onEdit(op: TextEditAction) = onKey(Key("", action = KeyAction.Edit(op)))
    fun onSpace() = onKey(Key(" ", action = KeyAction.Space))
}

/** The layout the keyboard draws for [kind], from the state or the shipped set. */
internal fun KeyboardUiState.panelLayout(kind: PanelKind): PanelLayoutSpec =
    panelLayouts[kind] ?: BuiltInPanelLayouts.default(kind)

/**
 * The emoji panel. Search mode keeps its compact form — the key rows return
 * beneath it, they are how the query is typed — and the layout draws the rest
 * of the time, inside the full-bleed chrome when that setting is on.
 */
@Composable
internal fun EmojiPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val session = rememberEmojiPanelSession(state)
    // Toggling the open panel closes it — back to the keys.
    val onClose = { callbacks.onPanelChange(PanelMode.EMOJI) }
    if (state.emojiSearchActive) {
        EmojiSearchPanel(state, session, callbacks.emoji, onClose)
    } else {
        val spec = state.panelLayout(PanelKind.EMOJI)
        val fields: @Composable (PanelFieldKind) -> Unit = { kind ->
            EmojiField(kind, state, session, callbacks.emoji)
        }
        if (state.settings.emojiFullBleed) {
            // The tabs are a cell of the layout now, so the header is back and
            // the panel's name and nothing else.
            FullBleedTool(state, stringResource(R.string.ime_tool_emoji), onClose = onClose) {
                PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
            }
        } else {
            // The always-on emoji row hides while this panel is open; absorbing
            // its height keeps the keyboard from resizing on panel switches.
            val barCompensation =
                if (state.settings.emojiBarMode == EmojiBarMode.ALWAYS) EmojiBarHeight else 0.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(keyRowsHeight(state) + barCompensation),
            ) {
                PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
            }
        }
    }
    // A Popup overlay, so opening it never reflows the fixed-height panel.
    if (session.reorderOpen) {
        FavouritesReorderPopup(
            favourites = state.emojiFavourites,
            onConfirm = {
                session.reorderOpen = false
                callbacks.emoji.onFavouritesReorder(it)
            },
            onDismiss = { session.reorderOpen = false },
        )
    }
}

/**
 * The clipboard panel. Searching hands the key rows back and steps out of
 * full-bleed, as before; otherwise the layout fills the key area, inside the
 * full-bleed chrome when that setting is on.
 */
@Composable
internal fun ClipboardPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val session = rememberClipboardPanelSession(state)
    val onClose = { callbacks.onPanelChange(PanelMode.CLIPBOARD) }
    if (state.clipboardSearchActive) {
        // The panel shrinks to its search field plus a couple of result rows.
        // The toolbar row is hidden too (see KeyboardBody), so the panel
        // absorbs its height the way the emoji panel's search mode does.
        ClipboardSearchPanel(
            state, session, callbacks.clipboard,
            modifier = Modifier
                .fillMaxWidth()
                .height(ClipboardSearchHeight + topBarHeight(state.settings)),
        )
        return
    }
    val spec = state.panelLayout(PanelKind.CLIPBOARD)
    val fields: @Composable (PanelFieldKind) -> Unit = { kind ->
        ClipboardField(kind, state, session, callbacks.clipboard)
    }
    if (state.settings.clipboard.fullBleed) {
        // Full-bleed (opt-in): the toolbar row becomes the back header and the
        // reclaimed rows go to the history.
        FullBleedTool(state, stringResource(R.string.ime_tool_clipboard), onClose = onClose) {
            PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyRowsHeight(state)),
        ) {
            PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
        }
    }
}

/** The text-editing pad: keys only, filling the key area. */
@Composable
internal fun TextEditPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val onClose = { callbacks.onPanelChange(PanelMode.TEXT_EDIT) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.TEXT_EDIT), callbacks, onClose,
            fields = { _ -> },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The trackpad panel (issue #39): the pointing surface over whatever keys the
 * layout puts beside it, filling the key area. Never full-bleed, because the
 * toolbar has to stay for the hold gesture that opened it.
 */
@Composable
internal fun TrackpadPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val onClose = { callbacks.onPanelChange(PanelMode.TRACKPAD) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.TRACKPAD), callbacks, onClose,
            fields = { kind ->
                if (kind == PanelFieldKind.TRACKPAD) TrackpadField(state.settings.trackpad, callbacks.trackpad)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Panel height while the clipboard search bar is capturing the keys. */
internal val ClipboardSearchHeight = 132.dp
