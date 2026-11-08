package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.script.RomanizedSpeech
import com.wasimaster.wmkeyboard.core.transliteration.CyrillicRomanizer
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.SinhalaRomanizer
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Dictation in a romanized language (#948), written the way that language is
 * typed: the recognizer hears Hindi and writes कल मीटिंग है, and this makes it
 * "Kal meeting hai".
 *
 * Each word of the native script goes through the language's
 * [RomanizedConverter.toRoman]: a word the spelling maps know takes their
 * spelling, which is how a loanword the recognizer spelled in Devanagari
 * (मीटिंग, listed in `en_hi.tsv`) comes back as the English word "meeting"
 * rather than "miting", and a common word takes the spelling people chat in.
 * Anything else goes through the script's romanizer, unless the caller
 * brought a neural model's readings for it ([unmapped], then [romanize]'s
 * `readings`): the rules spell every letter the same way every time, and a
 * model that learned from how people write knows the spelling a long or rare
 * word usually gets. Latin already in the phrase, an English word the
 * recognizer heard as English, is left alone.
 *
 * Sinhala and Russian have no phonetic layout and so no maps; their own
 * romanizers do the whole job. Nepali borrows Hindi's maps: the same letters,
 * mostly the same loanwords.
 *
 * [romanize] never waits on a file. A language whose maps are not loaded yet
 * ([prepare]) gets the romanizer alone for that phrase.
 */
class RomanizedDictation(private val openAsset: (String) -> InputStream) {

    /** By scheme language: the converter over its spelling maps, once loaded. */
    private val loaded = ConcurrentHashMap<String, RomanizedConverter>()

    /** By scheme language: the converter with no maps, for before they are loaded. */
    private val bare = ConcurrentHashMap<String, RomanizedConverter>()

    /**
     * Loads the spelling maps [romanizedIds] write with. Blocking: call it off
     * the main thread, before the first phrase if it can be.
     */
    fun prepare(romanizedIds: Collection<String>) {
        for (id in romanizedIds) {
            val scheme = RomanizedSpeech.spokenAs(id)?.let(::schemeFor) ?: continue
            loaded.getOrPut(scheme.languageId) { RomanizedConverter(scheme, load(scheme), PhoneticIndex.EMPTY) }
                .prepare()
        }
    }

    /**
     * [text] with the native script of each of [romanizedIds] written in
     * Latin letters, its digits and full stops with it, and a capital on the
     * first word when that word was in a script that has none. [text] itself
     * when none of it was in those scripts.
     *
     * [readings] is by native language (`bn` for `bn_rom`): a model's
     * spellings for words of [unmapped], best first. The best wins over the
     * rules for that word.
     */
    fun romanize(
        text: String,
        romanizedIds: Collection<String>,
        readings: Map<String, Map<String, List<String>>> = emptyMap(),
    ): String {
        if (text.isEmpty()) return text
        var out = text
        for (id in romanizedIds) {
            val native = RomanizedSpeech.spokenAs(id) ?: continue
            out = when (native) {
                SINHALA -> SinhalaRomanizer.romanize(out)
                RUSSIAN -> CyrillicRomanizer.romanize(out)
                else -> schemeFor(native)?.let { converterFor(it).toRoman(out, bestOf(readings[native])) } ?: out
            }
        }
        return if (out == text) text else finish(text, out)
    }

    /**
     * By native language, the words of [text] the spelling maps of
     * [romanizedIds] do not know and a neural model should spell rather than
     * the rules: the ones of [MODEL_MIN_LETTERS] or more. Empty for Sinhala
     * and Russian, which have no maps, and for a phrase with no such words.
     *
     * A short word stays with the rules. A common short word the maps have no
     * readable spelling for (only shorthand like `h` for है) reaches this; the
     * model learned from lists full of names and English words, and reads
     * those as an English word that sounds the same (की as key, कब as cub).
     * On the 955 words of the `*_rom` maps the inverted maps leave out, the
     * rules wrote the maps' spelling for 55.1% and the model for 18.6%; the
     * model on words of five characters or more and the rules below, 57.5%.
     * On Aksharantar's test words, mostly long and rare, the same split
     * loses 1.5 points against the model on everything (54.5% to 56.0%).
     */
    fun unmapped(text: String, romanizedIds: Collection<String>): Map<String, List<String>> = buildMap {
        for (id in romanizedIds) {
            val native = RomanizedSpeech.spokenAs(id) ?: continue
            val scheme = schemeFor(native) ?: continue
            converterFor(scheme).unmapped(text)
                .filter { it.length >= MODEL_MIN_LETTERS }
                .takeIf { it.isNotEmpty() }
                ?.let { put(native, it) }
        }
    }

    private fun converterFor(scheme: PhoneticScheme): RomanizedConverter =
        loaded[scheme.languageId]
            ?: bare.getOrPut(scheme.languageId) { RomanizedConverter(scheme, SpellingMap.EMPTY, PhoneticIndex.EMPTY) }

    /** The scheme's maps, or none when an asset will not open: the romanizer still works without them. */
    private fun load(scheme: PhoneticScheme): SpellingMap {
        val streams = ArrayList<InputStream>()
        return try {
            scheme.spellingAssets.mapTo(streams, openAsset)
            SpellingMap.load(*streams.toTypedArray(), loanwordStreams = scheme.loanwordAssetCount)
        } catch (_: Exception) {
            SpellingMap.EMPTY
        } finally {
            streams.forEach { runCatching { it.close() } }
        }
    }

    /**
     * What is left of the native script once its words are Latin: the
     * Arabic-script stops and commas, which no Latin text uses, and digits,
     * which chat writes in ASCII whatever the recognizer wrote. And the
     * opening capital a caseless script could not give the first word.
     */
    private fun finish(heard: String, romanized: String): String {
        val out = StringBuilder(romanized.length)
        for (c in romanized) {
            when {
                c in PUNCTUATION -> out.append(PUNCTUATION.getValue(c))
                c.isDigit() && c !in '0'..'9' -> out.append('0' + Character.digit(c, 10))
                else -> out.append(c)
            }
        }
        val firstHeard = heard.firstOrNull { it.isLetter() }
        val first = out.indexOfFirst { it.isLetter() }
        if (firstHeard != null && !firstHeard.isUpperCase() && !firstHeard.isLowerCase() && first >= 0) {
            out.setCharAt(first, out[first].uppercaseChar())
        }
        return out.toString()
    }

    private companion object {
        /** Native characters (code points, signs included) a word needs before a model spells it; see [unmapped]. */
        const val MODEL_MIN_LETTERS = 5

        const val SINHALA = "si"
        const val RUSSIAN = "ru"
        const val NEPALI = "ne"

        val PUNCTUATION = mapOf('।' to ".", '॥' to ".", '۔' to ".", '،' to ",", '؟' to "?", '؛' to ";")

        fun bestOf(readings: Map<String, List<String>>?): Map<String, String> =
            readings.orEmpty().mapNotNull { (word, spellings) -> spellings.firstOrNull()?.let { word to it } }.toMap()

        fun schemeFor(native: String): PhoneticScheme? =
            PhoneticSchemes.forLanguage(native) ?: PhoneticSchemes.HINDI.takeIf { native == NEPALI }
    }
}
