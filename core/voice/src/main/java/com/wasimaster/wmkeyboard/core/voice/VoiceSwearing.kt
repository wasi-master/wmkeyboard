package com.wasimaster.wmkeyboard.core.voice

import android.content.Context
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Masks swear words in dictated text the way Google's recognizer does: the
 * first letter stays and every letter after it becomes an asterisk, so
 * "fucking" goes in as "f******".
 *
 * The system recognizer masks on its own unless told not to; Whisper, the
 * other on-device engines and transcription servers have no filter at all.
 * Lists exist for English, Spanish, French, German, Italian and Portuguese.
 * With the setting on, every engine's words come through here, which leaves
 * an already-masked word from Google untouched: "f***" is not a word on any
 * list.
 *
 * A word is a run of letters (with their combining marks), joined across an
 * apostrophe, so "fuckin'" and "shit's" are judged as wholes and then by the
 * part before the apostrophe, whose ending is kept ("s***'s"). Hyphens
 * split, so "mother-fucker" masks only the half on the list. Case is
 * ignored for the lookup and kept in the output.
 */
object VoiceSwearing {

    /**
     * Each language's list, read once. Not the `offensive/` lists "Block
     * offensive words" reads: those hide suggestions and hold everyday words
     * (English's has "kill" and "sex", German's "aufrecht") that no one wants
     * masked in speech.
     */
    private val lists = ConcurrentHashMap<String, Set<String>>()

    /**
     * [text] with the swear words of any of [languageIds] masked. The lists
     * are the short hand-picked ones in `assets/dictionaries/swearing/`; a
     * language without one masks nothing.
     */
    fun mask(context: Context, text: String, languageIds: Collection<String>): String {
        if (text.isBlank() || languageIds.isEmpty()) return text
        val sets = languageIds.map { listFor(context, it) }.filter { it.isNotEmpty() }
        if (sets.isEmpty()) return text
        return mask(text) { word -> sets.any { word in it } }
    }

    private fun listFor(context: Context, languageId: String): Set<String> =
        lists.getOrPut(languageId) {
            runCatching {
                context.assets.open("dictionaries/swearing/$languageId.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .mapTo(HashSet()) { Normalizer.normalize(it.lowercase(), Normalizer.Form.NFC) }
                }
            }.getOrDefault(emptySet())
        }

    private val WORD = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")

    /** [text] with every word that [isSwearing] says yes to masked. */
    fun mask(text: String, isSwearing: (String) -> Boolean): String {
        if (text.isEmpty()) return text
        return WORD.replace(text) { match ->
            val word = match.value
            // NFC, as the lists are: a recognizer may spell "coño" with a combining tilde.
            val lower = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFC)
            val stemEnd = word.indexOfFirst { it == '\'' || it == '’' }
            when {
                isSwearing(lower) -> masked(word)
                // "shit's": the word is masked and its ending kept.
                stemEnd > 0 && isSwearing(Normalizer.normalize(word.substring(0, stemEnd).lowercase(), Normalizer.Form.NFC)) ->
                    masked(word.substring(0, stemEnd)) + word.substring(stemEnd)
                else -> word
            }
        }
    }

    /** [word] with its first letter kept and every later letter an asterisk. */
    internal fun masked(word: String): String {
        val first = Character.charCount(word.codePointAt(0))
        val out = StringBuilder(word.length).append(word, 0, first)
        var i = first
        while (i < word.length) {
            val cp = word.codePointAt(i)
            // A combining mark goes with the letter it sat on; an apostrophe stays.
            when {
                cp == '\''.code || cp == '’'.code -> out.appendCodePoint(cp)
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(cp) == Character.COMBINING_SPACING_MARK.toInt() ||
                    Character.getType(cp) == Character.ENCLOSING_MARK.toInt() -> Unit
                else -> out.append('*')
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
