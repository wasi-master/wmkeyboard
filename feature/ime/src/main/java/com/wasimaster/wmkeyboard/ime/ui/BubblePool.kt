package com.wasimaster.wmkeyboard.ime.ui

/**
 * Which of a few permanently composed bubbles shows which press, so the key
 * preview overlay reuses a bubble instead of building one per press.
 *
 * Keyed by the press, every keystroke composed a whole bubble (its modifier
 * chain, its shadow's render node, its glass node, its text layout) and
 * disposed it a moment later: some 2 MB of garbage per 80 keys, and the
 * collections that come with it land in the middle of typing. Keyed by
 * slot, a new press only recomposes a bubble that is already there with a
 * new label and place; the node-based modifiers update in place.
 *
 * A slot whose press has ended keeps its last preview and stays composed,
 * just not measured or placed, so it draws nothing. The slot taken by a new
 * press is the one idle longest, and a new slot only when none is idle. More presses at once than [size] (a hand
 * on the glass) spill over and are composed per press as before.
 *
 * A plain holder, written during composition: [assign] is a pure function
 * of what was shown last time and what is shown now, so a composition that
 * runs it twice gets the same answer.
 */
internal class BubblePool<T : Any>(val size: Int = DEFAULT_SIZE) {

    private val tokens = arrayOfNulls<Any>(size)
    private val items = arrayOfNulls<Any>(size)
    private val lastUsed = LongArray(size)
    private var clock = 0L

    /** Slots ever filled; only these are composed. */
    var used: Int = 0
        private set

    /** The latest item a slot showed, live or not. */
    @Suppress("UNCHECKED_CAST")
    fun item(slot: Int): T? = items[slot] as T?

    /** Which slots show a live item after the last [assign]. */
    val live = BooleanArray(size)

    /**
     * Seats [shown] (keyed by [tokenOf]) in slots and returns the ones that
     * did not fit, in order.
     */
    fun assign(shown: List<T>, tokenOf: (T) -> Any): List<T> {
        live.fill(false)
        clock++
        val waiting = ArrayList<T>(0)
        // Presses already seated keep their slot.
        for (item in shown) {
            val token = tokenOf(item)
            val slot = tokens.indexOfFirst { it == token }
            if (slot >= 0) {
                seat(slot, token, item)
            } else {
                waiting.add(item)
            }
        }
        val spill = ArrayList<T>(0)
        for (item in waiting) {
            val slot = freeSlot()
            if (slot < 0) {
                spill.add(item)
            } else {
                seat(slot, tokenOf(item), item)
            }
        }
        return spill
    }

    private fun seat(slot: Int, token: Any, item: T) {
        tokens[slot] = token
        items[slot] = item
        live[slot] = true
        lastUsed[slot] = clock
        if (slot >= used) used = slot + 1
    }

    /**
     * The composed slot idle longest, then a new one while the pool has room;
     * -1 when every slot is live. Reusing first keeps the pool as small as
     * the typing needs: one bubble for one finger.
     */
    private fun freeSlot(): Int {
        var best = -1
        for (slot in 0 until used) {
            if (live[slot]) continue
            if (best < 0 || lastUsed[slot] < lastUsed[best]) best = slot
        }
        if (best >= 0) return best
        return if (used < size) used else -1
    }

    companion object {
        /** Enough for two thumbs rolling over each other with a bubble or two still fading. */
        const val DEFAULT_SIZE = 4
    }
}
