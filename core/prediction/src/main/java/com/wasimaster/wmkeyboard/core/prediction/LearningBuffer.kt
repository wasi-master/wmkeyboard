package com.wasimaster.wmkeyboard.core.prediction

/**
 * Words committed into the field that have not settled yet.
 *
 * A word is not evidence of anything the moment it lands: half of them are
 * about to be backspaced, re-picked from the strip, or edited when the user
 * reads back what they wrote. So an unknown word goes here first and only
 * counts towards learning once the text around it has stopped moving —
 * "cached for proofreading", which is what the request that prompted this
 * asked for.
 *
 * Settling is decided by two signals, both cheap enough to run on the typing
 * path because neither reads the field:
 *
 * * **The caret going back.** Each entry remembers where the caret came to
 *   rest after its commit ([Entry.anchor], filled in from the commit's own
 *   selection echo). A later caret anywhere in front of that anchor means the
 *   user has gone back into that text, so the entry is dropped — whatever they
 *   are doing there, the word is no longer something they typed and left
 *   alone.
 * * **Leaving the text.** The keyboard closing, the field being sent or
 *   cleared, or moving to another field all mean the words that survived are
 *   the user's final answer. The caller [drain]s at those points.
 *
 * Purely in-memory and per-field: nothing here survives the keyboard going
 * away, because the whole question it answers ("did this text settle?") is
 * answered by then. The counts it feeds live in [PendingLearn].
 */
class LearningBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    /**
     * One committed word waiting to settle.
     *
     * [anchor] is -1 until the commit's selection echo arrives; an entry with
     * no anchor yet can never be invalidated by a caret move, which is
     * correct — the caret has not been reported since the word landed.
     */
    class Entry internal constructor(
        val word: String,
        val langId: String,
        /** How deliberate the commit was; see `WMKeyboardService.learn`. */
        val weight: Int,
        /**
         * Whether [word]'s capitals are the user's own rather than ones the
         * keyboard put there (auto-capitalize, caps lock, an all-caps field).
         * Only the commit site can tell, and by the time the word settles that
         * moment is long gone — so the answer rides along with it (#44).
         */
        val caseTrusted: Boolean = false,
    ) {
        internal var anchor: Int = UNANCHORED
    }

    private val entries = ArrayDeque<Entry>()

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * Queues a freshly committed [word].
     *
     * Returns whatever had to be pushed out to stay inside [capacity] —
     * already-settled by sheer distance, since the user has typed a hundred
     * words since without going back to them. The caller counts those the same
     * way it counts a drain.
     */
    fun push(
        word: String,
        langId: String,
        weight: Int,
        caseTrusted: Boolean = false,
    ): List<Entry> {
        entries.addLast(Entry(word, langId, weight, caseTrusted))
        if (entries.size <= capacity) return emptyList()
        val overflow = ArrayList<Entry>(entries.size - capacity)
        while (entries.size > capacity) overflow.add(entries.removeFirst())
        return overflow
    }

    /**
     * The caret was reported at [caret].
     *
     * Anchors every entry still waiting for its commit echo, then drops the
     * ones the caret has moved in front of. A collapsed caret is the only kind
     * that anchors: a range selection is a selection, not a resting place, and
     * anchoring to it would leave entries pointing at text about to be
     * replaced.
     */
    fun onCaret(caret: Int) {
        if (caret < 0 || entries.isEmpty()) return
        // Anchors only ever grow, so the newest one answers for all of them:
        // a caret at or past it is ordinary forward typing, which is every
        // keystroke. This runs on the typing path, so that case does no work.
        val newest = entries.last().anchor
        if (newest != UNANCHORED && caret >= newest) return
        // Drop first, anchor second: an entry pushed but not yet anchored is
        // the word this very update belongs to, and it must not be dropped by
        // its own echo.
        entries.removeAll { it.anchor != UNANCHORED && it.anchor > caret }
        for (entry in entries) {
            if (entry.anchor == UNANCHORED) entry.anchor = caret
        }
    }

    /**
     * Drops [word] outright — the user took the commit back by hand (undoing
     * an autocorrect, re-picking from the strip), which is a statement about
     * the word rather than about the text around it.
     */
    fun drop(word: String) {
        val key = WordKey.of(word)
        entries.removeAll { WordKey.of(it.word) == key }
    }

    /** Everything still queued, emptying the buffer. */
    fun drain(): List<Entry> {
        if (entries.isEmpty()) return emptyList()
        val all = entries.toList()
        entries.clear()
        return all
    }

    /** Throws the queue away unsettled — nothing in it counts. */
    fun clear() {
        entries.clear()
    }

    companion object {
        internal const val UNANCHORED = -1

        /**
         * Words held before the oldest is settled by distance. Long enough to
         * cover a paragraph the user may still scroll back through, short
         * enough that someone writing an essay in one field still teaches the
         * keyboard their vocabulary before they finish.
         */
        const val DEFAULT_CAPACITY = 96
    }
}
