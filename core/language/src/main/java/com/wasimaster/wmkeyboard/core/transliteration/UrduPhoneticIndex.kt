package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup from roman Urdu to word-list words — what
 * [HindiPhoneticIndex] is to Hindi phonetic, and the half of Urdu phonetic
 * typing that actually knows Urdu (issue #496).
 *
 * The fold is more brutal here than in either Indic index, for a reason that is
 * Urdu's own: **its script does not write short vowels.** کتاب has no letter
 * for the "i" of "kitab" and دن none for the "i" of "din", so a key that kept
 * the typed vowels could never reach the word that was typed. The key is
 * therefore the word's consonant skeleton — which is the Arabic-script
 * tradition's own way of spelling, and why it fits — and every vowel moves into
 * the [Folded.detail] mask beside it, where it ranks siblings instead of
 * choosing between them. So:
 *
 *  - **every vowel leaves the key**: "kitab", "kitaab" and the chat-spelled
 *    "ktab" all ask for `ktb`, which is what کتاب answers to;
 *  - **ت ٹ ط are all `t`**, د ڈ are `d`, ر ڑ are `r`, س ص ث are `s`, ز ذ ض ظ
 *    are `z`, ک ق are `k`, ہ ح are `h` — every letter Urdu keeps for its
 *    loanwords beside the one roman spells, and the retroflex row no roman
 *    spelling marks at all (this is what takes "roti" to روٹی and "bara" to
 *    بڑا). Which of them a word used is detail, not key;
 *  - **aspiration is detail too**: خ is `k` aspirated exactly as "kh" is, غ is
 *    `g` aspirated, ش is `s` aspirated ("sh"), ف is `p` aspirated ("ph"/"f");
 *  - **و and ی are consonants only where they open a word** (وہ, یار) and the
 *    long vowels everywhere else, which is the reading the roman side makes of
 *    "w" and "y" too, so "kya" and کیا agree on `k`;
 *  - **ع, ء, ئ and the diacritics fold away** altogether: they are
 *    orthography, not sound — دعا is `d` — and nobody types them.
 *
 * Two habits are too common to treat as mismatches at all, so a word is also
 * filed with them undone ([relaxations]): a closing ہ read as the "a" of a
 * Perso-Arabic noun rather than as an h (بچہ is "bacha" far more often than
 * "bachah"), and the nasalisation ں left off (ہیں typed "hai", نہیں "nahi").
 *
 * A skeleton key is lenient — in Bengali that was a mistake, here it is the
 * point — but it does leave the detail mask carrying the whole weight of
 * telling siblings apart, and the word list's own frequencies cannot help:
 * `ur_full` ships every word at 1. Three things therefore decide the order,
 * and the numbers behind them were measured over that list rather than guessed
 * (44% of a 176-word hand-written set come back first, 73% in the first three):
 *
 *  1. **Where the vowel sits.** Urdu always writes the vowel that opens or
 *     closes a word and never writes a short one inside, so a vowel typed at
 *     either end must find one written ([vowelMismatch]'s INITIAL / FINAL), and
 *     one typed inside is free to find nothing — which is what lets "kam" be
 *     کم and "kitab" کتاب at the same time.
 *  2. **Which letter of a key** the word used, lopsidedly as in the Bengali and
 *     Hindi indexes: typing "T" or "q" and not getting ٹ or ق is a request
 *     refused, while getting them unasked is ordinary spelling.
 *  3. **The shorter word**, last, as the nearest thing to a frequency prior a
 *     flat list offers: four typed letters are rarely a long compound.
 *
 * The curated spelling map (`ur_rom.tsv`) sits in front of all of it for the
 * words people type most often.
 */
class UrduPhoneticIndex(private val table: PhoneticTable) : PhoneticIndex {

    /** An index built on the device over [entries], `word to frequency`. */
    constructor(entries: List<Pair<String, Int>>) : this(table(entries))

    override val isEmpty: Boolean get() = table.isEmpty

    override fun lookup(input: String): List<String> {
        val typed = fold(romanSlots(input))
        val bucket = table.rows(typed.key)
        if (bucket.size <= 1) return bucket.map { it.word }
        return bucket
            .sortedWith(
                compareByDescending<PhoneticRow> {
                    it.frequency.toLong() * SCALE / handicap(it, typed)
                }
                    .thenBy { it.word.length }
                    .thenBy { it.word },
            )
            .map { it.word }
            .distinct()
    }

    override fun frequencyOf(word: String): Int = table.frequencyOf(word)

    override fun matchStrength(input: String): Int {
        val typed = fold(romanSlots(input))
        val best = table.rows(typed.key)
            .maxOfOrNull { it.frequency / handicap(it, typed) }
            ?: 0L
        return best.toInt()
    }

    override val maxFrequency: Int get() = table.maxFrequency

    /**
     * What to divide [entry]'s frequency by for disagreeing with what was
     * typed. 1 means it agrees on every count.
     */
    private fun handicap(entry: PhoneticRow, typed: Folded): Long {
        var divisor = if (entry.flags and FLAG_RELAXED != 0) RELAXED else 1L
        // Same key means the same number of slots, so the masks line up.
        if (entry.detail.length != typed.detail.length) return MAX_HANDICAP
        val last = typed.detail.lastIndex
        for (i in typed.detail.indices) {
            val position = when (i) {
                0 -> Position.INITIAL
                last -> Position.FINAL
                else -> Position.INNER
            }
            divisor *= mismatch(
                typed = typed.detail[i].code - DETAIL_BASE,
                word = entry.detail[i].code - DETAIL_BASE,
                position = position,
                key = typed.key.getOrNull(i),
            )
            if (divisor > MAX_HANDICAP) return MAX_HANDICAP
        }
        return divisor
    }

    private fun mismatch(typed: Int, word: Int, position: Position, key: Char?): Long {
        var divisor = 1L
        val typedAspirated = typed and ASPIRATED != 0
        // "ch" is how چھ gets written by anyone used to English — the same
        // reason BengaliPhoneticIndex leaves ch out of its aspiration set — so
        // that one key does not count aspiration at all.
        if (key != 'c' && typedAspirated != (word and ASPIRATED != 0)) {
            divisor *= if (typedAspirated) TYPED_NOT_FOUND else FOUND_NOT_TYPED
        }
        val typedVariant = typed and VARIANT != 0
        if (typedVariant != (word and VARIANT != 0)) {
            divisor *= if (typedVariant) TYPED_NOT_FOUND else FOUND_NOT_TYPED
        }
        if ((typed and PAIR) != (word and PAIR)) divisor *= VOWEL_PAIR
        divisor *= vowelMismatch(
            typed = (typed shr VOWEL_SHIFT) and VOWEL_MASK,
            word = (word shr VOWEL_SHIFT) and VOWEL_MASK,
            position = position,
        )
        return divisor
    }

    /** Where a vowel sits in the word, which is what decides how strict it is. */
    private enum class Position { INITIAL, INNER, FINAL }

    private fun vowelMismatch(typed: Int, word: Int, position: Position): Long {
        if (typed == word) return 1L
        // ی is the vowel of both دیکھ and میں, و of both ہو and خوب: one letter
        // where roman writes two different ones.
        if (compatible(typed, word)) return 1L
        // A vowel a relaxation synthesised stands for whatever was written.
        if (word == VOWEL_SHORT) return 1L
        if (position == Position.INNER) {
            return when {
                typed == VOWEL_NONE -> VOWEL_NOT_HINTED
                word == VOWEL_NONE -> if (typed == VOWEL_SHORT) 1L else TYPED_NOT_FOUND
                typed == VOWEL_SHORT -> SHORT_FOR_LONG
                else -> TYPED_NOT_FOUND
            }
        }
        // Opening or closing the word, where Urdu writes the vowel as a letter.
        return when {
            typed == VOWEL_NONE -> VOWEL_NOT_HINTED
            word == VOWEL_NONE -> TYPED_NOT_FOUND
            // "ki" is کی and "ap" آپ: one letter for a long vowel is the norm.
            typed == VOWEL_SHORT -> 1L
            else -> TYPED_NOT_FOUND
        }
    }

    private fun compatible(a: Int, b: Int): Boolean =
        (a == VOWEL_I && b == VOWEL_E) || (a == VOWEL_E && b == VOWEL_I) ||
            (a == VOWEL_O && b == VOWEL_U) || (a == VOWEL_U && b == VOWEL_O)

    companion object {

        /** [PhoneticTable] fold id of this index's tables. */
        const val FOLD = 2

        /**
         * Version of the fold and of how words are filed. Bumped whenever either
         * changes, which makes every prebuilt table of the old one read as
         * absent (the data repo publishes the new one under a new name).
         */
        const val REVISION = 1

        /** Filed with a closing ہ or a ں undone, which costs it a little. */
        private const val FLAG_RELAXED = 1

        /** Files [entries] by the skeleton fold. */
        fun table(entries: List<Pair<String, Int>>): PhoneticTable {
            val builder = PhoneticTableBuilder(FOLD, REVISION)
            file(builder, entries)
            return builder.build()
        }

        /**
         * Files [entries] into [builder]; see [table]. [spellingOf] is the word
         * in the letters the fold reads, for a language whose own it doesn't.
         */
        fun file(
            builder: PhoneticTableBuilder,
            entries: List<Pair<String, Int>>,
            spellingOf: (String) -> String = { it },
        ) {
            for ((word, frequency) in entries) {
                val spelling = spellingOf(word)
                // Compiled and scraped lists both carry tokens that are not words:
                // ur_full spells its compounds with an underscore (آؤ_بھگت), and the
                // fold would file those under the first word's key.
                if (!spelling.all(::isWordChar)) continue
                val slots = urduSlots(spelling)
                if (slots.isEmpty()) continue
                val seen = HashSet<String>(4)
                for ((relaxed, variant) in relaxations(slots)) {
                    val folded = fold(variant)
                    if (folded.key.isEmpty() || !seen.add(folded.key)) continue
                    builder.add(folded.key, word, frequency, folded.detail, if (relaxed) FLAG_RELAXED else 0)
                }
                // Known to frequencyOf even when it folds to no key at all (آ).
                builder.addWord(word, frequency)
            }
        }

        private const val SCALE = 1024L
        private const val MAX_HANDICAP = 1L shl 20

        /** Typed a detail the word does not have — a deliberate request, refused. */
        private const val TYPED_NOT_FOUND = 20L

        /** Left a detail out that the word has — ordinary casual spelling. */
        private const val FOUND_NOT_TYPED = 2L

        /** A single letter typed for a vowel written long, inside the word. */
        private const val SHORT_FOR_LONG = 3L

        /** The word writes a vowel where nothing at all was typed ("ktab"). */
        private const val VOWEL_NOT_HINTED = 8L

        /** One of the two spelled a vowel with two letters and the other did not. */
        private const val VOWEL_PAIR = 2L

        /** Filed with its closing ہ or its ں undone. */
        private const val RELAXED = 3L

        // One detail char per key char, plus a last one for whatever vowel
        // closes the word: which letter of the key, whether it was aspirated,
        // whether the vowel in front of it was spelled with two letters, and
        // which vowel that was.
        private const val DETAIL_BASE = '0'.code
        private const val ASPIRATED = 1
        private const val VARIANT = 2
        private const val PAIR = 4
        private const val VOWEL_SHIFT = 3
        private const val VOWEL_MASK = 15

        private const val VOWEL_NONE = 0

        /** A vowel the script would not write: the zabar of دن, the zer of کتاب. */
        private const val VOWEL_SHORT = 1
        private const val VOWEL_A = 2
        private const val VOWEL_I = 3
        private const val VOWEL_U = 4
        private const val VOWEL_E = 5
        private const val VOWEL_O = 6

        private const val DO_HE = 'ھ'
        private const val GOL_HE = 'ہ'
        private const val NUN_GHUNNA = 'ں'
        private const val ALIF = 'ا'
        private const val MADDA = 'آ'
        private const val WAO = 'و'
        private const val YE = 'ی'
        private const val BARI_YE = 'ے'
        private const val HAMZA_WAO = 'ؤ'

        /** Urdu letters and signs, as [UrduRomanizer.isUrdu] counts them. */
        private fun isWordChar(c: Char): Boolean = UrduRomanizer.isUrdu(c)

        /** A canonical key and, char for char, the detail the fold threw away. */
        class Folded(val key: String, val detail: String)

        /** Folds an Urdu word to its canonical phonetic key. */
        fun foldUrdu(word: String): String = fold(urduSlots(word)).key

        /** Folds roman Urdu typing to the same canonical key. */
        fun foldRoman(input: String): Folded = fold(romanSlots(input))

        // ---- The shared middle: both scripts are read into slots, and one
        // function turns slots into a key, so the two sides cannot drift.

        private sealed class Slot

        /**
         * @param variant the key's other letter — ٹ for `t`, ق for `k`, ص for
         *        `s` — which roman spelling does not distinguish
         * @param relaxable a closing ہ or a ں, which casual spelling leaves off
         */
        private class Consonant(
            val key: Char,
            val aspirated: Boolean = false,
            val variant: Boolean = false,
            val relaxable: Boolean = false,
        ) : Slot()

        private class Vowel(val cls: Int) : Slot()

        /**
         * The slot lists a word is filed under: itself, and the same word with
         * a relaxable slot read the other way. At most two are relaxed, which is
         * all any word has.
         */
        private fun relaxations(slots: List<Slot>): List<Pair<Boolean, List<Slot>>> {
            val relaxable = slots.indices.filter { (slots[it] as? Consonant)?.relaxable == true }.take(2)
            if (relaxable.isEmpty()) return listOf(false to slots)
            val out = ArrayList<Pair<Boolean, List<Slot>>>(1 shl relaxable.size)
            for (mask in 0 until (1 shl relaxable.size)) {
                val drop = relaxable.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
                val variant = slots.filterIndexed { i, _ -> i !in drop }
                // A dropped closing ہ still said its vowel: بچہ is "bacha".
                val read = if (drop.isEmpty()) variant else variant + Vowel(VOWEL_SHORT)
                out.add((mask != 0) to read)
            }
            return out
        }

        private fun fold(slots: List<Slot>): Folded {
            val key = StringBuilder(slots.size)
            val detail = StringBuilder(slots.size + 1)
            var vowel = VOWEL_NONE
            var pair = false
            // The base is ADDED and the fields OR-ed together: '0' is 0b110000,
            // which overlaps the vowel bits, so or-ing it in would swallow them.
            fun bits(extra: Int): Char {
                val fields = extra or (if (pair) PAIR else 0) or (vowel shl VOWEL_SHIFT)
                return (DETAIL_BASE + fields).toChar()
            }
            for (slot in slots) {
                when (slot) {
                    is Vowel -> {
                        if (vowel == VOWEL_NONE) vowel = slot.cls else if (slot.cls != VOWEL_NONE) pair = true
                    }
                    is Consonant -> {
                        val doubled = key.isNotEmpty() && key.last() == slot.key && vowel == VOWEL_NONE
                        if (doubled) {
                            // One letter: Urdu doubles with a shadda nobody
                            // types, so "acha" and "accha" are one word.
                            if (slot.aspirated) {
                                val at = detail.length - 1
                                detail.setCharAt(at, (detail[at].code or ASPIRATED).toChar())
                            }
                        } else {
                            key.append(slot.key)
                            detail.append(
                                bits(
                                    (if (slot.aspirated) ASPIRATED else 0) or
                                        (if (slot.variant) VARIANT else 0),
                                ),
                            )
                        }
                        vowel = VOWEL_NONE
                        pair = false
                    }
                }
            }
            detail.append(bits(0))
            return Folded(key.toString(), detail.toString())
        }

        /**
         * The consonants, folded: key letter, whether it counts as aspirated,
         * and whether it is the key's variant letter.
         */
        private val URDU_CONSONANTS: Map<Char, Consonant> = buildMap {
            fun put(chars: String, key: Char, aspirated: Boolean = false, variant: Boolean = false) {
                for (c in chars) put(c, Consonant(key, aspirated, variant))
            }
            put("ب", 'b')
            put("پ", 'p')
            // ف and پھ are one sound to roman spelling, which writes "f" for
            // either and "ph" for either.
            put("ف", 'p', aspirated = true, variant = true)
            put("ت", 't')
            put("ٹط", 't', variant = true)
            put("د", 'd')
            put("ڈ", 'd', variant = true)
            put("س", 's')
            put("صث", 's', variant = true)
            put("ش", 's', aspirated = true)
            put("ز", 'z')
            put("ذضظ", 'z', variant = true)
            put("ژ", 'z', aspirated = true)
            put("ج", 'j')
            put("چ", 'c')
            put("ک", 'k')
            put("ق", 'k', variant = true)
            put("خ", 'k', aspirated = true)
            put("گ", 'g')
            put("غ", 'g', aspirated = true)
            put("ل", 'l')
            put("م", 'm')
            put("ن", 'n')
            put("ر", 'r')
            put("ڑ", 'r', variant = true)
            put("ح", 'h', variant = true)
        }

        private fun urduSlots(raw: String): List<Slot> {
            val word = UrduRomanizer.normalize(raw)
            val slots = ArrayList<Slot>(word.length)
            var i = 0
            while (i < word.length) {
                val c = word[i]
                val aspirated = word.getOrNull(i + 1) == DO_HE
                val consonant = URDU_CONSONANTS[c]
                when {
                    consonant != null -> {
                        slots.add(
                            Consonant(
                                consonant.key,
                                consonant.aspirated || aspirated,
                                consonant.variant,
                            ),
                        )
                        if (aspirated) i++
                    }
                    // Opening the word these are consonants — وہ, یار — and
                    // inside it the long vowels, which is how the roman side
                    // reads "w" and "y" too.
                    c == WAO && i == 0 -> slots.add(Consonant('v'))
                    c == YE && i == 0 -> slots.add(Consonant('y'))
                    c == WAO || c == HAMZA_WAO -> slots.add(Vowel(VOWEL_O))
                    c == YE -> slots.add(Vowel(VOWEL_I))
                    c == BARI_YE -> slots.add(Vowel(VOWEL_E))
                    c == ALIF || c == MADDA -> slots.add(Vowel(VOWEL_A))
                    // A gol he closing the word is as often the "a" of بچہ as it
                    // is an h, so it is filed both ways ([relaxations]).
                    c == GOL_HE -> slots.add(
                        Consonant('h', aspirated, relaxable = i == word.lastIndex),
                    )
                    c == NUN_GHUNNA -> slots.add(Consonant('n', relaxable = true))
                    // ع, ء, ئ and the diacritics are orthography, not sound.
                    else -> Unit
                }
                i++
            }
            return slots
        }

        /** Consonants an "h" after them aspirates, ڑھ of "parhna" included. */
        private const val ASPIRABLE = "bptdjkgr"

        /** Capitals that name the variant letter: ٹ ڈ ڑ ص ض ح. */
        private const val VARIANT_CAPITALS = "TDRSZH"

        private fun romanSlots(input: String): List<Slot> {
            val slots = ArrayList<Slot>(input.length)
            var i = 0
            while (i < input.length) {
                val raw = input[i]
                val c = raw.lowercaseChar()
                val next = input.getOrNull(i + 1)?.lowercaseChar()
                val doubled = next == c
                val variant = raw in VARIANT_CAPITALS
                when {
                    c == 'a' -> when (next) {
                        // "hai" is ہے and "kaise" کیسے: the pair spells one vowel.
                        'i', 'y' -> { slots.add(Vowel(VOWEL_E)); i++ }
                        // "aur" is اور — two vowel letters, which the pair bit
                        // is what records.
                        'u' -> { slots.add(Vowel(VOWEL_O)); slots.add(Vowel(VOWEL_O)); i++ }
                        else -> {
                            slots.add(Vowel(if (doubled || raw == 'A') VOWEL_A else VOWEL_SHORT))
                            if (doubled) i++
                        }
                    }
                    c == 'i' -> {
                        slots.add(Vowel(if (doubled || raw == 'I') VOWEL_I else VOWEL_SHORT))
                        if (doubled) i++
                    }
                    c == 'e' -> when (next) {
                        'e' -> { slots.add(Vowel(VOWEL_I)); i++ }
                        // "mein" is میں: the i spells the vowel, it is not a second one.
                        'i', 'y' -> { slots.add(Vowel(VOWEL_E)); i++ }
                        else -> slots.add(Vowel(VOWEL_E))
                    }
                    c == 'u' -> {
                        slots.add(Vowel(if (doubled || raw == 'U') VOWEL_U else VOWEL_SHORT))
                        if (doubled) i++
                    }
                    c == 'o' -> {
                        slots.add(Vowel(VOWEL_O))
                        if (doubled) i++
                    }
                    c == 'y' -> slots.add(if (slots.isEmpty()) Consonant('y') else Vowel(VOWEL_I))
                    c == 'w' || c == 'v' ->
                        slots.add(if (slots.isEmpty()) Consonant('v') else Vowel(VOWEL_O))
                    c == 'c' && next == 'h' -> {
                        val aspirate = input.getOrNull(i + 2)?.lowercaseChar() == 'h'
                        slots.add(Consonant('c', aspirate))
                        i += if (aspirate) 2 else 1
                    }
                    c == 's' && next == 'h' -> { slots.add(Consonant('s', aspirated = true, variant = variant)); i++ }
                    c == 'z' && next == 'h' -> { slots.add(Consonant('z', aspirated = true, variant = variant)); i++ }
                    next == 'h' && c in ASPIRABLE -> {
                        slots.add(Consonant(c, aspirated = true, variant = variant))
                        i++
                    }
                    c == 'f' -> slots.add(Consonant('p', aspirated = true, variant = true))
                    c == 'q' -> slots.add(Consonant('k', variant = true))
                    c == 'x' -> {
                        slots.add(Consonant('k'))
                        slots.add(Consonant('s'))
                    }
                    c in 'a'..'z' -> slots.add(Consonant(c, variant = variant))
                    // digits, punctuation, the apostrophe standing for ع, and
                    // any other script: nothing the key is made of
                    else -> Unit
                }
                i++
            }
            return slots
        }
    }
}
