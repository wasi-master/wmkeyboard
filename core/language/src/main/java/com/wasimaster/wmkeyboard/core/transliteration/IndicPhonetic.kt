package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Phonetic typing for the Indic languages that do not have an engine of their
 * own: roman letters in, native script out (Marathi, Gujarati, Punjabi, Odia,
 * Tamil, Telugu, Kannada and Malayalam), one [IndicProfile] each. The engine
 * and every profile's rules live in Rust (`native/wm-engine/src/indic.rs` and
 * one module per language); this is the call into it.
 */
class IndicPhonetic(val profile: IndicProfile) {

    /** Transliterates roman text, one word or a sentence, into the script. */
    fun transliterate(input: String): String = EngineNative.indic(profile.languageId, input)

    /** Every reading of [input] worth offering, the likely one first. */
    fun variants(input: String): List<String> = EngineNative.indicVariants(profile.languageId, input).toList()
}

/** One of the Indic engine's languages: its id, and the script it writes. */
class IndicProfile(val languageId: String, val script: IndicScript)

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
        IndicProfile("mr", IndicScript.DEVANAGARI),
        IndicProfile("gu", IndicScript.GUJARATI),
        IndicProfile("pa", IndicScript.GURMUKHI),
        IndicProfile("or", IndicScript.ORIYA),
        IndicProfile("ta", IndicScript.TAMIL),
        IndicProfile("te", IndicScript.TELUGU),
        IndicProfile("kn", IndicScript.KANNADA),
        IndicProfile("ml", IndicScript.MALAYALAM),
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
     * Malayalam folds its nasals and voicing.
     */
    fun romanPrep(languageId: String): (String) -> String = when (languageId) {
        "ta", "te", "kn", "ml" -> { input -> EngineNative.indicRomanPrep(languageId, input) }
        else -> { input -> input }
    }

    /**
     * The list's words, in Devanagari, rewritten the same way as [romanPrep]
     * rewrites what is typed: Malayalam's voiced stops onto the unvoiced ones.
     */
    fun devanagariPrep(languageId: String): (String) -> String = when (languageId) {
        "ml" -> { dev -> EngineNative.indicDevanagariPrep(languageId, dev) }
        else -> { dev -> dev }
    }

    /**
     * Whether the index should break ties by closeness to what the rules
     * themselves spell: for a list with no frequencies to break them (Tamil's).
     */
    fun rerankFlatList(languageId: String): Boolean = EngineNative.indicRerankFlatList(languageId)
}
