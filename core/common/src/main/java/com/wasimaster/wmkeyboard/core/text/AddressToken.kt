package com.wasimaster.wmkeyboard.core.text

/**
 * The rule for "the text at the caret is a web or email address", shared by the
 * suggestion strip, autocorrect, learning and the word-at-a-time deletes
 * (HeliBoard's URL detection, gap list item 11).
 *
 * An address token is a run of letters, digits and the marks addresses are
 * built from ([IN_WORD]: `. / : _ - ? = & # % @ ~ +`), with no space in it,
 * that also has one of these shapes:
 *  - a scheme in front: `https://`, `http://`, `ftp://` and the few others in
 *    [SCHEMES], or `mailto:`;
 *  - `www.` in front, with something after it;
 *  - an email address: something before an "@" and a domain after it
 *    ("test@pm" counts already, since it is on its way to "test@pm.me");
 *  - a host whose last label is a known domain ending ([TLDS]): "example.com",
 *    "example.com/path?q=1".
 *
 * Prose stays prose. "end.Then" (a missing space after a full stop) is two
 * words because "Then" is no domain ending; "e.g.", "U.S." and "3.14" match
 * nothing. Domain endings that are also common words ("it", "in", "me", "us",
 * "to" and the rest of [WORD_TLDS]) count only with a path or port after the
 * host ("t.me/name", "bit.it:8080"), and a capitalised last label ("end.Com")
 * reads as the next sentence starting, not a domain. A token that ends on a
 * full stop, a colon or a question mark has not ended on its address: "Visit
 * example.com." ends a sentence, and its last step is the full stop.
 *
 * Pure text in, answer out, so it is unit-tested on its own.
 */
object AddressToken {

    /**
     * Characters to read behind the caret for this rule. Longer than most links
     * people type; a longer one is cut at the start of the window and judged
     * on what is left of it.
     */
    const val LOOKBACK = 256

    /** Marks that sit inside an address without ending it. */
    const val IN_WORD = "./:_-?=&#%@~+"

    /** Marks an address token does not end on: they close the sentence it sits in. */
    private const val PROSE_ENDS = ".:?"

    /** Whether [c] can be part of an address token. */
    fun isTokenChar(c: Char): Boolean = c.isLetterOrDigit() || c in IN_WORD || isCombiningMark(c)

    /**
     * Where the address token that ends exactly at [end] in [text] starts, or
     * -1 when the run of token characters ending there is not an address. The
     * run reaches back to the first space, bracket, quote or other mark that
     * is not part of an address, or to the start of [text].
     */
    fun startBefore(text: CharSequence, end: Int = text.length): Int {
        if (end <= 0 || end > text.length) return -1
        var start = end
        while (start > 0 && isTokenChar(text[start - 1])) start--
        if (start == end) return -1
        return if (isAddress(text.subSequence(start, end))) start else -1
    }

    /**
     * Where the address token that starts exactly at [start] in [text] ends, or
     * -1 when it is not one. The run goes forward to the first character that
     * is not part of an address; a full stop, colon or question mark it ends
     * on is left out, since it closes the sentence ("example.com." gives the
     * end of "example.com").
     */
    fun endAfter(text: CharSequence, start: Int = 0): Int {
        if (start < 0 || start >= text.length) return -1
        var end = start
        while (end < text.length && isTokenChar(text[end])) end++
        while (end > start && text[end - 1] in PROSE_ENDS) end--
        if (end == start) return -1
        return if (isAddress(text.subSequence(start, end))) end else -1
    }

    /** Whether [text] ends in an address token, so the caret after it sits in one. */
    fun endsInAddress(text: CharSequence): Boolean = startBefore(text) >= 0

    /**
     * Whether a caret with [before] and [after] around it sits inside an
     * address token or at its end, judging the whole run of token characters
     * it touches: "exam|ple.com" is in one, though "exam" alone is not. A
     * caret in front of the run's first character is not in it (what is typed
     * there starts a word of its own), and neither is one past the full stop,
     * colon or question mark the run ends on ("example.com.|").
     */
    fun caretIn(before: CharSequence, after: CharSequence): Boolean {
        var start = before.length
        while (start > 0 && isTokenChar(before[start - 1])) start--
        val caret = before.length - start
        if (caret == 0) return false
        var tail = 0
        while (tail < after.length && isTokenChar(after[tail])) tail++
        val run = StringBuilder(caret + tail).append(before, start, before.length).append(after, 0, tail)
        var end = run.length
        while (end > 0 && run[end - 1] in PROSE_ENDS) end--
        if (caret > end) return false
        return isAddress(run.substring(0, end))
    }

    /** Whether [token], taken whole, is an address under the rule above. */
    fun isAddress(token: CharSequence): Boolean {
        if (token.isEmpty() || !token.all(::isTokenChar)) return false
        if (token[token.length - 1] in PROSE_ENDS) return false
        val lower = token.toString().lowercase()
        if (lower.startsWith("mailto:") && lower.length > "mailto:".length) return true
        val schemeEnd = lower.indexOf("://")
        if (schemeEnd > 0 && lower.substring(0, schemeEnd) in SCHEMES) return true
        if (lower.startsWith("www.") && lower.length > "www.".length) return true
        if (isEmailShape(lower)) return true
        return hasDomainHost(token.toString())
    }

    /** "local@domain": both sides present, each spelled the way addresses are. */
    private fun isEmailShape(lower: String): Boolean {
        val at = lower.indexOf('@')
        if (at <= 0 || at >= lower.length - 1 || lower.indexOf('@', at + 1) >= 0) return false
        val local = lower.substring(0, at)
        val domain = lower.substring(at + 1)
        if (local.startsWith('.') || !local.all { it.isLetterOrDigit() || it in LOCAL_MARKS }) return false
        if (!domain[0].isLetterOrDigit()) return false
        return domain.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    /**
     * A bare host with a known ending: at least two labels, none empty, each
     * letters, digits and hyphens, the last in [TLDS]. Whatever follows the
     * host ("/path", "?q=", "#top", ":8080") just has to be token characters.
     */
    private fun hasDomainHost(token: String): Boolean {
        val hostEnd = token.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ':' }
            .let { if (it < 0) token.length else it }
        val host = token.substring(0, hostEnd)
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.any { label -> label.isEmpty() || !label.all { it.isLetterOrDigit() || it == '-' } }) return false
        if (!labels[0][0].isLetterOrDigit()) return false
        val last = labels.last()
        // "end.Com": a capital and then small letters is the next sentence starting.
        if (last.length > 1 && last[0].isUpperCase() && last.substring(1).none { it.isUpperCase() }) return false
        val ending = last.lowercase()
        if (ending in TLDS) return true
        // A word-like ending needs a path or port after it to be a host.
        return ending in WORD_TLDS && hostEnd < token.length && token[hostEnd] in "/:"
    }

    private fun isCombiningMark(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }

    /** What may stand in the part of an email address before the "@". */
    private const val LOCAL_MARKS = "._%+-~"

    /** Schemes that make a token an address whatever follows "://". */
    private val SCHEMES = setOf(
        "http", "https", "ftp", "ftps", "sftp", "file", "ws", "wss", "git", "ssh", "smb",
        "content", "intent", "market", "magnet", "irc", "ircs", "matrix", "xmpp",
    )

    /**
     * Domain endings no sentence plausibly continues with after a full stop and
     * no space. The generic ones people type, then country codes that are not
     * English words.
     */
    private val TLDS = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int", "io", "co", "dev", "app", "ai", "info", "biz",
        "xyz", "tv", "gg", "ly", "fm", "cc", "ws", "eu", "uk", "de", "fr", "es", "nl", "ru", "jp", "cn",
        "kr", "br", "au", "ca", "ch", "se", "pl", "pt", "tr", "ir", "pk", "bd", "np", "lk", "sg", "nz",
        "za", "mx", "ar", "cz", "gr", "ro", "ua", "vn", "th", "tw", "hk", "ph", "ng", "ke", "sa", "ae",
        "il", "dk", "fi", "hu", "sk", "bg", "hr", "rs", "si", "lt", "lv", "ee", "ie", "nu", "ac", "sh",
        "vc", "gl", "rw", "su", "onion", "online", "site", "tech", "blog", "cloud", "wiki", "pro", "moe",
        "website", "codes", "social", "design", "studio",
    )

    /**
     * Domain endings that are also words a sentence starts with or a slip
     * glues on ("end.it", "end.me"): a host only with a path or port after it.
     */
    private val WORD_TLDS = setOf(
        "it", "in", "me", "us", "to", "is", "so", "no", "do", "go", "am", "an", "as", "by", "my", "id",
        "at", "be", "la", "ma", "mo", "tk", "eg", "ms", "top", "page", "art", "zone", "space", "email",
        "chat", "store", "shop", "club", "lol", "fun", "live", "news", "link",
    )
}
