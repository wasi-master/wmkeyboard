package com.wasimaster.wmkeyboard.core.prediction

/**
 * The words exactly one edit from a typed word, read straight off the tries,
 * with no completion: what the strip shows beside a word it already knows
 * when the fuzzy walk had no room for them.
 *
 * The walk ranks everything in one list, and on a short word that list fills
 * with completions and slip readings long before it reaches a plain one-edit
 * word. `tnr` (a typo the Full English download kept twice) walks to `try`,
 * `three`, `turn`, `through` and twenty-eight more, and neither `tnr` itself
 * nor `tmr`, `tnt` or `tar` makes the top 32. So [SuggestionEngine]'s
 * known-word slips (#979) found nothing to offer and the strip stood on the
 * typed word alone.
 *
 * Priced as the walk prices one edit: the same substitution, deletion,
 * insertion and transposition costs, read from the taps when there are any
 * and from key adjacency otherwise, and the same capped match cost for every
 * letter consumed as itself. Learned edit habits are left out; this only
 * orders a few chips under a word the user has already typed.
 */
internal object OneEditWords {

    /** A one-edit word and its score on the walk's scale. */
    class Neighbour(val word: String, val score: Double)

    /**
     * Up to [limit] words one edit from [typed], best first (ties by the
     * word), merged over [sources] by their best score. [typed] itself is
     * never among them.
     */
    fun of(
        typed: String,
        sources: List<FuzzyBeamSearch.WalkSource>,
        proximity: KeyProximity,
        model: KeyTouchModel?,
        touch: List<TouchPoint?>?,
        limit: Int,
    ): List<Neighbour> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val pricing = Pricing(typed, proximity, model, touch)
        val found = HashMap<String, Double>()
        val buffer = ChildBuffer()
        for (src in sources) {
            Probe(typed, src, pricing, buffer, found).run()
        }
        found.remove(typed)
        return found.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { Neighbour(it.key, it.value) }
    }

    /** The walk's prices for one typed word and its taps. */
    private class Pricing(
        val typed: String,
        val proximity: KeyProximity,
        val model: KeyTouchModel?,
        touch: List<TouchPoint?>?,
    ) {
        private val points: List<TouchPoint?> = touch.orEmpty()
        private val best = DoubleArray(typed.length) { i ->
            val p = point(i)
            val key = p?.let { model?.bestKey(it) }
            if (p != null && key != null) model!!.logLikelihood(p, key) else Double.NEGATIVE_INFINITY
        }

        private fun point(pos: Int): TouchPoint? = points.getOrNull(pos)

        /** Consuming typed[pos] as itself: the tap's gap to its best key, capped. */
        fun match(pos: Int): Double {
            val m = model ?: return 0.0
            val p = point(pos) ?: return 0.0
            val expected = typed[pos]
            if (!m.knows(expected) || best[pos] == Double.NEGATIVE_INFINITY) return 0.0
            return (best[pos] - m.logLikelihood(p, expected)).coerceIn(0.0, FuzzyBeamSearch.MATCH_CAP)
        }

        fun substitution(pos: Int, label: Char): Double {
            val expected = typed[pos]
            val m = model
            val p = point(pos)
            if (m != null && p != null && m.knows(expected) && m.knows(label)) {
                val gap = m.logLikelihood(p, expected) - m.logLikelihood(p, label)
                return FuzzyBeamSearch.COST_SUB_ADJACENT +
                    gap.coerceIn(0.0, FuzzyBeamSearch.COST_SUB_FAR - FuzzyBeamSearch.COST_SUB_ADJACENT)
            }
            return if (proximity.areAdjacent(expected, label)) {
                FuzzyBeamSearch.COST_SUB_ADJACENT
            } else {
                FuzzyBeamSearch.COST_SUB_FAR
            }
        }

        fun deletion(pos: Int): Double {
            val expected = typed[pos]
            val doubled = (pos + 1 < typed.length && typed[pos + 1] == expected) ||
                (pos > 0 && typed[pos - 1] == expected)
            return if (doubled) FuzzyBeamSearch.COST_DELETE_DOUBLED else FuzzyBeamSearch.COST_DELETION
        }

        fun insertion(pos: Int, label: Char): Double {
            val adjacent = proximity.areAdjacent(typed[pos], label) ||
                (pos > 0 && (typed[pos - 1] == label || proximity.areAdjacent(typed[pos - 1], label)))
            return if (adjacent) FuzzyBeamSearch.COST_INSERT_ADJACENT else FuzzyBeamSearch.COST_INSERT_FAR
        }

        fun transposition(pos: Int): Double =
            if (proximity.sameHand(typed[pos], typed[pos + 1])) {
                FuzzyBeamSearch.COST_TRANSPOSITION_SAME_HAND
            } else {
                FuzzyBeamSearch.COST_TRANSPOSITION
            }
    }

    /**
     * One source's one-edit words: descend the typed prefix exactly, take the
     * single edit at each position, then follow the rest of the word exactly.
     */
    private class Probe(
        val typed: String,
        val src: FuzzyBeamSearch.WalkSource,
        val pricing: Pricing,
        val buffer: ChildBuffer,
        val found: HashMap<String, Double>,
    ) {
        private val walker = src.walker
        private val n = typed.length

        fun run() {
            var node = walker.root
            var matched = 0.0
            val prefix = StringBuilder(n + 1)
            for (pos in 0 until n) {
                editsAt(node, pos, prefix, matched)
                node = walker.child(node, typed[pos])
                if (node < 0) return
                matched += pricing.match(pos)
                prefix.append(typed[pos])
            }
        }

        /** Every single edit taken at [pos], from [node] (the trie after typed[0 until pos]). */
        private fun editsAt(node: Int, pos: Int, prefix: StringBuilder, matched: Double) {
            // Deletion: typed[pos] was an extra keypress.
            finish(node, pos + 1, prefix.toString(), matched + pricing.deletion(pos))
            // Copied out: the descents below reuse the shared buffer.
            val count = walker.childrenInto(node, buffer)
            val labels = buffer.labels.copyOf(count)
            val nodes = buffer.nodes.copyOf(count)
            for (i in 0 until count) {
                val label = labels[i]
                val child = nodes[i]
                if (label != typed[pos]) {
                    finish(child, pos + 1, "$prefix$label", matched + pricing.substitution(pos, label))
                }
                // Insertion: the word has `label` here and the typing missed it.
                finish(child, pos, "$prefix$label", matched + pricing.insertion(pos, label))
            }
            // Transposition of typed[pos] and typed[pos + 1].
            if (pos + 1 < n && typed[pos] != typed[pos + 1]) {
                val first = walker.child(node, typed[pos + 1])
                val second = if (first >= 0) walker.child(first, typed[pos]) else -1
                if (second >= 0) {
                    finish(second, pos + 2, "$prefix${typed[pos + 1]}${typed[pos]}", matched + pricing.transposition(pos))
                }
            }
        }

        /** Follows typed[from until n] exactly from [start] and records the word if one ends there. */
        private fun finish(start: Int, from: Int, head: String, costSoFar: Double) {
            var node = start
            var cost = costSoFar
            for (pos in from until n) {
                node = walker.child(node, typed[pos])
                if (node < 0) return
                cost += pricing.match(pos)
            }
            if (!walker.isWord(node)) return
            val word = head + typed.substring(from)
            // StrictMath, as the walk's own scores: one ulp can reorder two chips.
            val score = src.logWeight + StrictMath.log(1.0 + walker.frequency(node)) - cost
            found.merge(word, score, ::maxOf)
        }
    }
}
