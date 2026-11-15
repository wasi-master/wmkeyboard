package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup from romanized Hindi to dictionary words — what
 * [BengaliPhoneticIndex] is to Avro, and the half of Hindi phonetic typing
 * that actually knows Hindi.
 *
 * [HindiPhonetic] can only guess at what Latin spelling leaves out: whether
 * "rn" is a conjunct, whether a "t" is त or ट, whether an "a" is the inherent
 * vowel or आ. This does not guess. Both the typed spelling and every word of
 * the list are folded to a key in which all of that is gone —
 *
 *  - aspiration, and the dental / retroflex split: त थ ट ठ are all `t`;
 *  - श ष स are `s`, ज ज़ are `j` ("z" too), फ is `p` ("f" too), व is `v` ("w");
 *  - every nasal is `n`, written as a letter or as ं / ँ, and "m" before a
 *    labial is that same `n` ("lamba" is लंबा);
 *  - the virama, so a conjunct and a dropped schwa look alike — करना and a
 *    would-be कर्ना are both `krna`;
 *  - **every "a" inside the word**, inherent or long. Only the one that opens
 *    a word or closes it is kept. This is what lets "kam" find both कम and काम,
 *    and it is also why vowel-dropping chat spelling works unaided: "kr" is
 *    कर and "rha" is रहा;
 *  - vowel length: इ ई are `i`, उ ऊ are `u`; ऐ folds into `e` and औ into `o`,
 *    and so does an "a" running into an independent इ / उ (कई is "kai");
 *  - a doubled consonant, since "acha" and "accha" are one word
 *
 * — and words sharing a key are siblings, ranked by frequency.
 *
 * Frequency alone would hand "kaam" to कम whenever कम is the commoner word,
 * throwing away the second "a" the typist went to the trouble of typing. So
 * each entry keeps a [Folded.detail] mask, position for position with its key,
 * of what the fold discarded — aspirated or not, what kind of "a" followed,
 * long or short, diphthong or plain — and a sibling that contradicts what was
 * typed has its frequency divided. Lopsidedly, as in the Bengali index: typing
 * a detail and not getting it is a worse error than leaving one out and
 * getting it anyway, because casual spelling drops detail and never invents
 * it.
 *
 * Two habits are too common to treat as mismatches at all, so a word is filed
 * under a second key for each: ड़ typed as "r" as readily as "d" (लड़का is
 * "ladka" and "larka"), and nasalisation left off altogether (नहीं is "nahi",
 * हाँ is "ha").
 */
class HindiPhoneticIndex(
    private val table: PhoneticTable,
    /**
     * Whether a typed closing "a" may be the inherent vowel of any word, not
     * only of one closing on य, र or व ([spokenFinalA]): how Nepali and
     * Sanskrit are typed, "xa" for छ and "rAma" for राम.
     */
    private val typedSchwa: Boolean = false,
) : PhoneticIndex {

    /** An index built on the device over [entries], `word to frequency`. */
    constructor(entries: List<Pair<String, Int>>) : this(table(entries))

    override val isEmpty: Boolean get() = table.isEmpty

    override fun lookup(input: String): List<String> {
        val typed = foldRoman(input)
        val bucket = table.rows(typed.key)
        val spoken = spokenFinalA(typed)
        if (spoken.isEmpty()) {
            if (bucket.size <= 1) return bucket.map { it.word }
            return bucket
                .sortedByDescending { it.frequency.toLong() * SCALE / handicap(it, typed.detail) }
                .map { it.word }
                .distinct()
        }
        val bare = typed.detail.dropLast(1)
        val scored = bucket.map { it.word to it.frequency.toLong() * SCALE / handicap(it, typed.detail) } +
            spoken.map { it.word to it.frequency.toLong() * SCALE / (handicap(it, bare) * SPOKEN_FINAL_A) }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    /**
     * Words a closing "a" was typed for that the key has no closing "a" in:
     * "mitra" for मित्र, "satya" for सत्य, "dharma" for धर्म. Hindi writes no
     * final vowel there and says one all the same, because the word ends on a
     * conjunct that cannot be said without it. Only a conjunct closing on य, र
     * or व is like that: खर्च is said "kharch", so "kharcha" stays खर्चा, and
     * "kama" does not start finding कम. Which words end that way is decided
     * when they are filed ([FLAG_SPOKEN_A]), from their Devanagari spelling.
     *
     * With [typedSchwa] every word is like that, down to a one-letter key:
     * Nepali types "xa" for छ and "huncha" for हुन्छ.
     */
    private fun spokenFinalA(typed: Folded): List<PhoneticRow> {
        val key = typed.key
        if (key.length < (if (typedSchwa) 2 else 3) || key.last() != 'a') return emptyList()
        if ((typed.detail.last().code - DETAIL_BASE) and TAIL_MASK == TAIL_LONG) return emptyList()
        val rows = table.rows(key.dropLast(1))
        return if (typedSchwa) rows else rows.filter { it.flags and FLAG_SPOKEN_A != 0 }
    }

    override fun frequencyOf(word: String): Int = table.frequencyOf(word)

    override fun matchStrength(input: String): Int {
        val typed = foldRoman(input)
        val direct = table.rows(typed.key).maxOfOrNull { it.frequency / handicap(it, typed.detail) } ?: 0L
        val spoken = spokenFinalA(typed)
        if (spoken.isEmpty()) return direct.toInt()
        val bare = typed.detail.dropLast(1)
        return maxOf(direct, spoken.maxOf { it.frequency / (handicap(it, bare) * SPOKEN_FINAL_A) }).toInt()
    }

    override val maxFrequency: Int get() = table.maxFrequency

    /**
     * What to divide [entry]'s frequency by for disagreeing with what was
     * typed. 1 means it agrees on every count.
     */
    private fun handicap(entry: PhoneticRow, typed: String): Long {
        var divisor = if (entry.flags and FLAG_NASAL_DROPPED != 0) NASAL_DROPPED else 1L
        // Same key means same length, so the two masks line up.
        for (i in typed.indices) {
            divisor *= mismatch(typed[i].code - DETAIL_BASE, entry.detail[i].code - DETAIL_BASE, i == typed.lastIndex)
            if (divisor > MAX_HANDICAP) return MAX_HANDICAP
        }
        return divisor
    }

    private fun mismatch(typed: Int, word: Int, last: Boolean): Long {
        var divisor = 1L
        val typedAspirated = typed and ASPIRATED != 0
        val wordAspirated = word and ASPIRATED != 0
        if (typedAspirated != wordAspirated) divisor *= if (typedAspirated) TYPED_NOT_FOUND else FOUND_NOT_TYPED
        val typedDoubled = typed and DOUBLED != 0
        if (typedDoubled != (word and DOUBLED != 0)) divisor *= if (typedDoubled) TYPED_NOT_FOUND else FOUND_NOT_TYPED
        val t = typed and TAIL_MASK
        val w = word and TAIL_MASK
        if (t != w) {
            divisor *= when {
                // A long vowel or a diphthong that was spelled out and is not there.
                t == TAIL_LONG || t == TAIL_DIPHTHONG -> TYPED_NOT_FOUND
                // Length left off the last vowel of a word is not even casual,
                // it is how everyone writes "ladki".
                w == TAIL_LONG && last -> 1L
                w == TAIL_LONG && t == TAIL_NONE -> LONG_NOT_HINTED
                // One "a" typed, आ found. Casual, but dearer than the other
                // casual omissions, because here there is usually a rival that
                // matches exactly: "namak" is नमक before it is नामक.
                w == TAIL_LONG && t == TAIL_SHORT -> LONG_TYPED_SINGLE
                w == TAIL_LONG || w == TAIL_DIPHTHONG -> FOUND_NOT_TYPED
                // An "a" typed where the word has a conjunct: "dharam" for धर्म.
                t == TAIL_SHORT && w == TAIL_JOINED -> SCHWA_IN_CONJUNCT
                // No "a" against an inherent one, or against a join: exactly
                // the thing the spelling never says.
                else -> 1L
            }
        }
        return divisor
    }

    companion object {

        /** [PhoneticTable] fold id of this index's tables. */
        const val FOLD = 1

        /**
         * Version of the fold and of how words are filed. Bumped whenever either
         * changes, which makes every prebuilt table of the old one read as
         * absent (the data repo publishes the new one under a new name).
         */
        const val REVISION = 1

        /** Filed here with its nasalisation dropped, which costs it a little. */
        private const val FLAG_NASAL_DROPPED = 1

        /** Ends on a conjunct closing in य, र or व; see [spokenFinalA]. */
        private const val FLAG_SPOKEN_A = 2

        /**
         * Files [entries] by the Hindi fold. [spelling] gives the Devanagari a
         * word is folded from, for a language written in another Indic script
         * ([IndicPhoneticIndex]); the row keeps the word as the list spells it.
         */
        fun table(
            entries: List<Pair<String, Int>>,
            spelling: ((String) -> String)? = null,
        ): PhoneticTable {
            val builder = PhoneticTableBuilder(FOLD, REVISION)
            file(builder, entries, spelling)
            return builder.build()
        }

        /** Files [entries] into [builder]; see [table]. */
        fun file(builder: PhoneticTableBuilder, entries: List<Pair<String, Int>>, spelling: ((String) -> String)? = null) {
            for ((word, frequency) in entries) {
                val dev = spelling?.invoke(word) ?: word
                // Scraped lists carry tokens that are not words — है। with its
                // danda still attached outranks है in the list this was written
                // against — and the fold would file them under the real word's key,
                // where the commoner token wins and the danda gets committed.
                if (!dev.all(::isWordChar)) continue
                val slots = devanagariSlots(dev)
                if (slots.isEmpty()) continue
                val spokenA = dev.length >= 3 && dev[dev.length - 2] == VIRAMA && dev.last() in "यरव"
                val seen = HashSet<String>(4)
                for (dropNasals in BOTH) {
                    for (flapAsR in BOTH) {
                        val folded = fold(slots, dropNasals, flapAsR)
                        if (folded.key.isEmpty() || !seen.add(folded.key)) continue
                        val flags = (if (dropNasals) FLAG_NASAL_DROPPED else 0) or (if (spokenA) FLAG_SPOKEN_A else 0)
                        builder.add(folded.key, word, frequency, folded.detail, flags)
                    }
                }
                builder.addWord(word, frequency)
            }
        }

        private val BOTH = booleanArrayOf(false, true)

        private const val SCALE = 1024L
        private const val MAX_HANDICAP = 1L shl 20

        /** Typed a detail the word does not have — a deliberate request, refused. */
        private const val TYPED_NOT_FOUND = 20L

        /** Left a detail out that the word has — ordinary casual spelling. */
        private const val FOUND_NOT_TYPED = 2L

        private const val LONG_TYPED_SINGLE = 4L

        /** A closing "a" that the word says and does not write. See [spokenFinalA]. */
        private const val SPOKEN_FINAL_A = 2L

        /** The word has आ where not even a single "a" was typed. */
        private const val LONG_NOT_HINTED = 8L

        private const val SCHWA_IN_CONJUNCT = 3L
        private const val NASAL_DROPPED = 3L

        // One char of [Folded.detail] per key char: an aspiration bit, and
        // what followed the consonant (or what kind of vowel this is).
        private const val DETAIL_BASE = '0'.code
        private const val ASPIRATED = 1
        private const val TAIL_MASK = 7 shl 1
        private const val TAIL_NONE = 0 shl 1
        private const val TAIL_SHORT = 1 shl 1
        private const val TAIL_LONG = 2 shl 1
        private const val TAIL_JOINED = 3 shl 1
        private const val TAIL_DIPHTHONG = 4 shl 1

        /** The consonant was written twice: पत्ता against पता. */
        private const val DOUBLED = 1 shl 4

        private const val VIRAMA = '्'
        private const val NUKTA = '़'
        private const val ANUSVARA = 'ं'
        private const val CANDRABINDU = 'ँ'

        /** Devanagari letters and signs, and the joiners: not its digits, not the danda. */
        private fun isWordChar(c: Char): Boolean =
            c.code in 0x0900..0x0963 || c.code in 0x0970..0x097F || c == '‍' || c == '‌'

        /** A canonical key and, char for char, the detail the fold threw away. */
        class Folded(val key: String, val detail: String)

        /** Folds a Devanagari word to its canonical phonetic key. */
        fun foldDevanagari(word: String): String = fold(devanagariSlots(word), dropNasals = false, flapAsR = false).key

        /** Folds romanized Hindi typing to the same canonical key. */
        fun foldRoman(input: String): Folded = fold(romanSlots(input), dropNasals = false, flapAsR = false)

        // ---- The shared middle: both scripts are read into slots, and one
        // function turns slots into a key. Whatever the fold decides, it decides
        // once, so the two sides cannot drift apart.

        private sealed class Slot

        /** @param flap ड़ / ढ़, which is `d` under one key and `r` under the other */
        private class Consonant(val key: Char, val aspirated: Boolean, val flap: Boolean = false) : Slot() {
            var joined = false
        }

        /** Any "a": inherent-made-explicit (roman only), अ, आ or ा. */
        private class AVowel(val long: Boolean) : Slot()

        /** @param independent a letter of its own, not a matra on the consonant before */
        private class Vowel(val key: Char, val long: Boolean, val diphthong: Boolean, val independent: Boolean) : Slot()

        /** @param droppable ँ anywhere, or ं closing the word: the ones chat spelling leaves off */
        private class Nasal(var droppable: Boolean) : Slot()

        private fun fold(all: List<Slot>, dropNasals: Boolean, flapAsR: Boolean): Folded {
            val slots = if (dropNasals) all.filterNot { it is Nasal && it.droppable } else all
            val key = StringBuilder(slots.size)
            val detail = StringBuilder(slots.size)
            fun tail(bits: Int) {
                if (detail.isEmpty()) return
                val at = detail.length - 1
                val code = detail[at].code - DETAIL_BASE
                detail.setCharAt(at, (DETAIL_BASE + ((code and TAIL_MASK.inv()) or bits)).toChar())
            }
            fun emit(k: Char, bits: Int) {
                key.append(k)
                detail.append((DETAIL_BASE + bits).toChar())
            }
            var i = 0
            while (i < slots.size) {
                val slot = slots[i]
                val next = slots.getOrNull(i + 1)
                when (slot) {
                    is Consonant -> {
                        var k = if (slot.flap && flapAsR) 'r' else slot.key
                        if (k == 'm' && next is Consonant && next.key in "pb") k = 'n'
                        val bits = (if (slot.aspirated) ASPIRATED else 0) or (if (slot.joined) TAIL_JOINED else TAIL_NONE)
                        if (key.isNotEmpty() && key.last() == k && slots[i - 1] is Consonant) {
                            // A doubled consonant is one: keep whichever half
                            // was aspirated, and what follows the second.
                            val before = detail.last().code - DETAIL_BASE
                            detail.setCharAt(
                                detail.length - 1,
                                (DETAIL_BASE + ((before and ASPIRATED) or bits or DOUBLED)).toChar(),
                            )
                        } else {
                            emit(k, bits)
                        }
                    }
                    is Nasal -> emit('n', TAIL_NONE)
                    is AVowel -> {
                        // An "a" running into an independent इ / उ is ऐ / औ.
                        if (next is Vowel && next.independent && next.key in "iu") {
                            emit(if (next.key == 'i') 'e' else 'o', TAIL_DIPHTHONG)
                            i += 2
                            continue
                        }
                        when {
                            key.isEmpty() || i == slots.lastIndex -> emit('a', if (slot.long) TAIL_LONG else TAIL_NONE)
                            else -> tail(if (slot.long) TAIL_LONG else TAIL_SHORT)
                        }
                    }
                    is Vowel -> {
                        val glides = slot.independent && slot.key in "iu" && slots.getOrNull(i - 1) is Consonant
                        when {
                            // कई: the inherent vowel running into ई.
                            glides -> emit(if (slot.key == 'i') 'e' else 'o', TAIL_DIPHTHONG)
                            slot.diphthong -> emit(slot.key, TAIL_DIPHTHONG)
                            else -> emit(slot.key, if (slot.long) TAIL_LONG else TAIL_NONE)
                        }
                    }
                }
                i++
            }
            return Folded(key.toString(), detail.toString())
        }

        private val CONSONANTS: Map<Char, Consonant> = buildMap {
            fun put(chars: String, key: Char, aspirated: Boolean = false) {
                for (c in chars) put(c, Consonant(key, aspirated))
            }
            put("क", 'k'); put("ख", 'k', true)
            put("ग", 'g'); put("घ", 'g', true)
            put("च", 'c'); put("छ", 'c', true)
            put("ज", 'j'); put("झ", 'j', true)
            put("टत", 't'); put("ठथ", 't', true)
            put("डद", 'd'); put("ढध", 'd', true)
            put("णनङञ", 'n')
            put("प", 'p'); put("फ", 'p', true)
            put("ब", 'b'); put("भ", 'b', true)
            put("म", 'm'); put("य", 'y'); put("र", 'r'); put("ल", 'l'); put("ळ", 'l')
            put("व", 'v'); put("शषस", 's'); put("ह", 'h')
            // The letters Devanagari encodes for the Dravidian scripts, which
            // reach this fold through IndicPhoneticIndex: Tamil ன and ற, and ழ,
            // which roman spells "zh" or "l" and is folded as l. No Hindi word
            // holds any of them.
            put("ऩ", 'n'); put("ऱ", 'r'); put("ऴ", 'l')
        }

        /** Precomposed nukta letters (U+0958–U+095F), should a list carry them. */
        private val PRECOMPOSED = mapOf(
            'क़' to "क", 'ख़' to "ख", 'ग़' to "ग", 'ज़' to "ज",
            'ड़' to "ड़", 'ढ़' to "ढ़", 'फ़' to "फ", 'य़' to "य",
        )

        private fun devanagariSlots(raw: String): List<Slot> {
            val word = if (raw.any { it in PRECOMPOSED }) {
                buildString { for (c in raw) append(PRECOMPOSED[c] ?: c) }
            } else {
                raw
            }
            val slots = ArrayList<Slot>(word.length)
            var i = 0
            while (i < word.length) {
                val c = word[i]
                val consonant = CONSONANTS[c]
                when {
                    consonant != null -> {
                        val flap = (c == 'ड' || c == 'ढ') && word.getOrNull(i + 1) == NUKTA
                        // ज्ञ is said, and so typed, "gy".
                        if (c == 'ज' && word.getOrNull(i + 1) == VIRAMA && word.getOrNull(i + 2) == 'ञ') {
                            slots.add(Consonant('g', false).also { it.joined = true })
                            slots.add(Consonant('y', false))
                            i += 3
                            continue
                        }
                        slots.add(Consonant(consonant.key, consonant.aspirated, flap))
                    }
                    c == VIRAMA -> (slots.lastOrNull() as? Consonant)?.joined = true
                    c == 'अ' -> slots.add(AVowel(long = false))
                    c == 'आ' || c == 'ा' -> slots.add(AVowel(long = true))
                    c == 'इ' || c == 'ई' -> slots.add(Vowel('i', c == 'ई', diphthong = false, independent = true))
                    c == 'ि' || c == 'ी' -> slots.add(Vowel('i', c == 'ी', diphthong = false, independent = false))
                    c == 'उ' || c == 'ऊ' -> slots.add(Vowel('u', c == 'ऊ', diphthong = false, independent = true))
                    c == 'ु' || c == 'ू' -> slots.add(Vowel('u', c == 'ू', diphthong = false, independent = false))
                    c == 'ए' || c == 'े' -> slots.add(Vowel('e', long = false, diphthong = false, independent = c == 'ए'))
                    // The Dravidian short e and o, which length aside are the
                    // same vowels to a romanized spelling.
                    c == 'ऎ' || c == 'ॆ' -> slots.add(Vowel('e', long = false, diphthong = false, independent = c == 'ऎ'))
                    c == 'ऒ' || c == 'ॊ' -> slots.add(Vowel('o', long = false, diphthong = false, independent = c == 'ऒ'))
                    c == 'ऐ' || c == 'ै' -> slots.add(Vowel('e', long = false, diphthong = true, independent = c == 'ऐ'))
                    c == 'ओ' || c == 'ो' -> slots.add(Vowel('o', long = false, diphthong = false, independent = c == 'ओ'))
                    c == 'औ' || c == 'ौ' -> slots.add(Vowel('o', long = false, diphthong = true, independent = c == 'औ'))
                    c == 'ऋ' || c == 'ृ' -> {
                        slots.add(Consonant('r', false))
                        slots.add(Vowel('i', long = false, diphthong = false, independent = false))
                    }
                    c == ANUSVARA -> slots.add(Nasal(droppable = false))
                    c == CANDRABINDU -> slots.add(Nasal(droppable = true))
                    // nukta, visarga, ZWJ / ZWNJ and anything unmapped fold away
                }
                i++
            }
            // An anusvara that closes the word is nasalisation, not a consonant,
            // and goes missing from casual spelling as readily as ँ does.
            (slots.lastOrNull() as? Nasal)?.droppable = true
            return slots
        }

        private const val ASPIRABLE = "kgcjtdpb"

        private fun romanSlots(input: String): List<Slot> {
            val slots = ArrayList<Slot>(input.length)
            var i = 0
            while (i < input.length) {
                val raw = input[i]
                val c = raw.lowercaseChar()
                val next = input.getOrNull(i + 1)?.lowercaseChar()
                when {
                    raw == 'M' -> slots.add(Nasal(droppable = false))
                    raw == 'H' -> Unit
                    c == 'a' -> {
                        var j = i
                        while (j < input.length && input[j].lowercaseChar() == 'a') j++
                        slots.add(AVowel(long = j - i > 1 || raw == 'A'))
                        i = j
                        continue
                    }
                    c == 'i' -> {
                        val doubled = next == 'i'
                        // Straight after an "a" it is the second half of "ai".
                        slots.add(Vowel('i', doubled || raw == 'I', diphthong = false, independent = slots.lastOrNull() is AVowel))
                        if (doubled) i++
                    }
                    c == 'u' -> {
                        val doubled = next == 'u'
                        slots.add(Vowel('u', doubled || raw == 'U', diphthong = false, independent = slots.lastOrNull() is AVowel))
                        if (doubled) i++
                    }
                    c == 'e' -> when (next) {
                        'e' -> { slots.add(Vowel('i', long = true, diphthong = false, independent = false)); i++ }
                        // "mein" is में: the "i" is how the vowel is spelled, not a second one.
                        'i' -> { slots.add(Vowel('e', long = false, diphthong = false, independent = false)); i++ }
                        else -> slots.add(Vowel('e', long = false, diphthong = false, independent = false))
                    }
                    c == 'o' -> when (next) {
                        'o' -> { slots.add(Vowel('u', long = true, diphthong = false, independent = false)); i++ }
                        'u' -> { slots.add(Vowel('o', long = false, diphthong = true, independent = false)); i++ }
                        else -> slots.add(Vowel('o', long = false, diphthong = false, independent = false))
                    }
                    c == 'c' && next == 'h' -> {
                        // "ch" is च and "chh" is छ; plenty of people write छ as
                        // "ch" too, which the lopsided handicap already forgives.
                        val aspirated = input.getOrNull(i + 2)?.lowercaseChar() == 'h'
                        slots.add(Consonant('c', aspirated))
                        i += if (aspirated) 3 else 2
                        continue
                    }
                    c == 's' && next == 'h' -> { slots.add(Consonant('s', false)); i++ }
                    next == 'h' && c in ASPIRABLE -> { slots.add(Consonant(c, true)); i++ }
                    c == 'f' -> slots.add(Consonant('p', true))
                    c == 'q' -> slots.add(Consonant('k', false))
                    c == 'z' -> slots.add(Consonant('j', false))
                    c == 'w' -> slots.add(Consonant('v', false))
                    c == 'x' -> {
                        slots.add(Consonant('k', false))
                        slots.add(Consonant('s', false))
                    }
                    c in 'a'..'z' -> slots.add(Consonant(c, false))
                    // digits, punctuation and other scripts fold away
                }
                i++
            }
            return slots
        }
    }
}
