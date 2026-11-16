package com.wasimaster.wmkeyboard.ime

/**
 * One step of a space key's tap cycle (`Key.spaceCycle`, issue #1409): what
 * the next tap puts in place of the text the last one left, and the step that
 * text is.
 *
 * [step] is the index into [cycle] of the text the last tap left, or -1 for a
 * plain space. The tap after a plain space takes the first entry, each tap
 * after an entry takes the next, and the tap after the last entry goes back
 * to a plain space, so the cycle can be walked round again.
 */
internal fun spaceCycleNext(cycle: List<String>, step: Int): Pair<Int, String> {
    val next = step + 1
    return if (next in cycle.indices) next to cycle[next] else -1 to " "
}

/**
 * How much of the text before the caret a space cycle remembers to tell that
 * nothing was typed between two taps: long enough that a word picked from the
 * strip or a glide in between changes it.
 */
internal const val SPACE_CYCLE_TAIL = 48
