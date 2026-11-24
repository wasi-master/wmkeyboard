package com.wasimaster.wmkeyboard.ime.wmchroma

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the WM Chroma panel is: what WM Chroma answered when it was asked for the library. */
sealed interface ChromaPhase {
    data object Loading : ChromaPhase

    /** Not installed, or installed and not the real one. */
    data object Missing : ChromaPhase

    /** Installed, with Work with WM Keyboard off in its settings. */
    data object Off : ChromaPhase

    data object Failed : ChromaPhase

    data class Ready(val library: ChromaLibrary) : ChromaPhase
}

/** Everything the WM Chroma panel draws, owned by [ChromaController] as MusicUi is by its controller. */
data class ChromaUi(
    val phase: ChromaPhase = ChromaPhase.Loading,
    /** A colour held down: its formats, to type one of them. */
    val formats: ChromaDescribed? = null,
)

sealed interface ChromaAction {
    /** Open WM Chroma's pick from the screen; the colour is typed when the keyboard comes back. */
    data object Pick : ChromaAction

    /** Type a colour as WM Chroma wrote it. */
    data class Insert(val text: String) : ChromaAction

    /** A colour held down: ask WM Chroma for its other formats. */
    data class Formats(val colour: ChromaColour) : ChromaAction

    data object CloseFormats : ChromaAction

    data object OpenApp : ChromaAction

    data object GetApp : ChromaAction

    data object Retry : ChromaAction

    data object OpenSettings : ChromaAction
}

/** What the WM Chroma panel hands back; one bundle, see `ToolHoldCallbacks.chroma`. */
data class ChromaCallbacks(
    val state: StateFlow<ChromaUi> = MutableStateFlow(ChromaUi()),
    val onAction: (ChromaAction) -> Unit = {},
)

/**
 * The WM Chroma tool: the person's palettes, recent colours and tray from WM Chroma, typed with a
 * tap, and a pick from the screen that comes back typed. Everything is asked of WM Chroma when
 * the panel opens; nothing is kept here but the token of a pick on its way back.
 */
internal class ChromaController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    private val panelOpen: () -> Boolean,
    private val closePanel: () -> Unit,
    private val openToolSettings: () -> Unit,
) {
    private val state = MutableStateFlow(ChromaUi())
    val callbacks = ChromaCallbacks(state = state, onAction = ::dispatch)

    private var loadJob: Job? = null

    fun onPanelOpen() {
        state.update { it.copy(formats = null) }
        load()
    }

    /**
     * The keyboard is back over a field. If a pick was opened from here, WM Chroma may have its
     * colour ready: collect it and type it. Still picking leaves the token for the next return.
     */
    fun onKeyboardShown() {
        if (!WmChromaBridge.pickPending(context)) return
        scope.launch {
            val answer = withContext(Dispatchers.IO) { WmChromaBridge.takePick(context) }
            if (answer is ChromaAnswer.Ok) {
                insertText(answer.value.colour.text)
                // The panel was left open to wait; the colour is typed, so the keys come back.
                if (panelOpen()) closePanel()
            }
        }
    }

    private fun dispatch(action: ChromaAction) {
        when (action) {
            ChromaAction.Pick -> {
                haptic()
                if (!WmChromaBridge.startPick(context)) state.update { it.copy(phase = ChromaPhase.Missing) }
            }
            is ChromaAction.Insert -> {
                insertText(action.text)
                state.update { it.copy(formats = null) }
            }
            is ChromaAction.Formats -> {
                haptic()
                scope.launch {
                    val answer = withContext(Dispatchers.IO) { WmChromaBridge.describe(context, action.colour.text) }
                    if (answer is ChromaAnswer.Ok) state.update { it.copy(formats = answer.value) }
                }
            }
            ChromaAction.CloseFormats -> state.update { it.copy(formats = null) }
            ChromaAction.OpenApp -> WmChromaBridge.open(context)
            ChromaAction.GetApp -> WmChromaBridge.openStore(context)
            ChromaAction.Retry -> load()
            ChromaAction.OpenSettings -> openToolSettings()
        }
    }

    private fun load() {
        loadJob?.cancel()
        if (state.value.phase !is ChromaPhase.Ready) state.update { it.copy(phase = ChromaPhase.Loading) }
        loadJob = scope.launch {
            val answer = withContext(Dispatchers.IO) { WmChromaBridge.library(context) }
            val phase = when (answer) {
                is ChromaAnswer.Ok -> ChromaPhase.Ready(answer.value)
                ChromaAnswer.Off -> ChromaPhase.Off
                ChromaAnswer.Missing -> ChromaPhase.Missing
                ChromaAnswer.Waiting, ChromaAnswer.Failed -> ChromaPhase.Failed
            }
            state.update { it.copy(phase = phase) }
        }
    }
}
