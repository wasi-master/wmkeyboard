package com.wasimaster.wmkeyboard.core.selection

/**
 * Tidies Persian text the way its editors ask: Persian ی and ک for their Arabic look-alikes,
 * half-spaces (ZWNJ) in می‌روم, کتاب‌ها and خانه‌ام, and punctuation against the word it
 * follows. The rules are the spacing patterns of Hazm's normalizer (Roshan, MIT), narrowed
 * to what is safe on any selection: no digits or quotes change, and a dot only gets a space
 * before a Persian letter, so an address or a decimal stays whole.
 */
object PersianCleanup {

    private const val ZWNJ = "‌"
    private const val LETTERS = "ء-يٮ-ۓ"
    private const val AFTER = """.:!،؛؟»\]\)\}"""
    private const val BEFORE = """«\[\(\{"""
    private const val AFTER_NO_DOT = """:!،؛؟»\]\)\}"""

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Half-spaces that do nothing, then the ones that belong.
        Regex("$ZWNJ{2,}") to ZWNJ,
        Regex("$ZWNJ+ ") to " ",
        Regex(" $ZWNJ+") to " ",
        Regex("(?<![$LETTERS])$ZWNJ+") to "",
        Regex("$ZWNJ+(?![$LETTERS])") to "",
        Regex("ـ") to "",
        Regex("(?<=\\S) {2,}(?=\\S)") to " ",
        Regex("([^ \n]ه) ی(?= |\n|$)") to "$1$ZWNJ" + "ی",
        Regex("(^|[ \n])(ن?می) (?=[$LETTERS])", RegexOption.MULTILINE) to "$1$2$ZWNJ",
        Regex("(?<=[^\n\\d $AFTER$BEFORE]{2}) (تر(?:ین?)?|گری?|های?)(?=[ \n$AFTER$BEFORE]|$)") to "$ZWNJ$1",
        Regex("([^ \n]ه) (ا(?:م|یم|ش|ند|ی|ید|ت))(?=[ \n$AFTER]|$)") to "$1$ZWNJ$2",
        Regex("(?<=ه)(ها)") to "$ZWNJ$1",
        // Punctuation sits against the word before it.
        Regex(" ([$AFTER])") to "$1",
        Regex("([$BEFORE]) ") to "$1",
        Regex("([$AFTER_NO_DOT])(?=[$LETTERS])") to "$1 ",
        Regex("\\.(?=[$LETTERS])") to ". ",
        Regex("(?<=[$LETTERS])([$BEFORE])") to " $1",
    )

    /** [text] tidied; unchanged if there is nothing to do. */
    fun clean(text: String): String {
        var out = text.replace('ك', 'ک').replace('ي', 'ی').replace('ى', 'ی')
        for ((pattern, replacement) in RULES) out = pattern.replace(out, replacement)
        return out
    }
}
