package com.wasimaster.wmkeyboard.core.script

/**
 * A writing system, named by its Unicode script. This is the axis the keyboard's
 * script-level behaviour keys off — text direction, whether the script has an
 * upper/lower case, which [ComposerType] runs, and which font it wants — so that
 * "type Serbian" or "type Persian" is data in [ScriptRegistry] rather than a new
 * branch in the service.
 *
 * The enum lists every script the keyboard intends to support so ids are stable
 * from the start, and [ScriptRegistry] carries a [ScriptDef] for each. Most of
 * the long tail below arrived with the Keyman layout corpus and will only ever
 * back one keyboard; they are cheap to carry — a [ScriptDef] is a data row and
 * the enum constant costs nothing — and the alternative is worse, because
 * [ScriptRegistry.get] falls back to Latin for a script it does not know.
 */
enum class ScriptId {
    LATIN, CYRILLIC, GREEK, ARMENIAN, GEORGIAN,
    ARABIC, HEBREW, SYRIAC,
    DEVANAGARI, BENGALI, GURMUKHI, GUJARATI, ORIYA,
    TAMIL, TELUGU, KANNADA, MALAYALAM, SINHALA,
    THAI, LAO, KHMER, MYANMAR,
    HANGUL, ETHIOPIC, THAANA,
    JAPANESE, HAN,

    /**
     * The International Phonetic Alphabet. Not a language's writing system but a
     * transcription notation: Latin-derived glyphs plus the IPA Extensions block
     * and spacing/combining modifiers. Uncased, no dictionary, no composer — every
     * key commits its symbol as-is.
     */
    IPA,
    TIFINAGH, CHEROKEE,
    NKO, CANADIAN_ABORIGINAL_SYLLABICS,
    TIBETAN,
    OL_CHIKI, MEETEI_MAYEK, TAI_LE,

    /** Vai (Liberia): a syllabary, U+A500..A63F. Uncased, no composer. */
    VAI,

    /**
     * Osage, U+104B0..104FB. Cased — the block encodes separate capital and
     * small letters — and outside the BMP, so every character is a surrogate
     * pair. Anything counting characters here has to count code points.
     */
    OSAGE,

    /**
     * Adlam (Pular/Fulani), U+1E900..1E95F. Right to left *and* cased, which
     * few scripts are, and outside the BMP like [OSAGE].
     */
    ADLAM,

    // --- Scripts reached only through the Keyman layout corpus. ---
    //
    // Each backs at least one keyboard in the Keyman release set. Ranges,
    // direction and letter case were derived from Unicode 17.0 Scripts.txt
    // and UnicodeData.txt rather than written by hand, which is how Hanifi
    // Rohingya and Cypriot come out RTL and Old Hungarian comes out both RTL
    // and cased. Most will only ever back a single layout, and a good number
    // are historic. They are listed so a converted layout can name its own
    // script: an unregistered script falls back to Latin, which would draw
    // the user's Latin key font over glyphs that are not Latin and give no
    // hint on screen as to why.
    AHOM,  // 1 keyboard, U+11700..1171A
    AVESTAN,  // 1 keyboard, U+10B00..10B35
    BALINESE,  // 1 keyboard, U+1B05..1B33
    BAMUM,  // 1 keyboard, U+16800..16A38
    BASSA_VAH,  // 1 keyboard, U+16AD0..16AED
    BATAK,  // 1 keyboard, U+1BC0..1BE5
    BHAIKSUKI,  // 1 keyboard, U+11C0A..11C2E
    BOPOMOFO,  // 1 keyboard, U+3105..312F
    BRAHMI,  // 1 keyboard, U+11003..11037
    BUGINESE,  // 1 keyboard, U+1A00..1A16
    BUHID,  // 1 keyboard, U+1740..1751
    CAUCASIAN_ALBANIAN,  // 1 keyboard, U+10530..10563
    CHAKMA,  // 1 keyboard, U+11103..11126
    CHAM,  // 1 keyboard, U+AA00..AA28
    COPTIC,  // 3 keyboards, U+2C80..2CE4
    CYPRIOT,  // 1 keyboard, U+1080A..10835
    CYPRO_MINOAN,  // 1 keyboard, U+12F90..12FF0
    DESERET,  // 1 keyboard, U+10400..1044F
    DIVES_AKURU,  // 1 keyboard, U+11918..1192F
    DOGRA,  // 1 keyboard, U+11800..1182B
    ELBASAN,  // 1 keyboard, U+10500..10527
    GLAGOLITIC,  // 1 keyboard, U+2C00..2C5F
    GOTHIC,  // 1 keyboard, U+10330..10340
    GRANTHA,  // 1 keyboard, U+11313..11328
    GUNJALA_GONDI,  // 2 keyboards, U+11D6A..11D89
    HANIFI_ROHINGYA,  // 2 keyboards, U+10D00..10D23
    HANUNOO,  // 1 keyboard, U+1720..1731
    HATRAN,  // 1 keyboard, U+108E0..108F2
    INSCRIPTIONAL_PAHLAVI,  // 1 keyboard, U+10B60..10B72
    INSCRIPTIONAL_PARTHIAN,  // 1 keyboard, U+10B40..10B55
    JAVANESE,  // 1 keyboard, U+A984..A9B2
    KAITHI,  // 1 keyboard, U+11083..110AF
    KAWI,  // 1 keyboard, U+11F12..11F33
    KAYAH_LI,  // 1 keyboard, U+A90A..A925
    KHAROSHTHI,  // 1 keyboard, U+10A19..10A35
    KHOJKI,  // 1 keyboard, U+11213..1122B
    KHUDAWADI,  // 1 keyboard, U+112B0..112DE
    KIRAT_RAI,  // 1 keyboard, U+16D43..16D6A
    LEPCHA,  // 1 keyboard, U+1C00..1C23
    LIMBU,  // 2 keyboards, U+1900..191E
    LINEAR_B,  // 2 keyboards, U+10080..100FA
    LISU,  // 4 keyboards, U+A4D0..A4F7
    LYCIAN,  // 1 keyboard, U+10280..1029C
    LYDIAN,  // 1 keyboard, U+10920..10939
    MAHAJANI,  // 1 keyboard, U+11150..11172
    MAKASAR,  // 1 keyboard, U+11EE0..11EF2
    MANDAIC,  // 1 keyboard, U+0840..0858
    MANICHAEAN,  // 1 keyboard, U+10AC9..10AE4
    MARCHEN,  // 1 keyboard, U+11C72..11C8F
    MASARAM_GONDI,  // 1 keyboard, U+11D0B..11D30
    MEDEFAIDRIN,  // 1 keyboard, U+16E40..16E7F
    MENDE_KIKAKUI,  // 1 keyboard, U+1E800..1E8C4
    MEROITIC_CURSIVE,  // 1 keyboard, U+109D2..109FF
    MEROITIC_HIEROGLYPHS,  // 1 keyboard, U+10980..1099F
    MIAO,  // 5 keyboards, U+16F00..16F4A
    MODI,  // 1 keyboard, U+11600..1162F
    MONGOLIAN,  // 3 keyboards, U+1844..1878
    MRO,  // 1 keyboard, U+16A40..16A5E
    MULTANI,  // 1 keyboard, U+1128F..1129D
    NABATAEAN,  // 1 keyboard, U+10880..1089E
    NAG_MUNDARI,  // 1 keyboard, U+1E4D0..1E4EA
    NANDINAGARI,  // 1 keyboard, U+119AA..119D0
    NEWA,  // 3 keyboards, U+11400..11434
    NEW_TAI_LUE,  // 2 keyboards, U+1980..19AB
    NYIAKENG_PUACHUE_HMONG,  // 1 keyboard, U+1E100..1E12C
    OGHAM,  // 1 keyboard, U+1681..169A
    OLD_HUNGARIAN,  // 1 keyboard, U+10C80..10CB2
    OLD_ITALIC,  // 1 keyboard, U+10300..1031F
    OLD_PERMIC,  // 1 keyboard, U+10350..10375
    OLD_PERSIAN,  // 1 keyboard, U+103A0..103C3
    OLD_SOGDIAN,  // 1 keyboard, U+10F00..10F1C
    OLD_SOUTH_ARABIAN,  // 1 keyboard, U+10A60..10A7C
    OLD_UYGHUR,  // 1 keyboard, U+10F70..10F81
    OSMANYA,  // 1 keyboard, U+10480..1049D
    PAHAWH_HMONG,  // 1 keyboard, U+16B00..16B2F
    PALMYRENE,  // 1 keyboard, U+10860..10876
    PAU_CIN_HAU,  // 1 keyboard, U+11AC0..11AF8
    PHAGS_PA,  // 1 keyboard, U+A840..A873
    PHOENICIAN,  // 1 keyboard, U+10900..10915
    PSALTER_PAHLAVI,  // 1 keyboard, U+10B80..10B91
    REJANG,  // 1 keyboard, U+A930..A946
    RUNIC,  // 3 keyboards, U+16A0..16EA
    SAMARITAN,  // 1 keyboard, U+0800..0815
    SAURASHTRA,  // 1 keyboard, U+A882..A8B3
    SHARADA,  // 1 keyboard, U+11183..111B2
    SHAVIAN,  // 2 keyboards, U+10450..1047F
    SIDDHAM,  // 1 keyboard, U+11580..115AE
    SOGDIAN,  // 1 keyboard, U+10F30..10F45
    SORA_SOMPENG,  // 1 keyboard, U+110D0..110E8
    SOYOMBO,  // 1 keyboard, U+11A5C..11A89
    SUNDANESE,  // 1 keyboard, U+1B83..1BA0
    SYLOTI_NAGRI,  // 1 keyboard, U+A80C..A822
    TAGALOG,  // 1 keyboard, U+1700..1711
    TAGBANWA,  // 1 keyboard, U+1760..176C
    TAI_THAM,  // 1 keyboard, U+1A20..1A54
    TAI_VIET,  // 2 keyboards, U+AA80..AAAF
    TAKRI,  // 2 keyboards, U+11680..116AA
    TIRHUTA,  // 1 keyboard, U+11480..114AF
    TODHRI,  // 1 keyboard, U+105C0..105F3
    TOTO,  // 1 keyboard, U+1E290..1E2AD
    UGARITIC,  // 1 keyboard, U+10380..1039D
    VITHKUQI,  // 1 keyboard, U+1057C..1058A
    YEZIDI,  // 1 keyboard, U+10E80..10EA9
    YI,  // 1 keyboard, U+A016..A48C
    ZANABAZAR_SQUARE,  // 1 keyboard, U+11A0B..11A32

    /**
     * Western musical notation: the Musical Symbols block (U+1D100..1D1FF) plus
     * the BMP note/accidental characters (U+2669..266F). Not a writing system —
     * a notation, offered like IPA. Uncased, no composer; its dedicated font
     * ride is what matters, since device fonts rarely carry the SMP block.
     */
    MUSIC,

    /**
     * Braille patterns (U+2800..28FF). The six-key chorded layout types these —
     * or rather the Grade-1 letters they decode to — so the script mostly
     * exists to declare "uncased, no composer" and to pin a font that has the
     * dot-cell glyphs for the keycaps.
     */
    BRAILLE,
}

/** Which way the script runs. Drives the suggestion strip's layout direction. */
enum class TextDirection { LTR, RTL }

/**
 * How keystrokes turn into committed text beyond a straight 1:1 append.
 *
 *  - [NONE]           — the key's output is committed as-is.
 *  - [DEAD_KEY]       — combining-accent composition (Latin/Greek/Cyrillic …),
 *                       handled by the script-agnostic `DeadKeys` NFC path.
 *  - [TRANSLITERATE]  — roman letters transliterated to another script as a unit
 *                       (Avro → Bengali); the composing buffer is the input method.
 *  - [INDIC_CLUSTER]  — Brahmic/SEA scripts whose grapheme clusters (conjuncts,
 *                       vowel signs) must be deleted and shaped as a unit.
 *  - [HANGUL]         — Korean jamo composed into syllable blocks.
 *
 * A single script can host more than one: Bengali defaults to [INDIC_CLUSTER]
 * (Probhat/Jatiya) but the Avro layout overrides to [TRANSLITERATE]. The default
 * lives here; the per-layout override lands in Phase 1 on `LayoutSpec`.
 */
enum class ComposerType {
    NONE, DEAD_KEY, TRANSLITERATE, INDIC_CLUSTER, HANGUL,

    /** Vietnamese Telex: roman keystrokes fold into toned Vietnamese letters. */
    TELEX,

    /** Vietnamese VNI: digit keys apply tones and letter marks. */
    VNI,

    /** Japanese: romaji composes to kana, with kana→kanji candidates. */
    ROMAJI,

    /** Chinese: pinyin buffer with Hanzi candidates chosen from the strip. */
    PINYIN,

    /** Chinese: 笔画 stroke-class buffer (一丨丿丶乙) with Hanzi candidates. */
    STROKE,

    /** Chinese: 九宫格 pinyin typed as ambiguous 9-key digit runs (64 → ni). */
    T9_PINYIN,

    /** Chinese: 注音 bopomofo symbols with tone marks, Taiwan's standard method. */
    ZHUYIN,

    /** Chinese: 倉頡 Cangjie radical decomposition, typed as the letters a-y. */
    CANGJIE,

    /** Chinese: 速成 Quick — a character's first and last Cangjie radical only. */
    CANGJIE_QUICK,

    /** Cantonese: 粵拼 Jyutping romanisation with optional tone digits 1-6. */
    JYUTPING,
}

/**
 * Which font family a script wants, so [com.wasimaster.wmkeyboard.ime.ui.KbTheme]
 * and the downloadable-font fallback can pick per script rather than the old
 * Latin-or-Bengali binary. [GENERIC] takes the system default.
 */
enum class FontHint { LATIN, BENGALI, DEVANAGARI, ARABIC, HEBREW, TAMIL, THAI, HANGUL, GENERIC }

/**
 * The behaviour of one [ScriptId]. Everything script-shaped the runtime needs is
 * an attribute here, so adding a language is a [LanguageDef] plus (if its script
 * is new) one row in [ScriptRegistry].
 *
 * [hasLetterCase] gates auto-capitalisation and shift-uppercasing — the thing the
 * old `isLatinScript`/`isFixedBengali` booleans conflated with "is Bengali".
 * [unicodeRange] is the script's main block, used to test whether a character
 * belongs to the script and to bound grapheme-cluster deletion.
 */
data class ScriptDef(
    val id: ScriptId,
    val direction: TextDirection = TextDirection.LTR,
    val hasLetterCase: Boolean = false,
    val composer: ComposerType = ComposerType.NONE,
    val fontHint: FontHint = FontHint.GENERIC,
    val unicodeRange: IntRange = IntRange.EMPTY,
    /**
     * The mark this script ends a sentence with, for the key next to the
     * spacebar. Bengali writes দাঁড়ি (।), not a full stop, and a Bengali
     * keyboard that types "." is the single most-noticed way of being not
     * quite the keyboard people are used to. The ASCII "." moves to the key's
     * long-press wherever this is not "." — it is still wanted for numbers,
     * file names and URLs.
     */
    val fullStop: String = ".",
    /**
     * Marks this script keeps on the long press of a shared punctuation key,
     * keyed by the character that key types.
     *
     * The symbol layers are one grid for every language on the keyboard, so a
     * script's own punctuation has nowhere of its own to live. The visarga is
     * the case this exists for, Bengali's ঃ and Devanagari's ः alike: drawn as a
     * colon, doing a different job, and the colon key is where the typist who
     * wants one reaches. Appended to whatever the key already offers, so
     * nothing is displaced.
     */
    val punctuationAlternates: Map<String, List<String>> = emptyMap(),
)

/**
 * The scripts a shipped language uses. Seeded with the two the current five
 * languages need (Latin, Bengali); Phase 5 fills in the rest alongside the
 * ~60-language expansion. [get] never throws — an unregistered script falls back
 * to Latin so a language referencing a script this build lacks still renders.
 */
object ScriptRegistry {
    private val defs: Map<ScriptId, ScriptDef> = listOf(
        ScriptDef(
            id = ScriptId.LATIN,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.DEAD_KEY,
            fontHint = FontHint.LATIN,
            // Basic Latin letters through Latin Extended-B — covers the accented
            // letters the European layouts reach on long-press.
            unicodeRange = 0x0041..0x024F,
        ),
        ScriptDef(
            id = ScriptId.BENGALI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.BENGALI,
            unicodeRange = 0x0980..0x09FF,
            fullStop = "।",
            // বিসর্গ, on the colon it is drawn as. Avro types it from ":" as
            // well, but only while a word is composing, and the fixed Bengali
            // layouts reach it through shift or not at all.
            punctuationAlternates = mapOf(":" to listOf("ঃ")),
        ),
        ScriptDef(
            id = ScriptId.HANGUL,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.HANGUL,
            fontHint = FontHint.HANGUL,
            unicodeRange = 0xAC00..0xD7A3,
        ),
        ScriptDef(
            id = ScriptId.CYRILLIC,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.DEAD_KEY,
            fontHint = FontHint.LATIN,
            unicodeRange = 0x0400..0x04FF,
        ),
        ScriptDef(
            id = ScriptId.ARABIC,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.ARABIC,
            unicodeRange = 0x0600..0x06FF,
        ),
        ScriptDef(
            id = ScriptId.GREEK,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.DEAD_KEY,
            // Greek rides the Latin faces, which carry the Greek block — as
            // Cyrillic does — until the per-script font map lands.
            fontHint = FontHint.LATIN,
            unicodeRange = 0x0370..0x03FF,
        ),
        ScriptDef(
            id = ScriptId.HEBREW,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.HEBREW,
            unicodeRange = 0x0590..0x05FF,
        ),
        ScriptDef(
            id = ScriptId.SYRIAC,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0700..0x074F,
        ),
        ScriptDef(
            id = ScriptId.DEVANAGARI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.DEVANAGARI,
            unicodeRange = 0x0900..0x097F,
            // Hindi, Marathi, Nepali and the rest end a sentence with the danda
            // (।), the same way Bengali ends one with the dari. The danda was
            // reachable on the period key's long-press, which is the wrong way
            // round: it made the native mark the deliberate choice and the
            // foreign one the default, on fifteen layouts plus the built-in
            // Hindi grid. ASCII "." moves to the long-press, where it is still
            // one press away for numbers, file names and URLs.
            fullStop = "।",
            // Visarga, the same case as Bengali's: colon-shaped, a different
            // job, and no key of its own on a shared symbol grid.
            punctuationAlternates = mapOf(":" to listOf("ः")),
        ),
        ScriptDef(
            id = ScriptId.GEORGIAN,
            direction = TextDirection.LTR,
            // Mkhedruli is unicameral; extra letters ride shiftLabel, not case.
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10A0..0x10FF,
        ),
        // Armenian is bicameral (has upper/lower case), like Greek and Cyrillic,
        // but rides its own Unicode block; the system face supplies the glyphs.
        ScriptDef(
            id = ScriptId.ARMENIAN,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0530..0x058F,
            // Armenian ends a sentence with vertsaket (։), not a full stop.
            fullStop = "։",
        ),
        // Brahmic scripts: all uncased, all cluster-shaping (conjuncts joined by a
        // virama, vowel signs deleted with their base), so they share the generic
        // IndicClusterComposer keyed by their unicodeRange + virama (see viramaFor).
        ScriptDef(
            id = ScriptId.GURMUKHI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0A00..0x0A7F,
        ),
        ScriptDef(
            id = ScriptId.GUJARATI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0A80..0x0AFF,
        ),
        ScriptDef(
            id = ScriptId.ORIYA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0B00..0x0B7F,
        ),
        ScriptDef(
            id = ScriptId.TAMIL,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.TAMIL,
            unicodeRange = 0x0B80..0x0BFF,
        ),
        ScriptDef(
            id = ScriptId.TELUGU,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0C00..0x0C7F,
        ),
        ScriptDef(
            id = ScriptId.KANNADA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0C80..0x0CFF,
        ),
        ScriptDef(
            id = ScriptId.MALAYALAM,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0D00..0x0D7F,
        ),
        ScriptDef(
            id = ScriptId.SINHALA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0D80..0x0DFF,
        ),
        // Thai and Lao are alphasyllabaries but stack no conjuncts (no virama), so
        // they compose 1:1 and delete one code unit at a time — ComposerType.NONE.
        ScriptDef(
            id = ScriptId.THAI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.THAI,
            unicodeRange = 0x0E00..0x0E7F,
        ),
        ScriptDef(
            id = ScriptId.LAO,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0E80..0x0EFF,
        ),
        // Khmer (coeng) and Myanmar (virama/asat) DO stack, so they use the cluster
        // composer with their respective viramas (see viramaFor).
        ScriptDef(
            id = ScriptId.KHMER,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1780..0x17FF,
            // Khmer ends a sentence with khan (។); the ASCII stop moves to long-press.
            fullStop = "។",
        ),
        ScriptDef(
            id = ScriptId.MYANMAR,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1000..0x109F,
            // Burmese ends a sentence with the section mark (။), not a full stop.
            fullStop = "။",
        ),
        // Ethiopic (Amharic, Tigrinya …) is an abugida written left-to-right and
        // uncased. Its ~34 base consonants each have seven vowel orders; the
        // layout puts the base order on the key and the other six on long-press,
        // so it composes 1:1 with no special composer (system Noto face).
        ScriptDef(
            id = ScriptId.ETHIOPIC,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1200..0x137F,
            // Ethiopic ends a sentence with arat netib (።).
            fullStop = "።",
        ),
        // Thaana (Dhivehi) is written right-to-left; consonants carry vowel
        // diacritics (fili) typed after them, so like Arabic it composes 1:1 and
        // the field renders RTL from the visual-order grid.
        ScriptDef(
            id = ScriptId.THAANA,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0780..0x07BF,
        ),
        // Japanese and Chinese: the script default is a plain 1:1 append, and the
        // layouts override it (romaji→kana for ja, pinyin for zh). Their fonts are
        // resolved per script id (Noto Sans JP / SC), not the fontHint.
        ScriptDef(
            id = ScriptId.JAPANESE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x3040..0x30FF,
            // Japanese ends a sentence with the ideographic full stop (。).
            fullStop = "。",
        ),
        ScriptDef(
            id = ScriptId.HAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x4E00..0x9FFF,
        ),
        // IPA is uncased (a shift key would be inert) and composes 1:1 — each key
        // commits its phonetic symbol directly, combining diacritics included. It
        // rides the Latin faces, which carry the IPA Extensions block and the
        // spacing/combining modifiers, so it needs no dedicated font. The declared
        // range is the IPA Extensions block; the layout also reaches Latin,
        // spacing-modifier and combining-diacritic characters outside it.
        ScriptDef(
            id = ScriptId.IPA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.LATIN,
            unicodeRange = 0x0250..0x02AF,
        ),
        ScriptDef(
            id = ScriptId.TIFINAGH,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x2D30..0x2D7F,
        ),
        ScriptDef(
            id = ScriptId.CHEROKEE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x13A0..0x13FF,
        ),
        ScriptDef(
            id = ScriptId.NKO,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x07C0..0x07FF,
        ),
        ScriptDef(
            id = ScriptId.CANADIAN_ABORIGINAL_SYLLABICS,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1400..0x167F,
        ),
        ScriptDef(
            id = ScriptId.TIBETAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0F00..0x0FFF,
            // Tibetan ends a clause with shad (།).
            fullStop = "།",
        ),
        // Ol Chiki (Santali) is a true alphabet, not an abugida — no virama or
        // conjunct stacking, so it composes 1:1 like Thai/Lao rather than using
        // the cluster composer.
        ScriptDef(
            id = ScriptId.OL_CHIKI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1C50..0x1C7F,
            // Ol Chiki ends a sentence with mucaad (᱾).
            fullStop = "᱾",
        ),
        // Meetei Mayek (Manipuri) is an abugida with a virama-like killer stroke
        // (Apun Iyek), so it rides the generic cluster composer like the other
        // Brahmic-family scripts. Its extension block (vowel signs, U+AAE0..AAF6)
        // falls outside unicodeRange's single contiguous span; that range is used
        // for cluster-deletion bounds checks and the common case (consonants,
        // U+ABC0..ABFF) is what matters there.
        ScriptDef(
            id = ScriptId.MEETEI_MAYEK,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.INDIC_CLUSTER,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xABC0..0xABFF,
            // Meetei Mayek ends a sentence with cheikhei (꯫).
            fullStop = "꯫",
        ),
        // Tai Le (Tai Nuea) is an abugida written left to right with the tone
        // marks as spacing characters after the syllable, so it composes 1:1
        // rather than clustering.
        ScriptDef(
            id = ScriptId.TAI_LE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1950..0x197F,
        ),
        // Vai is a syllabary — one glyph per consonant-vowel syllable, no
        // vowel signs and no clustering — so it composes 1:1 and is uncased.
        ScriptDef(
            id = ScriptId.VAI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA500..0xA63F,
        ),
        // Osage is one of the few cased non-Latin scripts: U+104B0..104D3 are
        // the capitals and U+104D8..104FB the smalls, so shift-uppercasing is
        // meaningful and `hasLetterCase` is true. It sits outside the BMP, so
        // its characters are surrogate pairs — see the note on [ScriptId.OSAGE].
        ScriptDef(
            id = ScriptId.OSAGE,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x104B0..0x104FB,
        ),
        // Adlam is right to left and cased (U+1E900..1E921 capitals,
        // U+1E922..1E943 smalls), a combination no other script here has. Also
        // outside the BMP. It has combining marks but no virama and no
        // conjuncts, so it composes 1:1 rather than clustering.
        ScriptDef(
            id = ScriptId.ADLAM,
            direction = TextDirection.RTL,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1E900..0x1E95F,
            // No fullStop override: Unicode gives Adlam an initial exclamation
            // and question mark (U+1E95E, U+1E95F) but no full stop of its own,
            // and Adlam text ends a sentence with the ASCII one.
        ),

        // --- Scripts reached only through the Keyman layout corpus. ---
        // Generated from Unicode 17.0; see the matching block in ScriptId.
        // All take ComposerType.NONE: the Keyman rule engine, not a composer,
        // is what shapes text for these, so a composer here would run twice.
        ScriptDef(
            id = ScriptId.AHOM,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11700..0x1171A,
        ),
        ScriptDef(
            id = ScriptId.AVESTAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10B00..0x10B35,
        ),
        ScriptDef(
            id = ScriptId.BALINESE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1B05..0x1B33,
        ),
        ScriptDef(
            id = ScriptId.BAMUM,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x16800..0x16A38,
        ),
        ScriptDef(
            id = ScriptId.BASSA_VAH,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x16AD0..0x16AED,
        ),
        ScriptDef(
            id = ScriptId.BATAK,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1BC0..0x1BE5,
        ),
        ScriptDef(
            id = ScriptId.BHAIKSUKI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11C0A..0x11C2E,
        ),
        ScriptDef(
            id = ScriptId.BOPOMOFO,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x3105..0x312F,
        ),
        ScriptDef(
            id = ScriptId.BRAHMI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11003..0x11037,
        ),
        ScriptDef(
            id = ScriptId.BUGINESE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1A00..0x1A16,
        ),
        ScriptDef(
            id = ScriptId.BUHID,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1740..0x1751,
        ),
        ScriptDef(
            id = ScriptId.CAUCASIAN_ALBANIAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10530..0x10563,
        ),
        ScriptDef(
            id = ScriptId.CHAKMA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11103..0x11126,
        ),
        ScriptDef(
            id = ScriptId.CHAM,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xAA00..0xAA28,
        ),
        ScriptDef(
            id = ScriptId.COPTIC,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x2C80..0x2CE4,
        ),
        ScriptDef(
            id = ScriptId.CYPRIOT,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1080A..0x10835,
        ),
        ScriptDef(
            id = ScriptId.CYPRO_MINOAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x12F90..0x12FF0,
        ),
        ScriptDef(
            id = ScriptId.DESERET,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10400..0x1044F,
        ),
        ScriptDef(
            id = ScriptId.DIVES_AKURU,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11918..0x1192F,
        ),
        ScriptDef(
            id = ScriptId.DOGRA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11800..0x1182B,
        ),
        ScriptDef(
            id = ScriptId.ELBASAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10500..0x10527,
        ),
        ScriptDef(
            id = ScriptId.GLAGOLITIC,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x2C00..0x2C5F,
        ),
        ScriptDef(
            id = ScriptId.GOTHIC,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10330..0x10340,
        ),
        ScriptDef(
            id = ScriptId.GRANTHA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11313..0x11328,
        ),
        ScriptDef(
            id = ScriptId.GUNJALA_GONDI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11D6A..0x11D89,
        ),
        ScriptDef(
            id = ScriptId.HANIFI_ROHINGYA,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10D00..0x10D23,
        ),
        ScriptDef(
            id = ScriptId.HANUNOO,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1720..0x1731,
        ),
        ScriptDef(
            id = ScriptId.HATRAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x108E0..0x108F2,
        ),
        ScriptDef(
            id = ScriptId.INSCRIPTIONAL_PAHLAVI,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10B60..0x10B72,
        ),
        ScriptDef(
            id = ScriptId.INSCRIPTIONAL_PARTHIAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10B40..0x10B55,
        ),
        ScriptDef(
            id = ScriptId.JAVANESE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA984..0xA9B2,
        ),
        ScriptDef(
            id = ScriptId.KAITHI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11083..0x110AF,
        ),
        ScriptDef(
            id = ScriptId.KAWI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11F12..0x11F33,
        ),
        ScriptDef(
            id = ScriptId.KAYAH_LI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA90A..0xA925,
        ),
        ScriptDef(
            id = ScriptId.KHAROSHTHI,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10A19..0x10A35,
        ),
        ScriptDef(
            id = ScriptId.KHOJKI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11213..0x1122B,
        ),
        ScriptDef(
            id = ScriptId.KHUDAWADI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x112B0..0x112DE,
        ),
        ScriptDef(
            id = ScriptId.KIRAT_RAI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x16D43..0x16D6A,
        ),
        ScriptDef(
            id = ScriptId.LEPCHA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1C00..0x1C23,
        ),
        ScriptDef(
            id = ScriptId.LIMBU,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1900..0x191E,
        ),
        ScriptDef(
            id = ScriptId.LINEAR_B,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10080..0x100FA,
        ),
        ScriptDef(
            id = ScriptId.LISU,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA4D0..0xA4F7,
        ),
        ScriptDef(
            id = ScriptId.LYCIAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10280..0x1029C,
        ),
        ScriptDef(
            id = ScriptId.LYDIAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10920..0x10939,
        ),
        ScriptDef(
            id = ScriptId.MAHAJANI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11150..0x11172,
        ),
        ScriptDef(
            id = ScriptId.MAKASAR,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11EE0..0x11EF2,
        ),
        ScriptDef(
            id = ScriptId.MANDAIC,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0840..0x0858,
        ),
        ScriptDef(
            id = ScriptId.MANICHAEAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10AC9..0x10AE4,
        ),
        ScriptDef(
            id = ScriptId.MARCHEN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11C72..0x11C8F,
        ),
        ScriptDef(
            id = ScriptId.MASARAM_GONDI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11D0B..0x11D30,
        ),
        ScriptDef(
            id = ScriptId.MEDEFAIDRIN,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x16E40..0x16E7F,
        ),
        ScriptDef(
            id = ScriptId.MENDE_KIKAKUI,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1E800..0x1E8C4,
        ),
        ScriptDef(
            id = ScriptId.MEROITIC_CURSIVE,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x109D2..0x109FF,
        ),
        ScriptDef(
            id = ScriptId.MEROITIC_HIEROGLYPHS,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10980..0x1099F,
        ),
        ScriptDef(
            id = ScriptId.MIAO,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x16F00..0x16F4A,
        ),
        ScriptDef(
            id = ScriptId.MODI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11600..0x1162F,
        ),
        ScriptDef(
            id = ScriptId.MONGOLIAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1844..0x1878,
        ),
        ScriptDef(
            id = ScriptId.MRO,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x16A40..0x16A5E,
        ),
        ScriptDef(
            id = ScriptId.MULTANI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1128F..0x1129D,
        ),
        ScriptDef(
            id = ScriptId.NABATAEAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10880..0x1089E,
        ),
        ScriptDef(
            id = ScriptId.NAG_MUNDARI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1E4D0..0x1E4EA,
        ),
        ScriptDef(
            id = ScriptId.NANDINAGARI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x119AA..0x119D0,
        ),
        ScriptDef(
            id = ScriptId.NEWA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11400..0x11434,
        ),
        ScriptDef(
            id = ScriptId.NEW_TAI_LUE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1980..0x19AB,
        ),
        ScriptDef(
            id = ScriptId.NYIAKENG_PUACHUE_HMONG,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1E100..0x1E12C,
        ),
        ScriptDef(
            id = ScriptId.OGHAM,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1681..0x169A,
        ),
        ScriptDef(
            id = ScriptId.OLD_HUNGARIAN,
            direction = TextDirection.RTL,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10C80..0x10CB2,
        ),
        ScriptDef(
            id = ScriptId.OLD_ITALIC,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10300..0x1031F,
        ),
        ScriptDef(
            id = ScriptId.OLD_PERMIC,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10350..0x10375,
        ),
        ScriptDef(
            id = ScriptId.OLD_PERSIAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x103A0..0x103C3,
        ),
        ScriptDef(
            id = ScriptId.OLD_SOGDIAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10F00..0x10F1C,
        ),
        ScriptDef(
            id = ScriptId.OLD_SOUTH_ARABIAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10A60..0x10A7C,
        ),
        ScriptDef(
            id = ScriptId.OLD_UYGHUR,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10F70..0x10F81,
        ),
        ScriptDef(
            id = ScriptId.OSMANYA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10480..0x1049D,
        ),
        ScriptDef(
            id = ScriptId.PAHAWH_HMONG,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x16B00..0x16B2F,
        ),
        ScriptDef(
            id = ScriptId.PALMYRENE,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10860..0x10876,
        ),
        ScriptDef(
            id = ScriptId.PAU_CIN_HAU,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11AC0..0x11AF8,
        ),
        ScriptDef(
            id = ScriptId.PHAGS_PA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA840..0xA873,
        ),
        ScriptDef(
            id = ScriptId.PHOENICIAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10900..0x10915,
        ),
        ScriptDef(
            id = ScriptId.PSALTER_PAHLAVI,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10B80..0x10B91,
        ),
        ScriptDef(
            id = ScriptId.REJANG,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA930..0xA946,
        ),
        ScriptDef(
            id = ScriptId.RUNIC,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x16A0..0x16EA,
        ),
        ScriptDef(
            id = ScriptId.SAMARITAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x0800..0x0815,
        ),
        ScriptDef(
            id = ScriptId.SAURASHTRA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA882..0xA8B3,
        ),
        ScriptDef(
            id = ScriptId.SHARADA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11183..0x111B2,
        ),
        ScriptDef(
            id = ScriptId.SHAVIAN,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10450..0x1047F,
        ),
        ScriptDef(
            id = ScriptId.SIDDHAM,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11580..0x115AE,
        ),
        ScriptDef(
            id = ScriptId.SOGDIAN,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10F30..0x10F45,
        ),
        ScriptDef(
            id = ScriptId.SORA_SOMPENG,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x110D0..0x110E8,
        ),
        ScriptDef(
            id = ScriptId.SOYOMBO,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11A5C..0x11A89,
        ),
        ScriptDef(
            id = ScriptId.SUNDANESE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1B83..0x1BA0,
        ),
        ScriptDef(
            id = ScriptId.SYLOTI_NAGRI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA80C..0xA822,
        ),
        ScriptDef(
            id = ScriptId.TAGALOG,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1700..0x1711,
        ),
        ScriptDef(
            id = ScriptId.TAGBANWA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1760..0x176C,
        ),
        ScriptDef(
            id = ScriptId.TAI_THAM,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1A20..0x1A54,
        ),
        ScriptDef(
            id = ScriptId.TAI_VIET,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xAA80..0xAAAF,
        ),
        ScriptDef(
            id = ScriptId.TAKRI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11680..0x116AA,
        ),
        ScriptDef(
            id = ScriptId.TIRHUTA,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11480..0x114AF,
        ),
        ScriptDef(
            id = ScriptId.TODHRI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x105C0..0x105F3,
        ),
        ScriptDef(
            id = ScriptId.TOTO,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1E290..0x1E2AD,
        ),
        ScriptDef(
            id = ScriptId.UGARITIC,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10380..0x1039D,
        ),
        ScriptDef(
            id = ScriptId.VITHKUQI,
            direction = TextDirection.LTR,
            hasLetterCase = true,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1057C..0x1058A,
        ),
        ScriptDef(
            id = ScriptId.YEZIDI,
            direction = TextDirection.RTL,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x10E80..0x10EA9,
        ),
        ScriptDef(
            id = ScriptId.YI,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0xA016..0xA48C,
        ),
        ScriptDef(
            id = ScriptId.ZANABAZAR_SQUARE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x11A0B..0x11A32,
        ),
        // Musical notation composes 1:1 — every key commits its symbol as-is,
        // like IPA. The declared range is the Musical Symbols block; the layout
        // also reaches the BMP note characters (U+2669..266F) outside it. The
        // script's real job is the font: KeyboardFonts maps it to Noto Music,
        // because device fonts rarely carry the SMP musical glyphs.
        ScriptDef(
            id = ScriptId.MUSIC,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x1D100..0x1D1FF,
        ),
        // Braille is chorded, not tapped: the dot keys feed the chord engine in
        // the service and the *decoded* Grade-1 text is what gets committed, so
        // no composer runs. Uncased — capitals come from the dot-6 indicator
        // cell, not a shift key. The range covers the Braille Patterns block
        // the keycaps (and unknown-chord fallback commits) draw from.
        ScriptDef(
            id = ScriptId.BRAILLE,
            direction = TextDirection.LTR,
            hasLetterCase = false,
            composer = ComposerType.NONE,
            fontHint = FontHint.GENERIC,
            unicodeRange = 0x2800..0x28FF,
        ),
    ).associateBy { it.id }

    val all: List<ScriptDef> get() = defs.values.toList()

    /** Whether a [ScriptDef] is actually registered (not the Latin fallback). */
    fun isRegistered(id: ScriptId): Boolean = id in defs

    operator fun get(id: ScriptId): ScriptDef = defs[id] ?: defs.getValue(ScriptId.LATIN)
}
