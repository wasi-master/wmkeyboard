package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import com.wasimaster.wmkeyboard.core.prediction.WordKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the decoder asks of learned shapes: one layout's worth, read-only,
 * safe to read on the decode thread while the keyboard's thread learns.
 */
interface GlideShapeSource {
    /**
     * How far [drawn] sits from the nearest shape stored for [word], in the
     * same mean-distance units as the decoder's shape channel, or -1 when the
     * word has none here.
     */
    fun minDistance(word: String, drawn: ByteArray): Float

    /** Words with a stored shape within [radius] of [drawn], nearest first, at most [limit] of them. */
    fun wordsNear(drawn: ByteArray, radius: Float, limit: Int): List<String>
}

/**
 * One kept glide's shape, as the learning buffer carries it from commit to
 * settle: the grid it was drawn on and the stroke as [GlideBeam.sampleShape]
 * keeps it.
 */
class GlideShapeSample(val layoutKey: Long, val shape: ByteArray)

/**
 * How this user draws each word (issue #52): a few shapes per word, learned
 * from the glides they keep, for the decoder's shape channel to compare a
 * stroke against beside the word's ideal path.
 *
 * The ideal path through a word's key centres is what the shape channel has
 * to go on, and a real hand never draws it: it cuts corners its own way,
 * overshoots one key and undershoots the next, and does so the same way
 * every time. Swype kept the user's own strokes for exactly this reason. A
 * shape here is a stroke resampled and normalised the way the shape channel
 * normalises the drawn one — centred, scaled to unit spread — and quantised
 * to a byte a coordinate, so a word's whole record is a few hundred bytes.
 *
 * **Graduated, never taken at commit.** A shape is attached to the word in
 * the learning buffer and lands here only when the word settles: a glide the
 * user backspaces or replaces off the strip teaches nothing, and one they
 * read past and left alone does. A shape is also only kept for a word the
 * lexicon has, so nothing that is not yet a learned word is written down.
 *
 * **Keyed by grid, then word.** The same word on QWERTY and AZERTY is two
 * shapes, and so is the same word in portrait and landscape, whose row pitch
 * differs in key widths; two layouts with the same geometry share. The key
 * is a fingerprint of the keys as drawn, before the hand model moves them.
 *
 * **Accept and reject counts per shape.** A settle finds the nearest stored
 * shape of the word: within [MERGE_RADIUS] it blends in and counts an
 * acceptance, further off it is a new way of drawing the word and is added,
 * up to the user's limit ([setShapesPerWord], three unless they moved it),
 * the least-accepted making room. A glide the user undoes or replaces marks
 * the shape that matched it rejected, and a shape rejected more often than
 * accepted goes.
 *
 * Bounded at [MAX_WORDS] words across every layout, the longest untouched
 * evicted. Persisted as JSON in the learning directory beside the lexicon;
 * memory-only until the device is unlocked, like every store there. Reads
 * take a published immutable snapshot, swapped whole on every change.
 */
class GlideShapeStore(private val storageFile: File?) {

    private class Shape(var points: ByteArray, var accepted: Int, var rejected: Int)

    private class Entry(var tick: Long, val shapes: ArrayList<Shape>)

    @Serializable
    private data class StoredShape(val p: String, val a: Int = 1, val r: Int = 0)

    @Serializable
    private data class StoredWord(val t: Long = 0L, val s: List<StoredShape> = emptyList())

    @Serializable
    private data class Snapshot(
        val v: Int = VERSION,
        val tick: Long = 0L,
        val layouts: Map<String, Map<String, StoredWord>> = emptyMap(),
    )

    /** One layout's shapes as the decoder reads them: flat arrays, never mutated. */
    private class LayoutShapes(
        private val words: Array<String>,
        private val shapes: Array<ByteArray>,
        private val byWord: HashMap<String, IntArray>,
    ) : GlideShapeSource {

        override fun minDistance(word: String, drawn: ByteArray): Float {
            val indices = byWord[WordKey.of(word)] ?: return -1f
            var best = Float.MAX_VALUE
            for (i in indices) {
                val d = distance(shapes[i], drawn, best)
                if (d < best) best = d
            }
            return if (best == Float.MAX_VALUE) -1f else best
        }

        override fun wordsNear(drawn: ByteArray, radius: Float, limit: Int): List<String> {
            if (limit <= 0) return emptyList()
            val found = ArrayList<Pair<String, Float>>()
            for (i in shapes.indices) {
                val d = distance(shapes[i], drawn, radius)
                if (d > radius) continue
                val word = words[i]
                val at = found.indexOfFirst { it.first == word }
                if (at < 0) found.add(word to d) else if (d < found[at].second) found[at] = word to d
            }
            found.sortBy { it.second }
            return found.take(limit).map { it.first }
        }
    }

    private val layouts = HashMap<Long, HashMap<String, Entry>>()
    private var tick = 0L
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    @Volatile
    private var published: Map<Long, LayoutShapes> = emptyMap()

    /** How many ways of drawing one word are kept apart. See [setShapesPerWord]. */
    private var shapeLimit = DEFAULT_SHAPES_PER_WORD

    init {
        load()
    }

    /** The shapes stored for one layout, for the decoder; null when it has none. */
    fun forLayout(layoutKey: Long): GlideShapeSource? = published[layoutKey]

    /**
     * How many ways of drawing one word to keep apart (#326), clamped to
     * 1..[MAX_SHAPES_PER_WORD].
     *
     * Lowering it takes the least-accepted extras away from the decoder at
     * once, but drops them from the store only when that word next learns a
     * new way of being drawn. Until then raising it again brings them back,
     * so a slider dragged down and up again, or a settings emit that arrives
     * with the default before the user's value, loses nothing.
     */
    @Synchronized
    fun setShapesPerWord(limit: Int) {
        val clamped = limit.coerceIn(1, MAX_SHAPES_PER_WORD)
        if (clamped == shapeLimit) return
        shapeLimit = clamped
        publish()
    }

    /**
     * A settled glide of [word] drawn as [sample]: blends into the nearest
     * stored shape of the word when there is one within [MERGE_RADIUS], else
     * is kept as a new one.
     */
    @Synchronized
    fun learn(sample: GlideShapeSample, word: String) {
        val key = WordKey.of(word)
        if (key.isEmpty() || sample.shape.size != POINTS) return
        tick++
        val words = layouts.getOrPut(sample.layoutKey) { HashMap() }
        val entry = words.getOrPut(key) { Entry(tick, ArrayList(shapeLimit)) }
        entry.tick = tick
        val nearest = nearest(entry, sample.shape)
        if (nearest != null && distance(nearest.points, sample.shape, MERGE_RADIUS) <= MERGE_RADIUS) {
            blend(nearest, sample.shape)
            nearest.accepted++
        } else {
            if (entry.shapes.size >= shapeLimit) {
                // A new way of drawing a word always arrives at one acceptance,
                // so a plain count leaves an established shape unbeatable: it
                // evicts each newcomer in turn, and a hand that has changed can
                // never say so (issue #213). Every sighting of something new
                // ages what is already here instead. A shape still in use keeps
                // its lead by being accepted again; one the user has stopped
                // drawing halves away and gives up the slot in a few sightings.
                for (shape in entry.shapes) shape.accepted = (shape.accepted + 1) / 2
                // More than one goes when the limit was lowered since this word
                // last learned something new.
                while (entry.shapes.size >= shapeLimit) {
                    entry.shapes.remove(entry.shapes.minByOrNull { it.accepted - it.rejected })
                }
            }
            entry.shapes.add(Shape(sample.shape.copyOf(), 1, 0))
        }
        evict()
        settle()
    }

    /**
     * A glide read as [word] and drawn as [drawn] was undone or replaced: the
     * stored shape that matched it, if one did within [REJECT_RADIUS], counts
     * a rejection, and goes once rejected more often than accepted. True
     * when a shape was marked.
     */
    @Synchronized
    fun reject(layoutKey: Long, word: String, drawn: ByteArray): Boolean {
        val words = layouts[layoutKey] ?: return false
        val key = WordKey.of(word)
        val entry = words[key] ?: return false
        val nearest = nearest(entry, drawn) ?: return false
        if (distance(nearest.points, drawn, REJECT_RADIUS) > REJECT_RADIUS) return false
        tick++
        entry.tick = tick
        nearest.rejected++
        if (nearest.rejected > nearest.accepted) entry.shapes.remove(nearest)
        if (entry.shapes.isEmpty()) words.remove(key)
        if (words.isEmpty()) layouts.remove(layoutKey)
        settle()
        return true
    }

    /** Drops every shape of [word], on every layout. True when there were any. */
    @Synchronized
    fun forget(word: String): Boolean {
        val key = WordKey.of(word)
        var any = false
        val iterator = layouts.entries.iterator()
        while (iterator.hasNext()) {
            val words = iterator.next().value
            if (words.remove(key) != null) any = true
            if (words.isEmpty()) iterator.remove()
        }
        if (any) settle()
        return any
    }

    /** How many shapes [word] has that the decoder reads, across every layout. */
    @Synchronized
    fun countFor(word: String): Int {
        val key = WordKey.of(word)
        var count = 0
        for (words in layouts.values) count += minOf(words[key]?.shapes?.size ?: 0, shapeLimit)
        return count
    }

    @Synchronized
    fun wordCount(): Int = layouts.values.sumOf { it.size }

    @Synchronized
    fun isEmpty(): Boolean = layouts.isEmpty()

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                VERSION,
                tick,
                layouts.entries.associate { (layoutKey, words) ->
                    java.lang.Long.toHexString(layoutKey) to words.entries.associate { (word, entry) ->
                        word to StoredWord(
                            entry.tick,
                            entry.shapes.map { StoredShape(it.points.toHex(), it.accepted, it.rejected) },
                        )
                    }
                },
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    /** Re-reads the file after the settings app deleted or replaced it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        layouts.clear()
        tick = 0L
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        layouts.clear()
        tick = 0L
        publish()
        // The delete is the write; stay dirty only if it failed, so the next
        // save overwrites the stale file with the empty snapshot.
        dirty = snapshotFile?.delete() == false
    }

    private fun nearest(entry: Entry, drawn: ByteArray): Shape? {
        var best: Shape? = null
        var bestDistance = Float.MAX_VALUE
        for (shape in entry.shapes) {
            val d = distance(shape.points, drawn, bestDistance)
            if (d < bestDistance) {
                bestDistance = d
                best = shape
            }
        }
        return best
    }

    /**
     * Running mean in the quantised space, the shape's acceptances deep — but
     * never deeper than [BLEND_DEPTH], and rounded rather than truncated.
     *
     * Two things were wrong with the plain mean (issue #213). It freezes: at
     * fifty acceptances a new stroke moves the stored shape by a fiftieth, so a
     * word drawn often enough stops following the hand this store exists to
     * follow. And `toInt()` truncates *toward zero*, so every coordinate that
     * failed to move a whole quantised unit fell back toward the origin instead
     * of staying put — a slow pull on every stored shape, in the one direction
     * the normalisation has already centred them on.
     *
     * Capping the depth turns the tail into an exponential mean, which keeps
     * converging while a habit holds and still comes across when it changes.
     * The cap has to be shallow because the mean is kept in whole quantised
     * units: a step of half the weight or less rounds away to nothing, so a
     * depth of `n` cannot track a drift finer than about `n / 2` units. At four
     * that floor is an eighth of a key width, comfortably inside
     * [MERGE_RADIUS], which is the resolution the merge decision works at
     * anyway.
     */
    private fun blend(shape: Shape, drawn: ByteArray) {
        val depth = minOf(shape.accepted, BLEND_DEPTH)
        val weight = depth + 1
        for (i in 0 until POINTS) {
            val mixed = (shape.points[i] * depth + drawn[i]).toFloat() / weight
            shape.points[i] = mixed.roundToInt().coerceIn(-QUANT_LIMIT, QUANT_LIMIT).toByte()
        }
    }

    /** Past [MAX_WORDS] the word left alone longest makes room. */
    private fun evict() {
        while (wordCount() > MAX_WORDS) {
            var oldestLayout = 0L
            var oldestWord: String? = null
            var oldestTick = Long.MAX_VALUE
            for ((layoutKey, words) in layouts) {
                for ((word, entry) in words) {
                    if (entry.tick < oldestTick) {
                        oldestTick = entry.tick
                        oldestLayout = layoutKey
                        oldestWord = word
                    }
                }
            }
            val words = layouts[oldestLayout] ?: return
            words.remove(oldestWord ?: return)
            if (words.isEmpty()) layouts.remove(oldestLayout)
        }
    }

    private fun settle() {
        publish()
        dirty = true
    }

    private fun publish() {
        published = if (layouts.isEmpty()) {
            emptyMap()
        } else {
            layouts.entries.associate { (layoutKey, words) ->
                val wordList = ArrayList<String>()
                val shapeList = ArrayList<ByteArray>()
                val byWord = HashMap<String, IntArray>(words.size * 2)
                for ((word, entry) in words) {
                    val kept = readable(entry.shapes)
                    val indices = IntArray(kept.size)
                    for ((i, shape) in kept.withIndex()) {
                        indices[i] = shapeList.size
                        wordList.add(word)
                        shapeList.add(shape.points.copyOf())
                    }
                    byWord[word] = indices
                }
                layoutKey to LayoutShapes(wordList.toTypedArray(), shapeList.toTypedArray(), byWord)
            }
        }
    }

    /** The [shapeLimit] most-accepted of [shapes], or all of them when they fit. */
    private fun readable(shapes: List<Shape>): List<Shape> =
        if (shapes.size <= shapeLimit) {
            shapes
        } else {
            shapes.sortedByDescending { it.accepted - it.rejected }.take(shapeLimit)
        }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            if (snapshot.v != VERSION) return@runCatching
            tick = snapshot.tick.coerceAtLeast(0L)
            for ((layoutHex, words) in snapshot.layouts) {
                val layoutKey = java.lang.Long.parseUnsignedLong(layoutHex, 16)
                val entries = HashMap<String, Entry>()
                for ((word, stored) in words) {
                    val key = WordKey.of(word)
                    if (key.isEmpty()) continue
                    val shapes = ArrayList<Shape>(minOf(stored.s.size, MAX_SHAPES_PER_WORD))
                    for (s in stored.s) {
                        val points = s.p.fromHex() ?: continue
                        if (points.size != POINTS || s.a <= 0) continue
                        shapes.add(Shape(points, s.a, s.r.coerceAtLeast(0)))
                        if (shapes.size >= MAX_SHAPES_PER_WORD) break
                    }
                    if (shapes.isNotEmpty()) entries[key] = Entry(stored.t.coerceIn(0L, tick), shapes)
                }
                if (entries.isNotEmpty()) layouts[layoutKey] = entries
            }
            evict()
        }
        publish()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0 || any { it !in "0123456789abcdefABCDEF" }) return null
        return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    companion object {
        private const val VERSION = 2

        /** Words kept across every layout; the longest untouched goes first. */
        const val MAX_WORDS = 10000

        /** Ways of drawing one word that are kept apart, unless the user sets another number. */
        const val DEFAULT_SHAPES_PER_WORD = 3

        /**
         * Most ways of drawing one word the user can ask to keep (#326). Set
         * high on purpose, so a backup can show how many a hand really uses.
         * A full store at this is 10000 words of about 2 KB each.
         */
        const val MAX_SHAPES_PER_WORD = 20

        /**
         * Deepest the running mean behind a stored shape ever gets: past this
         * many acceptances a shape keeps following the hand at a fixed rate
         * rather than settling for good. See [blend].
         */
        const val BLEND_DEPTH = 4

        /** A settled shape this close to a stored one is the same way of drawing the word, and blends in (in key widths). */
        const val MERGE_RADIUS = 0.35f

        /** An undone stroke this close to a stored shape is the one that shape read wrongly (in key widths). */
        const val REJECT_RADIUS = 0.60f

        /** Coordinates per shape: x then y for each of the decoder's samples. */
        const val POINTS = 2 * GlideWorkspace.SAMPLE_POINTS

        /** Quantisation: a key-width coordinate times this, rounded to a byte. */
        const val QUANT = 10f

        private const val QUANT_LIMIT = 127

        /**
         * Mean distance between two shapes, in the shape channel's units — a
         * coordinate is `QUANT` bytes to the key width of spread. Stops
         * counting once the running mean can no longer come in under
         * [abortAbove], and answers something above it then.
         */
        fun distance(a: ByteArray, b: ByteArray, abortAbove: Float = Float.MAX_VALUE): Float {
            val n = GlideWorkspace.SAMPLE_POINTS
            val limit = abortAbove * n * QUANT
            var sum = 0f
            for (j in 0 until n) {
                val dx = (a[2 * j] - b[2 * j]).toFloat()
                val dy = (a[2 * j + 1] - b[2 * j + 1]).toFloat()
                sum += sqrt(dx * dx + dy * dy)
                if (sum > limit) return abortAbove + 1f
            }
            return sum / (n * QUANT)
        }
    }
}
