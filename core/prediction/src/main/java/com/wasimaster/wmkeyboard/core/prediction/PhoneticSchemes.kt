package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.ArabicPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.AssamesePhonetic
import com.wasimaster.wmkeyboard.core.transliteration.AssamesePhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.BengaliRomanizer
import com.wasimaster.wmkeyboard.core.transliteration.BornoPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.DevanagariRomanizer
import com.wasimaster.wmkeyboard.core.transliteration.DravidianRomanizer
import com.wasimaster.wmkeyboard.core.transliteration.GreekPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.GreekPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.IndicPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.IndicPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.IndicProfile
import com.wasimaster.wmkeyboard.core.transliteration.IndicProfiles
import com.wasimaster.wmkeyboard.core.transliteration.PersianPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticTables
import com.wasimaster.wmkeyboard.core.transliteration.UrduPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.UrduPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.UrduRomanizer
import java.text.Normalizer

/**
 * Everything the prediction side needs to know about one phonetic input
 * method: a Latin grid whose text is another script, ranked against that
 * script's dictionary (Avro for Bengali).
 *
 * The composer says *which* scheme a layout types through
 * (`Composer.phoneticLanguage`); this says what that means — the rules, the
 * lenient index over the word list, and the curated spellings that outrank
 * both. Adding a language is one more entry here, not another branch in the
 * engine or the service.
 */
class PhoneticScheme(
    /** `LanguageDef.id` of the language typed, and of the word list it is ranked against. */
    val languageId: String,
    /** Id of the downloadable romanized word list a glide is decoded against. */
    val romanizedListId: String,
    /**
     * `spelling<TAB>native` assets under `assets/`, most trusted first — where
     * two disagree the earlier one leads ([SpellingMap.load]).
     */
    val spellingAssets: List<String>,
    /** The rules alone: one romanized word (or free text) in native script. */
    val transliterate: (String) -> String,
    /**
     * Every reading the rules think plausible, the literal one first. More than
     * one only where the rules are genuinely undecided and there may be no
     * dictionary to settle it.
     */
    val variants: (String) -> List<String>,
    /** The lenient index over `word to frequency` entries of the language's list. */
    val buildIndex: (List<Pair<String, Int>>) -> PhoneticIndex,
    /** Whether [Char] belongs to a word of the script — letters and signs, not digits or the danda. */
    val isNative: (Char) -> Boolean,
    /** The script's spelling variants folded to the one the word lists use, before [romanizeWord]. */
    val normalize: (String) -> String,
    /** The other direction: one native word as people would spell it in Latin letters. */
    val romanizeWord: (String) -> String,
) {
    /**
     * How many of [spellingAssets], counted from the front, list another
     * language's words in this script (`en_bn.tsv`) rather than this language
     * romanized (`bn_rom.tsv`). What [SpellingMap.load] is told, so that
     * [SpellingMap.isLoanword] can tell the two apart.
     */
    val loanwordAssetCount: Int
        get() = spellingAssets.takeWhile { !it.substringAfterLast('/').startsWith(languageId) }.size
}

/** The phonetic schemes that ship, by language. */
object PhoneticSchemes {

    val BENGALI = PhoneticScheme(
        languageId = "bn",
        romanizedListId = "bn_rom",
        spellingAssets = listOf("dictionaries/en_bn.tsv", "dictionaries/bn_rom.tsv"),
        transliterate = AvroPhonetic::transliterate,
        variants = { listOf(AvroPhonetic.transliterate(it)) },
        buildIndex = ::BengaliPhoneticIndex,
        isNative = BengaliGraphemes::isBengali,
        normalize = BengaliRomanizer::normalize,
        romanizeWord = BengaliRomanizer::romanizeWord,
    )

    /** Bangla's list and index read through Borno Phonetic's rules: its literal reading, not Avro's. */
    val BENGALI_BORNO = PhoneticScheme(
        languageId = BENGALI.languageId,
        romanizedListId = BENGALI.romanizedListId,
        spellingAssets = BENGALI.spellingAssets,
        transliterate = BornoPhonetic::transliterate,
        variants = { listOf(BornoPhonetic.transliterate(it)) },
        buildIndex = BENGALI.buildIndex,
        isNative = BENGALI.isNative,
        normalize = BENGALI.normalize,
        romanizeWord = BENGALI.romanizeWord,
    )

    /**
     * Hindi has no bundled word list, so its index is built over whatever the
     * user downloaded or imported — and is empty until they have. That is why
     * its [PhoneticScheme.variants] carry more than one reading, and why its
     * spelling map is a vocabulary rather than a list of exceptions.
     */
    val HINDI = PhoneticScheme(
        languageId = "hi",
        romanizedListId = "hi_rom",
        spellingAssets = listOf("dictionaries/en_hi.tsv", "dictionaries/hi_rom.tsv"),
        transliterate = HindiPhonetic::transliterate,
        variants = HindiPhonetic::variants,
        buildIndex = ::HindiPhoneticIndex,
        isNative = DevanagariRomanizer::isDevanagari,
        normalize = DevanagariRomanizer::normalize,
        romanizeWord = DevanagariRomanizer::romanizeWord,
    )

    /**
     * Urdu's word list is a download too, and a flat one — every word in it has
     * frequency 1 — so its spelling map carries more weight than either of the
     * others': it is the only thing that can rank one reading of a spelling
     * above another for the words people type most (see [UrduPhoneticIndex]).
     */
    val URDU = PhoneticScheme(
        languageId = "ur",
        romanizedListId = "ur_rom",
        spellingAssets = listOf("dictionaries/en_ur.tsv", "dictionaries/ur_rom.tsv"),
        transliterate = UrduPhonetic::transliterate,
        variants = UrduPhonetic::variants,
        buildIndex = ::UrduPhoneticIndex,
        isNative = UrduRomanizer::isUrdu,
        normalize = UrduRomanizer::normalize,
        romanizeWord = UrduRomanizer::romanizeWord,
    )

    /**
     * An [IndicPhonetic] language: its rules, the Hindi fold reached through
     * Devanagari ([IndicPhoneticIndex]) with the language's own romanization
     * habits rewritten first ([IndicProfiles.romanPrep]), and for the way back
     * the Hindi romanizer over the same Devanagari spelling, or for the four
     * Dravidian languages, which say the vowel Hindi drops, their own.
     */
    private fun indic(profile: IndicProfile): PhoneticScheme {
        val engine = IndicPhonetic(profile)
        val script = profile.script
        val id = profile.languageId
        return PhoneticScheme(
            languageId = id,
            romanizedListId = "${id}_rom",
            // Only the eight with rules of their own have curated spellings.
            spellingAssets = if (profile in IndicProfiles.all) {
                listOf("dictionaries/en_$id.tsv", "dictionaries/${id}_rom.tsv")
            } else {
                emptyList()
            },
            transliterate = engine::transliterate,
            variants = engine::variants,
            buildIndex = { PhoneticTables.index(id, PhoneticTables.build(id, it)) },
            isNative = script::isNative,
            normalize = { Normalizer.normalize(it, Normalizer.Form.NFC) },
            romanizeWord = if (DravidianRomanizer.reads(script)) {
                { DravidianRomanizer.romanizeWord(script, it) }
            } else {
                { DevanagariRomanizer.romanizeWord(script.toDevanagari(it)) }
            },
        )
    }

    /** Marathi, Gujarati, Punjabi, Odia, Tamil, Telugu, Kannada, Malayalam, and the plain [IndicProfiles.minor]. */
    val INDIC: List<PhoneticScheme> = (IndicProfiles.all + IndicProfiles.minor).map(::indic)

    /** Assamese: Avro's rules with ৰ for র, and Bangla's index over the list in Bangla letters. */
    val ASSAMESE = PhoneticScheme(
        languageId = "as",
        romanizedListId = "as_rom",
        spellingAssets = listOf("dictionaries/en_as.tsv", "dictionaries/as_rom.tsv"),
        transliterate = AssamesePhonetic::transliterate,
        variants = { listOf(AssamesePhonetic.transliterate(it)) },
        buildIndex = ::AssamesePhoneticIndex,
        isNative = AssamesePhonetic::isAssamese,
        normalize = { BengaliRomanizer.normalize(it) },
        romanizeWord = { BengaliRomanizer.romanizeWord(AssamesePhonetic.toBengali(it)) },
    )

    /** Persian: Urdu's skeleton fold, with "gh" read as ق too. */
    val PERSIAN = PhoneticScheme(
        languageId = "fa",
        romanizedListId = "fa_rom",
        spellingAssets = listOf("dictionaries/en_fa.tsv", "dictionaries/fa_rom.tsv"),
        transliterate = PersianPhonetic::transliterate,
        variants = PersianPhonetic::variants,
        buildIndex = { PhoneticTables.index("fa", PhoneticTables.build("fa", it)) },
        isNative = UrduRomanizer::isUrdu,
        normalize = UrduRomanizer::normalize,
        romanizeWord = UrduRomanizer::romanizeWord,
    )

    /** Arabic: Urdu's skeleton fold, with the Arabizi digits read as letters. */
    val ARABIC = PhoneticScheme(
        languageId = "ar",
        romanizedListId = "ar_rom",
        spellingAssets = listOf("dictionaries/en_ar.tsv", "dictionaries/ar_rom.tsv"),
        transliterate = ArabicPhonetic::transliterate,
        variants = ArabicPhonetic::variants,
        buildIndex = { PhoneticTables.index("ar", PhoneticTables.build("ar", it)) },
        isNative = UrduRomanizer::isUrdu,
        normalize = UrduRomanizer::normalize,
        romanizeWord = UrduRomanizer::romanizeWord,
    )

    /** Greek: every spelling of a sound folds to one key, so the word list settles which it is. No curated spellings yet. */
    val GREEK = PhoneticScheme(
        languageId = "el",
        romanizedListId = "el_rom",
        spellingAssets = emptyList(),
        transliterate = GreekPhonetic::transliterate,
        variants = { listOf(GreekPhonetic.transliterate(it)) },
        buildIndex = ::GreekPhoneticIndex,
        isNative = GreekPhonetic::isGreek,
        normalize = { Normalizer.normalize(it, Normalizer.Form.NFC) },
        romanizeWord = GreekPhonetic::romanize,
    )

    val all: List<PhoneticScheme> = listOf(BENGALI, HINDI, URDU) + INDIC + listOf(ASSAMESE, PERSIAN, ARABIC, GREEK)

    fun forLanguage(languageId: String?): PhoneticScheme? =
        all.firstOrNull { it.languageId == languageId }
}

/**
 * One scheme with its data loaded: what the engine consults for a composing
 * buffer typed on that scheme's layout. [index] is a var because a word list
 * can arrive (a download, an import) under a running engine.
 */
class PhoneticBackend(
    @Volatile var scheme: PhoneticScheme,
    @Volatile var index: PhoneticIndex,
    val spellings: SpellingMap = SpellingMap.EMPTY,
)
