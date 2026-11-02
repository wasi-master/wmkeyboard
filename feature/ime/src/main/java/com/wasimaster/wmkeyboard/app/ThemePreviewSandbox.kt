package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Immutable
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.text.Graphemes
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.ShiftState

/**
 * What the theme editor's live keyboard is typing into.
 *
 * The editor draws the real keyboard (issue #148), and a real keyboard needs
 * somewhere for its keys to go: this is that somewhere. A small buffer and the
 * handful of state the grid reads back — shift, the open layer, the open panel —
 * reduced by one pure function per key, so the preview types, capitalizes and
 * swaps layers the way the keyboard does without an input connection, a
 * dictionary or the service behind it.
 *
 * Deliberately a subset. Nothing here reaches an app, learns a word or runs a
 * tool that needs a permission; a key the sandbox has no answer for is a key
 * that does nothing, which is the same answer a disabled tool gives on the real
 * board. The point of the preview is how the theme *looks* while the keyboard
 * moves under a finger, and every rule below exists because its absence would
 * make the preview look wrong: a spacebar that typed nothing, a shift that never
 * came back down, a ?123 key that went nowhere.
 */
@Immutable
data class ThemePreviewSandbox(
    /** Everything typed so far. Only ever grows at the end; there is no caret. */
    val text: String = "",
    val shift: ShiftState = ShiftState.OFF,
    /**
     * The live [shift] came from the user pressing the key rather than from
     * auto-capitalization arming it, which is what the grid reads to decide
     * whether Shift+Enter means a newline. Mirrors `KeyboardUiState.shiftPressedByUser`.
     */
    val shiftPressedByUser: Boolean = false,
    val layoutMode: LayoutMode = LayoutMode.LETTERS,
    /** The secondary grid on screen while [layoutMode] is [LayoutMode.SECONDARY]. */
    val secondaryLayoutId: String? = null,
    val panel: PanelMode = PanelMode.NONE,
    /**
     * The layout the preview types on, or null for the one the settings select.
     * Set by the globe key and the spacebar's language list, and kept here rather
     * than written to the settings: trying a theme must not change the keyboard.
     */
    val layoutId: String? = null,
    /** When shift was last tapped, for the caps-lock double tap. */
    val lastShiftTapMs: Long = 0L,
    /**
     * Words the editor puts on the strip while the user styles it (#265), so
     * there is a strip to look at without typing one up. Empty the rest of the
     * time, which is the only time the preview types.
     */
    val sampleSuggestions: List<String> = emptyList(),
) {
    /**
     * The strip's words: the word under the caret, the way the real strip leads
     * with what was typed. There is no engine behind the preview to offer more,
     * and inventing completions would show the user predictions their keyboard
     * never makes — except while the strip itself is being styled, when the
     * editor's [sampleSuggestions] stand in.
     */
    val suggestions: List<String>
        get() = sampleSuggestions.ifEmpty {
            text.takeLastWhile { !it.isWhitespace() }.takeIf { it.isNotEmpty() }
                ?.let { listOf(it) }
                ?: emptyList()
        }

    /**
     * One key of the grid, as the keyboard would take it.
     *
     * [context] is what the reduction needs from outside the buffer: whether the
     * script has case, which layers and layouts exist, and the caps-lock window.
     * [nowMs] is the clock, passed in so the double tap is testable.
     */
    fun onKey(key: Key, context: SandboxContext, nowMs: Long = 0L): ThemePreviewSandbox =
        when (val action = key.action) {
            KeyAction.Text, is KeyAction.KeymanKey -> typed(keyOutput(key, context), context)
            KeyAction.Shift -> onShift(nowMs, context.capsLockMs)
            KeyAction.CapsLock -> {
                val next = if (shift == ShiftState.CAPS_LOCK) ShiftState.OFF else ShiftState.CAPS_LOCK
                copy(shift = next, shiftPressedByUser = next != ShiftState.OFF)
            }
            KeyAction.Delete -> deleted(context)
            KeyAction.Space -> typed(" ", context)
            KeyAction.Enter, KeyAction.Newline -> typed("\n", context)
            KeyAction.Symbols -> copy(
                layoutMode = when (layoutMode) {
                    LayoutMode.LETTERS -> LayoutMode.SYMBOLS
                    LayoutMode.SYMBOLS -> LayoutMode.SYMBOLS_SHIFTED
                    LayoutMode.SYMBOLS_SHIFTED -> LayoutMode.SYMBOLS
                    LayoutMode.FN, LayoutMode.SECONDARY, LayoutMode.NAMED -> LayoutMode.SYMBOLS
                },
            )
            KeyAction.Letters -> copy(layoutMode = LayoutMode.LETTERS)
            KeyAction.Fn -> copy(
                layoutMode = when {
                    layoutMode == LayoutMode.FN -> LayoutMode.LETTERS
                    context.hasFnLayer -> LayoutMode.FN
                    else -> layoutMode
                },
            )
            KeyAction.LanguageSwitch -> {
                val ids = context.enabledLayoutIds.ifEmpty { listOf(context.activeLayoutId) }
                val current = layoutId ?: context.activeLayoutId
                selectingLayout(ids[(ids.indexOf(current) + 1).mod(ids.size)])
            }
            is KeyAction.Layout -> when {
                action.id !in context.secondaryLayoutIds -> this
                layoutMode == LayoutMode.SECONDARY && secondaryLayoutId == action.id ->
                    copy(layoutMode = LayoutMode.LETTERS)
                else -> copy(layoutMode = LayoutMode.SECONDARY, secondaryLayoutId = action.id)
            }
            KeyAction.Emoji -> copy(panel = PanelMode.EMOJI)
            KeyAction.Toolbox -> copy(panel = PanelMode.TOOLBOX)
            KeyAction.Numpad -> copy(panel = PanelMode.NUMPAD)
            is KeyAction.Tool -> onTool(action.tool)
            // Modifiers, raw key events, chords and the rest have nowhere to go
            // without a field behind the keyboard.
            else -> this
        }

    /** A toolbar tool, or a key bound to one: the tools that open a panel open it. */
    fun onTool(tool: ToolbarTool): ThemePreviewSandbox =
        panelForTool(tool)?.let { onPanel(it) } ?: this

    /**
     * A panel named by the chrome: the toolbox launcher, the back chevron on
     * the bar, a panel's own close button. Every one of those names the panel
     * it is standing in, and the service reads that as "close it" — so this
     * toggles the way `WMKeyboardService.onPanelChange` does. Setting the
     * name outright left the toolbox open under its own back arrow.
     */
    fun onPanel(panel: PanelMode): ThemePreviewSandbox =
        copy(panel = if (this.panel == panel) PanelMode.NONE else panel)

    /**
     * A typing layer put on screen by the theme editor's key picker (#265),
     * which has no ?123 key to press: every key there picks rather than types.
     */
    fun showingLayer(mode: LayoutMode): ThemePreviewSandbox =
        copy(layoutMode = mode, panel = PanelMode.NONE)

    /** A panel put on screen by the key picker, outright rather than toggled. */
    fun showingPanel(panel: PanelMode): ThemePreviewSandbox = copy(panel = panel)

    /** A word taken from the strip: replaces the word being typed and ends it. */
    fun onSuggestion(word: String, context: SandboxContext): ThemePreviewSandbox {
        val kept = text.dropLastWhile { !it.isWhitespace() }
        return copy(text = kept).typed("$word ", context)
    }

    /** Text from anywhere but a key: an emoji cell, a symbol, a popup alternate. */
    fun inserted(text: String, context: SandboxContext): ThemePreviewSandbox = typed(text, context)

    /** The globe key, or a pick from the spacebar's language list. */
    fun selectingLayout(id: String): ThemePreviewSandbox =
        copy(layoutId = id, layoutMode = LayoutMode.LETTERS, secondaryLayoutId = null)

    /**
     * Shift as auto-capitalization would leave it for the text so far: what the
     * preview asks on its first composition and after a layout switch, the way
     * the keyboard re-reads the field then. Every key press settles itself.
     */
    fun settled(context: SandboxContext): ThemePreviewSandbox = withAutoShift(context)

    private fun onShift(nowMs: Long, capsLockMs: Int): ThemePreviewSandbox {
        val doubleTap = nowMs - lastShiftTapMs < capsLockMs
        val next = when {
            doubleTap && shift != ShiftState.CAPS_LOCK -> ShiftState.CAPS_LOCK
            shift == ShiftState.OFF -> ShiftState.ON
            else -> ShiftState.OFF
        }
        return copy(shift = next, shiftPressedByUser = next != ShiftState.OFF, lastShiftTapMs = nowMs)
    }

    /**
     * Appends [output] and settles shift afterwards: a one-shot shift is spent
     * by the key it capitalized, and a sentence boundary arms it again, the
     * way auto-capitalization does on the keyboard.
     */
    private fun typed(output: String, context: SandboxContext): ThemePreviewSandbox =
        copy(text = text + output).withAutoShift(context)

    private fun deleted(context: SandboxContext): ThemePreviewSandbox {
        if (text.isEmpty()) return this
        // By the keyboard's own backspace rule, so an emoji with a skin tone
        // leaves in one press rather than in pieces, exactly as it would in a
        // real field.
        val cut = text.length - Graphemes.backspaceLength(text)
        return copy(text = text.substring(0, cut)).withAutoShift(context)
    }

    private fun withAutoShift(context: SandboxContext): ThemePreviewSandbox {
        if (shift == ShiftState.CAPS_LOCK) return this
        val arm = context.capitalize && context.cased && atSentenceStart(text)
        return when {
            arm -> copy(shift = ShiftState.ON, shiftPressedByUser = false)
            shift == ShiftState.ON -> copy(shift = ShiftState.OFF, shiftPressedByUser = false)
            else -> this
        }
    }

    /**
     * What the key types under the live shift: its shift label where it has
     * one, else its output upper-cased for a cased script. Cluster-shaping
     * composers (the Indic ones) never upper-case, since shift picks a
     * different letter there rather than a case.
     */
    private fun keyOutput(key: Key, context: SandboxContext): String {
        val base = key.output ?: key.label
        val shiftLabel = key.shiftLabel
        return when {
            shift != ShiftState.OFF && shiftLabel != null -> shiftLabel
            shift != ShiftState.OFF && context.cased && !context.clusterShaping -> base.uppercase()
            else -> base
        }
    }

    private companion object {
        /**
         * The buffer is at the start of a sentence: empty, on a fresh line, or
         * after a terminator and its space.
         */
        fun atSentenceStart(text: String): Boolean {
            if (text.isEmpty() || text.endsWith('\n')) return true
            val trimmed = text.trimEnd(' ')
            if (trimmed.length == text.length) return false
            return trimmed.lastOrNull() in SENTENCE_TERMINATORS
        }

        val SENTENCE_TERMINATORS = setOf('.', '!', '?', '।')
    }
}

/**
 * What [ThemePreviewSandbox.onKey] needs to know about the board it is
 * standing in for, gathered by the preview from the settings and the layout.
 */
@Immutable
data class SandboxContext(
    /** The script has letter case, so shift upper-cases and sentences capitalize. */
    val cased: Boolean = true,
    /** The composer shapes clusters, so shift never upper-cases. */
    val clusterShaping: Boolean = false,
    /** Auto-capitalization is on in the settings. */
    val capitalize: Boolean = true,
    val activeLayoutId: String = "",
    val enabledLayoutIds: List<String> = emptyList(),
    val secondaryLayoutIds: Set<String> = emptySet(),
    val hasFnLayer: Boolean = false,
    /** The caps-lock double-tap window, `LayoutBehaviorSettings.shiftCapsLockMs`. */
    val capsLockMs: Int = 350,
)

/**
 * The panel a toolbar tool opens, or null for the tools that do something
 * else: toggle a mode, move the caret, open the settings. The same table the
 * service's `runTool` dispatches by, reduced to the panel-opening rows.
 */
internal fun panelForTool(tool: ToolbarTool): PanelMode? = when (tool) {
    ToolbarTool.EMOJI -> PanelMode.EMOJI
    ToolbarTool.CLIPBOARD, ToolbarTool.CLIPBOARD_SEARCH -> PanelMode.CLIPBOARD
    ToolbarTool.SNIPPETS -> PanelMode.SNIPPETS
    ToolbarTool.TEXT_EDIT -> PanelMode.TEXT_EDIT
    ToolbarTool.TRACKPAD -> PanelMode.TRACKPAD
    ToolbarTool.COMPASS -> PanelMode.COMPASS
    ToolbarTool.LEVEL -> PanelMode.LEVEL
    ToolbarTool.MOON_PHASE -> PanelMode.MOON_PHASE
    ToolbarTool.WEATHER -> PanelMode.WEATHER
    ToolbarTool.CALENDAR -> PanelMode.CALENDAR
    ToolbarTool.THEMES -> PanelMode.THEMES
    ToolbarTool.SOUND_HAPTICS -> PanelMode.SOUND_HAPTICS
    ToolbarTool.NUMPAD -> PanelMode.NUMPAD
    ToolbarTool.HANDWRITING -> PanelMode.HANDWRITING
    ToolbarTool.CAMERA -> PanelMode.CAMERA
    ToolbarTool.DICTIONARY -> PanelMode.DICTIONARY
    ToolbarTool.VOCABULARY -> PanelMode.VOCABULARY
    ToolbarTool.LEARN_FROM_TEXT -> PanelMode.LEARN_FROM_TEXT
    ToolbarTool.FIND_REPLACE -> PanelMode.FIND_REPLACE
    ToolbarTool.NOTES, ToolbarTool.QUICK_NOTE -> PanelMode.NOTES
    ToolbarTool.TODO -> PanelMode.TODO
    ToolbarTool.WM_PASS -> PanelMode.WM_PASS
    ToolbarTool.LOCATION -> PanelMode.LOCATION
    ToolbarTool.CLOCK -> PanelMode.CLOCK
    ToolbarTool.HOME_ASSISTANT -> PanelMode.HOME_ASSISTANT
    ToolbarTool.SEND_FILES -> PanelMode.SEND_FILES
    ToolbarTool.GALLERY -> PanelMode.GALLERY
    ToolbarTool.MUSIC_ID -> PanelMode.MUSIC_ID
    ToolbarTool.TRANSLATE -> PanelMode.TRANSLATE
    ToolbarTool.GIF -> PanelMode.GIF
    ToolbarTool.STICKER -> PanelMode.STICKER
    ToolbarTool.EMOJI_MIX -> PanelMode.EMOJI_MIX
    ToolbarTool.WEB_SEARCH -> PanelMode.WEB_SEARCH
    ToolbarTool.IMAGE_SEARCH -> PanelMode.IMAGE_SEARCH
    ToolbarTool.OCR -> PanelMode.OCR
    ToolbarTool.QR_SCAN -> PanelMode.QR_SCAN
    ToolbarTool.VOICE -> PanelMode.VOICE
    ToolbarTool.GRAMMAR -> PanelMode.GRAMMAR
    ToolbarTool.WIKIPEDIA -> PanelMode.WIKIPEDIA
    ToolbarTool.SYMBOLS -> PanelMode.SYMBOLS
    ToolbarTool.CALCULATOR -> PanelMode.CALCULATOR
    ToolbarTool.UNIT_CONVERT -> PanelMode.UNIT_CONVERT
    ToolbarTool.CURRENCY -> PanelMode.CURRENCY
    ToolbarTool.QR_GEN -> PanelMode.QR_GEN
    ToolbarTool.PASSWORD_GEN -> PanelMode.PASSWORD_GEN
    ToolbarTool.TEXT_REPEAT -> PanelMode.TEXT_REPEAT
    ToolbarTool.TYPING_TEST -> PanelMode.TYPING_TEST
    ToolbarTool.SPEED_TEST -> PanelMode.SPEED_TEST
    ToolbarTool.MEDIA_CONTROL -> PanelMode.MEDIA_CONTROL
    ToolbarTool.KDE_CONNECT -> PanelMode.KDE_CONNECT
    ToolbarTool.PLUGINS -> PanelMode.PLUGINS
    ToolbarTool.APP_LAUNCHER -> PanelMode.APP_LAUNCHER
    ToolbarTool.AI -> PanelMode.AI
    ToolbarTool.MODES -> PanelMode.MODES
    else -> null
}
