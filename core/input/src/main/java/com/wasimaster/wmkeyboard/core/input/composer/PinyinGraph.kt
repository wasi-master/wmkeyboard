package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Full pinyin as a [Lattice.Graph]: every way of splitting the buffer that is
 * worth decoding, instead of the one [PinyinSyllables.segment] picks (#580).
 *
 * A single segmentation decides too early. `xian` is 先 as one syllable and
 * 西安 as two; `nine` is 您饿 split `nin`+`e` and 你呢 split `ni`+`ne`. The
 * longest-first walk takes `xian` and `nin` and the other reading never reaches
 * the decoder, however common it is. Here both splits are arcs over shared
 * nodes, and the language model picks.
 *
 * "Worth decoding" is the hard part, because with Jianpin on every letter is
 * a unit and *every* buffer splits every way: `xian` is also `xia`+`n`, so
 * offering all splits would answer 先 with 下年. The rule is the one people
 * type by: **as few abbreviations as the buffer allows, then as few
 * syllables.** That drops 下年 (one initial), keeps `nin`+`e` beside `ni`+`ne`
 * (two syllables each), and keeps `nihao` from reading as `ni`+`ha`+`o` —
 * 哦 is a syllable, but three is more than two.
 *
 * Five kinds of arc are added on top of that:
 *  - the **apostrophe split**: a syllable that is also two, the second
 *    starting with a or e — `xian` as `xi`+`an` (西安), `fangan` as
 *    `fang`+`an` (方案). Pinyin marks that boundary with an apostrophe, which
 *    almost nobody types on a phone, and it is the one place the
 *    fewest-syllables rule is wrong;
 *  - a **digraph** over two kept single-letter units (`z`+`h` → zh…), which is
 *    how Jianpin has always read `zhg` as 中国 while `sh` stays 上海 as well;
 *  - a **mistyped key** (`xiqn` → `xian`): a stretch the cheapest split can
 *    only read with two or more bare initials, which one neighbouring key
 *    turns into a whole syllable — or two swapped keys (`qign`), one key
 *    too many (`zhonng`) or one too few (`zhng`). Those cost a penalty and
 *    compete with the initials rather than replacing them, since `xiqn` may
 *    really be initials;
 *  - another **spelling** of a syllable ([PinyinSpellings.aliases]): `lue`
 *    for `lüe`, `jv` for `ju`, the textbook `liou` for `liu`, and the slips
 *    every pinyin IME forgives, `bagn` for `bang` and `zhon` for `zhong`.
 *    These are units like any syllable, read as the syllable they spell;
 *  - the **unfinished last syllable** ([PinyinSpellings.completions]): `nia`
 *    at the end of the buffer also reads as nian, niang and niao, so the
 *    word is on the strip before its last letter is;
 *  - a **missed space bar** (#1107): one of the letters over the space bar,
 *    c v b n m, between two syllables, where the thumb meant space and caught
 *    the row above. `nihaobwoshi` is nihao and woshi with the `b` left out.
 *    Without Jianpin that letter has no reading at all and used to stop the
 *    buffer converting past it; with Jianpin it is also a bare initial (不 for
 *    `b`), and the two readings compete, as a mistyped key does.
 */
internal object PinyinGraph {

    /** What a span of the buffer is, for the composer turning it into arcs. */
    enum class Kind {
        UNIT,
        DIGRAPH,

        /** One key replaced by its neighbour. */
        TYPO,

        /** Two keys typed the wrong way round. */
        SWAP,

        /** One key too many, or one too few. */
        EXTRA,

        /** Another spelling of a syllable; the unit is that syllable. */
        ALIAS,

        /** The start of a syllable at the end of the buffer; the unit is the syllable. */
        PARTIAL,

        /** A syllable after a letter typed for the space bar, which is left out; the unit is the syllable. */
        SLIP,
    }

    /**
     * The graph, how many units the cheapest split has, and whether that split
     * abbreviates anything — the composer's test for stitching.
     */
    class Built(val graph: Lattice.Graph, val units: Int, val abbreviated: Boolean)

    /** Longest real toneless pinyin syllable (`zhuang`). */
    private const val MAX_SYLLABLE = 6

    /** Shortest stretch a mistyped key is looked for in; see [Kind.TYPO]. */
    private const val MIN_TYPO = 3

    /** Bare initials a stretch must need before a mistyped key is suspected. */
    private const val TYPO_ABBREVIATIONS = 2

    private const val INF = Int.MAX_VALUE / 2

    /**
     * What a bare initial costs against a syllable in the split search: more
     * than any number of syllables can add up to, so initials are minimised
     * first and syllables only break ties.
     */
    private const val INITIAL = 1 shl 16

    /** How the second half of an apostrophe split starts; see the class notes. */
    private const val SPLIT_VOWELS = "ae"

    private class Span(
        val from: Int,
        val start: Int,
        val to: Int,
        val unit: String,
        val cost: Int,
        /** The letter at [from] is a missed space bar, left out; see [Kind.SLIP]. */
        val slip: Boolean = false,
    )

    /** The letters over a QWERTY space bar, the ones a thumb aiming for it catches. */
    private const val SPACE_SLIP_KEYS = "cvbnm"

    /**
     * What a missed space bar costs in the split search: the same as reading
     * its letter as a bare initial with the syllable after it, so where
     * Jianpin can do that both readings are kept and the decoder chooses, and
     * where nothing else reads the letter this is the one way past it.
     */
    private const val SLIP = INITIAL + 2

    /**
     * Builds the graph for [buffer] over [valid] syllables, with [jianpin]'s
     * initials as units when it is on. [arcsFor] turns one span into the arcs
     * the decoder reads, landing on node `to`. Null when nothing segments.
     */
    fun build(
        buffer: String,
        valid: Set<String>,
        jianpin: Jianpin.Index?,
        arcsFor: (unit: String, to: Int, kind: Kind) -> List<Lattice.Arc>,
    ): Built? {
        if (buffer.isEmpty() || valid.isEmpty()) return null
        val s = buffer.lowercase()
        val n = s.length
        val units = jianpin?.units?.takeIf { it.isNotEmpty() } ?: valid
        val spellings = PinyinSpellings.of(valid)
        val aliases = spellings.aliases

        // Every unit that starts at each position. An apostrophe is the user's
        // own boundary: skipped, and counted into the unit after it, so a
        // prefix commit deletes it with that unit.
        val from = Array(n + 1) { i ->
            var start = i
            while (start < n && s[start] == '\'') start++
            val out = ArrayList<Span>()
            for (len in minOf(MAX_SYLLABLE, n - start) downTo 1) {
                val unit = s.substring(start, start + len)
                if (unit in units) {
                    out.add(Span(i, start, start + len, unit, if (unit in valid) 1 else INITIAL + 1))
                } else if (unit in aliases) {
                    out.add(Span(i, start, start + len, unit, 1))
                }
            }
            // A missed space bar: never the buffer's first letter, and only
            // ahead of a whole syllable.
            if (i in 1 until n - 1 && start == i && s[i] in SPACE_SLIP_KEYS) {
                for (len in minOf(MAX_SYLLABLE, n - i - 1) downTo 1) {
                    val unit = s.substring(i + 1, i + 1 + len)
                    if (unit in valid) out.add(Span(i, i + 1, i + 1 + len, unit, SLIP, slip = true))
                }
            }
            out
        }

        // Fewest bare initials needed to reach each position…
        val best = IntArray(n + 1) { INF }
        best[0] = 0
        for (i in 0 until n) {
            if (best[i] == INF) continue
            for (sp in from[i]) best[sp.to] = minOf(best[sp.to], best[i] + sp.cost)
        }
        // …the furthest one any split reaches, where the convertible part ends…
        var end = n
        while (end > 0 && best[end] == INF) end--
        if (end == 0) return null
        // …and the fewest from each position on to there.
        val toEnd = IntArray(end + 1) { INF }
        toEnd[end] = 0
        for (i in end - 1 downTo 0) {
            for (sp in from[i]) {
                if (sp.to <= end && toEnd[sp.to] < INF) toEnd[i] = minOf(toEnd[i], sp.cost + toEnd[sp.to])
            }
        }
        val total = toEnd[0]
        val kept = ArrayList<Span>()
        for (i in 0 until end) {
            if (best[i] == INF) continue
            for (sp in from[i]) {
                if (sp.to <= end && toEnd[sp.to] < INF && best[i] + sp.cost + toEnd[sp.to] == total) kept.add(sp)
            }
        }
        if (kept.isEmpty()) return null

        // A syllable that also reads as two, split where an apostrophe would go.
        val splits = ArrayList<Span>()
        for (sp in kept) {
            if (sp.cost != 1 || sp.to - sp.start < 2 || sp.unit !in valid) continue
            for (k in sp.start + 1 until sp.to) {
                val head = s.substring(sp.start, k)
                val tail = s.substring(k, sp.to)
                if (tail[0] in SPLIT_VOWELS && head in valid && tail in valid) {
                    splits.add(Span(sp.from, sp.start, k, head, 1))
                    splits.add(Span(k, k, sp.to, tail, 1))
                }
            }
        }
        kept.addAll(splits)

        // The last syllable, still being typed: from where the buffer's own
        // split leaves off, or from a boundary inside it, to the end.
        val partials = ArrayList<Span>()
        val partialFrom = (kept.map { it.from } + end).distinct()
        for (i in partialFrom) {
            if (best[i] == INF) continue
            var start = i
            while (start < n && s[start] == '\'') start++
            val tail = s.substring(start)
            if (tail.length >= 2 && '\'' !in tail && spellings.completions.containsKey(tail)) {
                partials.add(Span(i, start, n, tail, INITIAL + 1))
            }
        }

        val positions = sortedSetOf(0, end)
        for (sp in kept) { positions.add(sp.from); positions.add(sp.to) }
        if (partials.isNotEmpty()) positions.add(n)
        val nodes = positions.toIntArray()
        val index = HashMap<Int, Int>(nodes.size * 2)
        for ((k, p) in nodes.withIndex()) index[p] = k
        val arcs = Array(nodes.size) { ArrayList<Lattice.Arc>() }

        for (sp in kept) {
            val kind = when {
                sp.slip -> Kind.SLIP
                sp.unit in units -> Kind.UNIT
                else -> Kind.ALIAS
            }
            val unit = if (kind == Kind.ALIAS) aliases.getValue(sp.unit) else sp.unit
            arcs.getValue(sp.from, index).addAll(arcsFor(unit, index.getValue(sp.to), kind))
        }
        for (sp in partials) {
            for (syllable in spellings.completions.getValue(sp.unit)) {
                arcs.getValue(sp.from, index).addAll(arcsFor(syllable, index.getValue(n), Kind.PARTIAL))
            }
        }

        // `z` then `h`, both bare and back to back, also read as the one
        // initial zh… — unless an apostrophe sits between them.
        if (jianpin != null) {
            val single = kept.filter { it.to - it.start == 1 && it.cost > INITIAL && !it.slip }
            val startingAt = single.associateBy { it.start }
            for (a in single) {
                val b = startingAt[a.to] ?: continue
                if (b.from != b.start) continue
                val digraph = a.unit + b.unit
                if (digraph !in Jianpin.DIGRAPHS) continue
                arcs.getValue(a.from, index).addAll(arcsFor(digraph, index.getValue(b.to), Kind.DIGRAPH))
            }
        }

        // A stretch that needs two or more bare initials, and that one slip
        // of a finger would make a whole syllable of: a neighbouring key, two
        // keys swapped, one key too many or one too few.
        for (i in nodes) {
            for (j in nodes) {
                if (j <= i || j > end || best[i] == INF || best[j] == INF) continue
                if ((best[j] - best[i]) / INITIAL < TYPO_ABBREVIATIONS) continue
                var start = i
                while (start < j && s[start] == '\'') start++
                val window = s.substring(start, j)
                if (window.length !in MIN_TYPO..MAX_SYLLABLE + 1 || '\'' in window) continue
                if (window in valid || window in aliases) continue
                val to = index.getValue(j)
                val seen = HashSet<String>()
                fun offer(fixed: String, kind: Kind) {
                    if (fixed in valid && seen.add(fixed)) arcs.getValue(i, index).addAll(arcsFor(fixed, to, kind))
                }
                for (p in window.indices) {
                    for (c in NEIGHBOURS[window[p]].orEmpty()) {
                        offer(window.substring(0, p) + c + window.substring(p + 1), Kind.TYPO)
                    }
                }
                for (p in 0 until window.length - 1) {
                    offer(window.substring(0, p) + window[p + 1] + window[p] + window.substring(p + 2), Kind.SWAP)
                }
                for (p in window.indices) offer(window.removeRange(p, p + 1), Kind.EXTRA)
                if (window.length < MAX_SYLLABLE) {
                    for (p in 1..window.length) {
                        for (c in 'a'..'z') offer(window.substring(0, p) + c + window.substring(p), Kind.EXTRA)
                    }
                }
            }
        }

        // The cheapest split's unit count.
        val unitsOnPath = total % INITIAL
        return Built(Lattice.Graph(nodes, Array(nodes.size) { arcs[it] }), unitsOnPath, total >= INITIAL)
    }

    private fun Array<ArrayList<Lattice.Arc>>.getValue(position: Int, index: Map<Int, Int>) = this[index.getValue(position)]

    /** Letters a finger lands on instead, on the QWERTY board every pinyin layout here uses. */
    private val NEIGHBOURS: Map<Char, String> = mapOf(
        'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg", 'y' to "tugh",
        'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol",
        'a' to "qwsz", 's' to "adwezx", 'd' to "sferxc", 'f' to "dgrtcv", 'g' to "fhtyvb",
        'h' to "gjyubn", 'j' to "hkuinm", 'k' to "jliom", 'l' to "kop",
        'z' to "asx", 'x' to "zcsd", 'c' to "xvdf", 'v' to "cbfg", 'b' to "vngh", 'n' to "bmhj", 'm' to "njk",
    )
}
