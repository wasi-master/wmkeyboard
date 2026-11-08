package com.wasimaster.wmkeyboard.ime

/**
 * How many spaces to take out from in front of the caret when [mark] is about
 * to land, under the "remove a space before punctuation" rule
 * (`AutoTextSettings.hugPunctuation`). 0 means leave the field alone.
 *
 * [before] is the text behind the caret, [marks] the user's list of marks the
 * rule applies to. The rule is deliberately narrow: only plain spaces (a line
 * break is a paragraph, not a slip), only when they follow a word, a number
 * or something that closes one (a bracket, a quote), and only when the whole
 * run is visible in [before]. A run that fills the entire read may continue
 * past it, and a mark at the start of a line, or after another mark, is not a
 * spacing slip but whatever the user meant.
 */
fun straySpacesBefore(before: CharSequence, mark: Char, marks: String): Int {
    if (mark !in marks) return 0
    var spaces = 0
    var i = before.length
    while (i > 0 && before[i - 1] == ' ') {
        spaces++
        i--
    }
    if (spaces == 0 || i == 0) return 0
    val prev = before[i - 1]
    return if (prev.isLetterOrDigit() || prev in HUG_CLOSERS) spaces else 0
}

/**
 * Whether [next], typed after a [mark] the rule above pulled onto the word in
 * front of it, makes that mark the eyes of an emoticon (#1166): `;` then `)`
 * is a wink, and the space the user typed before it was meant. Only `;` and
 * `:`, the eyes the marks list holds.
 *
 * [afterSpace] is true when the keyboard's own space after the mark stands
 * between the two. A letter there starts the next word (`word ; Do`), and so
 * may an opening bracket or a dash, so then only a mouth that closes counts.
 */
fun hugTurnsEmoticon(mark: Char, next: String, afterSpace: Boolean): Boolean {
    if (mark != ';' && mark != ':') return false
    val c = next.singleOrNull() ?: return false
    return c in if (afterSpace) EMOTICON_CLOSING_MOUTHS else EMOTICON_MOUTHS
}

/** What can follow the eyes of an emoticon straight away: `:)`, `;(`, `:-)`, `;P`, `:D`, `:3`. */
private const val EMOTICON_MOUTHS = ")(][|/\\*-'<>@DPpOoXx3"

/** The mouths that cannot open anything, the only ones read across a space. */
private const val EMOTICON_CLOSING_MOUTHS = ")]|>"

/**
 * Whether a mark landing with [before] behind the caret belongs to a number,
 * so the automatic space after punctuation (`AutoTextSettings.spaceAfterPunctuation`)
 * holds off (#206). "5,000", "3.14" and "5:00" carry on past the mark, and a
 * space typed for the user splits the number in two. Any script's digits
 * count, so "৫,০০০" is kept whole too.
 */
fun markContinuesNumber(before: CharSequence): Boolean = before.lastOrNull()?.isDigit() == true

/**
 * Whether a full stop landing with [before] behind the caret is the dot inside
 * an email address, so the automatic space after punctuation holds off the same
 * way it does for a number. "test@pm" followed by "." is on its way to
 * "test@pm.me", and a space typed there splits the address and hands the
 * domain a capital ("test@pm. Me").
 *
 * The run behind the caret back to the last space has to hold an "@" with
 * something on both sides of it. A bare "@john" is a mention, and a mention
 * can end a sentence, so it keeps its space. Only the full stop: a comma or a
 * question mark after an address is prose ("mail a@b.com, then") and still
 * gets one.
 */
fun markContinuesAddress(before: CharSequence, mark: Char): Boolean {
    if (mark != '.') return false
    var start = before.length
    while (start > 0 && !before[start - 1].isWhitespace() && !Character.isSpaceChar(before[start - 1])) start--
    val at = before.lastIndexOf('@')
    return at > start && at < before.length - 1
}

/** Characters [markContinuesAddress] reads behind the caret; longer than the local part of most addresses. */
const val ADDRESS_LOOKBACK = 64

/** What may stand in front of a hugged mark besides a word: things that close one. */
private const val HUG_CLOSERS = ")]}\"'”’"

/** Characters the hug rule reads behind the caret; longer than any honest run of spaces. */
const val HUG_LOOKBACK = 16

/**
 * What [word] should commit as when it closes up onto the full stop behind it,
 * or null when it stays a word of its own. [ahead] is the field text in front
 * of the word, which ends in the space the automatic space after punctuation
 * typed after that full stop.
 *
 * The space can't be held back when the "." lands, since nothing yet says
 * whether a sentence ended or an address is being typed, so it is taken back
 * once the next word turns out to be a domain ending or a file extension:
 * "google. com" commits as "google.com", "notes. txt" as "notes.txt". The dot
 * has to sit straight on a letter or a digit, so "Wait... com" and a dot that
 * closes a bracket stay apart.
 *
 * Only endings no sentence starts with are in [DOT_SUFFIXES]: "it", "in", "me",
 * "us", "go" and "log" are real domain endings or file types, and also the first
 * word of the next sentence far more often. The auto-capital the full stop arms
 * is dropped ("Com" back to "com"); a word typed all in capitals keeps them, so
 * "scan. PDF" gives "scan.PDF".
 */
fun dotSuffixJoin(ahead: CharSequence, word: String): String? {
    if (ahead.length < 3 || ahead[ahead.length - 1] != ' ' || ahead[ahead.length - 2] != '.') return null
    if (!ahead[ahead.length - 3].isLetterOrDigit()) return null
    if (word.lowercase() !in DOT_SUFFIXES) return null
    val capitalised = word.length > 1 && word[0].isUpperCase() && word.substring(1).none { it.isUpperCase() }
    return if (capitalised) word.lowercase() else word
}

/** Characters [dotSuffixJoin] needs in front of the word: the letter, the dot and the space. */
const val DOT_SUFFIX_LOOKBACK = 3

/**
 * Domain endings and file extensions [dotSuffixJoin] closes up onto a full stop.
 * Kept to what can't plausibly start a sentence; see there for what is left out.
 */
private val DOT_SUFFIXES = setOf(
    // Domain endings.
    "com", "org", "net", "edu", "gov", "mil", "int", "io", "co", "dev", "app", "ai", "info", "biz",
    "xyz", "tv", "gg", "ly", "fm", "cc", "ws", "eu",
    "uk", "de", "fr", "es", "nl", "ru", "jp", "cn", "kr", "br", "au", "ca", "ch", "se", "pl",
    "pt", "tr", "ir", "pk", "bd", "np", "lk", "sg", "nz", "za", "mx", "ar", "cz", "gr", "ro",
    // File extensions.
    "txt", "pdf", "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "heic", "avif",
    "mp3", "mp4", "m4a", "wav", "flac", "ogg", "opus", "mkv", "mov", "avi", "webm",
    "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "apk", "aab", "exe", "msi", "dmg", "iso", "deb", "rpm",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "epub", "csv", "tsv",
    "json", "xml", "html", "htm", "css", "scss", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts",
    "java", "py", "rb", "rs", "cpp", "hpp", "cs", "dart", "php", "lua", "sh", "bash",
    "zsh", "ps1", "bat", "md", "mdx", "yml", "yaml", "toml", "ini", "cfg", "conf", "sql", "db",
    "gradle", "srt", "ttf", "otf", "woff", "woff2", "psd", "vue", "svelte", "ipynb",
)
