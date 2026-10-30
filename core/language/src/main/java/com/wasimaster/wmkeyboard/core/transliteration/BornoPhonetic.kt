package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Borno Phonetic: the phonetic layout of Codepotro's Borno keyboard, written
 * here from how it behaves. No code, table or data is taken from Borno, whose
 * parser is GPL. Which keys write which letters follows Borno's published keymap
 * and its parser's behaviour; which consonants join is the list in [JOINS].
 *
 * It differs from Avro in the keys people notice: `c` is চ and `ch` is ছ, `z` is
 * য, `y` and `w` after a consonant are য-ফলা and ব-ফলা, `kkh` is ক্ষ, `gg` is জ্ঞ,
 * `A` or `xx` is চন্দ্রবিন্দু, and the reph takes `rr` (`korrm` is কর্ম, `korm` is
 * করম).
 *
 * Letters not named in either case read as their lowercase key: `K` is ক, but
 * `T` and `t` differ.
 */
object BornoPhonetic {

    private const val HASANT = "্"
    private const val ZWNJ = "‌"
    private const val ZWJ = "‍"
    private const val NUKTA = "়"

    private class Vowel(val letter: String, val sign: String)

    private val VOWELS = mapOf(
        "o" to Vowel("অ", ""),
        "a" to Vowel("আ", "া"),
        "i" to Vowel("ই", "ি"),
        "I" to Vowel("ঈ", "ী"),
        "ee" to Vowel("ঈ", "ী"),
        "u" to Vowel("উ", "ু"),
        "oo" to Vowel("উ", "ু"),
        "U" to Vowel("ঊ", "ূ"),
        "rri" to Vowel("ঋ", "ৃ"),
        "e" to Vowel("এ", "ে"),
        "OI" to Vowel("ঐ", "ৈ"),
        "O" to Vowel("ও", "ো"),
        "OU" to Vowel("ঔ", "ৌ"),
    )

    private val CONSONANTS = mapOf(
        "k" to "ক", "kh" to "খ", "g" to "গ", "gh" to "ঘ", "Ng" to "ঙ",
        "c" to "চ", "ch" to "ছ", "j" to "জ", "jh" to "ঝ", "NG" to "ঞ",
        "T" to "ট", "Th" to "ঠ", "D" to "ড", "Dh" to "ঢ", "N" to "ণ",
        "t" to "ত", "th" to "থ", "d" to "দ", "dh" to "ধ", "n" to "ন",
        "p" to "প", "ph" to "ফ", "f" to "ফ", "b" to "ব", "bh" to "ভ", "v" to "ভ",
        "m" to "ম", "l" to "ল",
        "sh" to "শ", "S" to "শ", "Sh" to "ষ", "SH" to "ষ", "s" to "স", "h" to "হ",
        "R" to "ড়", "Rh" to "ঢ়", "Y" to "য়", "q" to "ক",
        "kkh" to "ক্ষ", "kx" to "ক্ষ", "gg" to "জ্ঞ", "Ngx" to "ঙ্ক্ষ", "ngh" to "ঙ্ঘ", "ngH" to "ংহ",
    )

    /** Written as they are, with nothing joining on after them. */
    private val MARKS = mapOf(
        "X" to "ৎ", "TH" to "ৎ", "t``" to "ৎ",
        "::" to "ঃ", "ff" to "ঃ", "HH" to "ঃ",
        "^" to "ঁ", "xx" to "ঁ", "A" to "ঁ",
    )

    /** The hasant, and the joiners that shape the conjunct it makes. */
    private val BREAKS = mapOf(",," to HASANT, ",." to HASANT + ZWNJ, ",.." to HASANT + ZWJ)

    /** A vowel's sign whatever stands before it, and marks written as their own character. */
    private val FORCED = mapOf(
        "a`" to "া", "i`" to "ি", "I`" to "ী", "u`" to "ু", "U`" to "ূ", "e`" to "ে", "o`" to "",
        "O`" to "ো", "OI`" to "ৈ", "OU`" to "ৌ", "rri`" to "ৃ", "^`" to "^", ":`" to ":",
    )

    /** Punctuation and digits, which end whatever cluster was being built. */
    private val PLAIN: Map<String, String> = buildMap {
        put("`", "")
        put("``", "`")
        put(".", "।")
        put("..", "।।")
        put("...", "...")
        put("$", "৳")
        put("$$", "$")
        put("$%", "₹")
        "০১২৩৪৫৬৭৮৯".forEachIndexed { digit, bangla ->
            put(digit.toString(), bangla.toString())
            put(".$digit", ".$bangla")
        }
    }

    /** The other keys a token can be; the rules in [parse] say what each writes. */
    private val RULED = setOf("r", "rr", "y", "z", "w", "x", "ng", "./", "/")

    /** The longest key any table holds. */
    private const val LONGEST_KEY = 4

    /** Which consonants take which after them with no hasant typed. */
    private val JOINS: Map<Char, String> = mapOf(
        'ক' to "কটতমলসশষ",
        'গ' to "ধণনমল",
        'ঘ' to "ন",
        'ঙ' to "কখগঘম",
        'চ' to "চছঞ",
        'জ' to "জঝঞ",
        'ঞ' to "চছজঝ",
        'ট' to "টম",
        'ড' to "ড",
        'ণ' to "টঠডঢণনম",
        'ত' to "তথনম",
        'দ' to "গঘদধভম",
        'ধ' to "নম",
        'ন' to "টঠডতথদধনমস",
        'প' to "টতনপলস",
        'ফ' to "ল",
        'ব' to "জদধবল",
        'ভ' to "ল",
        'ম' to "থনপফবভমল",
        'ল' to "কখগঘটডপফবভধমল",
        'শ' to "চছতনমল",
        'ষ' to "কটঠণপফম",
        'স' to "কখটতথনপফমল",
        'হ' to "ণনমল",
    )

    /** `n` in front of these is the nasal of their own row: `nk` is ঙ্ক, `nj` is ঞ্জ. */
    private val ROW_NASAL = mapOf('ক' to 'ঙ', 'খ' to 'ঙ', 'চ' to 'ঞ', 'ছ' to 'ঞ', 'জ' to 'ঞ', 'ঝ' to 'ঞ')

    /** Stands for the consonant before a phala: another may still sign it, and nothing joins it. */
    private const val AFTER_PHALA = '্'

    /** What the text so far ends on, which decides what the next key writes. */
    private enum class Before { WORD_START, CONSONANT, VOWEL, MARK, OTHER }

    /**
     * One romanized word (or free text) in Bangla. Anything no table knows stays
     * as it is. Backslashes keep Latin: `facebook\\` is `facebook`, one of them is
     * itself, and past that each pair is one backslash, an odd run also keeping
     * what stands before it in Latin.
     */
    fun transliterate(input: String): String {
        val at = input.indexOf('\\')
        if (at < 0) return parse(input)
        var end = at
        while (end < input.length && input[end] == '\\') end++
        val run = end - at
        val left = input.substring(0, at)
        val latin = if (run <= 4) run == 2 || run == 3 else run % 2 == 1
        val kept = when (run) {
            1, 3 -> 1
            2 -> 0
            4 -> 2
            else -> run / 2
        }
        return (if (latin) left else parse(left)) + "\\".repeat(kept) + transliterate(input.substring(end))
    }

    private fun parse(input: String): String {
        val out = StringBuilder(input.length * 2)
        var before = Before.WORD_START
        // The letter a next consonant could join, or null when nothing can.
        var last: Char? = null
        // Whether the cluster has joined once already; it joins no further but for [THIRD].
        var joined = false
        // Whether [last] is a ra-phala rather than a written র.
        var raPhala = false

        fun consonant(text: String) {
            out.append(text)
            before = Before.CONSONANT
            last = text.last()
            raPhala = false
        }

        fun ended(state: Before) {
            before = state
            last = null
            joined = false
            raPhala = false
        }

        val tokens = tokens(input)
        var i = 0
        while (i < tokens.size) {
            val key = tokens[i++]
            val next = tokens.getOrNull(i)
            val vowel = VOWELS[key]
            when {
                vowel != null -> {
                    when (before) {
                        Before.CONSONANT -> out.append(vowel.sign)
                        Before.VOWEL -> out.append(if (key == "o") "ও" else vowel.letter)
                        else -> out.append(vowel.letter)
                    }
                    ended(Before.VOWEL)
                }
                key == " " -> {
                    out.append(' ')
                    ended(Before.WORD_START)
                }
                key == "r" -> {
                    if (before == Before.CONSONANT && last != null) {
                        out.append(HASANT).append('র')
                        last = 'র'
                        raPhala = true
                    } else {
                        consonant("র")
                    }
                    before = Before.CONSONANT
                }
                key == "rr" -> {
                    if (before == Before.CONSONANT && last != null) {
                        out.append(HASANT).append("রর")
                        before = Before.CONSONANT
                        last = 'র'
                    } else if (next != null && next !in VOWELS && next != "r" && next != "R") {
                        // A reph sits on what follows, so nothing is left to join.
                        out.append("র্")
                        ended(Before.OTHER)
                    } else {
                        consonant("রর")
                    }
                }
                key == "y" || key == "z" -> {
                    when {
                        before != Before.CONSONANT || last == null -> consonant(if (key == "y") "য়" else "য")
                        // র‍্য: a written র keeps its shape in front of the য-ফলা.
                        last == 'র' && !raPhala -> {
                            out.append(ZWJ).append(HASANT).append('য')
                            last = AFTER_PHALA
                        }
                        else -> {
                            out.append(HASANT).append('য')
                            last = AFTER_PHALA
                        }
                    }
                }
                key == "w" -> {
                    when {
                        before == Before.CONSONANT && last != null -> {
                            out.append(HASANT).append('ব')
                            last = AFTER_PHALA
                        }
                        before == Before.MARK -> consonant("ব")
                        next == "a" || next == "e" -> {
                            out.append(if (next == "a") "ওয়া" else "ওয়ে")
                            i++
                            ended(Before.VOWEL)
                        }
                        else -> {
                            out.append('ও')
                            ended(Before.VOWEL)
                        }
                    }
                }
                key == "x" -> consonant(if (before == Before.WORD_START) "এক্স" else "ক্স")
                key == "ng" -> {
                    val sign = next?.let { VOWELS[it] }
                    if (sign != null) {
                        i++
                        out.append("ঙ্গ").append(sign.sign)
                        ended(Before.VOWEL)
                    } else {
                        out.append('ং')
                        ended(Before.MARK)
                    }
                }
                key == "./" || key == "/" -> {
                    if (before == Before.CONSONANT) out.append(NUKTA) else out.append(key)
                    if (before != Before.CONSONANT) ended(Before.OTHER)
                }
                key in BREAKS -> {
                    out.append(BREAKS.getValue(key))
                    // A vowel after it is still a sign; a consonant joins by the hasant alone.
                    before = Before.CONSONANT
                    last = null
                    joined = false
                }
                key in MARKS -> {
                    out.append(MARKS.getValue(key))
                    ended(Before.MARK)
                }
                key in FORCED -> {
                    out.append(FORCED.getValue(key))
                    ended(Before.VOWEL)
                }
                key in CONSONANTS -> {
                    val text = CONSONANTS.getValue(key)
                    val head = text.first()
                    val previous = last
                    val nasal = if (previous == 'ন') ROW_NASAL[head] else null
                    when {
                        previous == null || before != Before.CONSONANT -> joined = false
                        joined && !joinsThird(out, head) -> joined = false
                        nasal != null -> {
                            out.setCharAt(out.length - 1, nasal)
                            out.append(HASANT)
                            joined = true
                        }
                        JOINS[previous]?.contains(head) == true -> {
                            out.append(HASANT)
                            joined = true
                        }
                        else -> joined = false
                    }
                    consonant(text)
                }
                key in PLAIN -> {
                    out.append(PLAIN.getValue(key))
                    ended(Before.OTHER)
                }
                else -> {
                    out.append(key)
                    ended(Before.OTHER)
                }
            }
        }
        return out.toString()
    }

    /** The two clusters a third consonant joins: স্ক্ল and স্প্ল. */
    private fun joinsThird(out: CharSequence, head: Char): Boolean =
        head == 'ল' && (out.endsWith("স্ক") || out.endsWith("স্প"))

    private fun tokens(input: String): List<String> {
        val out = ArrayList<String>()
        var at = 0
        while (at < input.length) {
            val token = tokenAt(input, at)
            if (token == null) {
                out += input[at].toString()
                at++
            } else {
                out += token.key
                at += token.length
            }
        }
        return out
    }

    private class Token(val key: String, val length: Int)

    /**
     * The longest key at [at]: as typed, and only then with its capitals folded,
     * so `Sh` stays ষ while `KH` still reads as `kh`.
     */
    private fun tokenAt(input: String, at: Int): Token? {
        for (length in minOf(LONGEST_KEY, input.length - at) downTo 1) {
            val typed = input.substring(at, at + length)
            if (known(typed)) return Token(typed, length)
            val folded = typed.lowercase()
            if (folded != typed && known(folded)) return Token(folded, length)
        }
        return null
    }

    private fun known(key: String): Boolean =
        key in RULED || key in VOWELS || key in CONSONANTS || key in MARKS || key in BREAKS ||
            key in FORCED || key in PLAIN
}
