package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/** Where a related item lives: another note, or a pinned clip. */
enum class RelatedKind { NOTE, CLIP }

/**
 * Something that may be related to a note (#1293): a note or a pinned clip,
 * by its [key] (the note's or the clip's id) and its text.
 */
data class RelatedSource(
    val kind: RelatedKind,
    val id: Long,
    val title: String,
    val text: String,
) {
    val key: String get() = "${kind.name}:$id"
}

/**
 * A related item and why: [score] in 0..1, and up to three [shared] words
 * or details the two have in common, for the row to say "both mention …".
 */
data class RelatedHit(
    val kind: RelatedKind,
    val id: Long,
    val title: String,
    val score: Double,
    val shared: List<String>,
)

/**
 * Which notes and pinned clips belong with a note (#1293), wherever they sit
 * in their lists, by what they say rather than by where they were filed.
 *
 * The words-only half, which needs no model: each text becomes its
 * distinctive words, weighted by how rare they are across everything being
 * compared (tf-idf), and two texts are as related as those weights are alike
 * (cosine). A phone number, email address or link the two share counts for
 * more than any word: two notes with the same landlord's number are about
 * the same thing whatever their words. Search by meaning, where its model is
 * installed, adds what this cannot see (two notes about rent that never use
 * the same word); the keyboard merges the two.
 */
object NoteRelated {

    /** The most related items a note lists. */
    const val MAX_RELATED = 6

    /** Below this a pair has only common words in common. */
    const val MIN_SCORE = 0.16

    /** What a shared phone number, address or link adds to a pair's score. */
    private const val SHARED_DETAIL_BONUS = 0.35

    /** Characters of a text that are read; the opening of a long note says what it is about. */
    private const val MAX_TEXT_CHARS = 8_000

    private val WORD = Regex("""[\p{L}\p{N}][\p{L}\p{N}\p{M}'’_-]*""")

    /**
     * The commonest English words, which every note shares with every other.
     * Other languages lean on the idf weighting alone, which already makes
     * any word that is everywhere count for little.
     */
    private val STOPWORDS: Set<String> = """
        a about above after again against all also am an and any are aren't as at be because been before being below
        between both but by can can't cannot could couldn't did didn't do does doesn't doing don't down during each
        few for from further get got had hadn't has hasn't have haven't having he her here hers herself him himself his
        how i if in into is isn't it it's its itself just let's like me more most much must my myself no nor not now of
        off on once only or other ought our ours ourselves out over own really same she should shouldn't so some such
        than that that's the their theirs them themselves then there there's these they this those through to too
        under until up very was wasn't we were weren't what what's when where which while who whom why will with won't
        would wouldn't you your yours yourself yourselves yes ok okay one two three need needs want make made thing things
        """.trim().split(Regex("""\s+""")).toSet()

    /**
     * The items in [pool] most related to [target], best first, at most
     * [limit], none under [minScore]. [target] itself (by key) is skipped.
     */
    fun related(
        target: RelatedSource,
        pool: List<RelatedSource>,
        limit: Int = MAX_RELATED,
        minScore: Double = MIN_SCORE,
    ): List<RelatedHit> {
        if (target.text.isBlank()) return emptyList()
        val others = pool.filter { it.key != target.key && it.text.isNotBlank() }
        if (others.isEmpty()) return emptyList()
        val space = Space(listOf(target) + others)
        return others.indices
            .mapNotNull { space.hit(0, it + 1, minScore) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Groups of items that belong together, for a list that clusters them:
     * every pair at [minScore] or above joins, transitively. Items in no
     * group are left out; each group is listed in [items]' order, and the
     * groups by where their first item sits.
     */
    fun groups(items: List<RelatedSource>, minScore: Double = GROUP_SCORE): List<List<RelatedSource>> {
        val live = items.filter { it.text.isNotBlank() }
        if (live.size < 2) return emptyList()
        val space = Space(live)
        val parent = IntArray(live.size) { it }
        fun root(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in live.indices) {
            for (j in i + 1 until live.size) {
                if (space.hit(i, j, minScore) != null) parent[root(i)] = root(j)
            }
        }
        return live.indices.groupBy(::root).values
            .filter { it.size > 1 }
            .map { group -> group.sorted().map { live[it] } }
            .sortedBy { group -> live.indexOf(group.first()) }
    }

    /** Every text of one comparison, weighed once against the whole set. */
    private class Space(val items: List<RelatedSource>) {
        private val details: List<Set<String>> = items.map { details(it.text) }
        private val weights: List<Map<String, Double>>
        private val norms: DoubleArray

        init {
            val docs = items.map { terms(it.title + "\n" + it.text) }
            val frequency = HashMap<String, Int>()
            for (doc in docs) for (term in doc.keys) frequency[term] = (frequency[term] ?: 0) + 1
            val n = docs.size
            weights = docs.map { doc -> doc.mapValues { (term, count) -> (1.0 + ln(count.toDouble())) * idf(n, frequency[term] ?: 1) } }
            norms = DoubleArray(n) { i -> sqrt(weights[i].values.sumOf { it * it }) }
        }

        /** [b] as [a]'s related item, or null when they have too little in common. */
        fun hit(a: Int, b: Int, minScore: Double): RelatedHit? {
            val mine = weights[a]
            val theirs = weights[b]
            var dot = 0.0
            val common = ArrayList<Pair<String, Double>>()
            for ((term, w) in mine) {
                val v = theirs[term] ?: continue
                dot += w * v
                common += term to w * v
            }
            var score = if (norms[a] == 0.0 || norms[b] == 0.0) 0.0 else dot / (norms[a] * norms[b])
            val sharedDetails = details[a].intersect(details[b])
            if (sharedDetails.isNotEmpty()) score += SHARED_DETAIL_BONUS
            score = score.coerceAtMost(1.0)
            if (score < minScore) return null
            val shared = sharedDetails.toList() + common.sortedByDescending { it.second }.map { it.first }
            val other = items[b]
            return RelatedHit(other.kind, other.id, other.title, score, shared.take(SHARED_SHOWN))
        }
    }

    /** A group needs more in common than a "see also" row does. */
    const val GROUP_SCORE = 0.3

    private const val SHARED_SHOWN = 3

    /** The distinctive words of [text] and how often each comes. */
    fun terms(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        val scanned = if (text.length > MAX_TEXT_CHARS) text.substring(0, MAX_TEXT_CHARS) else text
        for (match in WORD.findAll(scanned)) {
            val word = fold(match.value) ?: continue
            out[word] = (out[word] ?: 0) + 1
        }
        return out
    }

    /** Phone numbers, addresses and links in [text], normalised, which two texts sharing is a strong sign. */
    fun details(text: String): Set<String> {
        val scanned = if (text.length > MAX_TEXT_CHARS) text.substring(0, MAX_TEXT_CHARS) else text
        return ClipEntities.extract(scanned).mapNotNullTo(HashSet()) { entity ->
            when (entity.kind) {
                com.wasimaster.wmkeyboard.core.clipboard.ClipEntityKind.PHONE ->
                    entity.value.filter { it.isDigit() }.takeLast(PHONE_DIGITS).takeIf { it.length >= PHONE_MIN }
                com.wasimaster.wmkeyboard.core.clipboard.ClipEntityKind.OTP -> null
                else -> entity.value.lowercase(Locale.ROOT).removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
            }
        }
    }

    /** A phone number is matched on its last digits, so +880 17… and 017… agree. */
    private const val PHONE_DIGITS = 9
    private const val PHONE_MIN = 7

    /**
     * [raw] as a term: lower case, an English plural or possessive folded,
     * or null for a stopword, anything without a letter in it, or a word
     * too short to say much.
     */
    fun fold(raw: String): String? {
        var word = raw.lowercase(Locale.ROOT).trim('\'', '’', '-', '_')
        if (word.endsWith("'s") || word.endsWith("’s")) word = word.dropLast(2)
        if (word.isEmpty() || word in STOPWORDS) return null
        // A number, a date or a phone number's digits say nothing about what a note is about.
        if (word.none { it.isLetter() }) return null
        val latin = word.all { it.code < 0x250 }
        if (latin && word.length < 3) return null
        if (!latin && word.length < 2) return null
        if (latin && word.length > 4 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")) {
            word = if (word.endsWith("ies")) word.dropLast(3) + "y" else word.dropLast(1)
        }
        return word
    }

    private fun idf(n: Int, df: Int): Double = ln((n + 1.0) / (df + 1.0)) + 1.0
}
