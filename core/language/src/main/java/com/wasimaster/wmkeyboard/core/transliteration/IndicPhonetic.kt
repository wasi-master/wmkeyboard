package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Phonetic typing for the Indic languages that do not have an engine of their
 * own: roman letters in, native script out (Marathi, Gujarati, Punjabi, Odia,
 * Tamil, Telugu, Kannada, Malayalam, Nepali and Sanskrit), one [IndicProfile] each. The engine
 * and every profile's rules live in Rust (`native/wm-engine/src/indic.rs` and
 * one module per language); this is the call into it.
 */
class IndicPhonetic(val profile: IndicProfile) {

    /** Transliterates roman text, one word or a sentence, into the script. */
    fun transliterate(input: String): String = EngineNative.indic(profile.languageId, input)

    /** Every reading of [input] worth offering, the likely one first. */
    fun variants(input: String): List<String> = EngineNative.indicVariants(profile.languageId, input).toList()
}

/**
 * One of the Indic engine's languages: its id, the script it writes, and
 * whether it ships curated spellings (`dictionaries/<id>_rom.tsv` and
 * `en_<id>.tsv`) that outrank the rules and the list.
 */
class IndicProfile(val languageId: String, val script: IndicScript, val curated: Boolean = false)

/**
 * The Indic engine's languages, and what each one's word-list index needs
 * beyond the shared Hindi fold ([IndicPhoneticIndex]).
 */
object IndicProfiles {

    private val DEVANAGARI_IDS = listOf(
        "ahr", "awa", "bfy", "bgc", "bgq-Deva", "bhb", "bho", "bjj", "bns", "brx", "dhd", "doi", "gbm", "gdx",
        "gju-Deva", "gno", "hlb", "hne", "hoj", "kfq", "knn", "kok", "kru", "ks-Deva", "lmn", "mag", "mai", "mjl",
        "mtr", "mup", "noe", "rkt-Deva", "rwr", "sck", "sd-Deva", "sjp", "swv", "vah", "wbr", "wry", "xnr",
    )
    private val BENGALI_IDS = listOf("bpy", "brx-Beng", "ctg", "grt-Beng", "mni-Beng", "sat-Beng", "trp", "unr")

    val all: List<IndicProfile> = listOf(
        IndicProfile("mr", IndicScript.DEVANAGARI, curated = true),
        IndicProfile("gu", IndicScript.GUJARATI, curated = true),
        IndicProfile("pa", IndicScript.GURMUKHI, curated = true),
        IndicProfile("or", IndicScript.ORIYA, curated = true),
        IndicProfile("ta", IndicScript.TAMIL, curated = true),
        IndicProfile("te", IndicScript.TELUGU, curated = true),
        IndicProfile("kn", IndicScript.KANNADA, curated = true),
        IndicProfile("ml", IndicScript.MALAYALAM, curated = true),
        IndicProfile("ne", IndicScript.DEVANAGARI, curated = true),
        // Typed in ITRANS or Harvard-Kyoto, which spell every word exactly:
        // nothing for a chat-spelling map to correct.
        IndicProfile("sa", IndicScript.DEVANAGARI),
    )

    /**
     * The languages on the engine's plain models, with no rules of their own
     * (`native/wm-engine/src/minor_phonetic.rs`, which has to list the same ids).
     */
    val minor: List<IndicProfile> = buildList {
        for (id in DEVANAGARI_IDS) add(IndicProfile(id, IndicScript.DEVANAGARI))
        for (id in BENGALI_IDS) add(IndicProfile(id, IndicScript.BENGALI))
        add(IndicProfile("bhb-Gujr", IndicScript.GUJARATI))
        add(IndicProfile("hlb-Orya", IndicScript.ORIYA))
        add(IndicProfile("kok-Knda", IndicScript.KANNADA))
        add(IndicProfile("lmn-Knda", IndicScript.KANNADA))
        add(IndicProfile("tcy", IndicScript.KANNADA))
        add(IndicProfile("gno-Telu", IndicScript.TELUGU))
        add(IndicProfile("lmn-Telu", IndicScript.TELUGU))
    }

    private val byLanguage: Map<String, IndicProfile> by lazy { (all + minor).associateBy { it.languageId } }

    fun forLanguage(languageId: String): IndicProfile? = byLanguage[languageId]

    /**
     * The language's romanization rewritten into what the Hindi fold reads,
     * before an index lookup: the Dravidian four write "th" and "dh" for the
     * plain dentals, Telugu, Kannada and Malayalam close a word on the
     * anusvara where roman writes "m", Tamil writes no voiced stops,
     * Malayalam folds its nasals and voicing. Nepali writes छ "x" and भ
     * "v" and says व as b; Sanskrit's Harvard-Kyoto capitals spell ऋ and the
     * nasals.
     */
    fun romanPrep(languageId: String): (String) -> String = when (languageId) {
        "ta", "te", "kn", "ml", "ne", "sa" -> { input -> EngineNative.indicRomanPrep(languageId, input) }
        else -> { input -> input }
    }

    /**
     * The list's words, in Devanagari, rewritten the same way as [romanPrep]
     * rewrites what is typed: Malayalam's voiced stops onto the unvoiced ones,
     * Nepali's व onto ब with the joiners of र्‍य dropped.
     */
    fun devanagariPrep(languageId: String): (String) -> String = when (languageId) {
        "ml", "ne" -> { dev -> EngineNative.indicDevanagariPrep(languageId, dev) }
        else -> { dev -> dev }
    }

    /**
     * Whether the language's romanization spells, at the end of a word, the
     * inherent vowel its script does not write: Nepali "xa" छ, Sanskrit
     * "rAma" राम. The index then reads a typed closing "a" that way for any
     * word ([HindiPhoneticIndex]'s typed schwa). Mirrors
     * `indic_profiles::typed_schwa` in the engine.
     */
    fun typedSchwa(languageId: String): Boolean = languageId == "ne" || languageId == "sa"

    /**
     * Whether the index should break ties by closeness to what the rules
     * themselves spell: for a list with no frequencies to break them (Tamil's).
     */
    fun rerankFlatList(languageId: String): Boolean = EngineNative.indicRerankFlatList(languageId)
}
