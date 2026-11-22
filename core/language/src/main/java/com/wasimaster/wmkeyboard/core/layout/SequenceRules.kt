package com.wasimaster.wmkeyboard.core.layout

import android.content.res.AssetManager
import com.wasimaster.wmkeyboard.language.R
import kotlinx.serialization.Serializable

/**
 * One typing rule of a layout (#1493): when the text just before the caret,
 * with the key just typed, ends in [pattern], that tail becomes [replace].
 *
 * The rules are what a script needs from a keyboard that its keys alone
 * cannot say: Thai and Lao take their tone mark after the vowel however it was
 * typed, ํ then า is the one letter ำ, ا then a hamza is أ, and a then 1 is ā on
 * a pinyin board. Data rather than code, so a layout of anyone's can carry
 * them, and the keyboard has no per-language engine to keep up.
 *
 * Written with literal characters and `{group}` slots, each taking any one
 * item of a [LayoutSpec.characterGroups] group. In [replace], `{group}` writes
 * back what that group's slot matched (the second `{group}` the second slot,
 * and so on), `$1` to `$9` the slots by position, and `{to:from}` the item of
 * `to` at the place `from` matched in its own group. `{{` and `}}` are
 * literal braces, `$$` a literal dollar.
 *
 * With [undo], the Backspace right after the rule fired puts back what was
 * typed instead of deleting a character: a1 for the ā a pinyin rule wrote.
 */
@Serializable
data class SequenceRule(
    val pattern: String,
    val replace: String = "",
    val undo: Boolean = false,
)

/**
 * A shared set of typing rules, shipped as an asset (`assets/sequence_rules/`)
 * and named by layouts in [LayoutSpec.ruleSets], so the five Thai layouts
 * share one copy of the Thai rules rather than five that drift.
 *
 * Its groups are its own: a set's rules see only the set's groups, and a
 * layout's only the layout's, so the two can never collide on a name.
 */
@Serializable
data class SequenceRuleSet(
    val format: String = SequenceRuleSets.FORMAT,
    val id: String,
    val name: String = "",
    val description: String = "",
    val characterGroups: Map<String, List<String>> = emptyMap(),
    val sequenceRules: List<SequenceRule> = emptyList(),
)

/** What can be wrong with one [SequenceRule]; see [SequenceRuleProblem]. */
enum class SequenceRuleError {
    /** Nothing to match. */
    EMPTY_PATTERN,

    /** A `{name}` naming no group. */
    UNKNOWN_GROUP,

    /** A `{` with no `}`, a name with characters a group name cannot have, or `{a:b}` in a pattern. */
    BAD_PLACEHOLDER,

    /** Longer than [MaxSequencePatternLength] characters. */
    PATTERN_TOO_LONG,

    /** The replacement names a group, or a `$n`, the pattern has no slot for. */
    NO_SUCH_SLOT,

    /** `{to:from}` where `to` has fewer items than `from`, so some of `from` would map to nothing. */
    UNEVEN_MAP,
}

/** One rule that does not compile: its place in its list, what is wrong, and the name or text at fault. */
data class SequenceRuleProblem(val index: Int, val error: SequenceRuleError, val detail: String = "")

/** How many rules one layout or set keeps. */
const val MaxSequenceRules = 500

/** How many items one character group keeps. */
const val MaxSequenceGroupItems = 200

/** How many groups one layout or set keeps. */
const val MaxSequenceGroups = 100

/** The longest pattern, in characters. No script's syllable needs more; a longer one is a mistake. */
const val MaxSequencePatternLength = 32

/** What a group may be called: letters, digits, `_` and `-`. */
fun isSequenceGroupName(name: String): Boolean =
    name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '-' }

/**
 * The rules a layout types with, compiled once: its own rules, then the rules
 * of every set it names, in that order. First match wins and a keystroke makes
 * at most one rewrite, the way jquery.ime's rule files type, so two rules that
 * undo each other (a swap and its reverse) cannot loop.
 */
class CompiledSequenceRules internal constructor(val rules: List<CompiledSequenceRule>) {

    /**
     * The last characters any rule can end on, as code points. A key whose
     * text ends on none of them cannot fire a rule, which is the test the
     * keypress path makes before it reads the field.
     */
    val endChars: Set<Int> = rules.flatMapTo(HashSet()) { it.endChars }

    /** The most UTF-16 units before the caret any rule can need. */
    val lookback: Int = (rules.maxOfOrNull { it.longest } ?: 0).coerceAtMost(MaxLookback)

    /** Equal when the same rules from the same places, so a recompiled set compares equal to the last. */
    override fun equals(other: Any?): Boolean =
        other is CompiledSequenceRules && other.rules.size == rules.size &&
            rules.indices.all { rules[it].sameAs(other.rules[it]) }

    override fun hashCode(): Int = rules.fold(0) { h, r -> 31 * h + r.rule.hashCode() }

    /** Whether [typed] could fire a rule at all. */
    fun mayFire(typed: String): Boolean =
        typed.isNotEmpty() && typed.codePointBefore(typed.length) in endChars

    /**
     * What typing [typed] with [before] behind the caret does, or null when
     * no rule fires and the key types as it is.
     */
    fun edit(before: CharSequence, typed: String): SequenceEdit? {
        if (!mayFire(typed)) return null
        val window = before.takeLastSafe(lookback).toString()
        val text = window + typed
        for (rule in rules) {
            val match = rule.match(text) ?: continue
            val replaced = rule.replacement(match)
            val tail = text.substring(match.start)
            // A rule that writes back exactly what was typed is no rule at all
            // for this key: the key types as it is, and nothing is undone later.
            if (replaced == tail) return null
            val (delete, insert) = if (match.start <= window.length) {
                (window.length - match.start) to replaced
            } else {
                0 to typed.substring(0, match.start - window.length) + replaced
            }
            return SequenceEdit(
                delete = delete,
                text = insert,
                deleted = window.substring(window.length - delete),
                restore = if (rule.undo) tail else null,
                rule = rule,
            )
        }
        return null
    }

    /**
     * [keys] typed one character after another into an empty field: what it
     * then holds, and the rule the last rewrite came from. For the editor's
     * try-it box and for tests.
     */
    fun run(keys: String): SequenceRun {
        val out = StringBuilder()
        var last: CompiledSequenceRule? = null
        var i = 0
        while (i < keys.length) {
            val cp = keys.codePointAt(i)
            val key = String(Character.toChars(cp))
            i += key.length
            val edit = edit(out, key)
            if (edit == null) {
                out.append(key)
            } else {
                out.setLength(out.length - edit.delete)
                out.append(edit.text)
                last = edit.rule
            }
        }
        return SequenceRun(out.toString(), last)
    }

    companion object {
        /** UTF-16 units read back from the caret at most. */
        const val MaxLookback = 64

        /**
         * Compiles one list of rules against [groups], ignoring the rules that
         * do not compile and listing them in [Compiled.problems]. [source] is
         * the rule set's id, or null for a layout's own rules.
         */
        fun compile(
            groups: Map<String, List<String>>,
            rules: List<SequenceRule>,
            source: String? = null,
        ): Compiled {
            val problems = ArrayList<SequenceRuleProblem>()
            val compiled = ArrayList<CompiledSequenceRule>()
            // Groups as written, empty items and all: `{to:from}` pairs the two
            // groups by place, so dropping one item would shift every one after
            // it. Empty items simply never match; the repair pass reports them.
            rules.forEachIndexed { index, rule ->
                when (val result = RuleParser(groups, rule, index, source).parse()) {
                    is CompiledSequenceRule -> compiled += result
                    is SequenceRuleProblem -> problems += result
                }
            }
            return Compiled(compiled, problems)
        }

        /** Every rule of [parts] in order, or null when there are none. */
        fun of(parts: List<Compiled>): CompiledSequenceRules? {
            val all = parts.flatMap { it.rules }
            return if (all.isEmpty()) null else CompiledSequenceRules(all)
        }
    }

    /** One list's rules after [compile], and the ones that did not make it. */
    class Compiled(val rules: List<CompiledSequenceRule>, val problems: List<SequenceRuleProblem>)
}

/** What [CompiledSequenceRules.edit] does to the field: take [delete] UTF-16 units from before the caret, then type [text]. */
data class SequenceEdit(
    val delete: Int,
    val text: String,
    /**
     * The [delete] units this took out, so the edit can be wound back to the
     * text before the key: what a multitap key's next tap does to its last.
     */
    val deleted: String,
    /** What the Backspace right after this puts back, for a rule written with `undo`; null otherwise. */
    val restore: String?,
    val rule: CompiledSequenceRule,
)

/** What [CompiledSequenceRules.run] typed, and the rule that made its last rewrite (null if none did). */
data class SequenceRun(val text: String, val lastRule: CompiledSequenceRule?)

/**
 * One rule, parsed. [source] and [index] say where it came from, for the
 * editor to name the rule that fired.
 */
class CompiledSequenceRule internal constructor(
    val source: String?,
    val index: Int,
    val rule: SequenceRule,
    private val pattern: List<PatternToken>,
    private val replace: List<ReplaceToken>,
) {
    private val slots: List<PatternToken.Slot> = pattern.filterIsInstance<PatternToken.Slot>()

    val undo: Boolean get() = rule.undo

    internal fun sameAs(other: CompiledSequenceRule): Boolean =
        source == other.source && index == other.index && rule == other.rule && replacementKey == other.replacementKey

    /** What the groups resolved to, so two compiles of one rule against different groups are told apart. */
    private val replacementKey: List<Any> = pattern.map { if (it is PatternToken.Slot) it.items else (it as PatternToken.Literal).text }

    internal val endChars: Set<Int> = when (val last = pattern.last()) {
        is PatternToken.Literal -> setOf(last.text.codePointBefore(last.text.length))
        is PatternToken.Slot -> last.items.filter { it.isNotEmpty() }.mapTo(HashSet()) { it.codePointBefore(it.length) }
    }

    internal val longest: Int = pattern.sumOf { token ->
        when (token) {
            is PatternToken.Literal -> token.text.length
            is PatternToken.Slot -> token.items.maxOf { it.length }
        }
    }

    internal class Match(val start: Int, val picks: IntArray)

    /** The match of this rule at the end of [text], or null. */
    internal fun match(text: String): Match? {
        val picks = IntArray(pattern.count { it is PatternToken.Slot })
        val start = matchFrom(text, pattern.lastIndex, text.length, picks) ?: return null
        // Never begin inside a surrogate pair: half a character is not one to rewrite.
        if (start in 1 until text.length && Character.isHighSurrogate(text[start - 1]) &&
            Character.isLowSurrogate(text[start])
        ) {
            return null
        }
        return Match(start, picks)
    }

    private fun matchFrom(text: String, token: Int, end: Int, picks: IntArray): Int? {
        if (token < 0) return end
        return when (val t = pattern[token]) {
            is PatternToken.Literal -> {
                val start = end - t.text.length
                if (start < 0 || !text.regionMatches(start, t.text, 0, t.text.length)) return null
                matchFrom(text, token - 1, start, picks)
            }
            is PatternToken.Slot -> {
                // Longest item first, so a cluster item wins over its own first letter.
                for (i in t.byLength) {
                    val item = t.items[i]
                    if (item.isEmpty()) continue
                    val start = end - item.length
                    if (start < 0 || !text.regionMatches(start, item, 0, item.length)) continue
                    picks[t.slot] = i
                    matchFrom(text, token - 1, start, picks)?.let { return it }
                }
                null
            }
        }
    }

    internal fun replacement(match: Match): String {
        val out = StringBuilder()
        for (token in replace) {
            when (token) {
                is ReplaceToken.Literal -> out.append(token.text)
                is ReplaceToken.SlotRef -> out.append(slots[token.slot].items[match.picks[token.slot]])
                is ReplaceToken.Mapped -> out.append(token.to[match.picks[token.fromSlot]])
            }
        }
        return out.toString()
    }

    override fun toString(): String = "${source ?: "layout"}#$index ${rule.pattern} -> ${rule.replace}"
}

internal sealed interface PatternToken {
    class Literal(val text: String) : PatternToken

    /** The [slot]-th group slot of its pattern, taking any one of [items]. */
    class Slot(val group: String, val items: List<String>, val slot: Int) : PatternToken {
        /** Indices into [items], longest item first; ties keep the written order. */
        val byLength: List<Int> = items.indices.sortedByDescending { items[it].length }
    }
}

internal sealed interface ReplaceToken {
    class Literal(val text: String) : ReplaceToken
    class SlotRef(val slot: Int) : ReplaceToken
    class Mapped(val to: List<String>, val fromSlot: Int) : ReplaceToken
}

/** Reads one [SequenceRule] into a [CompiledSequenceRule], or says what is wrong with it. */
private class RuleParser(
    private val groups: Map<String, List<String>>,
    private val rule: SequenceRule,
    private val index: Int,
    private val source: String?,
) {
    private fun problem(error: SequenceRuleError, detail: String = "") = SequenceRuleProblem(index, error, detail)

    fun parse(): Any {
        if (rule.pattern.isEmpty()) return problem(SequenceRuleError.EMPTY_PATTERN)
        if (rule.pattern.codePointCount(0, rule.pattern.length) > MaxSequencePatternLength) {
            return problem(SequenceRuleError.PATTERN_TOO_LONG, rule.pattern)
        }
        val pattern = ArrayList<PatternToken>()
        val slotsByGroup = LinkedHashMap<String, MutableList<Int>>()
        var slots = 0
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                pattern += PatternToken.Literal(literal.toString())
                literal.setLength(0)
            }
        }
        val p = rule.pattern
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '{' && p.startsWith("{{", i) -> { literal.append('{'); i += 2 }
                c == '}' && p.startsWith("}}", i) -> { literal.append('}'); i += 2 }
                c == '{' -> {
                    val end = p.indexOf('}', i)
                    if (end < 0) return problem(SequenceRuleError.BAD_PLACEHOLDER, p.substring(i))
                    val name = p.substring(i + 1, end)
                    if (!isSequenceGroupName(name)) return problem(SequenceRuleError.BAD_PLACEHOLDER, "{$name}")
                    val items = groups[name]?.takeIf { list -> list.any { it.isNotEmpty() } }
                        ?: return problem(SequenceRuleError.UNKNOWN_GROUP, name)
                    flush()
                    pattern += PatternToken.Slot(name, items, slots)
                    slotsByGroup.getOrPut(name) { ArrayList() } += slots
                    slots++
                    i = end + 1
                }
                c == '}' -> return problem(SequenceRuleError.BAD_PLACEHOLDER, "}")
                else -> { literal.append(c); i++ }
            }
        }
        flush()
        if (pattern.isEmpty()) return problem(SequenceRuleError.EMPTY_PATTERN)

        val replace = ArrayList<ReplaceToken>()
        val used = HashMap<String, Int>()
        /** The next slot of [name], in order, the last one again once they run out. */
        fun nextSlot(name: String): Int? {
            val list = slotsByGroup[name] ?: return null
            val k = used.getOrDefault(name, 0)
            used[name] = k + 1
            return list[k.coerceAtMost(list.lastIndex)]
        }
        val r = rule.replace
        val lit = StringBuilder()
        fun flushReplace() {
            if (lit.isNotEmpty()) {
                replace += ReplaceToken.Literal(lit.toString())
                lit.setLength(0)
            }
        }
        i = 0
        while (i < r.length) {
            val c = r[i]
            when {
                c == '{' && r.startsWith("{{", i) -> { lit.append('{'); i += 2 }
                c == '}' && r.startsWith("}}", i) -> { lit.append('}'); i += 2 }
                c == '$' && r.startsWith("$$", i) -> { lit.append('$'); i += 2 }
                c == '$' && i + 1 < r.length && r[i + 1] in '1'..'9' -> {
                    val n = r[i + 1] - '1'
                    if (n >= slots) return problem(SequenceRuleError.NO_SUCH_SLOT, "$" + (n + 1))
                    flushReplace()
                    replace += ReplaceToken.SlotRef(n)
                    i += 2
                }
                c == '{' -> {
                    val end = r.indexOf('}', i)
                    if (end < 0) return problem(SequenceRuleError.BAD_PLACEHOLDER, r.substring(i))
                    val body = r.substring(i + 1, end)
                    val to = body.substringBefore(':')
                    val from = body.substringAfter(':', "")
                    if (!isSequenceGroupName(to) || (':' in body && !isSequenceGroupName(from))) {
                        return problem(SequenceRuleError.BAD_PLACEHOLDER, "{$body}")
                    }
                    flushReplace()
                    if (':' in body) {
                        val toItems = groups[to]?.takeIf { list -> list.any { it.isNotEmpty() } }
                            ?: return problem(SequenceRuleError.UNKNOWN_GROUP, to)
                        val slot = nextSlot(from) ?: return problem(SequenceRuleError.NO_SUCH_SLOT, from)
                        val fromSize = groups[from]?.size ?: 0
                        if (toItems.size < fromSize) return problem(SequenceRuleError.UNEVEN_MAP, "$to:$from")
                        replace += ReplaceToken.Mapped(toItems, slot)
                    } else {
                        val slot = nextSlot(to)
                            ?: return problem(
                                if (to in groups) SequenceRuleError.NO_SUCH_SLOT else SequenceRuleError.UNKNOWN_GROUP,
                                to,
                            )
                        replace += ReplaceToken.SlotRef(slot)
                    }
                    i = end + 1
                }
                c == '}' -> return problem(SequenceRuleError.BAD_PLACEHOLDER, "}")
                else -> { lit.append(c); i++ }
            }
        }
        flushReplace()
        return CompiledSequenceRule(source, index, rule, pattern, replace)
    }
}

/**
 * The shipped rule sets, read from `assets/sequence_rules/<id>.json` the first
 * time each is asked for. Loaded by [AssetLayouts.load], which already holds
 * the [AssetManager]; until then (and in a JVM test that never loads it) no set
 * is known, and a layout naming one simply types without it.
 */
object SequenceRuleSets {

    /** Tags a rule set file, as `wmkeyboard-layout` tags a layout. */
    const val FORMAT = "wmkeyboard-sequence-rules"

    private const val DIR = "sequence_rules"
    private const val SUFFIX = ".json"

    @Volatile private var assets: AssetManager? = null
    @Volatile private var known: List<String>? = null
    private val parsed = HashMap<String, SequenceRuleSet>()

    /** Sets given here win over the assets: for tests, and for anything that builds sets in code. */
    private val extra = HashMap<String, SequenceRuleSet>()

    fun load(assets: AssetManager) {
        if (this.assets != null) return
        this.assets = assets
        known = runCatching { assets.list(DIR)?.asList() }.getOrNull().orEmpty()
            .filter { it.endsWith(SUFFIX) }
            .map { it.removeSuffix(SUFFIX) }
            .sorted()
    }

    /** Whether the shipped list has been read, so an unknown id can be told from one not read yet. */
    val isLoaded: Boolean get() = known != null || synchronized(extra) { extra.isNotEmpty() }

    /** Every shipped set's id, in name order of the files. */
    fun ids(): List<String> = (known.orEmpty() + synchronized(extra) { extra.keys }).distinct()

    /** The set [id] names, or null when there is no such set (or the list is not read yet). */
    fun byId(id: String): SequenceRuleSet? {
        synchronized(extra) { extra[id] }?.let { return it }
        synchronized(parsed) { parsed[id] }?.let { return it }
        if (known?.contains(id) != true) return null
        val manager = assets ?: return null
        val set = runCatching {
            val text = manager.open("$DIR/$id$SUFFIX").use { it.readBytes().decodeToString() }
            decode(text)
        }.getOrNull()?.takeIf { it.id == id } ?: return null
        return synchronized(parsed) { parsed.getOrPut(id) { set } }
    }

    /** A rule set file, or null when [text] is not one. */
    fun decode(text: String): SequenceRuleSet? =
        runCatching { layoutJson.decodeFromString<SequenceRuleSet>(text) }.getOrNull()
            ?.takeIf { it.format == FORMAT && it.id.isNotBlank() }

    /** Registers [set] in place of any asset of the same id. */
    fun register(set: SequenceRuleSet) {
        synchronized(extra) { extra[set.id] = set }
        synchronized(compiledCache) { compiledCache.remove(set.id) }
    }

    private val compiledCache = HashMap<String, CompiledSequenceRules.Compiled>()

    /** [id]'s rules compiled, cached: every layout naming the set shares the one copy. */
    fun compiled(id: String): CompiledSequenceRules.Compiled? {
        synchronized(compiledCache) { compiledCache[id] }?.let { return it }
        val set = byId(id) ?: return null
        val compiled = CompiledSequenceRules.compile(set.characterGroups, set.sequenceRules, source = id)
        return synchronized(compiledCache) { compiledCache.getOrPut(id) { compiled } }
    }
}

/**
 * The rules this layout types with, compiled: its own, then each named set's.
 * Null when it has none, which is every layout but the few that need them.
 */
fun LayoutSpec.compiledSequenceRules(): CompiledSequenceRules? {
    val own = sequenceRules?.takeIf { it.isNotEmpty() }
        ?.let { CompiledSequenceRules.compile(characterGroups.orEmpty(), it) }
    val sets = ruleSets.orEmpty().distinct().mapNotNull { SequenceRuleSets.compiled(it) }
    return CompiledSequenceRules.of(listOfNotNull(own) + sets)
}

/** The last [n] UTF-16 units, one fewer when the cut would split a surrogate pair. */
private fun CharSequence.takeLastSafe(n: Int): CharSequence {
    if (length <= n) return this
    var start = length - n
    if (Character.isLowSurrogate(this[start])) start++
    return subSequence(start, length)
}

/**
 * [LayoutSpec.characterGroups], [LayoutSpec.sequenceRules] and
 * [LayoutSpec.ruleSets] after the repair pass: groups with a name a rule
 * cannot write, or nothing in them, go; rules that do not compile go; sets
 * this build does not ship go. One note each. Empty lists are stored as null.
 *
 * Empty items inside a group stay: `{to:from}` pairs two groups by place, and
 * an author may leave a hole on purpose where one group has no counterpart.
 */
internal fun LayoutSpec.withRepairedSequenceRules(repairs: MutableList<LayoutMessage>): LayoutSpec {
    if (characterGroups == null && sequenceRules == null && ruleSets == null) return this
    val groups = LinkedHashMap<String, List<String>>()
    for ((name, items) in characterGroups.orEmpty()) {
        when {
            !isSequenceGroupName(name) ->
                repairs += LayoutMessage(R.string.core_lang_repair_group_bad_name, args = listOf(name))
            items.none { it.isNotEmpty() } ->
                repairs += LayoutMessage(R.string.core_lang_repair_group_empty, args = listOf(name))
            groups.size >= MaxSequenceGroups -> Unit
            items.size > MaxSequenceGroupItems -> {
                repairs += LayoutMessage(
                    R.string.core_lang_repair_group_trimmed,
                    args = listOf(name, items.size, MaxSequenceGroupItems),
                )
                groups[name] = items.take(MaxSequenceGroupItems)
            }
            else -> groups[name] = items
        }
    }
    val validGroups = characterGroups.orEmpty().count { (name, items) ->
        isSequenceGroupName(name) && items.any { it.isNotEmpty() }
    }
    if (validGroups > MaxSequenceGroups) {
        repairs += LayoutMessage(
            R.string.core_lang_repair_groups_trimmed,
            args = listOf(validGroups, MaxSequenceGroups),
        )
    }

    var rules = sequenceRules.orEmpty()
    if (rules.size > MaxSequenceRules) {
        repairs += LayoutMessage(
            pluralsRes = R.plurals.core_lang_repair_rules_trimmed,
            quantity = rules.size,
            args = listOf(rules.size, MaxSequenceRules),
        )
        rules = rules.take(MaxSequenceRules)
    }
    val problems = CompiledSequenceRules.compile(groups, rules).problems.associateBy { it.index }
    for (problem in problems.values) repairs += problem.message()
    val keptRules = rules.filterIndexed { i, _ -> i !in problems }

    val sets = ruleSets.orEmpty().distinct().filter { id ->
        val keep = !SequenceRuleSets.isLoaded || SequenceRuleSets.byId(id) != null
        if (!keep) repairs += LayoutMessage(R.string.core_lang_repair_rule_set_unknown, args = listOf(id))
        keep
    }
    return copy(
        characterGroups = groups.takeIf { it.isNotEmpty() },
        sequenceRules = keptRules.takeIf { it.isNotEmpty() },
        ruleSets = sets.takeIf { it.isNotEmpty() },
    )
}

/** The repair note for a rule that does not compile, numbered from 1 as the editor shows it. */
fun SequenceRuleProblem.message(): LayoutMessage {
    val n = index + 1
    return when (error) {
        SequenceRuleError.EMPTY_PATTERN -> LayoutMessage(R.string.core_lang_repair_rule_empty, args = listOf(n))
        SequenceRuleError.UNKNOWN_GROUP ->
            LayoutMessage(R.string.core_lang_repair_rule_unknown_group, args = listOf(n, detail))
        SequenceRuleError.BAD_PLACEHOLDER ->
            LayoutMessage(R.string.core_lang_repair_rule_bad_placeholder, args = listOf(n, detail))
        SequenceRuleError.PATTERN_TOO_LONG ->
            LayoutMessage(R.string.core_lang_repair_rule_too_long, args = listOf(n, MaxSequencePatternLength))
        SequenceRuleError.NO_SUCH_SLOT ->
            LayoutMessage(R.string.core_lang_repair_rule_no_slot, args = listOf(n, detail))
        SequenceRuleError.UNEVEN_MAP ->
            LayoutMessage(R.string.core_lang_repair_rule_uneven_map, args = listOf(n, detail))
    }
}

/**
 * [set]'s groups and rules added after a layout's own [groups] and [rules], for
 * the editor's "Copy into this layout". A set group whose name the layout
 * already uses for other characters comes in as `<set id>_<name>`, and the
 * set's rules are rewritten to name it so; one with the same characters is
 * shared.
 */
fun copySequenceRuleSet(
    set: SequenceRuleSet,
    groups: Map<String, List<String>>,
    rules: List<SequenceRule>,
): Pair<Map<String, List<String>>, List<SequenceRule>> {
    val merged = LinkedHashMap(groups)
    val renames = HashMap<String, String>()
    for ((name, items) in set.characterGroups) {
        val existing = merged[name]
        when {
            existing == null -> merged[name] = items
            existing == items -> Unit
            else -> {
                var fresh = "${set.id}_$name"
                var n = 2
                while (fresh in merged && merged[fresh] != items) fresh = "${set.id}_${name}_${n++}"
                merged[fresh] = items
                renames[name] = fresh
            }
        }
    }
    val copied = set.sequenceRules.map { rule ->
        if (renames.isEmpty()) {
            rule
        } else {
            rule.copy(pattern = renameSequenceGroups(rule.pattern, renames), replace = renameSequenceGroups(rule.replace, renames))
        }
    }
    return merged to (rules + copied)
}

/**
 * [text] with every `{name}`, `{name:other}` and `{other:name}` placeholder
 * renamed by [renames]; literal braces (`{{`, `}}`) and everything else as
 * they are. Also what the editor uses when a group is renamed.
 */
fun renameSequenceGroups(text: String, renames: Map<String, String>): String {
    if ('{' !in text || renames.isEmpty()) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("{{", i) || text.startsWith("}}", i) -> {
                out.append(text, i, i + 2)
                i += 2
            }
            c == '{' -> {
                val end = text.indexOf('}', i)
                if (end < 0) {
                    out.append(text, i, text.length)
                    i = text.length
                } else {
                    val parts = text.substring(i + 1, end).split(':')
                    out.append('{').append(parts.joinToString(":") { renames[it] ?: it }).append('}')
                    i = end + 1
                }
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/** Whether [rule] names the group [name] anywhere. */
fun SequenceRule.usesGroup(name: String): Boolean =
    renameSequenceGroups(pattern, mapOf(name to "\u0000")) != pattern ||
        renameSequenceGroups(replace, mapOf(name to "\u0000")) != replace

/**
 * `\uXXXX` written in [text] as the character it names, and `\U0001F600` for one past
 * the BMP, for the editor's fields: a combining mark is easier to type by its
 * code than to find on any keyboard. Anything else is left as written.
 */
fun unescapeSequenceText(text: String): String {
    if ('\\' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val long = c == '\\' && text.startsWith("\\U", i) && i + 10 <= text.length
        val short = c == '\\' && text.startsWith("\\u", i) && i + 6 <= text.length
        val cp = when {
            long -> text.substring(i + 2, i + 10).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }
            short -> text.substring(i + 2, i + 6).toIntOrNull(16)
            else -> null
        }
        if (cp != null) {
            out.appendCodePoint(cp)
            i += if (long) 10 else 6
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
