package com.wasimaster.wmkeyboard.core.voice.speech

/**
 * English text made sayable for Kokoro, whose lexicon has words and letters
 * but no digits: a word it lacks is skipped, so "12" would come out as
 * nothing. Numbers become words before the text reaches the model:
 * cardinals ("1,250" → "one thousand two hundred fifty"), decimals
 * ("3.5" → "three point five"), ordinals ("3rd" → "third"), years
 * ("1999" → "nineteen ninety-nine"), money ("$12.50" → "twelve dollars
 * fifty cents") and percentages. A word written in camel case or with
 * capitals run together ("WMKeyboard") is split where its case changes, so
 * the lexicon finds its parts and letters.
 *
 * Chinese text passes through: Kokoro's own rule FSTs read numbers there.
 */
object KokoroText {

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SCALES = arrayOf("", "thousand", "million", "billion", "trillion")

    private val ORDINAL_WORDS = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth",
        "nine" to "ninth", "twelve" to "twelfth",
    )

    private val MONEY = Regex("""([$£€])\s?(\d[\d,]*)(?:\.(\d{1,2}))?""")
    private val PERCENT = Regex("""(\d[\d,]*(?:\.\d+)?)\s?%""")
    private val ORDINAL = Regex("""\b(\d+)(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val DECIMAL = Regex("""\b(\d[\d,]*)\.(\d+)\b""")
    private val NUMBER = Regex("""\b\d[\d,]*\b""")
    private val CASE_SPLIT = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
    private val MIXED_WORD = Regex("""\b[A-Za-z]*[a-z][A-Z][A-Za-z]*\b|\b[A-Z]{2,}[a-z]+\w*\b""")

    /** [text] with its numbers in words and its run-together words split. */
    fun normalize(text: String): String {
        var s = text
        s = MONEY.replace(s) { m ->
            val whole = m.groupValues[2].replace(",", "").toLongOrNull() ?: return@replace m.value
            val cents = m.groupValues[3].takeIf { it.isNotEmpty() }?.padEnd(2, '0')?.toInt() ?: 0
            val (one, many, centName) = when (m.groupValues[1]) {
                "£" -> Triple("pound", "pounds", "pence")
                "€" -> Triple("euro", "euros", "cents")
                else -> Triple("dollar", "dollars", "cents")
            }
            buildString {
                append(cardinal(whole)).append(' ').append(if (whole == 1L) one else many)
                if (cents > 0) append(' ').append(cardinal(cents.toLong())).append(' ').append(if (cents == 1 && centName == "cents") "cent" else centName)
            }
        }
        s = PERCENT.replace(s) { m -> "${spellDecimal(m.groupValues[1])} percent" }
        s = ORDINAL.replace(s) { m -> m.groupValues[1].toLongOrNull()?.let(::ordinal) ?: m.value }
        s = DECIMAL.replace(s) { m -> spellDecimal(m.value) }
        s = NUMBER.replace(s) { m ->
            val digits = m.value.replace(",", "")
            val n = digits.toLongOrNull() ?: return@replace digitByDigit(digits)
            when {
                // A year-like four-digit number, written without a comma.
                !m.value.contains(',') && digits.length == 4 && n in 1100..2099 && n % 1000 >= 10 -> year(n)
                // A long string of digits (a phone or account number): digit by digit.
                digits.length > 15 || (digits.length > 1 && digits.startsWith("0")) -> digitByDigit(digits)
                else -> cardinal(n)
            }
        }
        s = MIXED_WORD.replace(s) { m -> m.value.split(CASE_SPLIT).joinToString(" ") { part -> letters(part) } }
        return s
    }

    /** All capitals of two to four letters reads as letters ("WM" → "W M"); anything else stays a word. */
    private fun letters(part: String): String =
        if (part.length in 2..4 && part.all { it.isUpperCase() }) part.toList().joinToString(" ") else part

    private fun spellDecimal(text: String): String {
        val clean = text.replace(",", "")
        val dot = clean.indexOf('.')
        if (dot < 0) return clean.toLongOrNull()?.let(::cardinal) ?: text
        val whole = clean.substring(0, dot).toLongOrNull() ?: return text
        return "${cardinal(whole)} point ${digitByDigit(clean.substring(dot + 1))}"
    }

    private fun digitByDigit(digits: String): String =
        digits.filter(Char::isDigit).map { ONES[it - '0'] }.joinToString(" ")

    /** [n] in words, American style without "and": 1250 → "one thousand two hundred fifty". */
    fun cardinal(n: Long): String {
        if (n < 0) return "minus ${cardinal(-n)}"
        if (n < 20) return ONES[n.toInt()]
        val groups = ArrayList<Int>()
        var rest = n
        while (rest > 0) {
            groups.add((rest % 1000).toInt())
            rest /= 1000
        }
        if (groups.size > SCALES.size) return digitByDigit(n.toString())
        val words = ArrayList<String>()
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            words.add(hundreds(g))
            if (SCALES[i].isNotEmpty()) words.add(SCALES[i])
        }
        return words.joinToString(" ")
    }

    private fun hundreds(n: Int): String {
        val parts = ArrayList<String>()
        if (n >= 100) {
            parts.add(ONES[n / 100])
            parts.add("hundred")
        }
        val r = n % 100
        if (r > 0) {
            parts.add(if (r < 20) ONES[r] else TENS[r / 10] + if (r % 10 > 0) "-" + ONES[r % 10] else "")
        }
        return parts.joinToString(" ")
    }

    /** 1999 → "nineteen ninety-nine", 2005 → "two thousand five", 1900 → "nineteen hundred". */
    fun year(n: Long): String {
        val high = (n / 100).toInt()
        val low = (n % 100).toInt()
        return when {
            n in 2000..2009 -> cardinal(n)
            low == 0 -> "${hundreds(high)} hundred"
            low < 10 -> "${hundreds(high)} oh ${ONES[low]}"
            else -> "${hundreds(high)} ${hundreds(low)}"
        }
    }

    /** 3 → "third", 21 → "twenty-first", 100 → "one hundredth". */
    fun ordinal(n: Long): String {
        val words = cardinal(n)
        val cut = maxOf(words.lastIndexOf(' '), words.lastIndexOf('-')) + 1
        val head = words.substring(0, cut)
        val last = words.substring(cut)
        val tail = ORDINAL_WORDS[last] ?: when {
            last.endsWith("y") -> last.dropLast(1) + "ieth"
            else -> last + "th"
        }
        return head + tail
    }
}
