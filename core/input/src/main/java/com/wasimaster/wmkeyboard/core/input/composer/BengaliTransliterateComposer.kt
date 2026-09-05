package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes

/**
 * Avro: roman letters transliterated to Bengali as the buffer grows, committed
 * as a unit. Wraps the existing [AvroPhonetic] so the transliteration rules are
 * unchanged — the composer only puts it behind the shared interface.
 *
 * Committed output is Bengali, so backspace over already-committed text still
 * removes a whole conjunct cluster (the roman *buffer* is edited char by char by
 * the service, ahead of commit).
 */
object BengaliTransliterateComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override val isBengaliPhonetic: Boolean get() = true

    override fun composeBuffer(buffer: String): String = AvroPhonetic.transliterate(buffer)

    /**
     * Asks the transliterator itself what one more key would change, rather
     * than keeping a second table of "which Bengali does this letter mean
     * here" that could drift from the rules: transliterate the buffer with and
     * without the key, and read the answer off the two results.
     *
     * [wholeCluster] picks which answer. False gives only what the key adds —
     * ্ক on the k after a consonant, া on the a — diffed on the common prefix
     * rather than assuming the output only ever grows, because it does not: a
     * breaker turns a word-final ত into ৎ, and the ৎ is what that key commits.
     * True gives the whole cluster the key lands in — ক্ক, কা — which is the
     * shape that actually appears in the word, at the cost of repeating the
     * consonant that was already there.
     */
    override fun keyPreview(buffer: String, key: String, wholeCluster: Boolean): String? {
        // Only the keys that actually reach the buffer. A digit or a full stop
        // commits straight through — the transliterator never sees it — so
        // previewing the ৫ or the । the *rules* would have made of it would be
        // a promise the keypress does not keep.
        if (key.isEmpty() || !key.all { it.isLetter() }) return null
        val after = AvroPhonetic.transliterate(buffer + key)
        if (wholeCluster) return after.takeLast(BengaliGraphemes.clusterDeleteLength(after))
        val before = AvroPhonetic.transliterate(buffer)
        var shared = 0
        while (shared < before.length && shared < after.length && before[shared] == after[shared]) {
            shared++
        }
        return after.substring(shared)
    }

    override fun deleteLength(before: CharSequence): Int =
        BengaliGraphemes.clusterDeleteLength(before)
}
