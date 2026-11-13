package com.wasimaster.wmkeyboard.core.voice

/**
 * "Suppress symbols": a recognizer's own symbols taken out of a finished
 * phrase, so dictation writes words and ordinary punctuation only.
 *
 * Runs on what the engine heard, before spoken punctuation, so a symbol the
 * user said by name ("at sign", "dollar sign") still goes in: only the ones
 * the recognizer chose to write are dropped. Engines that format numbers and
 * addresses (`50%`, `john@example.com`) lose the mark and keep the rest.
 *
 * What stays: letters and their marks, digits, white space, and the
 * punctuation a sentence is written with in any script: full stops, commas,
 * question and exclamation marks (the Arabic, Devanagari and CJK ones too),
 * colons, semicolons, apostrophes, quotes, brackets, dashes and the ellipsis.
 * What goes: every Unicode symbol (currency, maths, arrows, emoji, `^`, `` ` ``)
 * and the punctuation-category characters that are symbols in use: `@ # % &
 * * / \ _ § ¶ † ‡ • ‰ ′ ″` and their full-width forms.
 */
object VoiceSymbols {

    /** Punctuation by category that a sentence never needs. */
    private val SYMBOL_PUNCTUATION: Set<Int> = buildSet {
        for (c in "@#%&*/\\_§¶†‡•‣‰‱′″‴※⁂") add(c.code)
        // Full-width forms, as a CJK recognizer writes them.
        for (c in "＠＃％＆＊／＼＿") add(c.code)
    }

    /** Whether [cp] is taken out of a phrase. */
    fun isSuppressed(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.MATH_SYMBOL,
        Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL,
        Character.OTHER_SYMBOL,
        -> true
        Character.CONNECTOR_PUNCTUATION -> true
        Character.OTHER_PUNCTUATION -> cp in SYMBOL_PUNCTUATION
        else -> false
    }

    /**
     * [text] without its symbols. A space the removal left doubled goes with
     * it, so "pay $ 5" reads "pay 5" and "5 %" reads "5"; every other space,
     * the text's own spacing, is untouched. A variation selector, joiner or
     * skin tone left with no emoji to hold goes too. The same string back
     * when there is nothing to take out.
     */
    fun strip(text: String): String {
        if (text.isEmpty() || !containsSuppressed(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        // Inside a run of removed characters, and whether a space stood
        // right before that run.
        var dropping = false
        var spaceBefore = false
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isSuppressed(cp) -> {
                    if (!dropping) spaceBefore = out.isEmpty() || out.last() == ' ' || out.last() == '\n'
                    dropping = true
                }
                // The glue an emoji was built with: nothing left to glue.
                dropping && (cp == ZWJ || cp in VS_RANGE || cp in SKIN_TONES || cp == KEYCAP) -> Unit
                // One space each side of what went: keep one of them.
                dropping && cp == ' '.code && spaceBefore -> dropping = false
                else -> {
                    dropping = false
                    out.appendCodePoint(cp)
                }
            }
        }
        // A phrase that ended on a removed symbol: the space before it was
        // only ever a separator.
        if (dropping && spaceBefore && out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    private fun containsSuppressed(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isSuppressed(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3
    private val VS_RANGE = 0xFE00..0xFE0F
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
}
