package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.ContentFlags
import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec

/**
 * What one press of the toolbar's Selection mode tool means, pulled out of
 * `WMKeyboardService` so the tap ladder can be checked without a keyboard.
 *
 * The tool answers to three gestures on the same button, told apart by nothing
 * but their spacing, which is why the counting lives here rather than inline: a
 * tap arms or disarms the mode, two taps in quick succession select the word at
 * the cursor, and three select the line. It is the shift key's double-tap
 * ladder, on the shift key's window, with one more rung.
 *
 * The first tap always acts. Waiting out the window to find out whether a second
 * one is coming would put a third of a second between pressing the button and
 * the mode coming on, which is the price the shift key does not pay either.
 */
enum class SelectionTap {
    /** A single press: turn selection mode on, or off when it is already on. */
    TOGGLE,

    /** A second press inside the window: select the word at the cursor. */
    WORD,

    /** A third: select the whole line the cursor is on. */
    LINE,
}

/**
 * Counts presses of the Selection mode tool into [SelectionTap]s.
 *
 * One instance per keyboard, since the button it counts is one button. Not
 * thread-safe, and does not need to be: every caller is the input thread.
 */
class SelectionTapCounter(private val windowMs: Long) {

    private var lastTapMs = 0L
    private var runLength = 0

    /**
     * The meaning of a press at [nowMs]. [multiTap] off answers TOGGLE every
     * time, so the setting costs the counter nothing to obey.
     *
     * The run stops at three: a fourth quick press starts a new ladder rather
     * than doing nothing, so a drummed-on button keeps toggling instead of going
     * dead. Presses spaced further apart than [windowMs] always start over.
     */
    fun tap(nowMs: Long, multiTap: Boolean): SelectionTap {
        if (!multiTap) {
            reset()
            return SelectionTap.TOGGLE
        }
        runLength = if (nowMs - lastTapMs < windowMs && runLength in 1..2) runLength + 1 else 1
        lastTapMs = nowMs
        return when (runLength) {
            2 -> SelectionTap.WORD
            3 -> SelectionTap.LINE
            else -> SelectionTap.TOGGLE
        }
    }

    /**
     * Forgets the run so far, so the next press reads as a first tap.
     *
     * Called for anything that is not a tap on this button: a press and hold on
     * it, and a new field. Without it, a hold followed by a tap would land as a
     * double tap and select a word nobody asked for.
     */
    fun reset() {
        lastTapMs = 0L
        runLength = 0
    }
}

/**
 * The selection macros as the bar draws them: what is selected, what it turned
 * out to be, and the actions offered for it.
 *
 * Resolved by the service on each selection change rather than by the bar,
 * because deciding costs a set of regexes and a look at the installed apps, and
 * the bar recomposes far more often than the selection changes. [text] travels
 * with the offer so the action a tap runs is about the text the chips were
 * drawn for, not whatever the field holds by the time the tap lands.
 */
data class SelectionMacroOffer(
    /** The selected text, trimmed, exactly as the macros will act on it. */
    val text: String,
    val kind: SelectionKind,
    /**
     * The built-in chips, in the order they are drawn. Empty only when
     * [plugin] is not; with neither, a null offer is used instead.
     */
    val macros: List<SelectionMacro>,
    /**
     * Whether the selection spans the whole field, which takes Select all off
     * the row. Kept with the offer so a settings change can rebuild the row
     * without reading the field again.
     */
    val wholeField: Boolean = false,
    /** What the text holds: the colour, the moment, the place the ladders and intents act on. */
    val content: ContentFlags = ContentFlags.NONE,
    /** AI actions drawn as chips of their own right after the AI chip. */
    val aiDirect: List<AiActionSpec> = emptyList(),
    /** The case ladder Format opens: the four fixed cases plus the programmer's ones that are on. */
    val caseLadder: List<SelectionMacro> = emptyList(),
    /** How many rewrites Undo can put back; the chip is drawn while this is above zero. */
    val undoDepth: Int = 0,
    /** The chip whose work is still running; it shows a spinner and ignores taps. */
    val busy: SelectionMacro? = null,
    /** The selection is being read aloud, so that chip reads Stop. */
    val speaking: Boolean = false,
    /** Selection actions from plugins, drawn after the built-in chips. */
    val plugin: List<PluginMacroSpec> = emptyList(),
    /** The plugin chip whose call is still running, by [PluginMacroSpec.key]. */
    val busyKey: String? = null,
)

/**
 * A plugin's selection action as the bar draws it. Copied out of the plugin
 * store when the offer is built, so drawing never reads the store.
 */
data class PluginMacroSpec(
    /** `plugin:<pluginId>/<actionId>`: what settings and the busy spinner key on. */
    val key: String,
    val pluginId: String,
    val actionId: String,
    val label: String,
    /** A Material Symbols or SF Symbols name, resolved by the bar; blank for the default. */
    val icon: String,
    /** The plugin's own name, for the chip's accessibility label. */
    val pluginName: String,
)
