package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.core.voice.VoiceSymbols

/**
 * Which of a Whisper model's tokens "Suppress symbols" keeps the decoder
 * from picking (S11): every token that writes a character [VoiceSymbols]
 * takes out of a phrase. Masked in whisper.cpp's logits filter, so the model
 * says "fifty percent" in words instead of writing `50%` and having the mark
 * cut off afterwards. [VoiceSymbols.strip] still runs on what comes back, for
 * anything a byte-level token slipped through in halves.
 *
 * Whisper's tokens are byte-level: one can hold half a character. A half
 * decodes to U+FFFD, which is never itself a reason to suppress. An emoji
 * starts with the bytes F0 9F, and a token holding them is suppressed whole,
 * since the rest of the emoji has no way to be written without it. Other
 * four-byte characters (rare Han, historic scripts) start F0 too, and are
 * left alone.
 */
object WhisperSymbolTokens {

    /**
     * The ids to suppress, from [vocab] as `nativeVocab` writes it: for each
     * id in turn, two bytes of length (little-endian), then the token's bytes.
     */
    fun ids(vocab: ByteArray): IntArray {
        val out = ArrayList<Int>()
        var at = 0
        var id = 0
        while (at + 2 <= vocab.size) {
            val length = (vocab[at].toInt() and BYTE) or ((vocab[at + 1].toInt() and BYTE) shl 8)
            at += 2
            if (at + length > vocab.size) break
            if (suppressed(vocab, at, length)) out += id
            at += length
            id++
        }
        return out.toIntArray()
    }

    /** Whether the token in [bytes] from [from], [length] long, writes a symbol. */
    internal fun suppressed(bytes: ByteArray, from: Int, length: Int): Boolean {
        if (length == 0) return false
        for (i in from until from + length - 1) {
            if (bytes[i] == EMOJI_LEAD_1 && bytes[i + 1] == EMOJI_LEAD_2) return true
        }
        val text = String(bytes, from, length, Charsets.UTF_8)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp != REPLACEMENT && VoiceSymbols.isSuppressed(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private const val BYTE = 0xFF
    private const val REPLACEMENT = 0xFFFD
    private const val EMOJI_LEAD_1: Byte = 0xF0.toByte()
    private const val EMOJI_LEAD_2: Byte = 0x9F.toByte()
}
