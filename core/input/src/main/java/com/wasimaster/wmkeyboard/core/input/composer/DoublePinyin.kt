package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Double Pinyin translation. Each syllable is two keystrokes — an initial key
 * and a final key — which this expands to full toneless Pinyin, after which the
 * ordinary [PinyinSyllables] pipeline takes over.
 *
 * Keys are ambiguous by design (one final key can stand for several finals, and
 * a/e/o double as zero-initial leads), so translation is *validity-filtered*:
 * the combination that forms a real syllable per the inventory wins. That both
 * disambiguates and makes the scheme tables forgiving of small mistakes.
 */
object DoublePinyin {

    /**
     * A scheme's key maps: first-key → initial, and final-key → candidate finals.
     *
     * [zeroLeads] are the first keys that start a zero-initial syllable, with
     * the final key after them (`oj` = an under Microsoft). a, e and o by
     * default, which is what every shipped table has always used. [codes] spell
     * a whole syllable from two keys outright and are tried before the key maps:
     * that is how a scheme like Xiaohe types ang as `ah`, and how a custom
     * scheme pins a pair the maps would read some other way.
     */
    data class Table(
        val initials: Map<Char, String>,
        val finals: Map<Char, List<String>>,
        val zeroLeads: Set<Char> = DEFAULT_ZERO_LEADS,
        val codes: Map<String, String> = emptyMap(),
    )

    /** The zero-initial lead keys of every shipped scheme. */
    val DEFAULT_ZERO_LEADS: Set<Char> = setOf('a', 'e', 'o')

    private val TABLES: Map<DoublePinyinScheme, Table> = mapOf(
        DoublePinyinScheme.MICROSOFT to MICROSOFT,
        DoublePinyinScheme.SOGOU to SOGOU,
        DoublePinyinScheme.XIAOHE to XIAOHE,
        DoublePinyinScheme.ZIRANMA to ZIRANMA,
        DoublePinyinScheme.PINYINPP to PINYINPP,
    )

    /**
     * The key maps for [scheme]. [DoublePinyinScheme.CUSTOM] is the user's own
     * scheme from [CjkConfig.customDoublePinyin], null while that is empty, so
     * typing falls back to full Pinyin rather than to some other scheme.
     */
    fun tableFor(scheme: DoublePinyinScheme): Table? =
        if (scheme == DoublePinyinScheme.CUSTOM) CjkConfig.customDoublePinyinTable else TABLES[scheme]

    /**
     * The syllables of a Double Pinyin key [buffer], each spanning exactly two
     * keys, using [valid] to disambiguate. A dangling final key (odd length —
     * a syllable still being typed) is left unconsumed, mirroring a partial full
     * Pinyin syllable.
     *
     * Apostrophes are skipped rather than paired. They carry no meaning here (a
     * Double Pinyin syllable is always exactly two keys, so there is no boundary
     * to disambiguate) but the user can still type one, and stepping blindly by
     * two would then pair the apostrophe with a real key and desync the parity of
     * every syllable after it. Skipped keys count toward the following syllable's
     * span so a prefix commit still deletes exactly what was typed.
     */
    fun segments(buffer: String, table: Table, valid: Set<String>): List<Seg> {
        val s = buffer.lowercase()
        val out = ArrayList<Seg>(s.length / 2)
        var i = 0
        while (i < s.length) {
            val a = skipSeparators(s, i)
            if (a + 1 >= s.length) break
            out.add(Seg(syllableFor(s[a], s[a + 1], table, valid), a + 2 - i))
            i = a + 2
        }
        return out
    }

    /**
     * Full toneless Pinyin for a key [buffer], for the composing-region preview.
     * A dangling final key is appended raw so the user still sees their keystroke.
     * Skips apostrophes on the same parity-preserving grounds as [segments].
     */
    fun translate(buffer: String, table: Table, valid: Set<String>): String {
        val s = buffer.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val a = skipSeparators(s, i)
            if (a + 1 >= s.length) { i = a; break }
            out.append(syllableFor(s[a], s[a + 1], table, valid))
            i = a + 2
        }
        if (i < s.length) out.append(s[i])
        return out.toString()
    }

    /** The table and inventory [keysCache] was read from, and what they gave. */
    @Volatile
    private var keysCache: Triple<Table, Set<String>, Map<String, String>>? = null

    /** Each syllable of [valid] and the two keys that type it under [table]. */
    fun keysOf(table: Table, valid: Set<String>): Map<String, String> {
        keysCache?.let { if (it.first === table && it.second === valid) return it.third }
        val out = HashMap<String, String>(valid.size * 2)
        for ((code, syllable) in table.codes) if (syllable !in out) out[syllable] = code
        for (first in table.initials.keys + table.zeroLeads) {
            for (second in table.finals.keys) {
                val syllable = syllableFor(first, second, table, valid)
                if (syllable in valid && syllable !in out) out[syllable] = "$first$second"
            }
        }
        keysCache = Triple(table, valid, out)
        return out
    }

    /**
     * The key that continues [syllables] after [typed], the keys pressed with
     * their apostrophes taken out (#1028): the next syllable's first key, or its
     * second after a first key is pressed. Null when [typed] reads otherwise or
     * the syllables are spent.
     */
    fun nextKey(
        typed: String,
        syllables: List<String>,
        table: Table,
        valid: Set<String>,
        spellings: (String) -> Set<String> = { setOf(it) },
    ): Char? {
        val done = segments(typed, table, valid)
        if (done.indices.any { i -> syllables.getOrNull(i)?.let { done[i].syllable in spellings(it) } != true }) return null
        val keys = keysOf(table, valid)[syllables.getOrNull(done.size) ?: return null] ?: return null
        return when {
            typed.length % 2 == 0 -> keys[0]
            typed.last() == keys[0] -> keys[1]
            else -> null
        }
    }

    /** First index at or after [from] that is not an apostrophe. */
    private fun skipSeparators(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] == '\'') i++
        return i
    }

    private fun syllableFor(c1: Char, c2: Char, table: Table, valid: Set<String>): String {
        if (table.codes.isNotEmpty()) table.codes["$c1$c2"]?.let { return it }
        val finals = table.finals[c2] ?: listOf(c2.toString())
        val cands = LinkedHashSet<String>()
        table.initials[c1]?.let { init -> finals.forEach { cands.add(init + it) } }
        // A lead key first means a zero-initial syllable (an, ang, ai, e, ou…).
        if (c1 in table.zeroLeads) finals.forEach { cands.add(it) }
        cands.firstOrNull { it in valid }?.let { return it }
        // Nothing valid yet (mid-typing or unknown code): best-effort literal.
        return (table.initials[c1] ?: c1.toString()) + (finals.firstOrNull() ?: c2.toString())
    }
}

/** Standard initials for Xiaohe, Microsoft, Sogou, and Ziranma: v=zh, i=ch, u=sh. */
private val STANDARD_INITIALS: Map<Char, String> = buildMap {
    for (c in listOf('b', 'p', 'm', 'f', 'd', 't', 'n', 'l', 'g', 'k', 'h',
            'j', 'q', 'x', 'r', 'z', 'c', 's', 'y', 'w')) put(c, c.toString())
    put('v', "zh"); put('i', "ch"); put('u', "sh")
}

/** Pinyin++ initials: v=zh, u=ch, i=sh. */
private val PINYINPP_INITIALS: Map<Char, String> = buildMap {
    for (c in listOf('b', 'p', 'm', 'f', 'd', 't', 'n', 'l', 'g', 'k', 'h',
            'j', 'q', 'x', 'r', 'z', 'c', 's', 'y', 'w')) put(c, c.toString())
    put('v', "zh"); put('u', "ch"); put('i', "sh")
}

/**
 * Xiaohe Shuangpin (小鹤双拼). Initials v/i/u stand for zh/ch/sh; every other
 * consonant key is itself. Final keys map to one or two finals, resolved by
 * validity. a/e/o additionally lead zero-initial syllables.
 */
private val XIAOHE = DoublePinyin.Table(
    initials = STANDARD_INITIALS,
    finals = mapOf(
        'a' to listOf("a"),
        'b' to listOf("in"),
        'c' to listOf("ao"),
        'd' to listOf("ai"),
        'e' to listOf("e"),
        'f' to listOf("en"),
        'g' to listOf("eng"),
        'h' to listOf("ang"),
        'i' to listOf("i"),
        'j' to listOf("an"),
        'k' to listOf("ing", "uai"),
        'l' to listOf("iang", "uang"),
        'm' to listOf("ian"),
        'n' to listOf("iao"),
        'o' to listOf("uo", "o"),
        'p' to listOf("ie"),
        'q' to listOf("iu"),
        'r' to listOf("uan", "er"),
        's' to listOf("ong", "iong"),
        't' to listOf("ue", "ve"),
        'u' to listOf("u"),
        'v' to listOf("ui", "v"),
        'w' to listOf("ei"),
        'x' to listOf("ia", "ua"),
        'y' to listOf("un"),
        'z' to listOf("ou"),
    ),
)

/** Microsoft Double Pinyin (微软双拼). */
private val MICROSOFT = DoublePinyin.Table(
    initials = STANDARD_INITIALS,
    finals = mapOf(
        'a' to listOf("a"),
        'b' to listOf("ou"),
        'c' to listOf("iao"),
        'd' to listOf("iang", "uang"),
        'e' to listOf("e"),
        'f' to listOf("en"),
        'g' to listOf("eng"),
        'h' to listOf("ang"),
        'i' to listOf("i"),
        'j' to listOf("an"),
        'k' to listOf("ao"),
        'l' to listOf("ai"),
        'm' to listOf("ian"),
        'n' to listOf("in"),
        'o' to listOf("uo", "o"),
        'p' to listOf("un"),
        'q' to listOf("iu"),
        'r' to listOf("uan", "er"),
        's' to listOf("ong", "iong"),
        't' to listOf("ue", "ve"),
        'u' to listOf("u"),
        'v' to listOf("ui", "v"),
        'w' to listOf("ia", "ua"),
        'x' to listOf("ie"),
        'y' to listOf("uai", "ing"),
        'z' to listOf("ei"),
    ),
)

/** Sogou Double Pinyin (搜狗双拼). Identical layout to Microsoft. */
private val SOGOU = MICROSOFT

/** Ziranma Double Pinyin (自然码双拼). */
private val ZIRANMA = DoublePinyin.Table(
    initials = STANDARD_INITIALS,
    finals = mapOf(
        'a' to listOf("a"),
        'b' to listOf("ou"),
        'c' to listOf("iao"),
        'd' to listOf("iang", "uang"),
        'e' to listOf("e"),
        'f' to listOf("en"),
        'g' to listOf("eng"),
        'h' to listOf("ang"),
        'i' to listOf("i"),
        'j' to listOf("an"),
        'k' to listOf("ao"),
        'l' to listOf("ai"),
        'm' to listOf("ian"),
        'n' to listOf("in"),
        'o' to listOf("uo", "o"),
        'p' to listOf("un"),
        'q' to listOf("iu"),
        'r' to listOf("uan", "er"),
        's' to listOf("ong", "iong"),
        't' to listOf("ue", "ve"),
        'u' to listOf("u"),
        'v' to listOf("ui", "v"),
        'w' to listOf("ia", "ua"),
        'x' to listOf("ie"),
        'y' to listOf("uai", "ing"),
        'z' to listOf("ei"),
    ),
)

/** Pinyin++ Double Pinyin (拼音加加双拼). Initials: v=zh, u=ch, i=sh. */
private val PINYINPP = DoublePinyin.Table(
    initials = PINYINPP_INITIALS,
    finals = mapOf(
        'a' to listOf("a"),
        'b' to listOf("ia", "ua"),
        'c' to listOf("uan"),
        'd' to listOf("ao"),
        'e' to listOf("e"),
        'f' to listOf("an"),
        'g' to listOf("ang"),
        'h' to listOf("iang", "uang"),
        'i' to listOf("i"),
        'j' to listOf("ian"),
        'k' to listOf("iao"),
        'l' to listOf("in"),
        'm' to listOf("ie"),
        'n' to listOf("iu"),
        'o' to listOf("uo", "o"),
        'p' to listOf("ou"),
        'q' to listOf("ing"),
        'r' to listOf("en"),
        't' to listOf("eng"),
        'u' to listOf("u"),
        'v' to listOf("ui", "v"),
        'w' to listOf("ei"),
        'x' to listOf("uai", "ue", "ve"),
        'y' to listOf("ong", "iong"),
        'z' to listOf("un"),
        's' to listOf("ai"),
    ),
)
