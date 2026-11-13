package com.wasimaster.wmkeyboard.core.prediction

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * An imported word list's bytes as UTF-8, whatever they were written in.
 *
 * Every list is stored and read back as UTF-8, and a plain-text import was
 * taken as UTF-8 too. That fit Gboard, Mozc and the open-source keyboards, but
 * not Windows: Microsoft IME's dictionary tool exports UTF-16 with a byte-order
 * mark, and ATOK and older tools write Shift-JIS (competitor gap list item 20,
 * FUTO's MS-IME and ATOK import). UTF-16 failed the binary sniff on its NUL
 * bytes and imported nothing; Shift-JIS imported as mojibake.
 *
 * So the bytes are decoded first: a byte-order mark says what they are; UTF-16
 * without one shows itself by the NUL half of its tabs and line breaks; text
 * that is valid UTF-8 stays as it is; and only text that is not UTF-8 but
 * reads cleanly as Shift-JIS (Windows-31J), with kana in it, is taken for
 * Shift-JIS. Anything else is returned untouched for the usual checks to judge.
 */
object ImportText {

    /** [bytes] re-encoded as UTF-8 without a byte-order mark, or [bytes] itself when they already are. */
    fun toUtf8(bytes: ByteArray): ByteArray {
        val decoded = when {
            bytes.startsWith(UTF8_BOM) -> return bytes.copyOfRange(UTF8_BOM.size, bytes.size)
            bytes.startsWith(UTF16LE_BOM) -> decode(bytes, 2, Charsets.UTF_16LE)
            bytes.startsWith(UTF16BE_BOM) -> decode(bytes, 2, Charsets.UTF_16BE)
            else -> when (bareUtf16(bytes)) {
                Charsets.UTF_16LE -> decode(bytes, 0, Charsets.UTF_16LE)
                Charsets.UTF_16BE -> decode(bytes, 0, Charsets.UTF_16BE)
                else -> if (strictly(bytes, Charsets.UTF_8) != null) return bytes else shiftJis(bytes)
            }
        } ?: return bytes
        return decoded.toByteArray(Charsets.UTF_8)
    }

    /**
     * UTF-16 with no byte-order mark: the line breaks and tabs every list has
     * are a character below 256 with a NUL beside it, on the high side only.
     * Little-endian puts the NULs at odd offsets, big-endian at even ones.
     */
    private fun bareUtf16(bytes: ByteArray): Charset? {
        val sample = minOf(bytes.size, SNIFF_BYTES) and 1.inv()
        var zeroEven = 0
        var zeroOdd = 0
        for (i in 0 until sample) {
            if (bytes[i] != 0.toByte()) continue
            if (i % 2 == 0) zeroEven++ else zeroOdd++
        }
        return when {
            zeroOdd >= MIN_NULS && zeroEven == 0 -> Charsets.UTF_16LE
            zeroEven >= MIN_NULS && zeroOdd == 0 -> Charsets.UTF_16BE
            else -> null
        }
    }

    /** Shift-JIS text, only when it decodes without a single bad byte and holds kana. */
    private fun shiftJis(bytes: ByteArray): String? {
        val charset = runCatching { Charset.forName("windows-31j") }.getOrNull() ?: return null
        val text = strictly(bytes, charset) ?: return null
        return text.takeIf { s -> s.any { it in 'ぁ'..'ヿ' } }
    }

    private fun decode(bytes: ByteArray, offset: Int, charset: Charset): String? =
        strictly(bytes.copyOfRange(offset, bytes.size), charset)

    /** [bytes] decoded as [charset], or null at the first malformed or unmappable sequence. */
    private fun strictly(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** How far into a file the UTF-16 sniff looks. */
    private const val SNIFF_BYTES = 4096

    /** NULs on one side before a file counts as bare UTF-16: a couple of lines' tabs and breaks. */
    private const val MIN_NULS = 4
}
