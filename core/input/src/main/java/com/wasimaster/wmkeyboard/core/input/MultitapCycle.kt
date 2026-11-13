package com.wasimaster.wmkeyboard.core.input

import com.wasimaster.wmkeyboard.core.layout.Key

/**
 * Where a key with [Key.multitap] is in its cycle: which key was tapped last,
 * how many times in a row, and when (discussion #372).
 *
 * Pure bookkeeping. The service owns the text — it asks [press] what a tap
 * types and whether that replaces what the previous tap typed, checks the
 * previous text is really still in front of the caret, and only then takes it
 * back. So the rules that decide "is this the same run of taps" live here,
 * where they are unit-tested, and the rules that touch the field stay next to
 * the field.
 *
 * A run ends when a different key is tapped, when [timeoutMs] passes between
 * two taps, or when the service calls [end] — any other keystroke, a
 * backspace, the caret moving, or the space that a 천지인 (Cheonjiin) pad
 * spends on saying "next letter". Ending is how the same letter is typed twice:
 * ㄱ, pause, ㄱ is ㄱㄱ, where ㄱ ㄱ in one run is ㅋ.
 */
class MultitapCycle(
    /** The pause that ends a run; the service sets it from the user's setting before each tap. */
    var timeoutMs: Long = TIMEOUT_MS,
) {

    /**
     * What one tap types. [replaces] is the text the previous tap of this run
     * typed, which this tap stands in for; null when the tap starts a run.
     */
    data class Tap(val text: String, val replaces: String?)

    private var key: Key? = null
    private var at = 0L

    /**
     * Where the run is in its key's cycle after the last [press]: 0 for the key
     * itself, n for `multitap[n - 1]`. What a Keyman key needs in place of the
     * text, since each of its steps is a key of its own for the rules.
     */
    var step = 0
        private set

    /** Whether a run is still open at [now]: a tap of the same key would continue it. */
    fun isLive(now: Long): Boolean = key != null && now - at in 0..timeoutMs

    /**
     * A tap of [key] at [now] (uptime milliseconds). Continues the run when
     * [key] is the key of the live one, else starts a new run at the key's own
     * text. A key with no [Key.multitap] never opens a run, and ends any other.
     */
    fun press(key: Key, now: Long): Tap {
        val cycle = cycleOf(key)
        if (cycle.size < 2) {
            end()
            return Tap(cycle.first(), replaces = null)
        }
        if (isLive(now) && this.key == key) {
            val previous = cycle[step % cycle.size]
            step = (step + 1) % cycle.size
            at = now
            return Tap(cycle[step], replaces = previous)
        }
        return restart(key, now)
    }

    /**
     * Starts a new run at [key]'s own text whatever came before — for the
     * service, when [press] said "replace" but the text to replace is no longer
     * in front of the caret.
     */
    fun restart(key: Key, now: Long): Tap {
        this.key = key
        step = 0
        at = now
        return Tap(cycleOf(key).first(), replaces = null)
    }

    /** Closes the run: the next tap of any key types that key afresh. */
    fun end() {
        key = null
        step = 0
    }

    private fun cycleOf(key: Key): List<String> = listOf(key.output ?: key.label) + key.multitap

    companion object {
        /**
         * How long a run waits for the next tap by default. A second is what
         * feature phones and Samsung's Cheonjiin pad settled on: long enough for
         * a deliberate second tap, short enough that a pause reads as "next
         * letter". The user can change it (`LayoutBehaviorSettings.multitapTimeoutMs`).
         */
        const val TIMEOUT_MS = 1_000L
    }
}
