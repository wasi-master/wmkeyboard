package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.ComposerType

/**
 * The layouts that ship with the keyboard.
 *
 * Their ids stay stable so stored references survive: editing one stores a
 * custom layout under the *same* id that shadows the shipped version (see
 * [findLayout]), so a 🌐 cycle pinned to "builtin_probhat" keeps working
 * and deleting the edit restores the original grid.
 *
 * Kotlin rather than JSON in assets, unlike the dictionaries: the letter grid
 * is needed synchronously on the first frame, so it cannot be parsed off the
 * main thread the way the word lists are, and a typo in a val fails the build
 * where a typo in an asset ships a blank keyboard. The KDoc explaining *why*
 * Probhat's tenth home-row key carries ে/ো is also the most valuable thing in
 * this file, and JSON has no comments.
 *
 * Only [QWERTY] defines every layer. The rest define [LayoutLayer.LETTERS] and
 * inherit the symbol layers and the five numeric keypads, so those grids exist
 * exactly once.
 */
object BuiltInLayouts {

    const val QWERTY_ID = "builtin_qwerty"
    const val AZERTY_ID = "builtin_azerty"
    const val DVORAK_ID = "builtin_dvorak"
    const val COLEMAK_ID = "builtin_colemak"
    const val WORKMAN_ID = "builtin_workman"
    const val HALMAK_ID = "builtin_halmak"
    const val AVRO_ID = "builtin_avro"
    const val BORNO_ID = "builtin_borno"
    const val KHIPRO_ID = "builtin_khipro"
    const val KHIPRO_DARK_ID = "builtin_khipro_dark"
    const val PROBHAT_ID = "builtin_probhat"
    const val JATIYA_ID = "builtin_jatiya"
    const val BSTI_ID = "builtin_bsti"
    const val FRENCH_ID = "builtin_french"
    const val GERMAN_ID = "builtin_qwertz"
    const val SPANISH_ID = "builtin_spanish"
    const val KOREAN_ID = "builtin_korean"
    const val RUSSIAN_ID = "builtin_russian"
    const val ARABIC_ID = "builtin_arabic"
    const val GREEK_ID = "builtin_greek"
    const val HEBREW_ID = "builtin_hebrew"
    const val HINDI_ID = "builtin_hindi"
    const val HINDI_PHONETIC_ID = "builtin_hindi_phonetic"
    const val URDU_PHONETIC_ID = "builtin_urdu_phonetic"
    const val MARATHI_PHONETIC_ID = "builtin_marathi_phonetic"
    const val GUJARATI_PHONETIC_ID = "builtin_gujarati_phonetic"
    const val PUNJABI_PHONETIC_ID = "builtin_punjabi_phonetic"
    const val ODIA_PHONETIC_ID = "builtin_odia_phonetic"
    const val ASSAMESE_PHONETIC_ID = "builtin_assamese_phonetic"
    const val TAMIL_PHONETIC_ID = "builtin_tamil_phonetic"
    const val TELUGU_PHONETIC_ID = "builtin_telugu_phonetic"
    const val KANNADA_PHONETIC_ID = "builtin_kannada_phonetic"
    const val MALAYALAM_PHONETIC_ID = "builtin_malayalam_phonetic"
    const val PERSIAN_PHONETIC_ID = "builtin_persian_phonetic"
    const val ARABIC_PHONETIC_ID = "builtin_arabic_phonetic"
    const val T9_ID = "builtin_t9"
    const val COMPACT_ID = "builtin_compact"

    const val DEFAULT_ID = QWERTY_ID

    /**
     * The shared layers every built-in falls back to: the two symbol grids and
     * the five field-driven keypads. Held on [QWERTY] because it is the default
     * layout, and [LayoutSpec.compile] resolves a missing layer against it.
     */
    private val sharedLayers: Map<String, LayerSpec> = mapOf(
        LayoutLayer.SYMBOLS.key to LayerSpec(symbolRows),
        LayoutLayer.SYMBOLS_SHIFTED.key to LayerSpec(symbolsShiftedRows),
        LayoutLayer.NUMBER.key to LayerSpec(numberRows),
        LayoutLayer.PHONE.key to LayerSpec(phoneRows),
        LayoutLayer.DATE.key to LayerSpec(dateRows),
        LayoutLayer.TIME.key to LayerSpec(timeRows),
        LayoutLayer.DATETIME.key to LayerSpec(dateTimeRows),
    )

    val QWERTY = LayoutSpec(
        id = QWERTY_ID,
        name = "QWERTY",
        langId = "en",
        layers = sharedLayers + (LayoutLayer.LETTERS.key to LayerSpec(qwertyRows)),
    )

    /**
     * AZERTY: the French/Belgian Latin layout. Letter positions follow the
     * standard AZERTY keymap (A/Q and Z/W swapped, M on the home row); the
     * apostrophe gets its own key where QWERTY's M sits. Long-press keeps the
     * Samsung-style symbol-first alternates, with French accents (é è à …) on
     * their base vowels, except that ç leads its c outright.
     */
    val AZERTY = LayoutSpec(
        id = AZERTY_ID,
        name = "AZERTY",
        langId = "en",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(azertyRows)),
    )

    /**
     * Dvorak: the simplified layout with vowels on the left home row. Follows
     * the standard Dvorak keymap adapted to the 10-column phone grid: ' , .
     * start the top row (with " < > on shift, ANSI style) and the desktop ; key's
     * spot is dropped — ; lives on long-press instead.
     */
    val DVORAK = LayoutSpec(
        id = DVORAK_ID,
        name = "Dvorak",
        langId = "en",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(dvorakRows)),
    )

    /**
     * Colemak: the modern ergonomic layout that keeps Z X C V in place. Follows
     * the standard Colemak keymap adapted to the 10-column phone grid — the top
     * row drops the desktop ; key (all 26 letters still fit as 9 / 10 / 7), with
     * Samsung-style symbol-first alternates and accents on the base vowels.
     */
    val COLEMAK = LayoutSpec(
        id = COLEMAK_ID,
        name = "Colemak",
        langId = "en",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(colemakRows)),
    )

    /**
     * Workman: the layout tuned to English digraph frequency. Follows the
     * standard Workman keymap adapted to the 10-column phone grid (9 / 10 / 7,
     * the desktop ; key dropped from the top row), same symbol-first alternates.
     */
    val WORKMAN = LayoutSpec(
        id = WORKMAN_ID,
        name = "Workman",
        langId = "en",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(workmanRows)),
    )

    /**
     * Halmak: the AI-designed layout tuned to English typing efficiency and hand
     * alternation. Follows the standard Halmak keymap adapted to the 10-column
     * phone grid (9 / 10 / 7, with desktop punctuation dropped to long-press),
     * same symbol-first alternates and vowel accents.
     */
    val HALMAK = LayoutSpec(
        id = HALMAK_ID,
        name = "Halmak",
        langId = "en",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(halmakRows)),
    )

    /** Avro types Bengali phonetically, so it wears the QWERTY grid unchanged. */
    val AVRO = LayoutSpec(
        id = AVRO_ID,
        name = "Avro phonetic",
        langId = "bn",
        composer = ComposerType.TRANSLITERATE,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(qwertyRows)),
    )

    /**
     * Borno Phonetic wears QWERTY too, with the signs its map puts on long
     * presses in front of each key's own: ৎ on t, ঃ on l, ঁ on a, the hasant on g.
     */
    val BORNO = LayoutSpec(
        id = BORNO_ID,
        name = "Borno phonetic",
        langId = "bn",
        composer = ComposerType.BORNO_PHONETIC,
        layers = mapOf(
            LayoutLayer.LETTERS.key to LayerSpec(
                qwertyRows.map { row ->
                    row.map { key ->
                        val sign = bornoLongPress[key.label]
                        if (sign == null) key else key.copy(longPress = listOf(sign) + key.longPress)
                    }
                },
            ),
        ),
    )

    /**
     * Khipro (ক্ষিপ্র), Bengali composed from lowercase keys (issue #400).
     * Named Khipro Classic, as the Khipro team has called it since Khipro
     * Dark came out (#892).
     *
     * The grid follows the Khipro team's own guideline for touchscreens, which
     * HeliBoard and Borno also follow: Khipro never needs shift, so the shift
     * key becomes `/`, the slicer, which Khipro types constantly (ৎ, ঁ, and
     * breaking a conjunct back apart). `;`, the separator, gets a key beside
     * the spacebar. The full stop key stays, and is the danda as on every
     * Bengali grid.
     *
     * No tablet expansion: that grid is built around a shift key this one does
     * not have.
     */
    val KHIPRO = LayoutSpec(
        id = KHIPRO_ID,
        name = "Khipro Classic",
        langId = "bn",
        composer = ComposerType.KHIPRO,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(khiproRows)),
        tabletExpand = false,
    )

    /**
     * Khipro Dark (issue #631): the same keys as [KHIPRO], read by Khipro's
     * newer edition, which leaves consonants apart until `/` joins them
     * (`lagbe` is লাগবে, `ntrz///` is ন্ত্র্য). Its own layout rather than a
     * switch on Khipro's, so the globe key flips between the two.
     */
    val KHIPRO_DARK = LayoutSpec(
        id = KHIPRO_DARK_ID,
        name = "Khipro Dark",
        langId = "bn",
        composer = ComposerType.KHIPRO_DARK,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(khiproRows)),
        tabletExpand = false,
    )

    /**
     * Probhat: the fixed Bengali layout popular on Linux, following the standard
     * keymap (as shipped by Avro/OpenBangla). Letter keys q–m match the desktop
     * layout exactly; characters that live on desktop punctuation keys are
     * adapted for the 10-column phone grid:
     *  - the 10th home-row key carries ে/ো (desktop `[`/`]`) with ৈ/ৌ on
     *    long-press (desktop Shift+`[`/Shift+`]`);
     *  - ৎ ঞ ঁ ৃ ৗ ঽ ় ॥ move to long-press on their phonetic neighbours.
     */
    val PROBHAT = LayoutSpec(
        id = PROBHAT_ID,
        name = "প্রভাত (Probhat)",
        langId = "bn",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(DigitHints.fill(probhatRows))),
    )

    /**
     * National (Jatiya): the Bangladesh national standard fixed layout,
     * following the standard keymap (as shipped by Avro/OpenBangla). Letter keys
     * match the desktop layout; independent vowels sit on desktop AltGr and are
     * adapted here as long-press on their kar keys (ই on ি, উ on ু, …), matching
     * how fixed-layout phone keyboards ship Jatiya.
     */
    val JATIYA = LayoutSpec(
        id = JATIYA_ID,
        name = "জাতীয় (National)",
        langId = "bn",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(DigitHints.fill(jatiyaRows))),
    )

    /**
     * BSTI: the revised national standard, BDS 1738:2018, in the touch
     * arrangement of Ekushey's Bangla BSTI Mobile (CC BY 4.0). Close to
     * [JATIYA] but its own grid: া and ব trade places on the home row, shift
     * there gives the reph (র্), অ and ঁ, and the bottom row opens with র-ফলা
     * (্য on shift). Typing is in logical Unicode order, consonant then kar,
     * as both Ekushey layouts are.
     *
     * The standard has one layer of holds where a phone key has one popup, so
     * each key's popup carries the base layer's hold first and then the shift
     * layer's: the independent vowels on their kars, and the conjuncts
     * (ঙ্ক, ক্ষ, জ্ঞ …) the mobile layout puts on its shift keys. The desktop
     * hasant dead key (্ then a kar gives the vowel) is not copied: those
     * vowels are on the holds, and a hasant here stays a hasant.
     */
    val BSTI = LayoutSpec(
        id = BSTI_ID,
        name = "বিএসটিআই (BSTI)",
        langId = "bn",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(DigitHints.fill(bstiRows))),
    )

    /**
     * French shares AZERTY's key grid; the modes differ in language and in the
     * currency the symbols page leads with.
     */
    val FRENCH = LayoutSpec(
        id = FRENCH_ID,
        name = "AZERTY (French)",
        langId = "fr",
        layers = mapOf(
            LayoutLayer.LETTERS.key to LayerSpec(azertyRows),
            LayoutLayer.SYMBOLS.key to LayerSpec(frenchSymbolRows),
        ),
    )

    /**
     * QWERTZ: the German Latin layout — QWERTY with Y and Z swapped. The umlauts
     * and ß don't get dedicated keys on the 10-column phone grid (desktop QWERTZ
     * puts them on ö/ä/ü/ß keys that don't exist here); they lead the long-press
     * alternates on their base letters instead, Samsung-style behind the symbol
     * hint.
     */
    val GERMAN = LayoutSpec(
        id = GERMAN_ID,
        name = "QWERTZ",
        langId = "de",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(qwertzRows)),
    )

    /**
     * Spanish: QWERTY with the Ñ key appended to the home row, as on Spanish
     * desktop keyboards and every Spanish phone layout. Acute accents (á é í ó ú)
     * and ü lead their vowels' long-press alternates; inverted punctuation (¿ ¡)
     * rides on the ? and ! alternates.
     */
    val SPANISH = LayoutSpec(
        id = SPANISH_ID,
        name = "QWERTY + Ñ",
        langId = "es",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(spanishRows)),
    )

    /**
     * Korean (2-set / 두벌식): the standard phone layout. Keys emit compatibility
     * jamo that [com.wasimaster.wmkeyboard.core.input.composer.HangulComposer]
     * assembles into syllable blocks; shift gives the tense consonants (ㅃ ㅉ …)
     * and ㅒ/ㅖ.
     */
    val KOREAN = LayoutSpec(
        id = KOREAN_ID,
        name = "한국어 (2-beolsik)",
        langId = "ko",
        composer = ComposerType.HANGUL,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(DigitHints.fill(koreanRows))),
    )

    /** Russian ЙЦУКЕН: the standard Cyrillic phone layout; shift uppercases. */
    val RUSSIAN = LayoutSpec(
        id = RUSSIAN_ID,
        name = "ЙЦУКЕН",
        langId = "ru",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(russianRows)),
    )

    /**
     * Arabic: the standard letter arrangement, keys authored in visual (left to
     * right) order — the committed text is logical and the field renders it
     * right to left, so the grid itself needs no mirroring.
     */
    val ARABIC = LayoutSpec(
        id = ARABIC_ID,
        name = "العربية",
        langId = "ar",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(DigitHints.fill(arabicRows))),
    )

    /** Greek: the national layout; shift uppercases, tonos vowels on long-press. */
    val GREEK = LayoutSpec(
        id = GREEK_ID,
        name = "Ελληνικά",
        langId = "el",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(DigitHints.fill(greekRows))),
    )

    /**
     * Hebrew: the standard letter arrangement, keys authored in visual (left to
     * right) order like Arabic — the committed text is logical and the field
     * renders it right to left, so the grid itself needs no mirroring.
     */
    val HEBREW = LayoutSpec(
        id = HEBREW_ID,
        name = "עברית",
        langId = "he",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(DigitHints.fill(hebrewRows))),
    )

    /**
     * Hindi (Devanagari), the standard InScript keymap. Inherits the script's
     * [ComposerType.INDIC_CLUSTER] (no per-layout override needed), so backspace
     * removes a whole consonant-sign cluster.
     */
    val HINDI = LayoutSpec(
        id = HINDI_ID,
        name = "हिन्दी",
        langId = "hi",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(DigitHints.fill(hindiRows))),
    )

    /**
     * Hindi typed the way it is written in a chat — "kaise ho" → कैसे हो — on
     * the QWERTY grid unchanged, as Avro is for Bengali. The composer override
     * is what makes it phonetic: the script's own default is the cluster
     * shaping [HINDI] uses.
     */
    val HINDI_PHONETIC = LayoutSpec(
        id = HINDI_PHONETIC_ID,
        name = "Hindi phonetic",
        langId = "hi",
        composer = ComposerType.TRANSLITERATE,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(qwertyRows)),
    )

    /**
     * Urdu typed the way it is written in a chat — "aap kaise hain" → آپ کیسے
     * ہیں — on the QWERTY grid unchanged, as Avro is for Bengali and
     * [HINDI_PHONETIC] for Hindi (issue #496). The composer override is what
     * makes it phonetic; the Arabic script's own default types its letters
     * directly, which is what Urdu's other layouts do.
     *
     * The keys stay left-to-right while the text they write runs right to left,
     * which is how every roman-key method for an RTL script works.
     */
    val URDU_PHONETIC = LayoutSpec(
        id = URDU_PHONETIC_ID,
        name = "Urdu phonetic",
        langId = "ur",
        composer = ComposerType.TRANSLITERATE,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(qwertyRows)),
    )

    /**
     * The rest of the phonetic layouts: roman letters on the QWERTY grid
     * unchanged, the language's own script written, as [URDU_PHONETIC] and
     * [HINDI_PHONETIC] do. The composer override is what makes each phonetic;
     * which rules it reads is the layout's language (`PhoneticComposers`), since
     * several of these share a script with another language's phonetic layout —
     * Marathi with Hindi, Assamese with Bangla, Persian and Arabic with Urdu.
     */
    private fun phonetic(id: String, name: String, langId: String) = LayoutSpec(
        id = id,
        name = name,
        langId = langId,
        composer = ComposerType.TRANSLITERATE,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(qwertyRows)),
    )

    val MARATHI_PHONETIC = phonetic(MARATHI_PHONETIC_ID, "Marathi phonetic", "mr")
    val GUJARATI_PHONETIC = phonetic(GUJARATI_PHONETIC_ID, "Gujarati phonetic", "gu")
    val PUNJABI_PHONETIC = phonetic(PUNJABI_PHONETIC_ID, "Punjabi phonetic", "pa")
    val ODIA_PHONETIC = phonetic(ODIA_PHONETIC_ID, "Odia phonetic", "or")
    val ASSAMESE_PHONETIC = phonetic(ASSAMESE_PHONETIC_ID, "Assamese phonetic", "as")
    val TAMIL_PHONETIC = phonetic(TAMIL_PHONETIC_ID, "Tamil phonetic", "ta")
    val TELUGU_PHONETIC = phonetic(TELUGU_PHONETIC_ID, "Telugu phonetic", "te")
    val KANNADA_PHONETIC = phonetic(KANNADA_PHONETIC_ID, "Kannada phonetic", "kn")
    val MALAYALAM_PHONETIC = phonetic(MALAYALAM_PHONETIC_ID, "Malayalam phonetic", "ml")
    val PERSIAN_PHONETIC = phonetic(PERSIAN_PHONETIC_ID, "Persian phonetic", "fa")
    val ARABIC_PHONETIC = phonetic(ARABIC_PHONETIC_ID, "Arabic phonetic", "ar")

    /**
     * T9: the phone keypad, three or four letters to a key, decoded by the
     * language model rather than by multi-tap (discussion #103).
     *
     * The reason to want it is key size — eight letter keys across a board that
     * usually carries twenty-six — and the reason it has not been possible on
     * an AOSP-derived keyboard is that those decoders are 1:1: one keystroke,
     * one letter. Ours is not, since [Key.letters]; what a tap commits is the
     * key's anchor letter and what the word means is decided at the space.
     *
     * The grid is the keypad as phones drew it, digit hints and all: `1` is the
     * punctuation key it always was, `2`–`9` carry the alphabet in the ITU
     * E.161 grouping every phone since 1994 has used, and each key's digit is
     * its first long-press alternate — so the corner hint reads like a keypad
     * and holding a key types the digit. The letters follow it in the popup,
     * which is how a name, a password or any word the dictionary has never seen
     * gets spelled out one letter at a time: a letter picked from the popup is
     * committed as itself and decodes as itself.
     *
     * Delete, shift and enter take a fourth column rather than a bottom row of
     * their own, which is what keeps the letter keys at a quarter of the width
     * of the board and the whole grid at four rows.
     *
     * Symbols are the shipped ten-column page, not a keypad-shaped one of its
     * own. Deliberately: `?123` is where a user goes for a character that has
     * to be exactly right, and that page being the same page on every layout is
     * worth more than the grid keeping its width across a layer change.
     */
    val T9 = LayoutSpec(
        id = T9_ID,
        name = "T9",
        langId = "en",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(t9Rows)),
        // Nothing about a four-column keypad wants Tab, caps lock and a
        // mirrored shift bolted around it on a tablet.
        tabletExpand = false,
    )

    /**
     * Compact QWERTY: the same letters in the same order, two to a key, five
     * keys to a row (discussion #103). Smart Keyboard Pro shipped this and
     * little else has.
     *
     * QWERTY's own row breaks are kept — `qwertyuiop` / `asdfghjkl` / `zxcvbnm`
     * — so a finger that knows where a letter lives still finds it; only the
     * pairing is new. `l` and `m` end their rows alone, because nine and seven
     * are odd numbers.
     *
     * Shift and delete are half-width, and on this grid that is not cramped: a
     * column here is two QWERTY columns, so half of one is exactly a normal
     * key. The bottom row is QWERTY's, every width halved, which puts the
     * spacebar, enter and `?123` at the same fraction of the board they occupy
     * on every other layout.
     */
    val COMPACT = LayoutSpec(
        id = COMPACT_ID,
        name = "Compact QWERTY",
        langId = "en",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(compactRows)),
        tabletExpand = false,
    )

    /**
     * Every compiled-in layout, in shipped order — even where two share a grid
     * (AZERTY and French are the same keys under different languages). This is
     * the boot-critical set drawn on the first frame; the JSON [AssetLayouts]
     * add the language tail at runtime, and both feed `findLayout`.
     */
    val all: List<LayoutSpec> = listOf(
        QWERTY, AZERTY, DVORAK, COLEMAK, WORKMAN, HALMAK, AVRO, BORNO, KHIPRO, KHIPRO_DARK, PROBHAT, JATIYA, BSTI,
        FRENCH, GERMAN, SPANISH, KOREAN, RUSSIAN, ARABIC, GREEK, HEBREW, HINDI, HINDI_PHONETIC,
        URDU_PHONETIC, MARATHI_PHONETIC, GUJARATI_PHONETIC, PUNJABI_PHONETIC, ODIA_PHONETIC,
        ASSAMESE_PHONETIC, TAMIL_PHONETIC, TELUGU_PHONETIC, KANNADA_PHONETIC, MALAYALAM_PHONETIC,
        PERSIAN_PHONETIC, ARABIC_PHONETIC, T9, COMPACT,
    )

    fun byId(id: String): LayoutSpec? = all.firstOrNull { it.id == id }

    val default: LayoutSpec get() = byId(DEFAULT_ID) ?: error("Built-in layout $DEFAULT_ID is missing")

    /**
     * A starting Fn layer, for a layout that wants one.
     *
     * Not attached to any built-in — nothing ships with an Fn key — but offered
     * by the editor as a template, and it doubles as the proof that
     * [KeyAction.SendKey] works end to end. Deliberately four rows, so enabling
     * it never inflates the row budget the panels are sized against.
     */
    val FN_DEFAULT: LayerSpec = LayerSpec(
        rows = listOf(
            listOf(
                fnKey("Esc", 111), fnKey("F1", 131), fnKey("F2", 132), fnKey("F3", 133),
                fnKey("F4", 134), fnKey("F5", 135), fnKey("F6", 136),
            ),
            listOf(
                fnKey("Tab", 61), fnKey("F7", 137), fnKey("F8", 138), fnKey("F9", 139),
                fnKey("F10", 140), fnKey("F11", 141), fnKey("F12", 142),
            ),
            listOf(
                fnKey("Home", 122), fnKey("↑", 19), fnKey("End", 123),
                fnKey("PgUp", 92), fnKey("Ins", 124), fnKey("Del", 112),
                Key("⌫", action = KeyAction.Delete),
            ),
            listOf(
                fnKey("←", 21), fnKey("↓", 20), fnKey("→", 22),
                fnKey("PgDn", 93),
                Key("ABC", action = KeyAction.Letters, width = 1.5f),
                Key(" ", action = KeyAction.Space, width = 1.5f),
                Key("⏎", action = KeyAction.Enter),
            ),
        ),
    )

    /**
     * Takes the place of the `?123` layer's own digit row when the number row
     * is on and already supplies those digits one row above. Carries the
     * symbols that layer has nowhere else to put. A layout overrides it with
     * [LayerSpec.fillRow].
     */
    val SYMBOLS_FILL_ROW: List<Key> = listOf("=", "\\", "<", ">", "[", "]", "{", "}", "|", "~")
        .map { Key(it) }

    /**
     * The number row while the symbols-2 (`=\<`) layer is showing. The digits
     * are one tap away on the symbols-1 layer, so this slot carries an extra
     * set of arrow and comparison symbols the symbol layers have no room for
     * rather than a second copy of the numbers.
     */
    val SYMBOLS_2_NUMBER_ROW: List<Key> = listOf(
        Key("←", longPress = listOf("⟵", "↔")),
        Key("→", longPress = listOf("⟶", "↦")),
        Key("↑", longPress = listOf("↕")),
        Key("↓"),
        Key("±", longPress = listOf("∓")),
        Key("∞"),
        Key("≈", longPress = listOf("≅", "≡")),
        Key("≠"),
        Key("≤", longPress = listOf("≪")),
        Key("≥", longPress = listOf("≫")),
    )

    /**
     * The number row [layer] shows when the layout has not authored one
     * ([LayerSpec.numberRow]). The digits are borrowed from the symbol layer
     * so they carry its fraction and superscript long-presses.
     */
    fun defaultNumberRow(layer: LayoutLayer): List<Key> = when (layer) {
        LayoutLayer.SYMBOLS_SHIFTED -> SYMBOLS_2_NUMBER_ROW
        else -> default.compile(LayoutLayer.SYMBOLS).rows.first()
    }

    /**
     * What a fresh install starts with before onboarding or the language screen
     * has said otherwise: one layout per language, English and Bengali.
     *
     * It used to be four — Probhat and Jatiya alongside Avro — because this list
     * was inherited from the old `InputMode` enum, where a "mode" was a layout
     * and every Bengali layout was therefore its own entry. That reads as three
     * Bengali keyboards to switch between when the user only ever wanted one:
     * whoever types Probhat picks it from the language screen, and Avro is what
     * the rest of Bangladesh types on.
     */
    val defaultEnabledIds: List<String> = listOf(QWERTY_ID, AVRO_ID)
}

// ---------------------------------------------------------------------------
// The grids themselves, as private top-level vals so the object above reads as
// a catalog rather than a wall of keys. Their KDoc lives on the LayoutSpec that
// exposes each one.
// ---------------------------------------------------------------------------

/**
 * The row every layout ends with: mode, comma, globe, space, full stop, enter.
 *
 * [scale] multiplies every width, for a grid narrower than the usual ten
 * columns — a five-column compact board takes 0.5 and gets a bottom row that
 * occupies exactly the fractions of the board this one does at full size, which
 * is what keeps the spacebar and the enter key where the hand expects them
 * whatever the grid above is doing.
 */
private fun bottomRow(
    symbols: Boolean = false,
    scale: Float = 1f,
    /** Appended to the full stop's hold, after the shared punctuation. */
    periodExtras: List<String> = emptyList(),
) = listOf(
    Key(
        if (symbols) "ABC" else "?123",
        action = if (symbols) KeyAction.Letters else KeyAction.Symbols,
        width = 1.5f * scale,
    ),
    Key(",", role = KeyRole.Comma, longPress = listOf("!", "?"), width = scale),
    Key("🌐", action = KeyAction.LanguageSwitch, width = scale),
    Key(" ", action = KeyAction.Space, width = 4f * scale),
    Key(
        ".",
        role = KeyRole.Period,
        longPress = listOf("…", ",", "?", "!", ":", ";", "।") + periodExtras,
        width = scale,
    ),
    Key("⏎", action = KeyAction.Enter, width = 1.5f * scale),
)

/**
 * One key of an ambiguous grid: [group] is every letter it stands for, and the
 * first of them is the anchor it actually commits.
 *
 * The label is the group in capitals, which is both how every keypad and
 * compact board has ever drawn it and the only honest thing to draw — the key
 * is a set, not a character, so there is no case for shift to track. (The
 * renderer leaves a multi-character label alone for exactly that reason.)
 *
 * [hint] rides at the front of the long-press popup, so the corner shows a
 * keypad's digit where there is one; the letters follow it, and picking one
 * spells it outright — the escape hatch for every word the dictionary does not
 * know. [longPress] takes anything the key should carry after those.
 */
private fun ambiguousKey(
    group: String,
    hint: String? = null,
    longPress: List<String> = emptyList(),
) = Key(
    label = group.uppercase(),
    output = group.take(1),
    letters = group,
    longPress = listOfNotNull(hint) + group.map { it.toString() } + longPress,
)

/** Borno Phonetic's long-press signs, by the letter key that holds each. */
private val bornoLongPress = mapOf("t" to "ৎ", "l" to "ঃ", "a" to "ঁ", "g" to "্")

private val qwertyRows = listOf(
    listOf(
        Key("q", longPress = listOf("1")), Key("w", longPress = listOf("2")),
        Key("e", longPress = listOf("3", "è", "é", "ê", "ë")), Key("r", longPress = listOf("4")),
        Key("t", longPress = listOf("5")), Key("y", longPress = listOf("6")),
        Key("u", longPress = listOf("7", "ù", "ú", "û", "ü")),
        Key("i", longPress = listOf("8", "ì", "í", "î", "ï")),
        Key("o", longPress = listOf("9", "ò", "ó", "ô", "ö")),
        Key("p", longPress = listOf("0")),
    ),
    // Every letter carries a symbol as its first alternate (the long-press
    // hint), Samsung-style; accents follow the symbol.
    listOf(
        Key("a", longPress = listOf("@", "à", "á", "â", "ä", "å")),
        Key("s", longPress = listOf("#", "ß", "ś")),
        Key("d", longPress = listOf("$")), Key("f", longPress = listOf("_")),
        Key("g", longPress = listOf("&")), Key("h", longPress = listOf("-")),
        Key("j", longPress = listOf("+")), Key("k", longPress = listOf("(")),
        Key("l", longPress = listOf(")")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("z", longPress = listOf("*", "ż", "ź")), Key("x", longPress = listOf("\"")),
        Key("c", longPress = listOf("'", "ç", "ć")), Key("v", longPress = listOf(":")),
        Key("b", longPress = listOf(";")), Key("n", longPress = listOf("!", "ñ", "ń")),
        Key("m", longPress = listOf("?")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

/** The digit or symbol that leads each QWERTY letter's hold, keyed by the letter. */
private val qwertyLeadingHolds: Map<String, String> = qwertyRows.flatten()
    .filter { it.action == KeyAction.Text && it.label.length == 1 && it.label[0].isLetter() && it.longPress.isNotEmpty() }
    .associate { it.label to it.longPress.first() }

/**
 * This grid with QWERTY's digits and symbols leading its letters' holds, when
 * it is QWERTY-shaped (its top row opens q w e r t y) and holds no digit on any
 * key. Hundreds of the shipped Latin layouts came over with their accents and
 * nothing else, so their corners showed no hint and a hold on q gave no 1,
 * where QWERTY's own keys do (Indonesian, reported in the Telegram group). A
 * key's own alternates follow the borrowed one, and the keys in [keep] (a
 * popup the user wrote) are left as they are.
 */
fun KeyboardLayout.withQwertyHolds(keep: Set<Key> = emptySet()): KeyboardLayout {
    val top = rows.firstOrNull()?.filter { it.action == KeyAction.Text }?.map { it.output ?: it.label }
        ?: return this
    if (top.take(QWERTY_SHAPE.size) != QWERTY_SHAPE) return this
    val hasDigitHolds = rows.any { row -> row.any { key -> key.longPress.any { it.length == 1 && it[0].isDigit() } } }
    if (hasDigitHolds) return this
    return copy(
        rows = rows.map { row ->
            row.map { key ->
                val lead = qwertyLeadingHolds[key.output ?: key.label]
                if (key.action != KeyAction.Text || key in keep || lead == null || lead in key.longPress) {
                    key
                } else {
                    key.copy(longPress = listOf(lead) + key.longPress)
                }
            }
        },
    )
}

private val QWERTY_SHAPE = listOf("q", "w", "e", "r", "t", "y")

/**
 * QWERTY's letters with its digit and symbol alternates but none of its Latin
 * accents: Khipro reads every letter key as a Khipro key, so an é picked from
 * a popup would only land in the word as a stray Latin letter.
 */
private val khiproRows = listOf(
    listOf(
        Key("q", longPress = listOf("1")), Key("w", longPress = listOf("2")),
        Key("e", longPress = listOf("3")), Key("r", longPress = listOf("4")),
        Key("t", longPress = listOf("5")), Key("y", longPress = listOf("6")),
        Key("u", longPress = listOf("7")), Key("i", longPress = listOf("8")),
        Key("o", longPress = listOf("9")), Key("p", longPress = listOf("0")),
    ),
    listOf(
        Key("a", longPress = listOf("@")), Key("s", longPress = listOf("#")),
        Key("d", longPress = listOf("৳", "$")), Key("f", longPress = listOf("_")),
        Key("g", longPress = listOf("&")), Key("h", longPress = listOf("-")),
        Key("j", longPress = listOf("+")), Key("k", longPress = listOf("(")),
        Key("l", longPress = listOf(")")),
    ),
    listOf(
        // The slicer where shift would be; the blinder, Khipro's other
        // modifier, on its long-press.
        Key("/", longPress = listOf("\\", "|"), width = 1.5f),
        Key("z", longPress = listOf("*")), Key("x", longPress = listOf("\"")),
        Key("c", longPress = listOf("'")), Key("v", longPress = listOf(":")),
        Key("b", longPress = listOf(";")), Key("n", longPress = listOf("!")),
        Key("m", longPress = listOf("?")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    listOf(
        Key("?123", action = KeyAction.Symbols, width = 1.5f),
        Key(",", role = KeyRole.Comma, longPress = listOf("!", "?")),
        Key("🌐", action = KeyAction.LanguageSwitch),
        Key(" ", action = KeyAction.Space, width = 3f),
        // Not a full stop: no role, so the danda rewrite leaves it alone.
        Key(";", longPress = listOf(":", "?")),
        // The danda once the script's full stop is applied; ॥ is what
        // Khipro's desktop spec spells as `.f`. ZWJ and ZWNJ next, which the
        // desktop spec types from `` ` `` and the touch one has no key for
        // (issue #489).
        Key(
            ".",
            role = KeyRole.Period,
            longPress = listOf("॥", "\u200D", "\u200C", "…", ",", "?", "!", ":"),
        ),
        Key("⏎", action = KeyAction.Enter, width = 1.5f),
    ),
)

// The holds are Choukajohn's, sent in as a layout (wmkeyboard-community-addons#2):
// ç leads the c, € leads the d, and each symbol sits on one key rather than ?
// twice. ä, ć, ñ and the curly quotes, which that layout dropped, stay at the end
// of their keys for Belgian and borrowed words.
private val azertyRows = listOf(
    listOf(
        Key("a", longPress = listOf("1", "à", "â", "æ", "á", "~", "%", "‰", "ä")),
        Key("z", longPress = listOf("2", "\\", "`")),
        Key("e", longPress = listOf("3", "é", "è", "ê", "ë", "ē", "|")),
        Key("r", longPress = listOf("4", "=")),
        Key("t", longPress = listOf("5", "√", "[")),
        Key("y", longPress = listOf("6", "ÿ", "]", "π")),
        Key("u", longPress = listOf("7", "ù", "ú", "û", "ü", "<", "÷")),
        Key("i", longPress = listOf("8", "ì", "í", "î", "ï", ">", "×")),
        Key("o", longPress = listOf("9", "ô", "ö", "œ", "ò", "ó", "ø", "§")),
        Key("p", longPress = listOf("0")),
    ),
    listOf(
        Key("q", longPress = listOf("@", "£")), Key("s", longPress = listOf("#", "¥", "ß")),
        Key("d", longPress = listOf("€", "$")), Key("f", longPress = listOf("_", "%", "‰")),
        Key("g", longPress = listOf("&", "^")), Key("h", longPress = listOf("-", "°")),
        Key("j", longPress = listOf("+", "±", "=", "≈", "≠", "∞")),
        Key("k", longPress = listOf("(", "<", "{", "[")),
        Key("l", longPress = listOf(")", ">", "}", "]")), Key("m", longPress = listOf("/")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("w", longPress = listOf("*", "\\")), Key("x", longPress = listOf("\"", "©")),
        Key("c", longPress = listOf("ç", "ć")), Key("v", longPress = listOf(":", "™")),
        Key("b", longPress = listOf(";", "%", "℅")), Key("n", longPress = listOf("!", "[", "ñ")),
        Key("'", longPress = listOf("?", "]", "‘", "’", "„")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    listOf(
        Key("?123", action = KeyAction.Symbols, width = 1.5f),
        Key(",", role = KeyRole.Comma, actionAlternates = listOf(KeyAlternate(KeyAction.Emoji))),
        Key("🌐", action = KeyAction.LanguageSwitch),
        Key(" ", action = KeyAction.Space, width = 4f),
        Key(
            ".",
            role = KeyRole.Period,
            longPress = listOf(
                "@", "'", ":", "-", "\"", "+", "%", "&",
                "?", ",", "!", "#", ")", "(", "/", ";", "…",
            ),
            alternateColumns = 8,
        ),
        Key("⏎", action = KeyAction.Enter, width = 1.5f),
    ),
)

private val qwertzRows = listOf(
    listOf(
        Key("q", longPress = listOf("1")), Key("w", longPress = listOf("2")),
        Key("e", longPress = listOf("3", "é", "è", "ê", "ë")), Key("r", longPress = listOf("4")),
        Key("t", longPress = listOf("5")), Key("z", longPress = listOf("6", "ż", "ź")),
        Key("u", longPress = listOf("7", "ü", "ù", "ú", "û")),
        Key("i", longPress = listOf("8", "ì", "í", "î", "ï")),
        Key("o", longPress = listOf("9", "ö", "ò", "ó", "ô")),
        Key("p", longPress = listOf("0")),
    ),
    listOf(
        Key("a", longPress = listOf("@", "ä", "à", "á", "â")),
        Key("s", longPress = listOf("#", "ß", "ś")),
        Key("d", longPress = listOf("$")), Key("f", longPress = listOf("_")),
        Key("g", longPress = listOf("&")), Key("h", longPress = listOf("-")),
        Key("j", longPress = listOf("+")), Key("k", longPress = listOf("(")),
        Key("l", longPress = listOf(")")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("y", longPress = listOf("*", "ÿ")), Key("x", longPress = listOf("\"")),
        Key("c", longPress = listOf("'", "ç", "ć")), Key("v", longPress = listOf(":")),
        Key("b", longPress = listOf(";")), Key("n", longPress = listOf("!", "ñ", "ń")),
        Key("m", longPress = listOf("?")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

private val spanishRows = listOf(
    listOf(
        Key("q", longPress = listOf("1")), Key("w", longPress = listOf("2")),
        Key("e", longPress = listOf("3", "é", "è", "ê", "ë")), Key("r", longPress = listOf("4")),
        Key("t", longPress = listOf("5")), Key("y", longPress = listOf("6")),
        Key("u", longPress = listOf("7", "ú", "ü", "ù", "û")),
        Key("i", longPress = listOf("8", "í", "ì", "î", "ï")),
        Key("o", longPress = listOf("9", "ó", "ò", "ô", "ö")),
        Key("p", longPress = listOf("0")),
    ),
    listOf(
        Key("a", longPress = listOf("@", "á", "à", "â", "ä")),
        Key("s", longPress = listOf("#", "ß", "ś")),
        Key("d", longPress = listOf("$")), Key("f", longPress = listOf("_")),
        Key("g", longPress = listOf("&")), Key("h", longPress = listOf("-")),
        Key("j", longPress = listOf("+")), Key("k", longPress = listOf("(")),
        Key("l", longPress = listOf(")")), Key("ñ"),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("z", longPress = listOf("*", "ż", "ź")), Key("x", longPress = listOf("\"")),
        Key("c", longPress = listOf("'", "ç", "ć")), Key("v", longPress = listOf(":")),
        Key("b", longPress = listOf(";")), Key("n", longPress = listOf("!", "¡")),
        Key("m", longPress = listOf("?", "¿")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

private val dvorakRows = listOf(
    listOf(
        Key("'", shiftLabel = "\"", longPress = listOf("1", "‘", "’")),
        Key(",", shiftLabel = "<", longPress = listOf("2")),
        Key(".", shiftLabel = ">", longPress = listOf("3", "…")),
        Key("p", longPress = listOf("4")), Key("y", longPress = listOf("5")),
        Key("f", longPress = listOf("6")), Key("g", longPress = listOf("7")),
        Key("c", longPress = listOf("8", "ç", "ć")),
        Key("r", longPress = listOf("9")), Key("l", longPress = listOf("0")),
    ),
    listOf(
        Key("a", longPress = listOf("@", "à", "á", "â", "ä", "å")),
        Key("o", longPress = listOf("#", "ò", "ó", "ô", "ö")),
        Key("e", longPress = listOf("$", "è", "é", "ê", "ë")),
        Key("u", longPress = listOf("_", "ù", "ú", "û", "ü")),
        Key("i", longPress = listOf("&", "ì", "í", "î", "ï")),
        Key("d", longPress = listOf("-")), Key("h", longPress = listOf("+")),
        Key("t", longPress = listOf("(")), Key("n", longPress = listOf(")", "ñ", "ń")),
        Key("s", longPress = listOf("/", "ß", "ś")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift),
        Key("q", longPress = listOf("*")), Key("j", longPress = listOf("\"")),
        Key("k", longPress = listOf("'")), Key("x", longPress = listOf(":")),
        Key("b", longPress = listOf(";")), Key("m", longPress = listOf("!")),
        Key("w", longPress = listOf("?")), Key("v", longPress = listOf("=")),
        Key("z", longPress = listOf("%", "ż", "ź")),
        Key("⌫", action = KeyAction.Delete),
    ),
    bottomRow(),
)

// Colemak: Z X C V stay put; top row drops the desktop ; key so all 26 letters
// fit the phone grid as 9 / 10 / 7. Number hints ride the top row, accents on
// the base vowels, symbol-first alternates elsewhere — Samsung-style like QWERTY.
private val colemakRows = listOf(
    listOf(
        Key("q", longPress = listOf("1")), Key("w", longPress = listOf("2")),
        Key("f", longPress = listOf("3")), Key("p", longPress = listOf("4")),
        Key("g", longPress = listOf("5")), Key("j", longPress = listOf("6")),
        Key("l", longPress = listOf("7")),
        Key("u", longPress = listOf("8", "ù", "ú", "û", "ü")),
        Key("y", longPress = listOf("9", "ÿ")),
    ),
    listOf(
        Key("a", longPress = listOf("@", "à", "á", "â", "ä", "å")),
        Key("r", longPress = listOf("#")), Key("s", longPress = listOf("$", "ß", "ś")),
        Key("t", longPress = listOf("_")), Key("d", longPress = listOf("&")),
        Key("h", longPress = listOf("-")), Key("n", longPress = listOf("+", "ñ", "ń")),
        Key("e", longPress = listOf("(", "è", "é", "ê", "ë")),
        Key("i", longPress = listOf(")", "ì", "í", "î", "ï")),
        Key("o", longPress = listOf("/", "ò", "ó", "ô", "ö")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("z", longPress = listOf("*", "ż", "ź")), Key("x", longPress = listOf("\"")),
        Key("c", longPress = listOf("'", "ç", "ć")), Key("v", longPress = listOf(":")),
        Key("b", longPress = listOf(";")), Key("k", longPress = listOf("!")),
        Key("m", longPress = listOf("?")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

// Workman: tuned to English digraph frequency. Same 9 / 10 / 7 phone adaptation
// as Colemak (desktop ; dropped from the top row), same alternate scheme.
private val workmanRows = listOf(
    listOf(
        Key("q", longPress = listOf("1")), Key("d", longPress = listOf("2")),
        Key("r", longPress = listOf("3")), Key("w", longPress = listOf("4")),
        Key("b", longPress = listOf("5")), Key("j", longPress = listOf("6")),
        Key("f", longPress = listOf("7")),
        Key("u", longPress = listOf("8", "ù", "ú", "û", "ü")),
        Key("p", longPress = listOf("9")),
    ),
    listOf(
        Key("a", longPress = listOf("@", "à", "á", "â", "ä", "å")),
        Key("s", longPress = listOf("#", "ß", "ś")), Key("h", longPress = listOf("$")),
        Key("t", longPress = listOf("_")), Key("g", longPress = listOf("&")),
        Key("y", longPress = listOf("-", "ÿ")), Key("n", longPress = listOf("+", "ñ", "ń")),
        Key("e", longPress = listOf("(", "è", "é", "ê", "ë")),
        Key("o", longPress = listOf(")", "ò", "ó", "ô", "ö")),
        Key("i", longPress = listOf("/", "ì", "í", "î", "ï")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("z", longPress = listOf("*", "ż", "ź")), Key("x", longPress = listOf("\"")),
        Key("m", longPress = listOf("'")), Key("c", longPress = listOf(":", "ç", "ć")),
        Key("v", longPress = listOf(";")), Key("k", longPress = listOf("!")),
        Key("l", longPress = listOf("?")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

// Halmak: tuned to English letter frequency & hand alternation. Same 9 / 10 / 7
// phone adaptation as Colemak and Workman (desktop punctuation dropped), same alternate scheme.
private val halmakRows = listOf(
    listOf(
        Key("w", longPress = listOf("1")), Key("l", longPress = listOf("2")),
        Key("r", longPress = listOf("3")), Key("b", longPress = listOf("4")),
        Key("z", longPress = listOf("5", "ż", "ź")), Key("q", longPress = listOf("6")),
        Key("u", longPress = listOf("7", "ù", "ú", "û", "ü")),
        Key("d", longPress = listOf("8")), Key("j", longPress = listOf("9")),
    ),
    listOf(
        Key("s", longPress = listOf("@", "ß", "ś")), Key("h", longPress = listOf("#")),
        Key("n", longPress = listOf("$", "ñ", "ń")), Key("t", longPress = listOf("_")),
        Key("f", longPress = listOf("&")), Key("g", longPress = listOf("-")),
        Key("a", longPress = listOf("+", "à", "á", "â", "ä", "å")),
        Key("e", longPress = listOf("(", "è", "é", "ê", "ë")),
        Key("o", longPress = listOf(")", "ò", "ó", "ô", "ö")),
        Key("i", longPress = listOf("/", "ì", "í", "î", "ï")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("m", longPress = listOf("*")), Key("v", longPress = listOf("\"")),
        Key("c", longPress = listOf("'", "ç", "ć")), Key("p", longPress = listOf(":")),
        Key("x", longPress = listOf(";")), Key("k", longPress = listOf("!")),
        Key("y", longPress = listOf("?", "ÿ")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

/**
 * ZWNJ then ZWJ, on the hold of hasant and of the full stop on every fixed
 * Bengali layout (issue #668). ZWNJ after hasant keeps two consonants from
 * forming a conjunct; ZWJ asks for a half form, as in the র-ফলা of র‍্যাব.
 * Desktop Jatiya and Probhat put them on AltGr and shift layers a phone
 * does not have. The popup draws both by name, since neither has a glyph.
 */
private val BengaliJoiners = listOf("\u200C", "\u200D")

private val probhatRows = listOf(
    listOf(
        Key("দ", shiftLabel = "ধ"), Key("ূ", shiftLabel = "ঊ"), Key("ী", shiftLabel = "ঈ"),
        Key("র", shiftLabel = "ড়"), Key("ট", shiftLabel = "ঠ"),
        Key("এ", shiftLabel = "ঐ"), Key("ু", shiftLabel = "উ"),
        Key("ি", shiftLabel = "ই"), Key("ও", shiftLabel = "ঔ", longPress = listOf("ৗ")),
        Key("প", shiftLabel = "ফ"),
    ),
    listOf(
        // আ and এ on the holds too (#900): after a vowel the taps give
        // the য়-glide, and the hold is how to get the bare vowel there.
        Key("া", shiftLabel = "অ", longPress = listOf("আ")), Key("স", shiftLabel = "ষ"),
        Key("ড", shiftLabel = "ঢ"), Key("ত", shiftLabel = "থ", longPress = listOf("ৎ")),
        Key("গ", shiftLabel = "ঘ"), Key("হ", shiftLabel = "ঃ", longPress = listOf("ঽ")),
        Key("জ", shiftLabel = "ঝ", longPress = listOf("ঞ")), Key("ক", shiftLabel = "খ"),
        Key("ল", shiftLabel = "ং"), Key("ে", shiftLabel = "ো", longPress = listOf("ৈ", "ৌ", "এ")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.2f),
        Key("য়", shiftLabel = "য"), Key("শ", shiftLabel = "ঢ়"), Key("চ", shiftLabel = "ছ"),
        Key("আ", shiftLabel = "ঋ", longPress = listOf("ৃ")), Key("ব", shiftLabel = "ভ"),
        Key("ন", shiftLabel = "ণ"), Key("ম", shiftLabel = "ঙ"),
        Key("্", shiftLabel = "।", longPress = listOf("ঁ", "়", "॥") + BengaliJoiners),
        Key("⌫", action = KeyAction.Delete, width = 1.3f),
    ),
    bottomRow(periodExtras = BengaliJoiners),
)

private val jatiyaRows = listOf(
    listOf(
        Key("ঙ", shiftLabel = "ং"), Key("য", shiftLabel = "য়"), Key("ড", shiftLabel = "ঢ"),
        Key("প", shiftLabel = "ফ"), Key("ট", shiftLabel = "ঠ"), Key("চ", shiftLabel = "ছ"),
        Key("জ", shiftLabel = "ঝ"), Key("হ", shiftLabel = "ঞ", longPress = listOf("ঽ")),
        Key("গ", shiftLabel = "ঘ"), Key("ড়", shiftLabel = "ঢ়"),
    ),
    listOf(
        Key("ৃ", shiftLabel = "ৗ", longPress = listOf("ঋ")),
        Key("ু", shiftLabel = "ূ", longPress = listOf("উ", "ঊ")),
        Key("ি", shiftLabel = "ী", longPress = listOf("ই", "ঈ")),
        Key("ব", shiftLabel = "ভ"),
        Key("্", shiftLabel = "।", longPress = listOf("॥", "ৎ") + BengaliJoiners),
        Key("া", shiftLabel = "অ", longPress = listOf("আ")),
        Key("ক", shiftLabel = "খ"), Key("ত", shiftLabel = "থ"),
        Key("দ", shiftLabel = "ধ"),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.2f),
        Key("ঁ", shiftLabel = "ঃ"),
        Key("ো", shiftLabel = "ৌ", longPress = listOf("ও", "ঔ")),
        Key("ে", shiftLabel = "ৈ", longPress = listOf("এ", "ঐ")),
        Key("র", shiftLabel = "ল"), Key("ন", shiftLabel = "ণ"),
        Key("স", shiftLabel = "ষ"), Key("ম", shiftLabel = "শ"),
        Key("⌫", action = KeyAction.Delete, width = 1.3f),
    ),
    bottomRow(periodExtras = BengaliJoiners),
)

// BSTI: each popup is the base layer's hold, then the shift layer's.
private val bstiRows = listOf(
    listOf(
        Key("ঙ", shiftLabel = "ং", longPress = listOf("ঙ্ক")),
        Key("য", shiftLabel = "য়", longPress = listOf("ঞ্ঝ")),
        Key("ড", shiftLabel = "ঢ"), Key("প", shiftLabel = "ফ"), Key("ট", shiftLabel = "ঠ"),
        Key("চ", shiftLabel = "ছ", longPress = listOf("ঞ্ছ")),
        Key("জ", shiftLabel = "ঝ", longPress = listOf("জ্ঞ")),
        Key("হ", shiftLabel = "ঞ", longPress = listOf("ঞ্জ", "ঽ")),
        Key("গ", shiftLabel = "ঘ", longPress = listOf("ঙ্গ")),
        Key("ড়", shiftLabel = "ঢ়"),
    ),
    listOf(
        Key("ৃ", shiftLabel = "র্", longPress = listOf("ঋ")),
        Key("ু", shiftLabel = "ূ", longPress = listOf("উ", "ঊ")),
        Key("ি", shiftLabel = "ী", longPress = listOf("ই", "ঈ")),
        Key("া", shiftLabel = "অ", longPress = listOf("আ")),
        Key("্", shiftLabel = "ঁ", longPress = listOf("॥") + BengaliJoiners),
        Key("ব", shiftLabel = "ভ"),
        Key("ক", shiftLabel = "খ", longPress = listOf("ক্ষ")),
        Key("ত", shiftLabel = "থ", longPress = listOf("ৎ")),
        Key("দ", shiftLabel = "ধ"),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.2f),
        Key("্র", shiftLabel = "্য"),
        Key("ো", shiftLabel = "ৌ", longPress = listOf("ও", "ঔ")),
        Key("ে", shiftLabel = "ৈ", longPress = listOf("এ", "ঐ")),
        Key("র", shiftLabel = "ল", longPress = listOf("ব়্য")),
        Key("ন", shiftLabel = "ণ", longPress = listOf("ঞ্চ")),
        Key("স", shiftLabel = "ষ", longPress = listOf("ষ্ণ")),
        Key("ম", shiftLabel = "শ", longPress = listOf("ঃ", "হ্ম")),
        Key("⌫", action = KeyAction.Delete, width = 1.3f),
    ),
    bottomRow(periodExtras = BengaliJoiners),
)

// Korean 2-beolsik: compatibility jamo in the standard phone arrangement, tense
// consonants and ㅒ/ㅖ on shift. HangulComposer assembles them into syllables.
private val koreanRows = listOf(
    listOf(
        Key("ㅂ", shiftLabel = "ㅃ"), Key("ㅈ", shiftLabel = "ㅉ"),
        Key("ㄷ", shiftLabel = "ㄸ"), Key("ㄱ", shiftLabel = "ㄲ"),
        Key("ㅅ", shiftLabel = "ㅆ"), Key("ㅛ"), Key("ㅕ"), Key("ㅑ"),
        Key("ㅐ", shiftLabel = "ㅒ"), Key("ㅔ", shiftLabel = "ㅖ"),
    ),
    listOf(
        Key("ㅁ"), Key("ㄴ"), Key("ㅇ"), Key("ㄹ"), Key("ㅎ"),
        Key("ㅗ"), Key("ㅓ"), Key("ㅏ"), Key("ㅣ"),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("ㅋ"), Key("ㅌ"), Key("ㅊ"), Key("ㅍ"), Key("ㅠ"), Key("ㅜ"), Key("ㅡ"),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

// Russian ЙЦУКЕН. Cyrillic has case, so shift uppercases the labels (no
// per-key shiftLabel needed). Digits ride the top row and symbols the other
// two, as QWERTY's do, so the corner hints match it; ё and ъ follow the
// symbol on their keys' long-press, as QWERTY's accents do.
private val russianRows = listOf(
    listOf(
        Key("й", longPress = listOf("1")), Key("ц", longPress = listOf("2")),
        Key("у", longPress = listOf("3")), Key("к", longPress = listOf("4")),
        Key("е", longPress = listOf("5", "ё")), Key("н", longPress = listOf("6")),
        Key("г", longPress = listOf("7")), Key("ш", longPress = listOf("8")),
        Key("щ", longPress = listOf("9")), Key("з", longPress = listOf("0")),
        Key("х", longPress = listOf("ъ")),
    ),
    listOf(
        Key("ф", longPress = listOf("@")), Key("ы", longPress = listOf("#")),
        Key("в", longPress = listOf("$", "₽")), Key("а", longPress = listOf("_")),
        Key("п", longPress = listOf("&")), Key("р", longPress = listOf("-")),
        Key("о", longPress = listOf("+")), Key("л", longPress = listOf("(")),
        Key("д", longPress = listOf(")")), Key("ж", longPress = listOf("/")),
        Key("э", longPress = listOf("=")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("я", longPress = listOf("*")), Key("ч", longPress = listOf("\"")),
        Key("с", longPress = listOf("'")), Key("м", longPress = listOf(":")),
        Key("и", longPress = listOf(";")), Key("т", longPress = listOf("!")),
        Key("ь", longPress = listOf("?", "ъ")), Key("б", longPress = listOf("«", "»")),
        Key("ю", longPress = listOf("»", "«")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

/**
 * The bottom row for the Arabic grid.
 *
 * [bottomRow] is shared with QWERTY and every Latin layout, so the Arabic
 * punctuation cannot go there: ، ؟ ؛ are different characters from , ? ; and
 * an Arabic writer reaching the Latin ones is the same paper cut as a Bengali
 * writer reaching "." instead of ৷. ASCII stays on long-press, where numbers,
 * URLs and code still need it.
 */
private fun arabicBottomRow() = listOf(
    Key("?123", action = KeyAction.Symbols, width = 1.5f),
    Key("،", role = KeyRole.Comma, longPress = listOf("؟", "؛", "ـ", ",", "!", "?")),
    Key("🌐", action = KeyAction.LanguageSwitch),
    Key(" ", action = KeyAction.Space, width = 4f),
    Key(".", role = KeyRole.Period, longPress = listOf("…", "٪", "،", "؟", "!", ":", ";")),
    Key("⏎", action = KeyAction.Enter, width = 1.5f),
)

// Arabic letters in visual (left-to-right) key order; the field renders the
// committed text right-to-left, so the grid itself is not mirrored.
//
// Arabic is uncased, so shift is free for a second plane rather than being
// inert: it carries the eight harakat, which the Arabic 101 keyboard also puts
// there. Without them the layout could write consonants but never vowel a text,
// which is required in the Qur'an, in poetry, in dictionaries and in anything
// written for learners.
private val arabicRows = listOf(
    listOf(
        Key("ض", shiftLabel = "َ"), Key("ص", shiftLabel = "ً"), Key("ث", shiftLabel = "ُ"),
        Key("ق", shiftLabel = "ٌ"), Key("ف", shiftLabel = "ِ"), Key("غ", shiftLabel = "ٍ"),
        Key("ع", shiftLabel = "ْ"), Key("ه", shiftLabel = "ّ"),
        Key("خ", shiftLabel = "ٰ"), Key("ح", shiftLabel = "ٓ"), Key("ج", shiftLabel = "ـ"),
    ),
    listOf(
        Key("ش"), Key("س"), Key("ي", shiftLabel = "ى", longPress = listOf("ئ")), Key("ب"),
        Key("ل", shiftLabel = "لا", longPress = listOf("لأ", "لإ", "لآ")),
        Key("ا", shiftLabel = "آ", longPress = listOf("أ", "إ", "ٱ")), Key("ت"),
        Key("ن"), Key("م"), Key("ك"), Key("ط", shiftLabel = "ظ"),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("ذ"), Key("د"), Key("ز"), Key("ر"), Key("و", longPress = listOf("ؤ")),
        Key("ة", shiftLabel = "ه"), Key("ى", shiftLabel = "ي"),
        Key("ء", longPress = listOf("أ", "إ", "ؤ", "ئ")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    arabicBottomRow(),
)

// Greek national layout. Greek has case, so shift uppercases the labels
// (ς→Σ, α→Α …) — no per-key shiftLabel — and the accented (tonos/dialytika)
// vowels ride long-press.
private val greekRows = listOf(
    listOf(
        Key("ς"), Key("ε", longPress = listOf("έ")), Key("ρ"), Key("τ"),
        Key("υ", longPress = listOf("ύ", "ϋ", "ΰ")), Key("θ"),
        Key("ι", longPress = listOf("ί", "ϊ", "ΐ")), Key("ο", longPress = listOf("ό")),
        Key("π"),
    ),
    listOf(
        Key("α", longPress = listOf("ά")), Key("σ"), Key("δ"), Key("φ"), Key("γ"),
        Key("η", longPress = listOf("ή")), Key("ξ"), Key("κ"), Key("λ"),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("ζ"), Key("χ"), Key("ψ"), Key("ω", longPress = listOf("ώ")),
        Key("β"), Key("ν"), Key("μ"),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

// Hebrew letters in visual (left-to-right) key order; the field renders the
// committed text right-to-left, so the grid itself is not mirrored (as Arabic).
// The five final forms (ן ם ך ף ץ) sit where a Hebrew keyboard places them.
private val hebrewRows = listOf(
    listOf(
        Key("ק"), Key("ר"), Key("א"), Key("ט"), Key("ו"),
        Key("ן"), Key("ם"), Key("פ"),
    ),
    listOf(
        Key("ש"), Key("ד"), Key("ג"), Key("כ"), Key("ע"), Key("י"),
        Key("ח"), Key("ל"), Key("ך"), Key("ף"),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.5f),
        Key("ז"), Key("ס"), Key("ב"), Key("ה"), Key("נ"), Key("מ"),
        Key("צ"), Key("ת"), Key("ץ"),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(),
)

// Hindi (Devanagari), the standard InScript keymap adapted to the 10-column
// phone grid: vowel signs (मात्रा) and consonants on the base plane, independent
// vowels and aspirates on shift — the arrangement InScript typists know. Rare
// desktop-number-row items move to long-press (ञ on ज, the nukta forms on ड).
// The Indic-cluster composer deletes a full consonant+sign cluster per delete.
private val hindiRows = listOf(
    listOf(
        Key("ौ", shiftLabel = "औ"), Key("ै", shiftLabel = "ऐ"), Key("ा", shiftLabel = "आ"),
        Key("ी", shiftLabel = "ई"), Key("ू", shiftLabel = "ऊ"), Key("ब", shiftLabel = "भ"),
        Key("ह", shiftLabel = "ङ"), Key("ग", shiftLabel = "घ"), Key("द", shiftLabel = "ध"),
        Key("ज", shiftLabel = "झ", longPress = listOf("ञ")),
        Key("ड", shiftLabel = "ढ", longPress = listOf("ड़", "ढ़", "़")),
    ),
    listOf(
        Key("ो", shiftLabel = "ओ"), Key("े", shiftLabel = "ए"), Key("्", shiftLabel = "अ"),
        Key("ि", shiftLabel = "इ"), Key("ु", shiftLabel = "उ"), Key("प", shiftLabel = "फ"),
        Key("र", shiftLabel = "ऱ"), Key("क", shiftLabel = "ख"), Key("त", shiftLabel = "थ"),
        Key("च", shiftLabel = "छ"), Key("ट", shiftLabel = "ठ"),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 1.2f),
        Key("ृ", shiftLabel = "ऋ"), Key("ं", shiftLabel = "ँ", longPress = listOf("ः", "ॐ")),
        Key("म", shiftLabel = "ण"), Key("न", shiftLabel = "ऩ"), Key("व"),
        Key("ल", shiftLabel = "ळ"), Key("स", shiftLabel = "श"), Key("ष"), Key("य"),
        Key("⌫", action = KeyAction.Delete, width = 1.3f),
    ),
    bottomRow(),
)

private val symbolRows = listOf(
    // This row is also what the optional number row shows over the letter
    // layer, so its long presses are the only symbols reachable there without
    // a layer switch. They follow one rule per key rather than a hand-picked
    // pile, so nothing has to be learnt digit by digit:
    //
    //   1. the character a physical US keyboard puts on Shift+digit — ! @ # $
    //      % ^ & * ( ) — which is where the fingers already expect them, and
    //      is how percent became typable from the number row at all;
    //   2. the superscript and subscript of that digit, all ten of each;
    //   3. every vulgar fraction with that digit as the *denominator*, so
    //      "hold 4 for quarters, hold 8 for eighths" holds for the whole row.
    //
    // What was here before came from AOSP: superscripts on 1-4 only, no
    // subscripts, fractions sorted by numerator so 1 carried ½ ⅓ ¼ and 5-9
    // carried nothing, and a superscript n on 0 that answers no question
    // anyone asks of a number row.
    listOf(
        Key("1", longPress = listOf("!", "¹", "₁")),
        Key("2", longPress = listOf("@", "²", "₂", "½")),
        Key("3", longPress = listOf("#", "³", "₃", "⅓", "⅔")),
        Key("4", longPress = listOf("$", "⁴", "₄", "¼", "¾")),
        Key("5", longPress = listOf("%", "⁵", "₅", "⅕", "⅖", "⅗", "⅘")),
        Key("6", longPress = listOf("^", "⁶", "₆", "⅙", "⅚")),
        Key("7", longPress = listOf("&", "⁷", "₇", "⅐")),
        Key("8", longPress = listOf("*", "⁸", "₈", "⅛", "⅜", "⅝", "⅞")),
        Key("9", longPress = listOf("(", "⁹", "₉", "⅑")),
        Key("0", longPress = listOf(")", "⁰", "₀", "⅒", "∅")),
    ),
    listOf(
        Key("@"), Key("#", longPress = listOf("№")),
        Key("$", longPress = listOf("৳", "€", "£", "¥", "₹", "₿")),
        // Percent sat two layers deep (?123 → =\< → %), which is one switch
        // too many for a character that belongs next to the digits. It takes
        // the underscore's slot here — the arrangement Gboard ships — and the
        // underscore drops onto the dash, where the other dash-shaped
        // characters already live.
        Key("%", longPress = listOf("‰")), Key("&"),
        Key("-", longPress = listOf("_", "–", "—", "·")),
        Key("+", longPress = listOf("±")), Key("(", longPress = listOf("[", "{", "<")),
        Key(")", longPress = listOf("]", "}", ">")), Key("/", longPress = listOf("\\", "÷")),
    ),
    listOf(
        Key("=\\<", action = KeyAction.Symbols, width = 1.5f),
        Key("*", longPress = listOf("†", "★", "×")),
        Key("\"", longPress = listOf("“", "”", "„", "«", "»")),
        Key("'", longPress = listOf("‘", "’", "‚", "‹", "›")), Key(":"), Key(";"),
        Key("!", longPress = listOf("¡")), Key("?", longPress = listOf("¿", "‽")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(symbols = true),
)

/** The symbols page with € on the currency key and $ first on its hold. */
private val frenchSymbolRows = symbolRows.map { row ->
    row.map { key ->
        if (key.label == "$") Key("€", longPress = listOf("$", "৳", "£", "¥", "₹", "₿")) else key
    }
}

private val symbolsShiftedRows = listOf(
    listOf(
        Key("~"), Key("`"), Key("|"), Key("•", longPress = listOf("◦", "‣")), Key("√"),
        Key("π", longPress = listOf("Π", "℮")), Key("÷"), Key("×"),
        Key("¶", longPress = listOf("§")),
        Key("=", longPress = listOf("≠", "≈", "≤", "≥", "∆")),
    ),
    listOf(
        Key("৳"), Key("€"), Key("£", longPress = listOf("₺")), Key("¥"), Key("₹"),
        Key("^", longPress = listOf("↑", "↓", "←", "→")), Key("°", longPress = listOf("′", "″")),
        Key("{"), Key("}"), Key("\\"),
        // Dead-key accents: tap one, then a letter, and the pair fuses (´ then
        // e → é). The label shows the accent on a dotted circle; the output is
        // the bare combining mark, which is what marks a key as a dead key.
        Key(
            label = "\u25CC\u0301",
            output = "\u0301",
            longPress = listOf(
                "\u0300", "\u0302", "\u0308", "\u0303", "\u030C",
                "\u0304", "\u030A", "\u0327", "\u0328", "\u030B",
            ),
),
    ),
    listOf(
        Key("?123", action = KeyAction.Symbols, width = 1.5f),
        // Percent moved up to the first symbol layer; the underscore it
        // displaced takes the slot it left behind.
        Key("_"), Key("©"), Key("®"), Key("™"),
        Key("✓", longPress = listOf("✔", "✗", "☆", "★")), Key("[", longPress = listOf("{")),
        Key("]", longPress = listOf("}")),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    ),
    bottomRow(symbols = true),
)

/**
 * 4-column keypad grid shared by the numeric field kinds: digits on the left 3
 * columns (0 under 8, phone-pad style), delete over enter anchoring the right
 * column, and the field-specific characters filling the rest.
 *
 * The keys every pad has stay put across all five (issue #594): the digits,
 * delete in the low-right slot under the thumb, enter below it, and space,
 * on the pads that carry one, at the top of the right column. Someone who
 * moves delete on one pad then finds it where they expect on the others, and
 * the field-specific keys are the only ones that differ.
 */
private fun numpad(
    rightTop: Key,
    rightMid: Key,
    bottomLeft: Key,
    bottomRight: Key,
    zero: Key = Key("0"),
) = listOf(
    listOf(Key("1"), Key("2"), Key("3"), rightTop),
    listOf(Key("4"), Key("5"), Key("6"), rightMid),
    listOf(Key("7"), Key("8"), Key("9"), Key("⌫", action = KeyAction.Delete)),
    listOf(bottomLeft, zero, bottomRight, Key("⏎", action = KeyAction.Enter)),
)

/**
 * The legend a keypad's space key wears (issue #459): the open box keycaps use
 * for a visible space. A spacebar with a label draws the language name, which a
 * key a quarter of the pad wide has no room for, and a blank one drew nothing
 * at all, so the renderer draws this one as the key's glyph instead.
 */
const val PAD_SPACE_LABEL = "\u2423"

/** A keypad space key, labelled [PAD_SPACE_LABEL] rather than the language name. */
private fun padSpace() = Key(PAD_SPACE_LABEL, action = KeyAction.Space)

/**
 * TYPE_CLASS_NUMBER. The minus and decimal keys are always present — fields that
 * lack the signed/decimal flags filter them out themselves, and showing them
 * keeps the pad stable across the many apps that forget to set the flags.
 */
private val numberRows = numpad(
    rightTop = padSpace(),
    rightMid = Key("-", longPress = listOf("+", "%", "*", "/")),
    bottomLeft = Key(".", longPress = listOf(":")),
    bottomRight = Key(","),
)

/** TYPE_CLASS_PHONE: dial pad with pause (,) and wait (;) on long press. */
private val phoneRows = numpad(
    rightTop = padSpace(),
    rightMid = Key("-", longPress = listOf("(", ")", "/", ".")),
    bottomLeft = Key("*", longPress = listOf(",")),
    bottomRight = Key("#", longPress = listOf(";")),
    zero = Key("0", longPress = listOf("+")),
)

/**
 * TYPE_DATETIME_VARIATION_DATE: separators for 20/07/2026, 2026-07-20, 20.7.26.
 * No space here, so the slot space holds on the other pads takes the slash.
 */
private val dateRows = numpad(
    rightTop = Key("/", longPress = listOf(":")),
    rightMid = Key("-"),
    bottomLeft = Key("."),
    bottomRight = Key(","),
)

/** TYPE_DATETIME_VARIATION_TIME: colon-led (17:45), space for AM/PM-ish suffixes. */
private val timeRows = numpad(
    rightTop = padSpace(),
    rightMid = Key(":", longPress = listOf(".")),
    bottomLeft = Key("."),
    bottomRight = Key("-"),
)

/** TYPE_DATETIME_VARIATION_NORMAL: date and time separators together. */
private val dateTimeRows = numpad(
    rightTop = padSpace(),
    rightMid = Key("/", longPress = listOf(".")),
    bottomLeft = Key("-"),
    bottomRight = Key(":"),
)

/**
 * A key that injects a raw key code. Written with the numeric code rather than
 * `KeyEvent.KEYCODE_*` so this file stays free of an Android import — it is
 * pure data, and the unit tests read it without a device.
 */
private fun fnKey(label: String, code: Int) = Key(label, action = KeyAction.SendKey(code))

/**
 * The T9 keypad. Four columns: the ITU E.161 letter groups in the left three,
 * the keys that are not letters in the fourth.
 *
 * `1` is the punctuation key, as it was on the phones this grid comes from —
 * it commits a full stop and carries the rest behind a hold, which is where a
 * comma, a question mark and the apostrophe every contraction needs live. It
 * is a plain 1:1 key: there is nothing for a language model to disambiguate
 * about punctuation, and a key set of one decodes as the character it is.
 *
 * Every row totals four, so the grid draws at one width throughout.
 */
private val t9Rows = listOf(
    listOf(
        Key(
            ".,?",
            output = ".",
            role = KeyRole.Period,
            longPress = listOf("1", ",", "?", "!", "'", "-", ":", ";"),
        ),
        ambiguousKey("abc", hint = "2"),
        ambiguousKey("def", hint = "3"),
        Key("⌫", action = KeyAction.Delete),
    ),
    listOf(
        ambiguousKey("ghi", hint = "4"),
        ambiguousKey("jkl", hint = "5"),
        ambiguousKey("mno", hint = "6"),
        Key("⇧", action = KeyAction.Shift),
    ),
    listOf(
        ambiguousKey("pqrs", hint = "7"),
        ambiguousKey("tuv", hint = "8"),
        ambiguousKey("wxyz", hint = "9"),
        Key("⏎", action = KeyAction.Enter),
    ),
    listOf(
        Key("?123", action = KeyAction.Symbols),
        Key("🌐", action = KeyAction.LanguageSwitch),
        Key(" ", action = KeyAction.Space, width = 2f),
    ),
)

/**
 * Compact QWERTY: QWERTY's three letter rows folded two letters to a key.
 *
 * The digits keep the positions they hold on the full board — `1` under the
 * key that starts with `q`, `0` under the one that ends with `p` — so the
 * long-press digits still read left to right across the top row, even though
 * there are now five keys carrying ten of them.
 *
 * Every row totals five.
 */
private val compactRows = listOf(
    listOf(
        ambiguousKey("qw", hint = "1", longPress = listOf("2")),
        ambiguousKey("er", hint = "3", longPress = listOf("4", "è", "é", "ê", "ë")),
        ambiguousKey("ty", hint = "5", longPress = listOf("6")),
        ambiguousKey("ui", hint = "7", longPress = listOf("8", "ù", "ú", "û", "ü", "î", "ï")),
        ambiguousKey("op", hint = "9", longPress = listOf("0", "ò", "ó", "ô", "ö")),
    ),
    listOf(
        ambiguousKey("as", longPress = listOf("@", "#", "à", "á", "â", "ä", "å", "ß")),
        ambiguousKey("df", longPress = listOf("$", "_")),
        ambiguousKey("gh", longPress = listOf("&", "-")),
        ambiguousKey("jk", longPress = listOf("+", "(")),
        Key("L", output = "l", longPress = listOf(")")),
    ),
    listOf(
        Key("⇧", action = KeyAction.Shift, width = 0.5f),
        ambiguousKey("zx", longPress = listOf("*", "\"", "ż", "ź")),
        ambiguousKey("cv", longPress = listOf("'", ":", "ç", "ć")),
        ambiguousKey("bn", longPress = listOf(";", "!", "ñ", "ń")),
        Key("M", output = "m", longPress = listOf("?")),
        Key("⌫", action = KeyAction.Delete, width = 0.5f),
    ),
    bottomRow(scale = 0.5f),
)
