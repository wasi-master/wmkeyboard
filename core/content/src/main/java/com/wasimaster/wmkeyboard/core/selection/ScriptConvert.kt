package com.wasimaster.wmkeyboard.core.selection

import com.wasimaster.wmkeyboard.core.transliteration.Lipi
import com.wasimaster.wmkeyboard.core.transliteration.LipiDetect
import com.wasimaster.wmkeyboard.core.transliteration.LipiScheme

/**
 * Convert script ([SelectionMacro.CONVERT_SCRIPT]): which selections it is
 * offered on, what each is read as, where it can go, and the conversion
 * itself. Shared by the keyboard's bar, the service that runs a pick, and the
 * selection menu's sheet, so the three agree on all of it.
 *
 * A selection's own script says what it is ([LipiDetect.script]); so do the
 * marked letters of IAST and ISO 15919, and the carets and tildes of ITRANS.
 * Plain letters could be any romanisation, or English, so they are only
 * offered while Sanskrit is being typed, and read as the scheme the user
 * picked in the settings ([plainLetters]).
 */
object ScriptConvert {

    /** Where plain letters are offered at all: the one keyboard that types a romanisation's letters. */
    private const val PLAIN_LETTERS_LANGUAGE = "sa"

    /** The source [SelectionMacros.detectContent] stores, read only from what the text says itself. */
    internal fun detect(text: String, hasLatin: Boolean): LipiScheme? =
        LipiDetect.script(text) ?: if (hasLatin) LipiDetect.romanisation(text) else null

    /** Whether a selection with [content] gets the chip, on a [languageId] keyboard. */
    fun offered(content: ContentFlags, languageId: String): Boolean =
        content.lipiSource != null || (languageId == PLAIN_LETTERS_LANGUAGE && content.hasLatin)

    /** The romanisation plain letters are read as, from the setting's stored name; ITRANS when it names none. */
    fun plainLetters(setting: String): LipiScheme =
        LipiScheme.of(setting)?.takeIf { it in LipiScheme.asciiInputs } ?: LipiScheme.ITRANS

    /** What [text] is read as, or null when it holds nothing to convert. */
    fun source(text: String, setting: String): LipiScheme? = LipiDetect.source(text, plainLetters(setting))

    /** The ladder for [text]: where it comes from and where it can go, or null when it holds nothing to convert. */
    fun choices(text: String, setting: String, languageId: String): Pair<LipiScheme, List<LipiScheme>>? {
        val from = source(text, setting) ?: return null
        return from to LipiScheme.targets(from, languageId)
    }

    /**
     * [text] written in the scheme named [target] ([LipiScheme.native]), read
     * as [source] says. Null while the library is missing, for an unknown
     * target, or when the result is the text unchanged, so a caller never
     * pushes a rewrite that did nothing.
     */
    fun convert(text: String, setting: String, target: String): String? {
        val to = LipiScheme.of(target) ?: return null
        val from = source(text, setting) ?: return null
        return Lipi.convert(text, from, to)?.takeIf { it != text }
    }
}
