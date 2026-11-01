package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Cangjie (倉頡) and its shorthand, Quick (速成) — the shape-based methods Hong
 * Kong and Taiwan use for Traditional Chinese. A character is spelled by the
 * radicals it is drawn from, typed as the letters a–y, each printed on the key
 * as its radical glyph (a = 日, b = 月, …).
 *
 * Like [StrokeComposer] and unlike Pinyin, one code run spells exactly one
 * character, so a commit takes the whole buffer (the interface default
 * [Composer.consumedFor]) — there is no tail to re-convert.
 *
 * Both methods read the same [CjkDictionaries.cangjie] table; Quick differs only
 * in the query, taking a character's first and last radical rather than all of
 * them. They are separate composers behind separate layouts rather than a
 * setting, because which one you type is a choice you make at the keyboard — the
 * same way Pinyin and Stroke are already separate layouts — not a background
 * preference that silently changes what a fixed set of keys does.
 */

/** Cangjie radical letters → the glyph printed on the key. */
private val CANGJIE_GLYPHS: Map<Char, Char> = mapOf(
    'a' to '日', 'b' to '月', 'c' to '金', 'd' to '木', 'e' to '水',
    'f' to '火', 'g' to '土', 'h' to '竹', 'i' to '戈', 'j' to '十',
    'k' to '大', 'l' to '中', 'm' to '一', 'n' to '弓', 'o' to '人',
    'p' to '心', 'q' to '手', 'r' to '口', 's' to '尸', 't' to '廿',
    'u' to '山', 'v' to '女', 'w' to '田', 'x' to '難', 'y' to '卜',
)

/** Radical glyph → its letter, so on-screen glyph keys and a hardware keyboard agree. */
private val CANGJIE_LETTERS: Map<Char, Char> =
    CANGJIE_GLYPHS.entries.associate { (letter, glyph) -> glyph to letter }

/** The typed keys as canonical radical letters, dropping anything off-alphabet. */
private fun cangjieNormalize(buffer: String): String = buildString {
    for (c in buffer.lowercase()) {
        when {
            c in 'a'..'y' -> append(c)
            else -> CANGJIE_LETTERS[c]?.let { append(it) }
        }
    }
}

/** The composing region shows radical glyphs rather than the raw letters. */
private fun cangjieGlyphs(buffer: String): String = buildString {
    for (c in cangjieNormalize(buffer)) append(CANGJIE_GLYPHS[c] ?: c)
}

/** Full Cangjie: every radical of the character, up to five, matched as a prefix. */
object CangjieComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val readsBySound: Boolean get() = false
    override val missingPack: String? get() = CjkDictionaries.missingCangjie

    override fun composeBuffer(buffer: String): String = cangjieGlyphs(buffer)

    override fun candidates(buffer: String): List<String> =
        CjkDictionaries.cangjie.candidates(cangjieNormalize(buffer)).map(HanVariant::toTraditional)
}

/**
 * Quick / 速成: only a character's first and last radical are typed, so two keys
 * usually suffice at the cost of more candidates to choose between.
 */
object CangjieQuickComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val readsBySound: Boolean get() = false
    override val missingPack: String? get() = CjkDictionaries.missingCangjie

    override fun composeBuffer(buffer: String): String = cangjieGlyphs(buffer)

    override fun candidates(buffer: String): List<String> {
        val code = cangjieNormalize(buffer)
        return when {
            code.isEmpty() -> emptyList()
            // With one key down the "last" radical is not yet known, so this is
            // still an ordinary prefix query; the second key turns it into a
            // first-and-last match.
            code.length == 1 -> CjkDictionaries.cangjie.candidates(code)
            else -> CjkDictionaries.cangjie.quickCandidates(code.first(), code.last())
        }.map(HanVariant::toTraditional)
    }
}
