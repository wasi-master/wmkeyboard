package com.wasimaster.wmkeyboard.core.gesture

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-cell index behind [GlideShapeSource.wordsNear] (issue #966 §5.4)
 * answers exactly what the scan over every stored shape answered.
 *
 * Random stores: a few hundred ways of drawing, each a cluster of words
 * whose shapes differ by a little noise, spread over the board. The noise
 * on a shape's first point is well under a key width, so every shape within
 * the seeding radius of a query starts in the query's cell or the one next
 * door, and the index must agree with the full scan on every query: the
 * words, their order, and the limit. A query the index's cells hold nothing
 * for takes the full scan, which the last test pins down.
 */
class GlideShapeSeedIndexTest {

    private val layout = 0x5e1fL

    /** The decoder's seeding radius and limit, as `NativeGlide` asks for them. */
    private val radius = 0.35f
    private val limit = 8

    /** Every (word, shape) the store was fed, in feed order, for the reference scan. */
    private class Fed(val word: String, val shape: ByteArray)

    /** [GlideShapeStore.LayoutShapes.wordsNear] as it was before the index: every shape, nearest word first. */
    private fun fullScan(fed: List<Fed>, drawn: ByteArray): List<String> {
        val found = ArrayList<Pair<String, Float>>()
        for (f in fed) {
            val d = GlideShapeStore.distance(f.shape, drawn, radius)
            if (d > radius) continue
            val at = found.indexOfFirst { it.first == f.word }
            if (at < 0) found.add(f.word to d) else if (d < found[at].second) found[at] = f.word to d
        }
        found.sortBy { it.second }
        return found.take(limit).map { it.first }
    }

    /** A shape across the board: x within five key widths of centre, y within two. */
    private fun randomShape(random: Random): ByteArray = ByteArray(GlideShapeStore.POINTS) { i ->
        (if (i % 2 == 0) random.nextInt(-50, 51) else random.nextInt(-20, 21)).toByte()
    }

    /** [base] with every coordinate moved by up to [amount] quantised units. */
    private fun jitter(base: ByteArray, random: Random, amount: Int): ByteArray =
        ByteArray(base.size) { i -> (base[i] + random.nextInt(-amount, amount + 1)).coerceIn(-127, 127).toByte() }

    /**
     * A store of [clusters] ways of drawing, each under [perCluster] words
     * whose shapes sit within [noise] units of the cluster's base on every
     * coordinate, and what it was fed.
     */
    private fun randomStore(random: Random, clusters: Int, perCluster: Int, noise: Int): Pair<GlideShapeStore, List<Fed>> {
        val store = GlideShapeStore(null)
        val fed = ArrayList<Fed>()
        var n = 0
        repeat(clusters) {
            val base = randomShape(random)
            repeat(perCluster) {
                val shape = jitter(base, random, noise)
                val word = "w${n++}"
                store.learn(GlideShapeSample(layout, shape), word)
                fed.add(Fed(word, shape))
            }
        }
        return store to fed
    }

    @Test
    fun `the index answers every query as the full scan did`() {
        var hits = 0
        var queries = 0
        for (seed in 1L..6L) {
            val random = Random(seed)
            // Two units of noise is a fifth of a key on any one coordinate
            // and about 0.2 key widths of mean distance between two shapes
            // of a cluster: inside the radius, and a first point that is
            // never more than four units from the query's, so never past
            // the cell next door.
            val (store, fed) = randomStore(random, clusters = 120, perCluster = 4, noise = 2)
            val source = store.forLayout(layout) ?: error("nothing learned")
            // A query on every cluster, drawn a little off one of its shapes...
            for (f in fed.filterIndexed { i, _ -> i % 4 == 0 }) {
                val drawn = jitter(f.shape, random, 2)
                val expected = fullScan(fed, drawn)
                assertEquals("seed $seed, near ${f.word}", expected, source.wordsNear(drawn, radius, limit))
                assertEquals("seed $seed, near ${f.word}, limit 1", expected.take(1), source.wordsNear(drawn, radius, 1))
                queries++
                if (expected.isNotEmpty()) hits++
            }
            // ...and some anywhere on the board, most of them near nothing.
            repeat(60) {
                val drawn = randomShape(random)
                assertEquals("seed $seed, random query", fullScan(fed, drawn), source.wordsNear(drawn, radius, limit))
                queries++
            }
        }
        assertTrue("the queries must have found things: $hits of $queries", hits > queries / 3)
    }

    @Test
    fun `a query finds shapes that start just across its cell's edges`() {
        val store = GlideShapeStore(null)
        val fed = ArrayList<Fed>()
        // Shapes whose first point sits either side of the cell boundaries
        // at x = 0 and x = 10: the cells left and right of the queries'.
        for ((i, x0) in listOf(-1, 0, 9, 10).withIndex()) {
            val shape = ByteArray(GlideShapeStore.POINTS) { j -> (if (j == 0) x0 else 20).toByte() }
            val word = "edge$i"
            store.learn(GlideShapeSample(layout, shape), word)
            fed.add(Fed(word, shape))
        }
        val source = store.forLayout(layout) ?: error("nothing learned")
        // Every query starts in the cell between, and every shape is within
        // a key of it: all four come back, as the full scan has them.
        for (x0 in listOf(0, 4, 9)) {
            val drawn = ByteArray(GlideShapeStore.POINTS) { j -> (if (j == 0) x0 else 20).toByte() }
            val expected = fullScan(fed, drawn)
            assertEquals("query starting at $x0", 4, expected.size)
            // Shapes the same distance off come in either order.
            assertEquals("query starting at $x0", expected.toSet(), source.wordsNear(drawn, radius, limit).toSet())
        }
    }

    @Test
    fun `a stroke whose cells hold nothing takes the full scan`() {
        val store = GlideShapeStore(null)
        // One shape, the same as the query everywhere but its first point,
        // which is two and a half keys off: 0.05 key widths of mean
        // distance, well inside the radius, in a cell the index never looks
        // at. The cells it does look at are empty, so the full scan answers.
        val base = ByteArray(GlideShapeStore.POINTS) { 20 }
        val farStart = base.copyOf().also { it[0] = 45 }
        store.learn(GlideShapeSample(layout, farStart), "far")
        val source = store.forLayout(layout) ?: error("nothing learned")
        assertTrue(GlideShapeStore.distance(farStart, base) < radius)
        assertEquals(listOf("far"), source.wordsNear(base, radius, limit))
    }
}
