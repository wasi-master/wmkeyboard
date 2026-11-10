package com.wasimaster.wmkeyboard.core.prediction

/**
 * The marks at the end of a phonetic buffer that the rules write as they are.
 *
 * Borno Phonetic takes a comma into the word being typed, because `,,` is a
 * hasant there. One comma on its own is still a comma, and the rules read
 * `accha,` as আচ্ছা, — but the word list knows আচ্ছা, not আচ্ছা, with a comma.
 * Ranked whole, the buffer came back as আচ্ছা, and the comma the user typed
 * was gone from the word and from the field (Telegram 7029). Ranked without
 * the tail and given it back afterwards, the word is found and the comma stays.
 */
object PhoneticTail {

    /**
     * The run of trailing non-letters in [buffer] that [transliterate] writes
     * unchanged, or "" when there is none. A tail the rules do read — `,,`
     * becoming a hasant — is part of the word and stays in it. Letters, digits
     * and apostrophes are never a tail: they spell, or may.
     */
    fun of(buffer: String, transliterate: (String) -> String): String {
        var cut = buffer.length
        while (cut > 0 && buffer[cut - 1].let { !it.isLetterOrDigit() && it != '\'' }) cut--
        if (cut == 0 || cut == buffer.length) return ""
        val head = buffer.substring(0, cut)
        val tail = buffer.substring(cut)
        return if (transliterate(buffer) == transliterate(head) + tail) tail else ""
    }
}
