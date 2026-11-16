package com.wasimaster.wmkeyboard.core.clipboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One rule that tidies a copy before the history keeps it (#612): every match
 * of [pattern], a case-insensitive regular expression, is replaced with
 * [replacement]. An empty replacement removes the match, which is what most
 * rules are for: `https?://(www\.)?` takes the front off every address.
 * [replacement] may name the pattern's groups (`$1`) to add to a match
 * instead of only taking from it.
 */
@Serializable
data class ClipCopyRule(
    val pattern: String,
    val replacement: String = "",
    val enabled: Boolean = true,
)

/**
 * Decorated Unicode letters back to the plain ones they stand for (#612): the
 * bold, italic, script and double-struck alphabets chat apps and bio
 * generators type with, full-width forms, circled and squared letters, small
 * capitals, and the combining strokes that fake strikethrough and underline.
 * Pasted as they are, they read as noise to a search box and to a screen
 * reader, and sometimes as boxes.
 */
object ClipPlainLetters {

    /** Small capitals have no compatibility mapping, so they are listed. */
    private val SMALL_CAPS: Map<Char, Char> = mapOf(
        'ᴀ' to 'a', 'ʙ' to 'b', 'ᴄ' to 'c', 'ᴅ' to 'd', 'ᴇ' to 'e', 'ꜰ' to 'f', 'ɢ' to 'g',
        'ʜ' to 'h', 'ɪ' to 'i', 'ᴊ' to 'j', 'ᴋ' to 'k', 'ʟ' to 'l', 'ᴍ' to 'm', 'ɴ' to 'n',
        'ᴏ' to 'o', 'ᴘ' to 'p', 'ʀ' to 'r', 'ꜱ' to 's', 'ᴛ' to 't', 'ᴜ' to 'u', 'ᴠ' to 'v',
        'ᴡ' to 'w', 'ʏ' to 'y', 'ᴢ' to 'z',
    )

    /** Combining marks laid over each letter to fake a line through or under it. */
    private fun isOverlayMark(cp: Int): Boolean = cp in 0x0332..0x0338 || cp == 0x0305 || cp == 0x035F

    /** [text] with its decorated letters made plain; the same string when it has none. */
    fun plain(text: String): String {
        val out = StringBuilder(text.length)
        var changed = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val mapped = map(cp)
            if (mapped == null) {
                out.appendCodePoint(cp)
            } else {
                out.append(mapped)
                changed = true
            }
        }
        return if (changed) out.toString() else text
    }

    /** What [cp] becomes, or null to keep it as it is. */
    private fun map(cp: Int): String? {
        if (isOverlayMark(cp)) return ""
        if (cp <= 0xFFFF) SMALL_CAPS[cp.toChar()]?.let { return it.toString() }
        // Negative circled and squared capitals have no compatibility mapping.
        if (cp in 0x1F150..0x1F169) return ('A' + (cp - 0x1F150)).toString()
        if (cp in 0x1F170..0x1F189) return ('A' + (cp - 0x1F170)).toString()
        val decorated = cp in 0x1D400..0x1D7FF || cp in 0xFF01..0xFF5E || cp in 0x2460..0x24FF ||
            cp in 0x1F100..0x1F149 || cp in 0x2100..0x214F
        if (!decorated) return null
        val folded = java.text.Normalizer.normalize(String(Character.toChars(cp)), java.text.Normalizer.Form.NFKC)
        // The letterlike block also holds ℃, № and ™, which are symbols of
        // their own rather than dressed-up letters: only a single letter or
        // digit counts there.
        if (cp in 0x2100..0x214F && !(folded.length == 1 && folded[0].isLetterOrDigit())) return null
        return folded.takeIf { it != String(Character.toChars(cp)) }
    }
}

/** Applies [ClipCopyRule]s to copied text, and stores them as one string. */
object ClipCopyRules {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ClipCopyRule.serializer())

    /** [rule]'s pattern compiled, or null while it does not compile or is empty. */
    fun compile(rule: ClipCopyRule): Regex? =
        rule.pattern.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }

    /**
     * [text] with every enabled rule applied in order, each to what the one
     * before it left. A rule that does not compile, or whose replacement names
     * a group the pattern does not have, is skipped rather than failing the
     * copy. The result is trimmed, so a rule that removes the front of a clip
     * does not leave the space that stood after it.
     */
    fun apply(text: String, rules: List<ClipCopyRule>): String {
        var out = text
        for (rule in rules) {
            if (!rule.enabled) continue
            val regex = compile(rule) ?: continue
            out = runCatching { regex.replace(out, rule.replacement) }.getOrDefault(out)
        }
        return if (out == text) text else out.trim()
    }

    fun encode(rules: List<ClipCopyRule>): String = json.encodeToString(serializer, rules)

    /** The rules [stored] holds; empty for nothing stored or a string that does not parse. */
    fun decode(stored: String?): List<ClipCopyRule> =
        stored?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()
}

/**
 * Several clips joined into one (#612).
 *
 * Plainly, the clips' texts with [joiner] between them. With a pattern, a
 * regular expression, each clip gives only the first part of it that
 * matches, and a clip with no match is left out. A template then says what
 * each match becomes, `$1` standing for the pattern's first group; without
 * one the whole match is kept. The prefix goes in front of the lot, and may
 * name the first clip's groups too, so a rule can say whose links these are
 * once: prefix `$1: github.com`, pattern `github\.com/([^/]+)(/[^/]+)`,
 * template `/$1$2` and joiner `; ` turn two of one owner's GitHub links into
 * `wasi-master: github.com/wasi-master/wmkeyboard; /wasi-master/wmkeyboard-addon-repository`.
 */
object ClipCombine {

    /**
     * The joined text, or null when there is nothing to join: no clip had a
     * match, or [append] is not a pattern that compiles. See [ClipCombine].
     */
    fun combine(
        texts: List<String>,
        joiner: String,
        base: String = "",
        append: String = "",
        template: String = "",
        /** The pattern must match a clip's whole text (#612); a clip it does not is left out. */
        whole: Boolean = false,
    ): String? {
        if (append.isEmpty()) {
            val kept = texts.filter { it.isNotEmpty() }
            if (kept.isEmpty()) return null
            return base + kept.joinToString(joiner)
        }
        val pattern = appendPattern(append) ?: return null
        val matches = texts.mapNotNull { if (whole) pattern.matchEntire(it) else pattern.find(it) }
        val parts = matches.map { match -> if (template.isEmpty()) match.value else expand(template, match) }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val prefix = if ('$' in base) expand(base, matches.first()) else base
        return prefix + parts.joinToString(joiner)
    }

    /**
     * What each clip gives, for the preview of the clips being joined: the
     * range of its text the pattern matched, or null for a clip it misses.
     * Every clip's whole text without a pattern.
     */
    fun matchedRanges(texts: List<String>, append: String, whole: Boolean = false): List<IntRange?> {
        if (append.isEmpty()) return texts.map { it.indices }
        val pattern = appendPattern(append) ?: return texts.map { null }
        return texts.map { text -> (if (whole) pattern.matchEntire(text) else pattern.find(text))?.range }
    }

    /**
     * [append] compiled, or null when it does not compile. Case counts here,
     * as it always has; a count written `{2, 5}` is read as `{2,5}` (#612).
     */
    fun appendPattern(append: String): Regex? = clipPattern(append, ignoreCase = false, dotAll = false)

    /**
     * [template] with `$0` to `$9` (or `${12}`) replaced by [match]'s groups,
     * a group that took no part as nothing. A backslash keeps the character
     * after it as it is, so `\$` is a dollar sign.
     */
    fun expand(template: String, match: MatchResult): String {
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            val next = template.getOrNull(i + 1)
            when {
                c == '\\' && next != null -> {
                    out.append(next)
                    i += 2
                }
                c == '$' && next == '{' -> {
                    val close = template.indexOf('}', i + 2)
                    val group = if (close > 0) template.substring(i + 2, close).toIntOrNull() else null
                    if (group == null) {
                        out.append(c)
                        i++
                    } else {
                        out.append(match.groupValues.getOrNull(group).orEmpty())
                        i = close + 1
                    }
                }
                c == '$' && next != null && next.isDigit() -> {
                    out.append(match.groupValues.getOrNull(next.digitToInt()).orEmpty())
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
