package com.wasimaster.wmkeyboard.core.stickers.dynamic

import com.wasimaster.wmkeyboard.core.stickers.StickerKeywords
import java.util.Locale

/**
 * The text side of dynamic stickers: what a message says once emoji and
 * punctuation are set aside, the emoji it carries (which say how it feels),
 * and the words it is laid out from.
 */
object DynText {

    /**
     * [text] in the form phrases are compared in: lower case, apostrophes
     * dropped ("can't" and "cant" are one phrase), every other punctuation
     * mark and every emoji turned into a space, runs of space folded. Letters
     * and the marks inside words (a Bengali vowel sign) are kept as they are.
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (cluster in StickerKeywords.clusters(text)) {
            val first = cluster.codePointAt(0)
            when {
                isApostrophe(first) -> Unit
                isPictographic(cluster) || !isWordCluster(cluster) -> space(out)
                else -> out.append(cluster.lowercase(Locale.ROOT))
            }
        }
        return out.toString().trim()
    }

    /** The emoji in [text], in order, each as typed (with its selectors and tone). */
    fun emoji(text: String): List<String> = StickerKeywords.clusters(text).filter(::isPictographic)

    /**
     * [text] as it goes onto a sticker: emoji taken out (they would be drawn
     * in the template's font, which has none), whitespace folded, ends
     * trimmed. Punctuation stays: "Thanks!" keeps its "!".
     */
    fun display(text: String): String {
        val out = StringBuilder(text.length)
        for (cluster in StickerKeywords.clusters(text)) {
            if (isPictographic(cluster)) {
                space(out)
            } else if (cluster.isBlank()) {
                space(out)
            } else {
                out.append(cluster)
            }
        }
        return out.toString().trim()
    }

    /** The words of [text] for laying out: [display] split on spaces. */
    fun words(text: String): List<String> = display(text).split(' ').filter { it.isNotEmpty() }

    /** How many words [text] has, emoji and punctuation not counted. */
    fun wordCount(text: String): Int = normalize(text).split(' ').count { it.isNotEmpty() }

    /** [text] cased as [case] asks, in [locale] (Turkish dotted I and the like). */
    fun applyCase(text: String, case: TextCase?, locale: Locale = Locale.getDefault()): String = when (case) {
        null, TextCase.KEEP -> text
        TextCase.UPPER -> text.uppercase(locale)
        TextCase.LOWER -> text.lowercase(locale)
        TextCase.TITLE -> text.split(' ').joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { it.titlecase(locale) }
        }
    }

    /**
     * True for an emoji: a cluster carrying a symbol from the emoji blocks, a
     * regional-indicator flag, or a keycap. Plain punctuation is not one,
     * though [StickerKeywords.isEmoji] counts it, since there it only means
     * "needs no word boundary".
     */
    fun isPictographic(cluster: String): Boolean {
        var i = 0
        while (i < cluster.length) {
            val cp = cluster.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == KEYCAP) return true
            if (cp in EMOJI_BLOCKS) return true
            if (Character.getType(cp).toByte() == Character.OTHER_SYMBOL) return true
        }
        return false
    }

    /** Letters, digits and the marks that live inside words. */
    private fun isWordCluster(cluster: String): Boolean {
        val cp = cluster.codePointAt(0)
        return Character.isLetterOrDigit(cp) || when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }

    private fun isApostrophe(cp: Int): Boolean = cp == '\''.code || cp == RIGHT_QUOTE || cp == MODIFIER_APOSTROPHE

    private fun space(out: StringBuilder) {
        if (out.isNotEmpty() && out[out.length - 1] != ' ') out.append(' ')
    }

    private const val KEYCAP = 0x20E3
    /** Mahjong tiles through Symbols and Pictographs Extended-A: every emoji block past the BMP. */
    private val EMOJI_BLOCKS = 0x1F000..0x1FAFF
    private const val RIGHT_QUOTE = 0x2019
    private const val MODIFIER_APOSTROPHE = 0x02BC
}
