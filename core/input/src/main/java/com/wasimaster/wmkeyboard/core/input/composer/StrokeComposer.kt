package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Chinese stroke input (笔画). The buffer is a run of the five stroke classes,
 * typed as the keys h/s/p/n/z (or the digits 1–5), and the strip offers the Han
 * characters whose stroke order starts with what's typed ([CodeTableDictionary]).
 *
 * Unlike Pinyin, one character is built from a single stroke run, so a commit
 * consumes the whole buffer (the default [consumedFor]) — there is no tail to
 * re-convert. A `*`/`?` key is a wildcard stroke for when the exact class is
 * uncertain. With no stroke pack downloaded there are simply no candidates.
 */
object StrokeComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val readsBySound: Boolean get() = false
    override val missingPack: String? get() = CjkDictionaries.missingStroke

    /**
     * Key/character → canonical stroke digit. The on-screen keys type the stroke
     * glyphs (一丨丿丶乙); h/s/p/n/z and 1–5 are accepted too for a hardware
     * keyboard. `*`/`?`/`＊` are the wildcard stroke.
     */
    private val TO_DIGIT: Map<Char, Char> = mapOf(
        '一' to '1', '丨' to '2', '丿' to '3', '丶' to '4', '乙' to '5',
        'h' to '1', 's' to '2', 'p' to '3', 'n' to '4', 'z' to '5',
        '1' to '1', '2' to '2', '3' to '3', '4' to '4', '5' to '5',
        '*' to '.', '?' to '.', '＊' to '.',
    )

    /** Stroke digit → its glyph, for the composing-region preview. */
    private val GLYPH: Map<Char, Char> = mapOf(
        '1' to '一', '2' to '丨', '3' to '丿', '4' to '丶', '5' to '乙', '.' to '＊',
    )

    /** The typed keys as a canonical digit/wildcard pattern for lookup. */
    private fun normalize(buffer: String): String = buildString {
        for (c in buffer.lowercase()) TO_DIGIT[c]?.let { append(it) }
    }

    /** The wildcard stroke is punctuation, so the buffer has to admit it by name. */
    override fun buffersChar(c: Char): Boolean = TO_DIGIT[c] == '.'

    /** The composing region shows the stroke glyphs, not the raw letter keys. */
    override fun composeBuffer(buffer: String): String = buildString {
        for (c in normalize(buffer)) append(GLYPH[c] ?: c)
    }

    override fun candidates(buffer: String): List<String> =
        CjkDictionaries.stroke.candidates(normalize(buffer)).map(HanVariant::toTraditional)
}
