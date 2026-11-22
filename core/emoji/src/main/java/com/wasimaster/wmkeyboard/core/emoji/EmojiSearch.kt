package com.wasimaster.wmkeyboard.core.emoji

/**
 * Semantic emoji search over the catalog.
 *
 * Beyond literal keyword matching this understands:
 *  - `:tada:`-style shortcodes, the GitHub/Discord/Slack names (see
 *    [EmojiShortcodes]) — an exact one is unambiguous, so it outranks
 *    everything the keyword layers can find;
 *  - synonyms and related concepts ("party" also surfaces 🍾 via
 *    "celebration"; "fire" surfaces 💥 and ☄️);
 *  - prefix matching while the user is still typing;
 *  - other forms of a word: with a [stem] function (the engine's light
 *    stemmers), "cats" finds the emoji filed under "cat" and "dancing" those
 *    under "dance";
 *  - fuzzy matching for one-typo queries ("hapy" still finds 😀);
 *  - multilingual queries — Bengali keywords live in the same index, so
 *    বিড়াল finds the cat emojis and হাসি the smiles.
 *
 * Scoring: exact shortcode > shortcode prefix > exact keyword >
 * synonym-expanded > same stem > keyword prefix > fuzzy; matches on multiple
 * query tokens accumulate, and an exact keyword always outweighs a stem.
 *
 * [stem] turns words into their stems in one batch, a list in and the same
 * size out (each a word's stem; a phrase's stems joined by spaces), or null
 * when it cannot. It is first called on the first search, never while the
 * keyboard starts.
 */
class EmojiSearch(
    private val entries: List<EmojiEntry>,
    private val shortcodes: EmojiShortcodes = EmojiShortcodes.EMPTY,
    private val stem: ((List<String>) -> List<String>?)? = null,
) {

    // The keyword index, packed: every term sorted in [terms], and term `t`'s
    // catalog positions at `postings[postingStart[t] until postingStart[t + 1]]`,
    // ascending and without repeats. As a map of sets of boxed ints it was
    // ~6 MB of heap for the bundled catalog and the language packs merged into
    // it, held for as long as the keyboard ran; sorted, a prefix is also a
    // range to read rather than a scan of every term.
    private val terms: Array<String>
    private val postingStart: IntArray
    private val postings: IntArray

    /** Catalog position of each emoji, for scoring a shortcode hit. */
    private val indexByEmoji = HashMap<String, Int>()

    init {
        val building = HashMap<String, MutableList<Int>>()
        fun add(term: String, index: Int) {
            val list = building.getOrPut(term) { ArrayList(2) }
            // Entries are visited in order, so a repeat is always the last one.
            if (list.isEmpty() || list[list.size - 1] != index) list.add(index)
        }
        entries.forEachIndexed { index, entry ->
            indexByEmoji.putIfAbsent(entry.emoji, index)
            for (keyword in entry.keywords) {
                // Index both the full keyword ("heart on fire") and its tokens.
                add(keyword, index)
                for (token in keyword.split(' ')) {
                    if (token.isNotEmpty()) add(token, index)
                }
            }
        }
        terms = building.keys.toTypedArray().also { it.sort() }
        postingStart = IntArray(terms.size + 1)
        var total = 0
        for ((t, term) in terms.withIndex()) {
            postingStart[t] = total
            total += building.getValue(term).size
        }
        postingStart[terms.size] = total
        postings = IntArray(total)
        for ((t, term) in terms.withIndex()) {
            var at = postingStart[t]
            for (index in building.getValue(term)) postings[at++] = index
        }
    }

    /**
     * The terms grouped by stem: `stems` sorted, and the [terms] slots of
     * stem `i` at `slots[start[i] until start[i + 1]]`. One int a term, the
     * stem strings shared with [terms] where stemming changed nothing.
     */
    private class StemIndex(val stems: Array<String>, val start: IntArray, val slots: IntArray)

    private val stemIndex: StemIndex? by lazy {
        val stemmed = stem?.invoke(terms.asList())?.takeIf { it.size == terms.size } ?: return@lazy null
        val grouped = HashMap<String, MutableList<Int>>()
        for ((t, s) in stemmed.withIndex()) {
            if (s.isEmpty()) continue
            grouped.getOrPut(if (s == terms[t]) terms[t] else s) { ArrayList(1) }.add(t)
        }
        val stems = grouped.keys.toTypedArray().also { it.sort() }
        val start = IntArray(stems.size + 1)
        val slots = IntArray(stemmed.size)
        var at = 0
        for ((i, s) in stems.withIndex()) {
            start[i] = at
            for (t in grouped.getValue(s)) slots[at++] = t
        }
        start[stems.size] = at
        StemIndex(stems, start, slots.copyOf(at))
    }

    /** [term]'s slot in [terms], or -1. */
    private fun termIndex(term: String): Int = terms.binarySearch(term).let { if (it < 0) -1 else it }

    /** The slots of every term starting with [prefix], [prefix] itself included. */
    private fun prefixRange(prefix: String): IntRange {
        val low = terms.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        var high = low
        while (high < terms.size && terms[high].startsWith(prefix)) high++
        return low until high
    }

    private inline fun forEachPosting(t: Int, action: (Int) -> Unit) {
        for (p in postingStart[t] until postingStart[t + 1]) action(postings[p])
    }

    private fun postingCount(t: Int): Int = postingStart[t + 1] - postingStart[t]

    fun search(query: String, limit: Int = 40): List<EmojiEntry> {
        val code = EmojiShortcodes.normalize(query)
        // Shortcodes are snake_case; the keyword index is words. Spelling the
        // underscores as spaces lets the long tail of gemoji names that *are*
        // the Unicode name ("face_with_monocle") hit the catalog directly,
        // leaving the shortcode table to carry only the ones that differ.
        val tokens = code.replace('_', ' ').split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val scores = HashMap<Int, Int>()
        // An exact `:joy:` means one emoji and nothing else; a partial one
        // still beats the keyword layers, in the order the table offers it.
        shortcodes.exact(code)?.let { emoji ->
            indexByEmoji[emoji]?.let { scores.merge(it, 1_000, Int::plus) }
        }
        if (code.length >= 2) {
            shortcodes.prefix(code, limit).forEachIndexed { rank, emoji ->
                indexByEmoji[emoji]?.let { scores.merge(it, 500 - rank, Int::plus) }
            }
        }
        // The query's stems, one crossing for all its words.
        val stems = stemIndex?.let { stem?.invoke(tokens) }?.takeIf { it.size == tokens.size }
        for ((i, token) in tokens.withIndex()) {
            score(token, weight = 100, scores)
            stems?.get(i)?.let { scoreStem(token, it, scores) }
            for (synonym in SYNONYMS[token].orEmpty()) {
                score(synonym, weight = 60, scores)
            }
            if (token.length >= 2) {
                for (t in prefixRange(token)) {
                    if (terms[t].length > token.length) forEachPosting(t) { scores.merge(it, 40, Int::plus) }
                }
            }
            if (token.length >= 4) {
                for (t in terms.indices) {
                    if (isOneEditAway(token, terms[t])) forEachPosting(t) { scores.merge(it, 30, Int::plus) }
                }
            }
        }

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { entries[it.key] }
    }

    /**
     * Query terms this index can actually answer, to complete the word being
     * typed in the emoji panel's search box (#161).
     *
     * The box searches the emoji catalog, so completing its text against the
     * *language's* word list offers spellings no emoji is filed under —
     * "cathedral", "catalogue", "catastrophe" while the user is typing "cat" —
     * and every one of them lands on an empty grid. These are the terms the
     * index holds instead: the catalog's keywords in every language merged
     * into it, the shortcode names, and the synonyms [search] expands. Each
     * one finds at least one emoji.
     *
     * [context] is the rest of the query, so a second word narrows to terms
     * that share an emoji with the first: "red hea" offers "heart", which 💗
     * carries both of, ahead of "headphone", which nothing red is. Terms
     * already in the query are dropped — they would score nothing new — and so
     * is [typed] itself, since it is in the box already and a query box has no
     * autocorrect for a chip to hold off.
     */
    fun completions(typed: String, context: List<String> = emptyList(), limit: Int = 6): List<String> {
        val prefix = typed.trim().lowercase()
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val already = context.mapTo(HashSet()) { it.trim().lowercase() }.apply { add(prefix) }
        // Which emoji the rest of the query is already about, so a completion
        // can be judged on whether it points at the same ones.
        val narrowed = HashSet<Int>()
        for (token in already) {
            if (token == prefix) continue
            val t = termIndex(token)
            if (t >= 0) forEachPosting(t) { narrowed.add(it) }
        }

        /** Term to the number of catalog entries it reaches. */
        val reach = HashMap<String, Int>()
        /** ...of which the rest of the query names too. */
        val shared = HashMap<String, Int>()
        for (t in prefixRange(prefix)) {
            val keyword = terms[t]
            if (keyword.length <= prefix.length || keyword in already) continue
            reach[keyword] = postingCount(t)
            if (narrowed.isNotEmpty()) {
                var count = 0
                forEachPosting(t) { if (it in narrowed) count++ }
                shared[keyword] = count
            }
        }
        // Shortcodes carry the names the keywords do not — `tada`, `joy`. The
        // underscored ones are left out: `search` already spells them as
        // spaces, so they reach the catalog through the keywords above, and a
        // strip offering `face_with_monocle` reads as nothing anyone types.
        for (code in shortcodes.namesWithPrefix(prefix, limit * SHORTCODE_OVERDRAW)) {
            if ('_' in code || code in already) continue
            reach.putIfAbsent(code, 1)
        }
        for ((word, expansions) in SYNONYMS) {
            if (word.length <= prefix.length || !word.startsWith(prefix)) continue
            if (word in already || word in reach) continue
            val expanded = expansions.sumOf { termIndex(it).let { t -> if (t < 0) 0 else postingCount(t) } }
            if (expanded > 0) reach[word] = expanded
        }

        return reach.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { shared[it.key] ?: 0 }
                    .thenByDescending { it.value }
                    .thenBy { it.key.length }
                    .thenBy { it.key },
            )
            .take(limit)
            .map { it.key }
    }

    /**
     * The emoji filed under another form of [token]: every term whose stem
     * is [tokenStem], [token] itself left to the exact layer.
     */
    private fun scoreStem(token: String, tokenStem: String, scores: HashMap<Int, Int>) {
        val index = stemIndex ?: return
        if (tokenStem.isEmpty()) return
        val i = index.stems.binarySearch(tokenStem)
        if (i < 0) return
        val reached = HashSet<Int>()
        for (k in index.start[i] until index.start[i + 1]) {
            val t = index.slots[k]
            if (terms[t] != token) forEachPosting(t) { reached.add(it) }
        }
        // Once per emoji, however many forms of the word it is filed under.
        for (entry in reached) scores.merge(entry, STEM_WEIGHT, Int::plus)
    }

    private fun score(token: String, weight: Int, scores: HashMap<Int, Int>) {
        val t = termIndex(token)
        if (t >= 0) forEachPosting(t) { scores.merge(it, weight, Int::plus) }
    }

    /** Damerau-Levenshtein distance exactly 1 (substitution, indel, swap). */
    private fun isOneEditAway(a: String, b: String): Boolean {
        if (a == b) return false
        val lenDiff = a.length - b.length
        if (lenDiff < -1 || lenDiff > 1) return false
        if (lenDiff == 0) {
            var mismatches = 0
            var swapPossible = false
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    mismatches++
                    if (mismatches == 2) {
                        swapPossible = a[i] == b[i - 1] && a[i - 1] == b[i]
                    }
                    if (mismatches > 2) return false
                }
            }
            return mismatches == 1 || (mismatches == 2 && swapPossible)
        }
        val (short, long) = if (a.length < b.length) a to b else b to a
        var i = 0
        var j = 0
        var skipped = false
        while (i < short.length && j < long.length) {
            if (short[i] == long[j]) {
                i++; j++
            } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }

    companion object {

        /**
         * How many shortcode names [completions] pulls per strip slot, before
         * the underscored ones are dropped. Most of the table is underscored,
         * so asking for exactly the slots on offer would usually leave none.
         */
        private const val SHORTCODE_OVERDRAW = 8

        /**
         * A keyword another form of the query word stems to: under an exact
         * keyword (100) and a synonym (60), over a keyword the query only
         * starts (40).
         */
        private const val STEM_WEIGHT = 50

        /**
         * Concept expansion for common searches. Keys are query tokens,
         * values are additional index tokens to look up. Deliberately small
         * and curated — the catalog's own keyword lists carry most synonyms.
         */
        private val SYNONYMS: Map<String, List<String>> = mapOf(
            "happy" to listOf("joy", "smile", "cheerful", "celebrate"),
            "sad" to listOf("cry", "tear", "disappointed", "unhappy"),
            "laugh" to listOf("lol", "funny", "haha", "joy", "rofl"),
            "funny" to listOf("laugh", "lol", "joke"),
            "love" to listOf("heart", "romance", "affection", "adore", "kiss"),
            "party" to listOf("celebrate", "celebration", "festive", "confetti", "birthday"),
            "birthday" to listOf("party", "celebration", "cake", "gift", "balloon"),
            "celebrate" to listOf("party", "celebration", "congrats"),
            "angry" to listOf("mad", "rage", "furious"),
            "food" to listOf("eat", "hungry", "tasty", "meal"),
            "eat" to listOf("food", "tasty", "hungry"),
            "drink" to listOf("beverage", "coffee", "tea", "juice"),
            "fire" to listOf("flame", "hot", "burn", "explosion", "lit", "comet"),
            "hot" to listOf("fire", "heat", "sun"),
            "cold" to listOf("snow", "ice", "freezing", "winter"),
            "fast" to listOf("speed", "quick", "rocket", "run"),
            "money" to listOf("cash", "dollar", "rich", "payment"),
            "work" to listOf("office", "job", "laptop", "computer"),
            "sleep" to listOf("tired", "zzz", "nap", "bed"),
            "music" to listOf("song", "melody", "sing", "instrument"),
            "travel" to listOf("flight", "vacation", "trip", "airplane"),
            "win" to listOf("trophy", "winner", "champion", "medal", "first"),
            "cute" to listOf("adorable", "sweet", "baby", "kitten", "puppy"),
            "animal" to listOf("pet", "dog", "cat", "bird"),
            "flower" to listOf("blossom", "rose", "bouquet", "spring"),
            "rain" to listOf("weather", "storm", "umbrella", "cloud"),
            "night" to listOf("moon", "star", "sleep", "dark"),
            "pray" to listOf("prayer", "dua", "worship", "religion"),
            "sick" to listOf("ill", "fever", "doctor", "medicine"),
            "strong" to listOf("muscle", "power", "gym", "flex"),
            "think" to listOf("thinking", "wonder", "brain", "idea"),
            "scared" to listOf("fear", "afraid", "horror", "scream"),
            "cool" to listOf("sunglasses", "awesome"),
            "ok" to listOf("okay", "thumbs up", "check"),
            "yes" to listOf("check", "correct", "thumbs up"),
            "no" to listOf("cross", "wrong", "prohibited", "thumbs down"),
            // Bengali concept expansion
            "খুশি" to listOf("হাসি", "আনন্দ", "joy"),
            "মজা" to listOf("হাসি", "funny"),
            "কান্না" to listOf("দুঃখ", "cry"),
            "রাগ" to listOf("angry", "mad"),
            "ভালোবাসা" to listOf("প্রেম", "হৃদয়", "heart"),
            "খাবার" to listOf("food", "ভাত"),
            "আগুন" to listOf("fire", "শিখা"),
        )
    }
}
