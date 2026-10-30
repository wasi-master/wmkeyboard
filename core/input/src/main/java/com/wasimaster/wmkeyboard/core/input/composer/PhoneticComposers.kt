package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.transliteration.ArabicPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.AssamesePhonetic
import com.wasimaster.wmkeyboard.core.transliteration.GreekPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.IndicPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.IndicProfiles
import com.wasimaster.wmkeyboard.core.transliteration.IndicScript
import com.wasimaster.wmkeyboard.core.transliteration.MinorPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.PersianPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.WylieTibetan

/**
 * A phonetic layout's composer for a language whose rules are data rather than
 * an engine of their own: the [IndicPhonetic] languages, Assamese, Persian and
 * Arabic. Bengali, Hindi, Urdu and Khipro keep their own objects; this is the
 * same contract over a [compose] function.
 *
 * @param phoneticLanguage the language the buffer is ranked against
 * @param compose the rules: roman buffer to script
 * @param cluster the script's block and virama, for a backspace over committed
 *        text that takes a whole conjunct; null where the script has none
 * @param isNative a letter of the script, which is what a learned word has to
 *        contain to be worth remembering
 */
class SchemeTransliterateComposer(
    override val phoneticLanguage: String?,
    private val compose: (String) -> String,
    private val cluster: Pair<IntRange, Char?>?,
    private val isNative: (Char) -> Boolean,
    override val bufferDigits: Boolean = false,
    override val digitsStartBuffer: Boolean = false,
    override val spaceText: String? = null,
    /** Non-letters the rules read, which the buffer otherwise wouldn't take. */
    private val bufferedSigns: String = "",
) : Composer {

    override fun buffersChar(c: Char): Boolean = c in bufferedSigns

    override val isTransliterating: Boolean get() = true

    override val isRomanBuffer: Boolean get() = true

    override fun composeBuffer(buffer: String): String = compose(buffer)

    /**
     * What one more key writes, read off the rules themselves: the buffer
     * transliterated with and without it, diffed from the common prefix — not
     * from the end, because a key can change the letter before it (the closing
     * vowel of an Urdu or Arabic word, a cluster forming in an Indic one).
     */
    override fun keyPreview(buffer: String, key: String, wholeCluster: Boolean): String? {
        if (key.isEmpty() || !key.all { it.isLetter() }) return null
        val before = compose(buffer)
        val after = compose(buffer + key)
        var shared = 0
        while (shared < before.length && shared < after.length && before[shared] == after[shared]) shared++
        return after.substring(shared)
    }

    override fun deleteLength(before: CharSequence): Int =
        cluster?.let { (range, virama) -> clusterDeleteLength(before, range, virama) } ?: defaultDeleteLength(before)

    /** A word with no letter of the script in it (a number, say) is not one to learn. */
    override fun isPlausibleWord(word: String): Boolean = word.any(isNative)
}

/**
 * The phonetic composer for a language, by `LanguageDef.id`, or null when the
 * language has none — and then [composerFor] falls back to its script's.
 */
object PhoneticComposers {

    private val byLanguage: Map<String, Composer> by lazy {
        buildMap {
            put("bn", BengaliTransliterateComposer)
            put("hi", HindiTransliterateComposer)
            put("ur", UrduTransliterateComposer)
            for (profile in IndicProfiles.all + IndicProfiles.minor) {
                val engine = IndicPhonetic(profile)
                val script = profile.script
                put(
                    profile.languageId,
                    SchemeTransliterateComposer(
                        phoneticLanguage = profile.languageId,
                        compose = engine::transliterate,
                        cluster = script.block to viramaFor(scriptIdOf(script)),
                        isNative = script::isNative,
                    ),
                )
            }
            for ((id, letters) in MinorPhonetic.languages) {
                put(
                    id,
                    SchemeTransliterateComposer(
                        // Rules only: these have no index to rank a list against.
                        phoneticLanguage = null,
                        compose = { MinorPhonetic.transliterate(id, it) },
                        cluster = null,
                        isNative = { it.code in letters },
                    ),
                )
            }
            put(
                "as",
                SchemeTransliterateComposer(
                    phoneticLanguage = "as",
                    compose = AssamesePhonetic::transliterate,
                    cluster = IndicScript.BENGALI.block to viramaFor(ScriptId.BENGALI),
                    isNative = AssamesePhonetic::isAssamese,
                ),
            )
            put(
                "fa",
                SchemeTransliterateComposer(
                    phoneticLanguage = "fa",
                    compose = PersianPhonetic::transliterate,
                    cluster = null,
                    isNative = ::isArabicLetter,
                ),
            )
            put(
                "ar",
                SchemeTransliterateComposer(
                    phoneticLanguage = "ar",
                    compose = ArabicPhonetic::transliterate,
                    cluster = null,
                    isNative = ::isArabicLetter,
                    // Arabizi spells ح ع خ ط ص ق with digits, and a word starts
                    // with one as often as not ("3ala" على), so a digit opens a
                    // word too. A number typed alone stays a number: the rules
                    // pass an all-digit run through untouched.
                    bufferDigits = true,
                    digitsStartBuffer = true,
                ),
            )
            put(
                "el",
                SchemeTransliterateComposer(
                    phoneticLanguage = "el",
                    compose = GreekPhonetic::transliterate,
                    cluster = null,
                    isNative = GreekPhonetic::isGreek,
                    // Chat spells θ with an 8 and ξ with a 3, also to start a word.
                    bufferDigits = true,
                    digitsStartBuffer = true,
                ),
            )
            put(
                "bo",
                SchemeTransliterateComposer(
                    // Wylie spells one exact thing, so nothing to rank.
                    phoneticLanguage = null,
                    compose = WylieTibetan::transliterate,
                    cluster = null,
                    isNative = WylieTibetan::isTibetan,
                    spaceText = WylieTibetan.TSHEG,
                    bufferedSigns = "+.~-",
                ),
            )
        }
    }

    fun forLanguage(langId: String): Composer? = byLanguage[langId]

    private fun scriptIdOf(script: IndicScript): ScriptId = when (script) {
        IndicScript.DEVANAGARI -> ScriptId.DEVANAGARI
        IndicScript.BENGALI -> ScriptId.BENGALI
        IndicScript.GURMUKHI -> ScriptId.GURMUKHI
        IndicScript.GUJARATI -> ScriptId.GUJARATI
        IndicScript.ORIYA -> ScriptId.ORIYA
        IndicScript.TAMIL -> ScriptId.TAMIL
        IndicScript.TELUGU -> ScriptId.TELUGU
        IndicScript.KANNADA -> ScriptId.KANNADA
        IndicScript.MALAYALAM -> ScriptId.MALAYALAM
    }

    private fun isArabicLetter(c: Char): Boolean = c.code in 0x0621..0x064A || c.code in 0x0671..0x06D3
}
