package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.core.regex.GuardedRegex
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * The pattern half of snippet expansion: a snippet whose trigger is a regular
 * expression over the words behind the cursor, rather than one exact word.
 *
 * `^hello (.+)$` with the text `Hello, $1! Nice to meet you.` turns
 * "hello John" into "Hello, John! Nice to meet you." when the space lands.
 *
 * Three facts shape everything in this file. The patterns come from the user
 * *and* from installed add-ons, so a hostile one is in scope. They run on the
 * input thread, where a stall is a dropped keystroke. And `java.util.regex` has
 * no timeout, so the only defence is to count the work and stop. [SnippetIndex]
 * therefore does as little matching as it can get away with (a literal-prefix
 * gate), over as little text as it can ([MAX_WINDOW]), with a hard step budget
 * per matcher run and a hard run budget per commit.
 */
object SnippetMatcher {

    /** Longest span behind the cursor a pattern may ever see, in characters. */
    const val MAX_WINDOW = 100

    /** Longest pattern source the app accepts, in characters. */
    const val MAX_PATTERN_LENGTH = 200

    /** Words behind the cursor a snippet sees when it does not say. */
    const val DEFAULT_WORDS = 3

    /** Largest [Snippet.triggerWords] a snippet may ask for. */
    const val MAX_WORDS = 8

    /**
     * Largest capture reference a snippet text may use. `$10` cannot be told
     * apart from `$1` followed by a `0`, so nine is where the syntax stops.
     */
    const val MAX_GROUPS = 9

    /** Matcher runs allowed for one commit, across every snippet. */
    const val MAX_ATTEMPTS = 40

    /**
     * Characters one matcher run may read before the app judges it runaway.
     * Sized well above an honest pattern over [MAX_WINDOW] and far below the
     * time one frame costs.
     */
    const val MAX_STEPS = 40_000

    /**
     * Longest run a trigger may carry in front of its last word, in characters.
     *
     * Bounds the read [SnippetIndex.matchPrefix] asks the field for. Espanso's
     * punctuation convention is one or two characters (`:`, `;`, `//`); a
     * multi-word trigger such as `gr db` needs room for whole words, so this is
     * sized for a short phrase rather than for a symbol.
     */
    const val MAX_PREFIX = 32

    /** A trigger's lead-in and the word it ends with. */
    data class Prefixed(val prefix: String, val word: String)

    /**
     * [trigger] split into everything in front of its final word and that word,
     * or null when it is an ordinary whole-word trigger.
     *
     * `:shrug` gives `(":", "shrug")` and `gr db` gives `("gr ", "db")`. Neither
     * can go through the plain whole-word lookup: the keyboard's composing
     * buffer only ever holds letters, digits and apostrophes, so by the time the
     * last word is finished the colon — or the earlier word and the space after
     * it — has already been committed to the field.
     *
     * Null for a plain word (nothing to look back at) and for a trigger that
     * does not end in a word (nothing to look up), such as `->` or `x:`. That
     * second case is reported as unsupported by the Espanso importer rather than
     * stored and left inert. A lead-in may hold spaces but no other whitespace:
     * a tab or a newline is not something the keyboard can put in a field.
     */
    fun splitPrefix(trigger: String): Prefixed? {
        val cut = trigger.indexOfLast { !isTriggerWordChar(it) } + 1
        if (cut <= 0 || cut > MAX_PREFIX || cut == trigger.length) return null
        val prefix = trigger.substring(0, cut)
        if (prefix.any { it != ' ' && it.isWhitespace() }) return null
        return Prefixed(prefix, trigger.substring(cut))
    }

    /**
     * [trigger] split the way the composing buffer holds it when the trigger's
     * last word is a hyphenated compound, or null when it has none (#659).
     *
     * A hyphen typed straight after a letter stays in the buffer as part of the
     * word (`well-paid`, `e-mail`), so `m:f-u` reaches the end of the word as
     * "f-u" with only "m:" in the field, and `f-u` reaches it as one word with
     * nothing in front. [splitPrefix] keys both under "u", which is what a
     * buffer that did not join the hyphen holds, so the index registers this
     * spelling as well. `m:f-u` gives `("m:", "f-u")`; `f-u` gives `("", "f-u")`,
     * a plain trigger.
     */
    fun splitCompound(trigger: String): Prefixed? {
        var cut = trigger.length
        while (cut > 0 && isTriggerWordChar(trigger[cut - 1])) cut--
        if (cut == trigger.length) return null
        var joined = false
        // A hyphen with a word character on both sides is inside the word, the
        // same rule the keyboard's buffer and its word read-back both use.
        while (cut >= 2 && trigger[cut - 1] == '-' && isTriggerWordChar(trigger[cut - 2])) {
            cut--
            while (cut > 0 && isTriggerWordChar(trigger[cut - 1])) cut--
            joined = true
        }
        if (!joined || cut > MAX_PREFIX) return null
        val prefix = trigger.substring(0, cut)
        if (prefix.any { it != ' ' && it.isWhitespace() }) return null
        return Prefixed(prefix, trigger.substring(cut))
    }

    /**
     * True for a character the keyboard's composing buffer holds.
     *
     * Deliberately the same set as `isComposingWordChar` on the IME side: a
     * trigger word made of anything else could never be handed to a lookup.
     */
    fun isTriggerWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\''

    /**
     * True when the keyboard could actually watch for [trigger]: a plain word,
     * a word behind a lead-in ([splitPrefix]), or a symbol-ended trigger
     * ([isSuffixTrigger]). Every importer asks this before it keeps a trigger,
     * because a stored trigger that can never fire is a snippet that silently
     * does nothing.
     */
    fun isMatchableTrigger(trigger: String): Boolean =
        trigger.isNotEmpty() && (
            trigger.all(::isTriggerWordChar) ||
                splitPrefix(trigger) != null ||
                isSuffixTrigger(trigger)
            )

    /**
     * True for a trigger that ends in a symbol — `js:`, `->`, `gr db:` — and
     * so fires the moment that symbol is typed, matched by reading the field
     * back rather than by looking the composing word up (#471). The symbol
     * itself is the lookup key; what stands in front of it is confirmed off
     * the field, the way a lead-in is for [splitPrefix]. Spaces may appear
     * inside it, but no other whitespace, for the reason [splitPrefix] gives.
     */
    fun isSuffixTrigger(trigger: String): Boolean {
        if (trigger.length < 2 || trigger.length > MAX_PREFIX) return false
        val last = trigger.last()
        if (isTriggerWordChar(last) || last.isWhitespace()) return false
        return trigger.none { it != ' ' && it.isWhitespace() }
    }

    /** Why a pattern cannot be used. */
    enum class Fault {
        /** There is no pattern to compile. */
        EMPTY,

        /** The source is longer than [MAX_PATTERN_LENGTH]. */
        TOO_LONG,

        /** `java.util.regex` refused it. */
        SYNTAX,

        /** It uses `\1`-style backreferences, which no snippet needs. */
        BACKREFERENCE,

        /** It quantifies a group that is itself quantified, e.g. `(a+)+`. */
        NESTED_QUANTIFIER,
    }

    /**
     * A pattern the app will not run.
     *
     * [description] carries the raw text `java.util.regex` produced, such as
     * "Unclosed group near index 6". It is diagnostic data in the same class as
     * the pattern itself, so it is deliberately not translated
     * (config/i18n/STRINGS.md).
     */
    data class PatternError(val fault: Fault, val description: String? = null)

    /** Characters that end a run of literal pattern text. */
    private const val META = "()[]{}|?*+.^$"

    /** Characters that quantify whatever came before them. */
    private const val QUANTIFIERS = "?*+{"

    /** Flags a pattern may set inline, as in `(?im)`. */
    private const val INLINE_FLAGS = "idmsuxU-"

    /** Checks a pattern the way [SnippetIndex] will, without building one. */
    fun validate(pattern: String): PatternError? {
        val error = GuardedRegex.check(pattern, MAX_PATTERN_LENGTH) ?: return null
        val fault = when (error.fault) {
            GuardedRegex.Fault.EMPTY -> Fault.EMPTY
            GuardedRegex.Fault.TOO_LONG -> Fault.TOO_LONG
            GuardedRegex.Fault.SYNTAX -> Fault.SYNTAX
            GuardedRegex.Fault.BACKREFERENCE -> Fault.BACKREFERENCE
            GuardedRegex.Fault.NESTED_QUANTIFIER -> Fault.NESTED_QUANTIFIER
        }
        return PatternError(fault, error.description)
    }

    /**
     * The lowercased literal prefix a pattern always starts with, or null when
     * it has none and must be tried at every candidate start.
     *
     * The settings screen calls this to warn about a pattern with no literal
     * start; [SnippetIndex] calls it to bucket one. The answer is a prefilter,
     * so being wrong may only ever cost speed, never correctness: a null head
     * makes a pattern slower, never broken.
     */
    fun headOf(pattern: String): String? {
        var i = skipInlineFlags(pattern) ?: return null
        if (i < pattern.length && pattern[i] == '^') i++
        val head = StringBuilder()
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                // A backslash before a letter or digit opens a class such as
                // \d or \p{L}; before punctuation it just spells that character.
                val escaped = pattern.getOrNull(i + 1) ?: break
                if (escaped.isLetterOrDigit()) break
                head.append(escaped)
                i += 2
                continue
            }
            if (c.isWhitespace() || c in META) break
            head.append(c)
            i++
        }
        // A run that ends at a quantifier ends one character early: the
        // quantifier owns that character, so `^hello?` starts with "hell".
        val after = pattern.getOrNull(i)
        if (head.isNotEmpty() && after != null && after in QUANTIFIERS) {
            head.setLength(head.length - 1)
        }
        return head.toString().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }

    /**
     * Where the literal run starts, or null when the pattern turns
     * case-insensitivity off.
     *
     * The head index is lowercased, so a case-sensitive pattern would need a
     * second index of its own for a case nobody writes. It goes ungated instead.
     */
    private fun skipInlineFlags(pattern: String): Int? {
        if (!pattern.startsWith("(?")) return 0
        val close = pattern.indexOf(')', startIndex = 2)
        if (close < 0) return 0
        val flags = pattern.substring(2, close)
        if (flags.isEmpty() || !flags.all { it in INLINE_FLAGS }) return 0
        val minus = flags.indexOf('-')
        if (minus >= 0 && flags.indexOf('i', minus) > minus) return null
        return close + 1
    }

    /** Compiles [source] the way every pattern in this file is compiled. */
    internal fun compile(source: String): Pattern = GuardedRegex.compile(source)

    /**
     * The numbers of [source]'s groups that may only hold a name (#1291): the
     * named groups called `name`, or `name` and a number (`name2`), as in
     * `^hello (?<name>.+)$`. Such a group still counts as `$1` in the text.
     *
     * `^hello (.+)$` turned "hello am" into "Hello, Am! Nice to meet you."; a
     * name group makes the keyboard ask whether each word it caught reads as a
     * name first (see [SnippetIndex.matchPattern]).
     *
     * Counted off the source rather than asked of the pattern, because
     * `Matcher.group(String)` arrived on Android only at API 26. Escapes and
     * character classes are skipped; any other `(?` opens no group, except
     * `(?<` followed by a letter, which is a named one.
     */
    fun nameGroupsOf(source: String): IntArray {
        if (!source.contains("(?<")) return NO_GROUPS
        val found = ArrayList<Int>(2)
        var count = 0
        var inClass = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' && source.getOrNull(i + 1) != '?' -> count++
                c == '(' && source.getOrNull(i + 2) == '<' && source.getOrNull(i + 3)?.isLetter() == true -> {
                    count++
                    val close = source.indexOf('>', startIndex = i + 3)
                    if (close > 0 && isNameGroup(source.substring(i + 3, close))) found += count
                }
            }
            i++
        }
        return found.toIntArray()
    }

    /** True for a group name that asks for a name: `name`, or `name` and digits. */
    private fun isNameGroup(group: String): Boolean =
        group.startsWith(NAME_GROUP, ignoreCase = true) &&
            group.drop(NAME_GROUP.length).all { it in '0'..'9' }

    /** What a group is called when it may only hold a name. */
    const val NAME_GROUP = "name"

    private val NO_GROUPS = IntArray(0)

    /**
     * A snippet text with its capture references blanked out.
     *
     * This is what a tap in the snippet panel inserts: `Hello, $1! Nice to meet
     * you.` becomes `Hello, ! Nice to meet you.`, and
     * [Expansion.blankCaret] says where to leave the caret so the user can type
     * the missing part.
     */
    fun blankTemplate(
        text: String,
        now: Long = System.currentTimeMillis(),
        context: SnippetStore.Companion.Context = SnippetStore.Companion.Context(),
    ): Expansion = expandTemplate(text, now, context) { null }

    /**
     * A snippet text with its template variables and capture references filled
     * in.
     *
     * The order the two are resolved in is a security property, not a
     * preference. One left-to-right scan of [text] splits it into template
     * parts and capture references; [SnippetStore.expand] runs on the template
     * parts **only**, and a group's own text is spliced in raw. Otherwise a
     * message that happened to contain `{clip}` would paste the clipboard into
     * the field, and a group holding `$2` would be substituted a second time.
     *
     * [group] returns the text a reference stands for, or null when the pattern
     * has no such group. Both cases insert nothing.
     */
    internal fun expandTemplate(
        text: String,
        now: Long,
        context: SnippetStore.Companion.Context,
        group: (Int) -> String?,
    ): Expansion {
        val out = StringBuilder(text.length)
        val literal = StringBuilder()
        var firstRef = -1

        fun flush() {
            if (literal.isEmpty()) return
            out.append(SnippetStore.expand(literal.toString(), now, context = context))
            literal.setLength(0)
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '$') {
                literal.append(c)
                i++
                continue
            }
            val ref = readReference(text, i)
            if (ref == null) {
                // A lone "$" is a dollar sign, and "$$" is an escaped one.
                if (text.getOrNull(i + 1) == '$') {
                    literal.append('$')
                    i += 2
                } else {
                    literal.append('$')
                    i++
                }
                continue
            }
            flush()
            if (firstRef < 0) firstRef = out.length
            out.append(transform(group(ref.index).orEmpty(), ref.transform))
            i = ref.end
        }
        flush()

        val marker = out.indexOf(SnippetStore.CURSOR_MARKER)
        if (marker < 0) return Expansion(out.toString(), cursorOffset = -1, firstRefOffset = firstRef)
        val stripped = out.toString().replace(SnippetStore.CURSOR_MARKER, "")
        val shift = if (firstRef > marker) firstRef - SnippetStore.CURSOR_MARKER.length else firstRef
        return Expansion(stripped, cursorOffset = marker, firstRefOffset = shift)
    }

    /** A `$1` or `${1:upper}` reference, and where it ends in the text. */
    private class Reference(val index: Int, val transform: String?, val end: Int)

    /** Reads the reference that starts at [at], or null when none does. */
    private fun readReference(text: String, at: Int): Reference? {
        val next = text.getOrNull(at + 1) ?: return null
        if (next in '0'..'9') return Reference(next - '0', transform = null, end = at + 2)
        if (next != '{') return null
        val close = text.indexOf('}', startIndex = at + 2)
        if (close < 0) return null
        val body = text.substring(at + 2, close)
        val digits = body.substringBefore(':')
        val index = digits.toIntOrNull()?.takeIf { it in 0..MAX_GROUPS } ?: return null
        val name = if (body.length > digits.length) body.substring(digits.length + 1) else null
        return Reference(index, name, close + 1)
    }

    /**
     * A captured group as the snippet asks for it.
     *
     * The cursor marker is scrubbed here rather than at the splice, because a
     * group is the one part of an expansion the user did not write: text that
     * happened to contain the marker must not move the caret.
     */
    private fun transform(value: String, name: String?): String {
        val clean = value.replace(SnippetStore.CURSOR_MARKER, "")
        val locale = Locale.getDefault()
        return when (name) {
            "upper" -> clean.uppercase(locale)
            "lower" -> clean.lowercase(locale)
            "title" -> titleCase(clean, locale)
            "trim" -> clean.trim()
            else -> clean
        }
    }

    /** Every word in [value] with a capital first letter and the rest small. */
    private fun titleCase(value: String, locale: Locale): String {
        val out = StringBuilder(value.length)
        var atStart = true
        for (c in value) {
            out.append(if (atStart) c.uppercase(locale) else c.lowercase(locale))
            atStart = !c.isLetterOrDigit() && c != '\''
        }
        return out.toString()
    }
}

/**
 * A snippet text after expansion, and the two places a caret may go in it.
 *
 * [cursorOffset] is where a `{cursor}` marker sat, or -1 when the text has
 * none. [firstRefOffset] is where the first capture reference sat, or -1.
 */
data class Expansion(
    val text: String,
    val cursorOffset: Int,
    val firstRefOffset: Int,
) {

    /** Where the caret goes after a trigger fires: the marker, else the end. */
    val caret: Int get() = if (cursorOffset >= 0) cursorOffset else text.length

    /**
     * Where the caret goes after a panel tap: the marker, else the first blank
     * a capture reference left behind, else the end.
     */
    val blankCaret: Int
        get() = when {
            cursorOffset >= 0 -> cursorOffset
            firstRefOffset >= 0 -> firstRefOffset
            else -> text.length
        }
}

/**
 * A pattern snippet that fits the text behind the cursor.
 *
 * [consumedChars] is measured against the window the caller passed in, not
 * against the snippet and not against the composing buffer: delete exactly that
 * many characters back from the cursor, then commit [text]. A count rather than
 * an index, so a caller that read a short window and one that read a long
 * window get the same number.
 */
data class SnippetMatch(
    val snippet: Snippet,
    val text: String,
    val cursorOffset: Int,
    val consumedChars: Int,
    /** What the match consumed, verbatim, so a revert can put it back. */
    val consumedText: String,
    /**
     * What the pattern captured, whole match first, so a snippet's other
     * expansions can be templated with the same groups this one was.
     *
     * Carried on the match rather than looked up again later: the matcher is
     * gone by then, and running the pattern a second time may not even give the
     * same answer once the text has been rewritten.
     */
    val groups: List<String?> = emptyList(),
)

/** One compiled pattern snippet, ready to be run. */
internal class CompiledSnippet(
    val snippet: Snippet,
    val pattern: Pattern,
    /** Literal text every match starts with, or null when there is none. */
    val head: String?,
    /** Words behind the cursor this snippet may reach across. */
    val words: Int,
    /** Groups that may only hold a name (#1291); see [SnippetMatcher.nameGroupsOf]. */
    val nameGroups: IntArray = IntArray(0),
)

/**
 * Every snippet's triggers, prepared for lookup. Rebuilt whenever the store
 * changes, which is rare, so that matching — which happens on every word — is a
 * map hit and a handful of matcher runs.
 *
 * Replaces the linear scan [SnippetStore.matchTrigger] used to do.
 */
/**
 * One trigger with something in front of its last word — punctuation, earlier
 * words, or both — ready to be checked against the text behind the composing
 * word.
 *
 * [typed] is the trigger as written, prefix included, which is what
 * [SnippetStore.casingFor] needs to decide whether the user shouted it.
 */
class PrefixTrigger(val prefix: String, val typed: String, val snippet: Snippet)

/**
 * A trigger that ends in a symbol (#471), keyed under that symbol: `js:`
 * fires when the colon lands behind "js". [typed] is the trigger as written,
 * for the casing rule and for how much of the field to take back.
 */
class SuffixTrigger(val typed: String, val snippet: Snippet)

/**
 * A trigger that expands on the keystroke that finishes it ([Snippet.immediate]),
 * keyed under its last character. [typed] is the trigger as written.
 *
 * [tail] is the run of word characters it ends with — all of `btw`, the
 * `sig` of `:sig`, the `db` of `gr db` — which is the most the composing buffer
 * can hold of it. A buffer that does not end with it cannot be finishing this
 * trigger, so it is asked before the field is read.
 */
class ImmediateTrigger(val typed: String, val snippet: Snippet) {
    val tail: String = typed.substring(typed.indexOfLast { !SnippetMatcher.isTriggerWordChar(it) } + 1)
}

class SnippetIndex private constructor(
    private val plain: Map<String, Snippet>,
    private val prefixed: Map<String, List<PrefixTrigger>>,
    private val suffixed: Map<Char, List<SuffixTrigger>>,
    private val immediate: Map<Char, List<ImmediateTrigger>>,
    /** The one-word immediate triggers, lowercased, for a glide to land on. */
    private val immediateWords: Map<String, Snippet>,
    private val byHead: Map<Char, List<CompiledSnippet>>,
    private val ungated: List<CompiledSnippet>,
    /**
     * Snippets that offer themselves rather than rewriting text: the ones told
     * to ask first, plus the ones with several expansions that the app is set
     * to show as chips. See [Snippet.asks].
     */
    private val asking: Set<Long>,
) {

    /** True when [snippet] is on the asking half of every question below. */
    fun asks(snippet: Snippet): Boolean = snippet.id in asking

    /** Snippets stopped for running away, by id. Never runs them again. */
    private val stopped = ConcurrentHashMap.newKeySet<Long>()

    /** True when any snippet at all carries a pattern. */
    val hasPatterns: Boolean = byHead.isNotEmpty() || ungated.isNotEmpty()

    /** True when some pattern has no literal start and must be tried anywhere. */
    val hasUngated: Boolean = ungated.isNotEmpty()

    /**
     * True when some pattern asks before it expands.
     *
     * The two kinds are looked for at different moments — one at the commit,
     * one on every keystroke — so each side asks whether the other exists
     * before it does any work at all. A user with no asking patterns never
     * pays for the keystroke path, which is the expensive one.
     */
    val hasConfirmPatterns: Boolean = (byHead.values.flatten() + ungated).any { asks(it.snippet) }

    /** True when some pattern expands on its own. */
    val hasAutoPatterns: Boolean = (byHead.values.flatten() + ungated).any { !asks(it.snippet) }

    /** True when some plain trigger asks before it expands. */
    val hasConfirmTriggers: Boolean = plain.values.any { asks(it) }

    /** The snippet whose plain trigger is [word], ignoring case. */
    fun matchTrigger(word: String): Snippet? = plain[word.lowercase(Locale.ROOT)]

    /**
     * Every plain trigger that expands on its own, lowercased.
     *
     * What a glide may decode to (#170). A trigger like "omw" is in no word
     * list, so a stroke could never spell it; handed to the decoder, it can.
     * The asking ones are left out because a glide has nowhere to ask: it
     * commits its word the moment the finger lifts. Built once per index, so
     * the same set comes back until the snippets change — which is what lets
     * the keyboard tell by identity whether to rebuild anything.
     *
     * One-word immediate triggers are in it too: a glide commits a whole word
     * at once, so the keystroke an immediate trigger waits for is the lift.
     */
    val expandingTriggers: Set<String> = plain
        .filter { (key, snippet) -> snippet.id !in asking && key.all(SnippetMatcher::isTriggerWordChar) }
        .keys
        .let { typed -> if (immediateWords.isEmpty()) typed else typed + immediateWords.keys }

    /**
     * True when some expanding plain trigger opens with a digit, like `123`
     * (#554). A word-initial digit commits straight to the field and never
     * reaches the composing buffer, so such a trigger can only be matched by
     * reading the field back — and the keyboard asks this first, so a user
     * without one never pays for the read. Immediate triggers are not counted:
     * they are read back on their own keystroke, not at the space.
     */
    val hasDigitLedTriggers: Boolean = plain.any { (key, snippet) ->
        snippet.id !in asking && key.firstOrNull()?.isDigit() == true && key.all(SnippetMatcher::isTriggerWordChar)
    }

    /** True when some trigger expands on its own last keystroke. */
    val hasImmediateTriggers: Boolean = immediate.isNotEmpty()

    /**
     * Longest immediate trigger, in characters: how far back the keyboard has
     * to read to confirm one, before the one character in front of it that
     * decides the word boundary and the escape.
     */
    val immediateReach: Int = immediate.values.maxOfOrNull { list -> list.maxOf { it.typed.length } } ?: 0

    /**
     * True when typing [last] could finish an immediate trigger, given [buffer]
     * is what the composing buffer holds with [last] already in it (empty when
     * nothing composes).
     *
     * The free half of the immediate gate, asked on every keystroke. A user
     * whose immediate triggers end in `w` pays a map lookup per key, and a field
     * read only on a `w` that ends the buffer the way one of them ends.
     */
    fun couldFinishImmediate(last: Char, buffer: CharSequence): Boolean {
        val list = immediate[last.lowercaseChar()] ?: return false
        if (buffer.isEmpty()) return true
        return list.any { buffer.endsWith(it.tail, ignoreCase = true) }
    }

    /**
     * The immediate trigger that [before] ends with — the text in front of the
     * caret, the keystroke just typed included — or null. Longest wins, so
     * `;;addr` beats `addr`.
     *
     * A trigger that starts with a word character needs a word boundary in
     * front of it unless its snippet allows [Snippet.insideWords]; one that
     * starts with a symbol is its own boundary, as everywhere else.
     */
    fun matchImmediate(before: CharSequence): ImmediateTrigger? {
        val last = before.lastOrNull() ?: return null
        for (candidate in immediate[last.lowercaseChar()].orEmpty()) {
            val typed = candidate.typed
            if (before.length < typed.length) continue
            if (!before.endsWith(typed, ignoreCase = true)) continue
            if (!candidate.snippet.insideWords && SnippetMatcher.isTriggerWordChar(typed[0])) {
                val ahead = before.getOrNull(before.length - typed.length - 1)
                if (ahead != null && SnippetMatcher.isTriggerWordChar(ahead)) continue
            }
            return candidate
        }
        return null
    }

    /** The snippet whose one-word immediate trigger is [word], ignoring case. */
    fun matchImmediateWord(word: String): Snippet? =
        if (immediateWords.isEmpty()) null else immediateWords[word.lowercase(Locale.ROOT)]

    /** True when some trigger reaches back past its last word, so the keyboard need not look. */
    val hasPrefixTriggers: Boolean = prefixed.isNotEmpty()

    /** True when some prefix trigger offers itself instead of expanding. */
    val hasConfirmPrefixTriggers: Boolean = prefixed.values.any { list -> list.any { asks(it.snippet) } }

    /** True when some trigger ends in a symbol, so the keyboard need not look otherwise (#471). */
    val hasSuffixTriggers: Boolean = suffixed.isNotEmpty()

    /** True when a trigger ends in [symbol]: the free half of the suffix gate, asked before any read. */
    fun couldEndWith(symbol: Char): Boolean = suffixed.containsKey(symbol.lowercaseChar())

    /**
     * The symbol-ending trigger that [before] ends with — the text in front of
     * the caret, the symbol just typed included — or null. Longest wins, so
     * `gr db:` beats `db:`. A trigger that starts with a word character needs
     * a word boundary in front of it, as every other trigger does; one that
     * starts with a symbol is its own boundary.
     */
    fun matchSuffix(before: CharSequence): SuffixTrigger? {
        val last = before.lastOrNull() ?: return null
        for (candidate in suffixed[last.lowercaseChar()].orEmpty()) {
            val typed = candidate.typed
            if (before.length < typed.length) continue
            if (!before.endsWith(typed, ignoreCase = true)) continue
            if (SnippetMatcher.isTriggerWordChar(typed[0])) {
                val ahead = before.getOrNull(before.length - typed.length - 1)
                if (ahead != null && SnippetMatcher.isTriggerWordChar(ahead)) continue
            }
            return candidate
        }
        return null
    }

    /**
     * The prefix triggers whose word part is [word], longest prefix first, or
     * an empty list.
     *
     * This is the free half of the prefix gate, and it exists for the same
     * reason [couldStartAt] does: the keyboard already holds the composing word,
     * so it can ask this without touching the input connection, and only a
     * non-empty answer buys the right to read the characters in front of it.
     */
    fun prefixCandidates(word: String): List<PrefixTrigger> =
        if (prefixed.isEmpty()) emptyList() else prefixed[word.lowercase(Locale.ROOT)].orEmpty()

    /**
     * The prefix trigger that [word] completes, given [before] is the text
     * immediately in front of it, or null.
     *
     * Longest prefix wins, so `::x` beats `:x` when both are installed, and
     * `gr db` beats `db` when the words in front line up. [confirm] picks which
     * half of the list is asked, for the same reason [matchPattern] takes one:
     * the two are looked for at different moments and neither may answer for
     * the other.
     *
     * A lead-in that begins with a word character needs a word boundary in
     * front of it. Punctuation is its own boundary — `hello:shrug` has always
     * fired `:shrug` — but without this check the trigger `gr db` would fire in
     * the middle of "xgr db".
     */
    fun matchPrefix(word: String, before: CharSequence, confirm: Boolean = false): PrefixTrigger? {
        for (candidate in prefixCandidates(word)) {
            if (asks(candidate.snippet) != confirm) continue
            val prefix = candidate.prefix
            if (before.length < prefix.length) continue
            if (!before.endsWith(prefix, ignoreCase = true)) continue
            if (SnippetMatcher.isTriggerWordChar(prefix[0])) {
                val ahead = before.getOrNull(before.length - prefix.length - 1)
                if (ahead != null && SnippetMatcher.isTriggerWordChar(ahead)) continue
            }
            return candidate
        }
        return null
    }

    /**
     * True when a word starting with [first] could begin a gated pattern.
     *
     * This is the free half of the gate: the keyboard asks it about words it
     * already holds in memory, and only reads the field when it says yes.
     */
    fun couldStartAt(first: Char): Boolean = byHead.containsKey(first.lowercaseChar())

    /** Ids of the patterns the app stopped for taking too long. */
    fun stopped(): Set<Long> = stopped.toSet()

    /**
     * The pattern snippet that fits the end of [window], or null.
     *
     * [window] is the text immediately before the cursor. It is cut at the last
     * line break and trimmed to [SnippetMatcher.MAX_WINDOW], so a pattern can
     * never reach across a line or across more text than one glance.
     *
     * Candidate spans are tried nearest-to-cursor first, so the shortest match
     * wins. The operation this feeds deletes text the user typed, so a tie has
     * to err toward consuming less; it also stops one careless `^(.+)$` from
     * eating every commit's whole window.
     *
     * A span starts at a word, or at the run of spaces in front of one. The
     * second kind exists for patterns about spacing: `\s+([.,?!:;])` can only
     * ever match a span that begins with the space it wants to remove, and a
     * word-only start would never hand it one. The space run counts as part of
     * the word it precedes for the word budget, and is tried right after it.
     *
     * [atFieldStart] says whether [window] reached the beginning of the field.
     * When it did not, a span is never anchored at the very first character:
     * the read may have cut a word in half, and a pattern matching that half
     * would delete text the user cannot see the start of.
     *
     * [confirm] picks which half of the list is searched: the patterns that
     * rewrite text on their own, or the ones that only offer to. They are asked
     * for at different moments and one must never answer for the other — a
     * pattern that expands by itself has no business appearing as a chip
     * mid-word, and one that asks first must not fire on the space.
     *
     * [isName] says whether one word reads as a name, for the groups a pattern
     * names `name` (#1291): a match whose name group holds a word that does
     * not is no match, and the search goes on. Null lets any word through, as
     * the settings screen's tester does, having no dictionary to ask.
     */
    fun matchPattern(
        window: CharSequence,
        atFieldStart: Boolean = false,
        now: Long = System.currentTimeMillis(),
        context: SnippetStore.Companion.Context = SnippetStore.Companion.Context(),
        confirm: Boolean = false,
        isName: ((String) -> Boolean)? = null,
    ): SnippetMatch? {
        if (if (confirm) !hasConfirmPatterns else !hasAutoPatterns) return null
        val whole = window.toString()
        val line = whole.substringAfterLast('\n')
        val trimmed = if (line.length > SnippetMatcher.MAX_WINDOW) {
            line.substring(line.length - SnippetMatcher.MAX_WINDOW)
        } else {
            line
        }
        // A cut window can open in the middle of a surrogate pair. Drop the
        // orphan so no span ever starts on half a character.
        val w = if (trimmed.isNotEmpty() && Character.isLowSurrogate(trimmed[0])) {
            trimmed.substring(1)
        } else {
            trimmed
        }
        if (w.isBlank()) return null
        // A span may start at the very first character only when that character
        // is known to begin something: the field itself, or a line. Cutting the
        // window at [SnippetMatcher.MAX_WINDOW] can land in the middle of a
        // word, and matching that half would delete text the user cannot see
        // the start of — so a cut window is never anchored, whatever the caller
        // says about the field.
        val cutAtCap = trimmed.length < line.length
        val anchored = !cutAtCap && (atFieldStart || line.length < whole.length)
        return search(w, spanStarts(w), anchored, now, context, confirm, isName)
    }

    /**
     * Where a candidate span may start in [w], nearest to the cursor first:
     * each word's first character, then the first space of the run in front
     * of it (when there is one). [SpanStart.back] is how many words the span
     * reaches past the nearest one, the number the word budget is checked
     * against; a space run shares it with the word it precedes.
     */
    private fun spanStarts(w: String): List<SpanStart> {
        val starts = ArrayList<SpanStart>(SnippetMatcher.MAX_WORDS * 2)
        var back = 0
        var i = w.length
        while (i > 0 && back < SnippetMatcher.MAX_WORDS) {
            var j = i - 1
            while (j >= 0 && w[j].isWhitespace()) j--
            if (j < 0) break
            while (j >= 0 && !w[j].isWhitespace()) j--
            val word = j + 1
            starts += SpanStart(word, back)
            while (j >= 0 && w[j].isWhitespace()) j--
            val gap = j + 1
            if (gap < word) starts += SpanStart(gap, back)
            back++
            i = word
        }
        return starts
    }

    /** One place a span may begin, and how many words back from the cursor it is. */
    private class SpanStart(val offset: Int, val back: Int)

    private fun search(
        w: String,
        starts: List<SpanStart>,
        anchored: Boolean,
        now: Long,
        context: SnippetStore.Companion.Context,
        confirm: Boolean,
        isName: ((String) -> Boolean)?,
    ): SnippetMatch? {
        var attempts = 0
        for (candidate in starts) {
            val start = candidate.offset
            val back = candidate.back
            if (start == 0 && !anchored) continue
            val span = w.substring(start)
            val gated = byHead[span[0].lowercaseChar()].orEmpty()
            for (pass in 0..1) {
                for (compiled in if (pass == 0) gated else ungated) {
                    if (asks(compiled.snippet) != confirm) continue
                    if (compiled.snippet.id in stopped) continue
                    if (back + 1 > compiled.words) continue
                    val head = compiled.head
                    if (head != null &&
                        !span.regionMatches(0, head, 0, head.length, ignoreCase = true)
                    ) {
                        continue
                    }
                    if (attempts++ >= SnippetMatcher.MAX_ATTEMPTS) return null
                    val hit = run(compiled, span, w.length - start, now, context, isName)
                    if (hit != null) return hit
                }
            }
        }
        return null
    }

    /** Runs one pattern under the step budget, or stops it for good. */
    private fun run(
        compiled: CompiledSnippet,
        span: String,
        consumed: Int,
        now: Long,
        context: SnippetStore.Companion.Context,
        isName: ((String) -> Boolean)?,
    ): SnippetMatch? {
        val matcher = compiled.pattern.matcher(GuardedRegex.budgeted(span, SnippetMatcher.MAX_STEPS))
        val matched = try {
            matcher.matches()
        } catch (_: GuardedRegex.Runaway) {
            stopped.add(compiled.snippet.id)
            return null
        }
        if (!matched) return null
        // A name group holding a word that is no name: "hello am" is not a
        // greeting for someone called Am (#1291).
        if (isName != null) {
            for (index in compiled.nameGroups) {
                if (index > matcher.groupCount()) continue
                val caught = matcher.group(index) ?: continue
                if (!isNameText(caught, isName)) return null
            }
        }
        val groups = minOf(matcher.groupCount(), SnippetMatcher.MAX_GROUPS)
        // Read out now, while the matcher still holds the match. The snippet's
        // other expansions are templated with exactly these, so every chip on
        // the strip means the same thing by `$1`.
        val captured = ArrayList<String?>(groups + 1)
        captured.add(matcher.group())
        for (index in 1..groups) captured.add(matcher.group(index))
        val expansion = SnippetMatcher.expandTemplate(compiled.snippet.text, now, context) { index ->
            captured.getOrNull(index)
        }
        return SnippetMatch(
            snippet = compiled.snippet,
            text = expansion.text,
            cursorOffset = expansion.caret,
            consumedChars = consumed,
            consumedText = span,
            groups = captured,
        )
    }

    /**
     * Whether every word in [text] reads as a name to [isName], with the
     * punctuation around each word left out ("John," is John). Text with no
     * word in it is no name.
     */
    private fun isNameText(text: String, isName: (String) -> Boolean): Boolean {
        var any = false
        for (raw in text.split(' ', '\t')) {
            val word = raw.trim { !SnippetMatcher.isTriggerWordChar(it) && it != '-' }
            if (word.isEmpty()) continue
            any = true
            if (!isName(word)) return false
        }
        return any
    }

    companion object {

        fun of(
            snippets: List<Snippet>,
            multiExpand: MultiExpandMode = MultiExpandMode.CHIPS_ONLY,
        ): SnippetIndex {
            val asking = HashSet<Long>()
            val plain = LinkedHashMap<String, Snippet>()
            val prefixed = LinkedHashMap<String, MutableList<PrefixTrigger>>()
            val suffixed = LinkedHashMap<Char, MutableList<SuffixTrigger>>()
            val immediate = LinkedHashMap<Char, MutableList<ImmediateTrigger>>()
            val immediateWords = LinkedHashMap<String, Snippet>()
            val byHead = LinkedHashMap<Char, MutableList<CompiledSnippet>>()
            val ungated = ArrayList<CompiledSnippet>()
            for (snippet in snippets) {
                if (snippet.asks(multiExpand)) asking.add(snippet.id)
                // A snippet that asks has nothing to fire early, so it keeps
                // the ordinary trigger paths and its chip.
                val early = snippet.immediate && snippet.id !in asking
                // A plain trigger is the more specific and the cheaper rule, so
                // a snippet carrying both never reaches the pattern side. Every
                // alias is registered exactly as the trigger is, prefix rules
                // included, so which spelling a snippet was reached by makes no
                // difference to how it expands.
                var triggered = false
                for (spelling in snippet.spellings()) {
                    triggered = true
                    // A symbol at the end is matched off the field the moment
                    // it lands (#471); it has no word for either map below.
                    if (SnippetMatcher.isSuffixTrigger(spelling)) {
                        suffixed.getOrPut(spelling.last().lowercaseChar()) { ArrayList() } +=
                            SuffixTrigger(spelling, snippet)
                        continue
                    }
                    // Fired on its own last character, and kept out of the two
                    // maps below on purpose: those are read when a word ends,
                    // and an immediate trigger still standing at that point was
                    // escaped or taken back, so it must stay as typed.
                    if (early) {
                        immediate.getOrPut(spelling.last().lowercaseChar()) { ArrayList() } +=
                            ImmediateTrigger(spelling, snippet)
                        if (spelling.all(SnippetMatcher::isTriggerWordChar)) {
                            immediateWords.putIfAbsent(spelling.lowercase(Locale.ROOT), snippet)
                        }
                        continue
                    }
                    val split = SnippetMatcher.splitPrefix(spelling)
                    if (split == null) {
                        plain.putIfAbsent(spelling.lowercase(Locale.ROOT), snippet)
                    } else {
                        prefixed.getOrPut(split.word.lowercase(Locale.ROOT)) { ArrayList() } +=
                            PrefixTrigger(split.prefix, spelling, snippet)
                    }
                    // A hyphenated last word also arrives whole, the way the
                    // buffer joins it (#659). Only one of the two spellings can
                    // ever fit what was typed, so registering both never
                    // doubles a match.
                    val compound = SnippetMatcher.splitCompound(spelling) ?: continue
                    if (compound.prefix.isEmpty()) {
                        plain.putIfAbsent(compound.word.lowercase(Locale.ROOT), snippet)
                    } else {
                        prefixed.getOrPut(compound.word.lowercase(Locale.ROOT)) { ArrayList() } +=
                            PrefixTrigger(compound.prefix, spelling, snippet)
                    }
                }
                if (triggered) continue
                val source = snippet.triggerPattern?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (SnippetMatcher.validate(source) != null) continue
                val compiled = CompiledSnippet(
                    snippet = snippet,
                    pattern = SnippetMatcher.compile(source),
                    head = SnippetMatcher.headOf(source),
                    words = snippet.triggerWords.takeIf { it in 1..SnippetMatcher.MAX_WORDS }
                        ?: SnippetMatcher.DEFAULT_WORDS,
                    nameGroups = SnippetMatcher.nameGroupsOf(source),
                )
                val head = compiled.head
                if (head == null) {
                    ungated += compiled
                } else {
                    byHead.getOrPut(head[0]) { ArrayList() } += compiled
                }
            }
            // Longest prefix first, so `::x` is preferred to `:x` when a user
            // has installed both and the field ends in "::".
            for (list in prefixed.values) list.sortByDescending { it.prefix.length }
            for (list in suffixed.values) list.sortByDescending { it.typed.length }
            for (list in immediate.values) list.sortByDescending { it.typed.length }
            return SnippetIndex(plain, prefixed, suffixed, immediate, immediateWords, byHead, ungated, asking)
        }
    }
}
