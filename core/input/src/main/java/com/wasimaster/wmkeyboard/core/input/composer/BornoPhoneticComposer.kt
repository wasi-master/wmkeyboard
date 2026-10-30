package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes
import com.wasimaster.wmkeyboard.core.transliteration.BornoPhonetic

/**
 * Borno Phonetic: roman keys read by [BornoPhonetic], ranked against the same
 * Bangla word list as Avro. A separate composer rather than a setting on Avro's,
 * so the globe key flips between the two.
 *
 * Its signs are punctuation keys, and `,` and `.` are ordinary punctuation
 * between words, so those only join a word already being typed: `,,` the hasant,
 * `,.` and `,..` the hasant with a joiner. The long-press letters land as the
 * Bangla sign itself and stay in the word too.
 */
object BornoPhoneticComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override val isRomanBuffer: Boolean get() = true

    override val phoneticLanguage: String get() = "bn"

    override fun buffersChar(c: Char): Boolean = c in SIGN_KEYS || c in LONG_PRESS

    override fun buffersChar(c: Char, composing: CharSequence): Boolean = when (c) {
        ',', ':', '`' -> composing.isNotEmpty()
        '.' -> composing.lastOrNull() == ',' || composing.endsWith(",.")
        else -> buffersChar(c)
    }

    override fun composeBuffer(buffer: String): String = BornoPhonetic.transliterate(buffer)

    /** Read off the rules, as Avro's is, since a key can rewrite the one before it (`k` then `h`). */
    override fun keyPreview(buffer: String, key: String, wholeCluster: Boolean): String? {
        if (key.isEmpty() || !key.all { it.isLetter() }) return null
        val after = composeBuffer(buffer + key)
        if (wholeCluster) return after.takeLast(BengaliGraphemes.clusterDeleteLength(after))
        val before = composeBuffer(buffer)
        var shared = 0
        while (shared < before.length && shared < after.length && before[shared] == after[shared]) shared++
        return after.substring(shared)
    }

    override fun deleteLength(before: CharSequence): Int = BengaliGraphemes.clusterDeleteLength(before)

    override fun isPlausibleWord(word: String): Boolean =
        word.any { BengaliGraphemes.isBengali(it) && it.isLetter() } &&
            word.none { it in 'a'..'z' || it in 'A'..'Z' } &&
            word.last() != HASANT && word.last() != ZWNJ && word.last() != ZWJ

    /** `^` is the only sign key that means nothing outside a word. */
    private const val SIGN_KEYS = "^"

    /** What the letter keys' long presses write: ৎ on t, ঃ on l, ঁ on a, the hasant on g. */
    private const val LONG_PRESS = "ৎঃঁ্"

    private const val HASANT = '্'
    private const val ZWNJ = '‌'
    private const val ZWJ = '‍'
}
