package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Extended Wylie in, Tibetan out ("bkra shis" → བཀྲ་ཤིས), written from the published
 * EWTS scheme and checked against pyewts. A space is a tsheg and `/` a shad, so plain
 * Wylie text transliterates whole. Which letters of a syllable stack is worked out by the
 * same rules a reader uses: the prefix, the superscripts and the subscripts Tibetan
 * allows. `.` keeps two letters apart (g.yag) and `+` stacks them regardless (pad+ma).
 */
object WylieTibetan {

    const val TSHEG = "་"
    private const val SHAD = "།"
    private const val DOUBLE_SHAD = "༎"

    /** Each consonant's full form; its subjoined form is 0x50 on, but for the four below. */
    private val CONSONANTS = mapOf(
        "k" to 0x0F40, "kh" to 0x0F41, "g" to 0x0F42, "ng" to 0x0F44, "c" to 0x0F45, "ch" to 0x0F46, "j" to 0x0F47, "ny" to 0x0F49,
        "t" to 0x0F4F, "th" to 0x0F50, "d" to 0x0F51, "n" to 0x0F53, "p" to 0x0F54, "ph" to 0x0F55, "b" to 0x0F56, "m" to 0x0F58,
        "ts" to 0x0F59, "tsh" to 0x0F5A, "dz" to 0x0F5B, "w" to 0x0F5D, "zh" to 0x0F5E, "z" to 0x0F5F, "'" to 0x0F60, "y" to 0x0F61,
        "r" to 0x0F62, "l" to 0x0F63, "sh" to 0x0F64, "s" to 0x0F66, "h" to 0x0F67,
        "T" to 0x0F4A, "Th" to 0x0F4B, "D" to 0x0F4C, "N" to 0x0F4E, "Sh" to 0x0F65, "f" to 0x0F55, "v" to 0x0F56,
    )

    private val SUBJOINED_FIXED = mapOf("w" to 0x0FAD, "y" to 0x0FB1, "r" to 0x0FB2, "l" to 0x0FB3, "'" to 0x0FB0)

    private val VOWELS = mapOf(
        "a" to "", "i" to "ི", "u" to "ུ", "e" to "ེ", "o" to "ོ", "ai" to "ཻ", "au" to "ཽ",
        "A" to "ཱ", "I" to "ཱི", "U" to "ཱུ", "-i" to "ྀ", "-I" to "ཱྀ",
    )

    private val SIGNS = mapOf("M" to "ཾ", "~M" to "ྃ", "H" to "ཿ")

    private val PREFIXES = setOf("g", "d", "b", "m", "'")
    private val OVER_R = setOf("k", "g", "ng", "j", "ny", "t", "d", "n", "b", "m", "ts", "dz")
    private val OVER_L = setOf("k", "g", "ng", "c", "j", "t", "d", "p", "b", "h")
    private val OVER_S = setOf("k", "g", "ng", "ny", "t", "d", "n", "p", "b", "m", "ts")
    private val UNDER_Y = setOf("k", "kh", "g", "p", "ph", "b", "m", "h")
    private val UNDER_R = setOf("k", "kh", "g", "t", "th", "d", "n", "p", "ph", "b", "m", "sh", "s", "h", "dz")
    private val UNDER_L = setOf("k", "g", "b", "z", "r", "s")
    private val UNDER_W = setOf(
        "k", "kh", "g", "ny", "d", "ts", "tsh", "zh", "z", "r", "l", "sh", "s", "h", "c", "t", "n", "ph",
    )

    private val LONGEST_CONSONANT = CONSONANTS.keys.maxOf { it.length }

    /** A Tibetan letter or sign, which is what a word has to hold to be worth remembering. */
    fun isTibetan(c: Char): Boolean = c in 'ༀ'..'࿿'

    /** Transliterates Wylie, one syllable or free text. */
    fun transliterate(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == ' ' -> {
                    // After a shad a space is a space.
                    out.append(if (out.endsWith(SHAD) || out.endsWith(DOUBLE_SHAD)) " " else TSHEG)
                    i++
                }
                c == '/' -> {
                    if (input.getOrNull(i + 1) == '/') {
                        out.append(DOUBLE_SHAD)
                        i += 2
                    } else {
                        out.append(SHAD)
                        i++
                    }
                }
                c in '0'..'9' -> {
                    out.append((0x0F20 + (c - '0')).toChar())
                    i++
                }
                startsLetter(input, i) -> {
                    var end = i
                    while (end < input.length && (startsLetter(input, end) || input[end] in "+.~-")) end++
                    out.append(syllable(input.substring(i, end)))
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun startsLetter(text: String, at: Int): Boolean {
        val c = text[at]
        return c == '\'' || c == '+' || c == '.' || c == '~' || c == '-' || c in 'a'..'z' || c in 'A'..'Z'
    }

    private fun consonantAt(text: String, at: Int): String? {
        for (length in minOf(LONGEST_CONSONANT, text.length - at) downTo 1) {
            val piece = text.substring(at, at + length)
            if (piece in CONSONANTS) return piece
        }
        return null
    }

    private fun vowelAt(text: String, at: Int): String? {
        for (length in minOf(2, text.length - at) downTo 1) {
            val piece = text.substring(at, at + length)
            if (piece in VOWELS) return piece
        }
        return null
    }

    /** One run of letters between spaces: stacks ended by their vowels, and the letters after the last. */
    private fun syllable(text: String): String {
        val out = StringBuilder(text.length)
        val run = ArrayList<String>()
        val forced = ArrayList<Boolean>()
        var nextForced = false
        var sawVowel = false
        var i = 0
        fun endsAt(vowel: String?) {
            stack(run, forced, vowel, sawVowel, out)
            run.clear()
            forced.clear()
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '+') {
                nextForced = true
                i++
                continue
            }
            if (c == '.') {
                // Everything before the dot stands alone: g.yag.
                run.forEach { out.appendCodePoint(CONSONANTS.getValue(it)) }
                run.clear()
                forced.clear()
                i++
                continue
            }
            val sign = SIGNS.keys.firstOrNull { text.startsWith(it, i) }
            if (sign != null) {
                endsAt(null)
                out.append(SIGNS.getValue(sign))
                i += sign.length
                continue
            }
            val vowel = vowelAt(text, i)
            if (vowel != null) {
                endsAt(vowel)
                sawVowel = true
                i += vowel.length
                continue
            }
            val consonant = consonantAt(text, i)
            if (consonant == null) {
                endsAt(null)
                out.append(c)
                i++
                continue
            }
            run += consonant
            forced += nextForced
            nextForced = false
            i += consonant.length
        }
        endsAt(null)
        return out.toString()
    }

    /** Writes [run], the consonants before [vowel] (or after the syllable's vowel, when [afterVowel] and it has none). */
    private fun stack(run: List<String>, forced: List<Boolean>, vowel: String?, afterVowel: Boolean, out: StringBuilder) {
        if (run.isEmpty()) {
            if (vowel != null) {
                // A vowel carried by nothing sits on the letter a.
                out.appendCodePoint(0x0F68)
                out.append(VOWELS.getValue(vowel))
            }
            return
        }
        if (vowel == null && afterVowel) {
            // Suffixes each stand alone, unless the writer said otherwise.
            run.forEachIndexed { index, letter -> out.appendCodePoint(if (forced[index]) subjoined(letter) else CONSONANTS.getValue(letter)) }
            return
        }
        val start = stackStart(run, forced)
        for (index in 0 until start) out.appendCodePoint(CONSONANTS.getValue(run[index]))
        out.appendCodePoint(CONSONANTS.getValue(run[start]))
        for (index in start + 1 until run.size) out.appendCodePoint(subjoined(run[index]))
        if (vowel != null) out.append(VOWELS.getValue(vowel))
    }

    /** Where the stack starts in [run]: past a prefix, and past nothing if the letters can't stand as one. */
    private fun stackStart(run: List<String>, forced: List<Boolean>): Int {
        if (run.size == 1) return 0
        if (forced.drop(1).all { it }) return 0
        var end = run.lastIndex
        // A subscript the letter above can take.
        if (end >= 1 && underOk(run[end - 1], run[end])) end--
        // A superscript over what is left.
        var top = end
        if (top >= 1 && overOk(run[top - 1], run[top])) top--
        // Whatever comes before that is a prefix, if it can be one.
        if (top == 1 && run[0] in PREFIXES) return 1
        if (top == 0) return 0
        // Letters no rule accounts for, while the syllable is still being typed: stack from the last that fits.
        return top
    }

    private fun overOk(over: String, under: String): Boolean = when (over) {
        "r" -> under in OVER_R
        "l" -> under in OVER_L
        "s" -> under in OVER_S
        else -> false
    }

    private fun underOk(above: String, under: String): Boolean = when (under) {
        "y" -> above in UNDER_Y
        "r" -> above in UNDER_R
        "l" -> above in UNDER_L
        "w" -> above in UNDER_W
        else -> false
    }

    private fun subjoined(letter: String): Int = SUBJOINED_FIXED[letter] ?: (CONSONANTS.getValue(letter) + 0x50)
}
