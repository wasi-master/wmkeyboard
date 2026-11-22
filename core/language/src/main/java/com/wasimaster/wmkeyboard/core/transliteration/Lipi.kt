package com.wasimaster.wmkeyboard.core.transliteration

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import kotlinx.coroutines.flow.StateFlow
import java.text.Normalizer

/**
 * A script or romanisation vidyut-lipi reads and writes.
 *
 * [native] is vidyut's own `Scheme` variant name, the contract with
 * native/lipi-jni (its `kotlin_names_are_schemes` test lists every one of
 * them). [label] is what a chip shows: the script's own name for the scripts
 * of living languages, the English one for the historical scripts most phones'
 * readers could not read their own name in, and the scheme's usual name for a
 * romanisation. [block] is the Unicode block a script owns, which is how a
 * selection's source is found ([LipiDetect]); a romanisation has none.
 */
enum class LipiScheme(val native: String, val label: String, val block: IntRange? = null) {
    IAST("Iast", "IAST"),
    ISO_15919("Iso15919", "ISO 15919"),
    ITRANS("Itrans", "ITRANS"),
    HARVARD_KYOTO("HarvardKyoto", "Harvard-Kyoto"),
    SLP1("Slp1", "SLP1"),
    VELTHUIS("Velthuis", "Velthuis"),
    WX("Wx", "WX"),
    BARAHA("BarahaSouth", "Baraha"),

    DEVANAGARI("Devanagari", "देवनागरी", 0x0900..0x097F),
    BENGALI("Bengali", "বাংলা", 0x0980..0x09FF),
    /** Bengali's block too; told apart by ৰ and ৱ, which only Assamese writes. */
    ASSAMESE("Assamese", "অসমীয়া"),
    GURMUKHI("Gurmukhi", "ਗੁਰਮੁਖੀ", 0x0A00..0x0A7F),
    GUJARATI("Gujarati", "ગુજરાતી", 0x0A80..0x0AFF),
    ODIA("Odia", "ଓଡ଼ିଆ", 0x0B00..0x0B7F),
    TAMIL("Tamil", "தமிழ்", 0x0B80..0x0BFF),
    TELUGU("Telugu", "తెలుగు", 0x0C00..0x0C7F),
    KANNADA("Kannada", "ಕನ್ನಡ", 0x0C80..0x0CFF),
    MALAYALAM("Malayalam", "മലയാളം", 0x0D00..0x0D7F),
    SINHALA("Sinhala", "සිංහල", 0x0D80..0x0DFF),
    OL_CHIKI("OlChiki", "ᱚᱞ ᱪᱤᱠᱤ", 0x1C50..0x1C7F),
    MEETEI_MAYEK("MeeteiMayek", "ꯃꯤꯇꯩ ꯃꯌꯦꯛ", 0xABC0..0xABFF),
    LIMBU("Limbu", "Limbu", 0x1900..0x194F),
    THAI("Thai", "ไทย", 0x0E00..0x0E7F),
    BURMESE("Burmese", "မြန်မာ", 0x1000..0x109F),
    KHMER("Khmer", "ខ្មែរ", 0x1780..0x17FF),
    TIBETAN("Tibetan", "བོད་ཡིག", 0x0F00..0x0FFF),
    TAI_THAM("TaiTham", "Tai Tham", 0x1A20..0x1AAF),
    JAVANESE("Javanese", "Javanese", 0xA980..0xA9DF),
    BALINESE("Balinese", "Balinese", 0x1B00..0x1B7F),
    CHAM("Cham", "Cham", 0xAA00..0xAA5F),
    GRANTHA("Grantha", "Grantha", 0x11300..0x1137F),
    SHARADA("Sharada", "Sharada", 0x11180..0x111DF),
    SIDDHAM("Siddham", "Siddham", 0x11580..0x115FF),
    BRAHMI("Brahmi", "Brahmi", 0x11000..0x1107F),
    MODI("Modi", "Modi", 0x11600..0x1165F),
    NEWA("Newa", "Newa", 0x11400..0x1147F),
    TIRHUTA("Tirhuta", "Tirhuta", 0x11480..0x114DF),
    NANDINAGARI("Nandinagari", "Nandinagari", 0x119A0..0x119FF),
    TAKRI("Takri", "Takri", 0x11680..0x116CF),
    DOGRA("Dogra", "Dogra", 0x11800..0x1184F),
    KAITHI("Kaithi", "Kaithi", 0x11080..0x110CF),
    KHUDAWADI("Khudawadi", "Khudawadi", 0x112B0..0x112FF),
    SAURASHTRA("Saurashtra", "Saurashtra", 0xA880..0xA8DF),
    BHAIKSUKI("Bhaiksuki", "Bhaiksuki", 0x11C00..0x11C6F),
    KHAROSHTHI("Kharoshthi", "Kharoshthi", 0x10A00..0x10A5F),
    SOYOMBO("Soyombo", "Soyombo", 0x11A50..0x11AAF),
    ZANABAZAR_SQUARE("ZanabazarSquare", "Zanabazar Square", 0x11A00..0x11A4F),
    MASARAM_GONDI("MasaramGondi", "Masaram Gondi", 0x11D00..0x11D5F),
    GUNJALA_GONDI("GunjalaGondi", "Gunjala Gondi", 0x11D60..0x11DAF),
    ;

    /** A Latin romanisation rather than a script of its own. */
    val roman: Boolean get() = ordinal <= BARAHA.ordinal

    companion object {
        /** By [native] name, as the settings store a scheme. */
        fun of(native: String?): LipiScheme? = entries.firstOrNull { it.native == native }

        /** The romanisations a plain-letter selection may be read as: every one that types on the letter keys. */
        val asciiInputs: List<LipiScheme> = listOf(ITRANS, HARVARD_KYOTO, SLP1, VELTHUIS, WX, BARAHA)

        /** The script a language is written in, for the languages whose keyboards this matters on. */
        fun scriptOf(languageId: String): LipiScheme? = when (languageId) {
            "hi", "mr", "ne", "sa", "mai", "bho", "awa", "mag", "kok", "doi", "new" -> DEVANAGARI
            "bn" -> BENGALI
            "as" -> ASSAMESE
            "pa" -> GURMUKHI
            "gu" -> GUJARATI
            "or" -> ODIA
            "ta" -> TAMIL
            "te" -> TELUGU
            "kn" -> KANNADA
            "ml" -> MALAYALAM
            "si" -> SINHALA
            "sat" -> OL_CHIKI
            "mni" -> MEETEI_MAYEK
            "th" -> THAI
            "my" -> BURMESE
            "km" -> KHMER
            "bo" -> TIBETAN
            else -> null
        }

        /**
         * Where Convert script's choices go for a selection in [from], typed on
         * a [languageId] keyboard: the keyboard's own script first (the likely
         * way back), then the romanisations, then every other script in the
         * enum's order, which puts India's and Sri Lanka's before the rest.
         */
        fun targets(from: LipiScheme, languageId: String): List<LipiScheme> {
            val own = scriptOf(languageId)
            val lead = listOfNotNull(own) + entries.filter { it.roman }
            return (lead + entries).distinct().filter { it != from }
        }
    }
}

/**
 * Which [LipiScheme] a piece of text is in, read from the text alone: the
 * Unicode block most of its letters are in, or for Latin text the marks only
 * a romanisation writes. Pure and cheap, so the selection bar can ask on
 * every selection change without loading the library.
 */
object LipiDetect {

    /**
     * The dotted and lined letters only an Indic romanisation writes (ṛ ṃ ṭ
     * ṇ ṣ …), lower and upper. Deliberately not ā ī ū ē ō ś ñ, which Pinyin,
     * Latvian, Maori, Polish and Spanish write as well.
     */
    private const val INDIC_MARKED = "ṛṝḷḹṃṁḥṅṭḍṇṣṚṜḶḸṂṀḤṄṬḌṆṢ"

    /** What ISO 15919 writes and IAST does not: ṁ, ē, ō, and the ring below (U+0325) of its r and l. */
    private const val ISO_ONLY = "ṁṀēĒōŌ\u0325"

    /** The script most of [text]'s letters are in, or null for text in none of them. */
    fun script(text: CharSequence): LipiScheme? {
        val counts = IntArray(LipiScheme.entries.size)
        var assamese = false
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (cp < 0x0900) continue
            if (cp == 0x09F0 || cp == 0x09F1) assamese = true
            val scheme = blockOf(cp) ?: continue
            counts[scheme.ordinal]++
        }
        val best = counts.indices.maxByOrNull { counts[it] }?.takeIf { counts[it] > 0 } ?: return null
        val scheme = LipiScheme.entries[best]
        return if (scheme == LipiScheme.BENGALI && assamese) LipiScheme.ASSAMESE else scheme
    }

    private fun blockOf(cp: Int): LipiScheme? {
        for (scheme in LipiScheme.entries) {
            val block = scheme.block ?: continue
            if (cp in block) return scheme
        }
        return null
    }

    /**
     * The romanisation Latin [text] is written in, when it says so itself:
     * IAST or ISO 15919 from their marked letters, ITRANS from the carets,
     * tildes and dots only it spells with. Null for plain letters, which
     * could be any of [LipiScheme.asciiInputs] and English besides.
     */
    fun romanisation(text: CharSequence): LipiScheme? {
        val s = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (s.any { it in INDIC_MARKED } || ('\u0325' in s && s.any { it == 'r' || it == 'l' })) {
            return if (s.any { it in ISO_ONLY }) LipiScheme.ISO_15919 else LipiScheme.IAST
        }
        if (ITRANS_MARKS.containsMatchIn(s)) return LipiScheme.ITRANS
        return null
    }

    /** R^i, L^I, ~N, ~n, .N, .h, .a, RRi, LLi: the spellings that are ITRANS's alone. */
    private val ITRANS_MARKS = Regex("""[RL]\^[iI]|~[Nn]|[a-zA-Z]\.[Nha]|\.[Nh]\b|RR[iI]|LL[iI]""")

    /**
     * Where [text] comes from for Convert script: its script, else the
     * romanisation it shows, else [plainLetters] when it has Latin letters at
     * all, else null (digits and punctuation have nothing to convert).
     */
    fun source(text: CharSequence, plainLetters: LipiScheme?): LipiScheme? =
        script(text) ?: romanisation(text) ?: plainLetters?.takeIf { text.any { it in 'a'..'z' || it in 'A'..'Z' } }
}

/**
 * Text from one Indic script or romanisation to another, through vidyut-lipi
 * (native/lipi-jni). A gate of its own (`lipi` in native/gates.json), so every
 * caller asks [ready] first and offers [requestModule] when it is not.
 */
object Lipi {

    /** The library is here and loaded. */
    val ready: Boolean get() = LipiNative.available

    /** This build can have it, fetched or not. */
    val shipped: Boolean get() = LipiNative.shipped

    val moduleState: StateFlow<ModuleState> get() = FeatureModules.lipi.state

    fun requestModule() = FeatureModules.lipi.requestInstall()

    /**
     * [text] in [to], read as [from]. The same text when the two are the same
     * scheme; null while the library is missing or when it refuses. Text in
     * neither scheme (English inside a Devanagari sentence) comes through as
     * it was: vidyut only maps what it knows.
     */
    fun convert(text: String, from: LipiScheme, to: LipiScheme): String? {
        if (from == to) return text
        if (!ready) return null
        return runCatching { LipiNative.nativeTransliterate(text, from.native, to.native) }.getOrNull()
    }
}
